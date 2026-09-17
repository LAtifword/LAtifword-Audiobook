from __future__ import annotations

import os
import wave
from dataclasses import dataclass
from pathlib import Path
from threading import Event
from typing import Callable

import numpy as np
import onnxruntime as ort

from .book_parser import prepare_for_narration
from .resources import model_dir

SAMPLE_RATE = 24_000
MIN_STEPS = 8
MAX_STEPS = 32
AUTHOR_SPEED = 0.90
DEFAULT_REFERENCE_TEXT = (
    "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية "
    "ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."
)

ASSET_SIZES = {
    "F5_Preprocess.onnx": 73_904_440,
    "model.onnx": 612_437_669,
    "F5_Decode.onnx": 62_546_929,
    "config.json": 156_367,
    "default_ref.wav": 372_680,
    "vocab.txt": 36_357,
}


@dataclass(frozen=True)
class VoiceReference:
    samples: np.ndarray
    transcript: str


class GenerationCancelled(RuntimeError):
    pass


class SilmaDesktopEngine:
    """Desktop SILMA F5 ONNX engine with DirectML -> CPU fallback."""

    def __init__(self, assets: Path | None = None, force_backend: str | None = None):
        self.assets = Path(assets or model_dir())
        self.force_backend = force_backend
        self.preprocess: ort.InferenceSession | None = None
        self.transformer: ort.InferenceSession | None = None
        self.decoder: ort.InferenceSession | None = None
        self.backend_name = "not loaded"
        self.worker_threads = max(2, min((os.cpu_count() or 4) - 1, 16))
        self.vocab: dict[str, int] = {}

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

    def validate_assets(self) -> None:
        missing = []
        invalid = []
        for name, expected in ASSET_SIZES.items():
            path = self.assets / name
            if not path.is_file():
                missing.append(name)
            elif path.stat().st_size != expected:
                invalid.append((name, path.stat().st_size, expected))
        if missing:
            raise FileNotFoundError("Missing SILMA assets: " + ", ".join(missing))
        if invalid:
            detail = "; ".join(f"{n}={a} expected={e}" for n, a, e in invalid)
            raise RuntimeError("SILMA asset size mismatch: " + detail)

    def _session_options(self, directml: bool) -> ort.SessionOptions:
        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.inter_op_num_threads = 1
        if directml:
            # Required by the DirectML EP.
            options.enable_mem_pattern = False
        else:
            options.enable_mem_pattern = True
            options.intra_op_num_threads = self.worker_threads
            options.add_session_config_entry("session.intra_op.allow_spinning", "1")
        return options

    def _candidates(self) -> list[tuple[str, list]]:
        available = set(ort.get_available_providers())
        candidates: list[tuple[str, list]] = []
        if self.force_backend:
            key = self.force_backend.lower()
            if key == "directml":
                candidates.append(("DirectML GPU", [("DmlExecutionProvider", {"device_id": 0}), "CPUExecutionProvider"]))
            elif key == "cpu":
                candidates.append(("ORT tuned CPU", ["CPUExecutionProvider"]))
            elif key == "cuda":
                candidates.append(("NVIDIA CUDA", ["CUDAExecutionProvider", "CPUExecutionProvider"]))
            else:
                raise ValueError(f"Unknown backend: {self.force_backend}")
            return candidates

        if "CUDAExecutionProvider" in available:
            candidates.append(("NVIDIA CUDA", ["CUDAExecutionProvider", "CPUExecutionProvider"]))
        if "DmlExecutionProvider" in available:
            candidates.append(("DirectML GPU", [("DmlExecutionProvider", {"device_id": 0}), "CPUExecutionProvider"]))
        candidates.append(("ORT tuned CPU", ["CPUExecutionProvider"]))
        return candidates

    def load(self, progress: Callable[[str], None] | None = None) -> None:
        if self.preprocess and self.transformer and self.decoder:
            return
        self.validate_assets()
        progress and progress("Loading SILMA vocabulary…")
        with (self.assets / "vocab.txt").open("r", encoding="utf-8") as f:
            self.vocab = {line.rstrip("\r\n"): i for i, line in enumerate(f)}
        if not self.vocab:
            raise RuntimeError("SILMA vocabulary is empty")

        last_error: Exception | None = None
        for label, providers in self._candidates():
            progress and progress(f"Trying {label}…")
            try:
                directml = providers and (
                    providers[0] == "DmlExecutionProvider"
                    or (isinstance(providers[0], tuple) and providers[0][0] == "DmlExecutionProvider")
                )
                options = self._session_options(directml)
                pre = ort.InferenceSession(str(self.assets / "F5_Preprocess.onnx"), sess_options=options, providers=providers)
                transformer = ort.InferenceSession(str(self.assets / "model.onnx"), sess_options=options, providers=providers)
                decoder = ort.InferenceSession(str(self.assets / "F5_Decode.onnx"), sess_options=options, providers=providers)
                self.preprocess, self.transformer, self.decoder = pre, transformer, decoder
                self.backend_name = label
                progress and progress(f"SILMA ready · {label} · {self.worker_threads} CPU threads available")
                return
            except Exception as exc:
                last_error = exc
                self.close()
                progress and progress(f"{label} unavailable; trying fallback…")
        raise RuntimeError("Unable to initialize SILMA desktop backend") from last_error

    def built_in_reference(self) -> VoiceReference:
        samples = _read_wav_pcm16(self.assets / "default_ref.wav", SAMPLE_RATE)
        return VoiceReference(samples=samples, transcript=DEFAULT_REFERENCE_TEXT)

    def reference_from_wav(self, path: str | Path, transcript: str) -> VoiceReference:
        if not transcript.strip():
            raise ValueError("Reference transcript is required")
        samples = _read_wav_pcm16(Path(path), SAMPLE_RATE)
        max_samples = SAMPLE_RATE * 15
        samples = samples[:max_samples]
        if samples.size < SAMPLE_RATE * 2:
            raise ValueError("Reference voice should be at least 2 seconds")
        return VoiceReference(samples=samples, transcript=transcript.strip())

    def synthesize(
        self,
        reference: VoiceReference,
        text: str,
        speed: float = AUTHOR_SPEED,
        nfe_steps: int = 32,
        cancel: Event | None = None,
        on_step: Callable[[int, int], None] | None = None,
    ) -> np.ndarray:
        self.load()
        clean_text = prepare_for_narration(text)
        if not clean_text:
            raise ValueError("Narration text is empty")
        steps = max(MIN_STEPS, min(MAX_STEPS, int(nfe_steps)))

        ref_text = reference.transcript.strip() + " "
        ids = self._encode(ref_text + clean_text)
        max_duration = self._compute_max_duration(
            reference.samples.size, ref_text, clean_text, speed, 12
        )

        pre_inputs = {
            "audio": reference.samples.reshape(1, 1, -1).astype(np.int16, copy=False),
            "text_ids": ids.reshape(1, -1).astype(np.int32, copy=False),
            "max_duration": np.asarray([max_duration], dtype=np.int64),
        }
        pre_names = [o.name for o in self.preprocess.get_outputs()]
        pre_values = self.preprocess.run(pre_names, pre_inputs)
        pre = dict(zip(pre_names, pre_values))

        current_noise = pre["noise"]
        current_time = np.asarray([0], dtype=np.int32)
        transformer_outputs = [o.name for o in self.transformer.get_outputs()]

        for i in range(steps - 1):
            if cancel and cancel.is_set():
                raise GenerationCancelled()
            values = self.transformer.run(
                transformer_outputs,
                {
                    "noise": current_noise,
                    "rope_cos_q": pre["rope_cos_q"],
                    "rope_sin_q": pre["rope_sin_q"],
                    "rope_cos_k": pre["rope_cos_k"],
                    "rope_sin_k": pre["rope_sin_k"],
                    "cat_mel_text": pre["cat_mel_text"],
                    "cat_mel_text_drop": pre["cat_mel_text_drop"],
                    "time_step.1": current_time,
                },
            )
            out = dict(zip(transformer_outputs, values))
            current_noise = out["denoised"]
            current_time = out["time_step"]
            if on_step:
                on_step(i + 1, steps - 1)

        if cancel and cancel.is_set():
            raise GenerationCancelled()

        decode_names = [o.name for o in self.decoder.get_outputs()]
        decode_values = self.decoder.run(
            decode_names,
            {"denoised": current_noise, "ref_signal_len": pre["ref_signal_len"]},
        )
        decoded = dict(zip(decode_names, decode_values))["output_audio"]
        return _to_pcm16(decoded)

    def _encode(self, text: str) -> np.ndarray:
        return np.asarray([self.vocab.get(ch, 0) for ch in text], dtype=np.int32)

    @staticmethod
    def _compute_max_duration(
        reference_sample_count: int,
        reference_text: str,
        generation_text: str,
        speed: float,
        tail_padding_frames: int,
    ) -> int:
        ref_bytes = max(1, len(reference_text.encode("utf-8")))
        gen_bytes = len(generation_text.encode("utf-8"))
        ref_frames = reference_sample_count // 256 + 1
        rate = 1.0 if speed <= 0 else float(speed)
        return (
            ref_frames
            + int((ref_frames / ref_bytes) * gen_bytes / rate)
            + max(0, tail_padding_frames)
        )

    def close(self) -> None:
        self.preprocess = None
        self.transformer = None
        self.decoder = None
        if self.backend_name != "not loaded":
            self.backend_name = "closed"


def _to_pcm16(value: np.ndarray) -> np.ndarray:
    audio = np.asarray(value).reshape(-1)
    if audio.dtype == np.int16:
        return audio.copy()
    if np.issubdtype(audio.dtype, np.floating):
        return (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    return np.clip(audio, -32768, 32767).astype(np.int16)


def _read_wav_pcm16(path: Path, target_rate: int) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError(f"Only PCM16 WAV is supported: {path.name}")
    data = np.frombuffer(frames, dtype="<i2")
    if channels == 2:
        data = data.reshape(-1, 2).astype(np.int32).mean(axis=1).astype(np.int16)
    elif channels != 1:
        raise ValueError(f"Unsupported WAV channels: {channels}")
    if rate != target_rate:
        old_x = np.linspace(0.0, 1.0, num=data.size, endpoint=False)
        new_size = max(1, round(data.size * target_rate / rate))
        new_x = np.linspace(0.0, 1.0, num=new_size, endpoint=False)
        data = np.interp(new_x, old_x, data.astype(np.float32)).astype(np.int16)
    return data
