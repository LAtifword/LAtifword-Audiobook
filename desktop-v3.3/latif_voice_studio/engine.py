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

 latif-voice-studio-v3.3-desktop
ASSET_SIZES = {
    "F5_Preprocess.onnx": 73_904_440,
    "model.onnx": 612_437_669,
    "F5_Decode.onnx": 62_546_929,
    "config.json": 156_367,
    "default_ref.wav": 372_680,
    "vocab.txt": 36_357,
}


def app_root() -> Path:
    frozen = getattr(sys, "_MEIPASS", None)
    if frozen:
        return Path(frozen)
    return Path(__file__).resolve().parents[1]


def prepare_arabic_text(text: str) -> str:
    text = _ARABIC_LAYOUT_SEPARATOR.sub(" ", text)
    text = text.replace("\ufeff", " ").replace("\u00a0", " ").replace("ـ", " ")
    text = _ARABIC_SPACES.sub(" ", text)
    text = _ARABIC_PUNCTUATION.sub(r"\1 ", text)
    text = _ARABIC_NEWLINES.sub(" ", text)
    return text.strip()


def _resample_linear(samples: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate or samples.size == 0:
        return samples.astype(np.int16, copy=False)
    out_len = max(1, int(round(samples.size * target_rate / source_rate)))
    old_x = np.linspace(0.0, 1.0, num=samples.size, endpoint=False)
    new_x = np.linspace(0.0, 1.0, num=out_len, endpoint=False)
    out = np.interp(new_x, old_x, samples.astype(np.float32))
    return np.clip(np.round(out), -32768, 32767).astype(np.int16)


def read_pcm_wav(path: Path, target_rate: int = SAMPLE_RATE) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        width = wav.getsampwidth()
        rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError("Reference WAV must use 16-bit PCM")
    samples = np.frombuffer(frames, dtype="<i2")
    if channels == 2:
        samples = samples.reshape(-1, 2).astype(np.int32).mean(axis=1).astype(np.int16)
    elif channels != 1:
        raise ValueError("Reference WAV must be mono or stereo")
    return _resample_linear(samples, rate, target_rate)
 mainLAtifword


@dataclass(frozen=True)
class VoiceReference:
    samples: np.ndarray
    transcript: str


class GenerationCancelled(RuntimeError):
    pass


class SilmaDesktopEngine:
 latif-voice-studio-v3.3-desktop
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

    def __init__(
        self,
        model_dir: Path | None = None,
        backend: str = "auto",
        dml_device_id: int = 0,
    ) -> None:
        self.model_dir = model_dir or (app_root() / "models" / "silma-f5")
        self.backend_preference = backend.lower().strip()
        self.dml_device_id = max(0, int(dml_device_id))
        self.worker_threads = max(2, min(12, os.cpu_count() or 4))
        self.backend_name = "not loaded"
        self.preprocess: ort.InferenceSession | None = None
        self.transformer: ort.InferenceSession | None = None
        self.decoder: ort.InferenceSession | None = None
        self.vocab: dict[str, int] = {}
        self._char_vocab = np.zeros(65536, dtype=np.int32)
        self._char_present = np.zeros(65536, dtype=np.bool_)
 mainLAtifword

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

 latif-voice-studio-v3.3-desktop
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

    def _required_files(self) -> dict[str, int]:
        return {
            "F5_Preprocess.onnx": 73_904_440,
            "model.onnx": 612_437_669,
            "F5_Decode.onnx": 62_546_929,
            "config.json": 156_367,
            "default_ref.wav": 372_680,
            "vocab.txt": 36_357,
        }

    def verify_assets(self) -> None:
        missing: list[str] = []
        bad: list[str] = []
        for name, expected in self._required_files().items():
            path = self.model_dir / name
            if not path.is_file():
                missing.append(name)
            elif path.stat().st_size != expected:
                bad.append(f"{name}: {path.stat().st_size} != {expected}")
        if missing:
            raise FileNotFoundError("Missing SILMA assets: " + ", ".join(missing))
        if bad:
            raise RuntimeError("SILMA asset size mismatch: " + "; ".join(bad))
 mainLAtifword

    def _session_options(self, directml: bool) -> ort.SessionOptions:
        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.inter_op_num_threads = 1
        if directml:
 latif-voice-studio-v3.3-desktop
            # Required by the DirectML EP.
            options.enable_mem_pattern = False

            # Required by the DirectML execution provider.
            options.enable_mem_pattern = False
            options.intra_op_num_threads = 1
 mainLAtifword
        else:
            options.enable_mem_pattern = True
            options.intra_op_num_threads = self.worker_threads
            options.add_session_config_entry("session.intra_op.allow_spinning", "1")
        return options

 latif-voice-studio-v3.3-desktop
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

    def _provider_candidates(self) -> list[tuple[str, list, bool]]:
        available = set(ort.get_available_providers())
        candidates: list[tuple[str, list, bool]] = []
        wants_dml = self.backend_preference in {"auto", "directml", "gpu", "dml"}
        wants_cpu = self.backend_preference in {"auto", "cpu"}
        if wants_dml and "DmlExecutionProvider" in available:
            providers = [
                ("DmlExecutionProvider", {"device_id": str(self.dml_device_id)}),
                "CPUExecutionProvider",
            ]
            candidates.append((f"DirectML GPU {self.dml_device_id}", providers, True))
        if wants_cpu:
            candidates.append((f"ORT CPU · {self.worker_threads} threads", ["CPUExecutionProvider"], False))
        if not candidates:
            candidates.append(("ORT CPU", ["CPUExecutionProvider"], False))
 mainLAtifword
        return candidates

    def load(self, progress: Callable[[str], None] | None = None) -> None:
        if self.preprocess and self.transformer and self.decoder:
            return
 latif-voice-studio-v3.3-desktop
        self.validate_assets()
        progress and progress("Loading SILMA vocabulary…")
        with (self.assets / "vocab.txt").open("r", encoding="utf-8") as f:
            self.vocab = {line.rstrip("\r\n"): i for i, line in enumerate(f)}

        progress = progress or (lambda _message: None)
        progress("Checking embedded SILMA model…")
        self.verify_assets()
        vocab_path = self.model_dir / "vocab.txt"
        self.vocab = {
            line.rstrip("\r\n"): index
            for index, line in enumerate(vocab_path.read_text(encoding="utf-8").splitlines())
        }
        for token, index in self.vocab.items():
            if len(token) == 1:
                code = ord(token)
                if code < self._char_vocab.size:
                    self._char_vocab[code] = index
                    self._char_present[code] = True
 mainLAtifword
        if not self.vocab:
            raise RuntimeError("SILMA vocabulary is empty")

        last_error: Exception | None = None
 latif-voice-studio-v3.3-desktop
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


        for label, providers, directml in self._provider_candidates():
            progress(f"Trying {label}…")
            self.close_sessions()
            try:
                opts = self._session_options(directml)
                pre = ort.InferenceSession(
                    str(self.model_dir / "F5_Preprocess.onnx"),
                    sess_options=opts,
                    providers=providers,
                )
                trans = ort.InferenceSession(
                    str(self.model_dir / "model.onnx"),
                    sess_options=opts,
                    providers=providers,
                )
                dec = ort.InferenceSession(
                    str(self.model_dir / "F5_Decode.onnx"),
                    sess_options=opts,
                    providers=providers,
                )
                self.preprocess, self.transformer, self.decoder = pre, trans, dec
                self.backend_name = label
                progress(f"SILMA ready · {label}")
                return
            except Exception as exc:  # noqa: BLE001
                last_error = exc
                self.close_sessions()
                progress(f"{label} unavailable; trying fallback…")
        raise RuntimeError("Unable to initialize SILMA desktop backend") from last_error

    def built_in_reference(self) -> VoiceReference:
        samples = read_pcm_wav(self.model_dir / "default_ref.wav", SAMPLE_RATE)
        return VoiceReference(samples=samples, transcript=DEFAULT_REFERENCE_TEXT)

    def custom_reference(self, wav_path: Path, transcript: str) -> VoiceReference:
        if not transcript.strip():
            raise ValueError("Reference transcript is required for a custom voice")
        samples = read_pcm_wav(wav_path, SAMPLE_RATE)
        if samples.size < SAMPLE_RATE * 2:
            raise ValueError("Reference voice should be at least 2 seconds")
        samples = samples[: SAMPLE_RATE * 15]
        return VoiceReference(samples=samples, transcript=transcript.strip())

    def _encode(self, text: str) -> np.ndarray:
        ids = np.zeros(len(text), dtype=np.int32)
        for index, ch in enumerate(text):
            code = ord(ch)
            if code < self._char_vocab.size and self._char_present[code]:
                ids[index] = self._char_vocab[code]
            else:
                ids[index] = self.vocab.get(ch, 0)
        return ids

    @staticmethod
    def _normalize_reference_text(text: str) -> str:
        value = text.strip()
        return value if value.endswith(" ") else value + " "

    @staticmethod
    def _max_duration(
        reference_samples: int,
        reference_text: str,
        generation_text: str,
        speed: float,
        tail_padding_frames: int = 12,
    ) -> int:
        ref_bytes = max(1, len(reference_text.encode("utf-8")))
        gen_bytes = len(generation_text.encode("utf-8"))
        ref_frames = reference_samples // 256 + 1
        rate = 1.0 if speed <= 0 else float(speed)
        return int(
            ref_frames
            + (ref_frames / ref_bytes) * gen_bytes / rate
            + max(0, tail_padding_frames)
        )

 mainLAtifword
    def synthesize(
        self,
        reference: VoiceReference,
        text: str,
 latif-voice-studio-v3.3-desktop
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

        speed: float = 0.90,
        nfe_steps: int = 32,
        cancel_event: Event | None = None,
        on_step: Callable[[int, int], None] | None = None,
    ) -> np.ndarray:
        self.load()
        if not text.strip():
            raise ValueError("Narration text is empty")
        if reference.samples.size == 0:
            raise ValueError("Reference voice is empty")
        pre = self.preprocess
        trans = self.transformer
        dec = self.decoder
        if pre is None or trans is None or dec is None:
            raise RuntimeError("SILMA engine is not loaded")

        steps = max(MIN_STEPS, min(MAX_STEPS, int(nfe_steps)))
        ref_text = self._normalize_reference_text(reference.transcript)
        clean_text = prepare_arabic_text(text)
        ids = self._encode(ref_text + clean_text).reshape(1, -1)
        max_duration = self._max_duration(
            reference.samples.size,
            ref_text,
            clean_text,
            speed,
        )
        pre_out = pre.run(
            None,
            {
                "audio": reference.samples.reshape(1, 1, -1).astype(np.int16, copy=False),
                "text_ids": ids,
                "max_duration": np.asarray([max_duration], dtype=np.int64),
            },
        )
        pre_names = [item.name for item in pre.get_outputs()]
        values = dict(zip(pre_names, pre_out, strict=True))
        current_noise = values["noise"]
        current_time = np.asarray([0], dtype=np.int32)
        constant_inputs = {
            "rope_cos_q": values["rope_cos_q"],
            "rope_sin_q": values["rope_sin_q"],
            "rope_cos_k": values["rope_cos_k"],
            "rope_sin_k": values["rope_sin_k"],
            "cat_mel_text": values["cat_mel_text"],
            "cat_mel_text_drop": values["cat_mel_text_drop"],
        }
        trans_names = [item.name for item in trans.get_outputs()]
        for index in range(steps - 1):
            if cancel_event and cancel_event.is_set():
                raise GenerationCancelled()
            outputs = trans.run(
                None,
                {
                    "noise": current_noise,
                    **constant_inputs,
                    "time_step.1": current_time,
                },
            )
            step_values = dict(zip(trans_names, outputs, strict=True))
            current_noise = step_values["denoised"]
            current_time = step_values["time_step"]
            if on_step:
                on_step(index + 1, steps - 1)

        if cancel_event and cancel_event.is_set():
            raise GenerationCancelled()
        decoded = dec.run(
            ["output_audio"],
            {
                "denoised": current_noise,
                "ref_signal_len": values["ref_signal_len"],
            }
        )[0]
        audio = np.asarray(decoded).reshape(-1)
        if audio.size == 0:
            raise RuntimeError("SILMA decoder returned empty audio")
        if np.issubdtype(audio.bdtype, np.floating):
            audio = np.clip(audio, -1.0, 1.0) * 32767.0
        return np.clip(np.rint(audio), -32768, 32767).astype(np.int16)

    def close_sessions(self) -> None:
        self.preprocess = None
        self.transformer = None
        self.decoder = None

    def close(self) -> None:
        self.close_sessions()
        self.backend_name = "closed"
 mainLAtifword
