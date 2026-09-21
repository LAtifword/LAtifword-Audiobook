from __future__ import annotations

import hashlib
import json
import re
import subprocess
import time
import wave
from dataclasses import dataclass
from pathlib import Path
from threading import Event
from typing import Callable

import imageio_ffmpeg
import numpy as np

from .book_parser import prepare_for_narration, split_for_narration, target_chars_for_steps
from .engine import AUTHOR_SPEED, GenerationCancelled, SAMPLE_RATE, SilmaDesktopEngine, VoiceReference
from .resources import cache_dir


@dataclass
class RenderRequest:
    title: str
    text: str
    output_dir: Path
    steps: int = 32
    speed: float = AUTHOR_SPEED
    preview_only: bool = False
    reference_wav: Path | None = None
    reference_text: str = ""


@dataclass
class RenderResult:
    audio_path: Path
    sidecar_path: Path
    backend: str
    elapsed_seconds: float
    audio_seconds: float
    realtime_factor: float
    resumed_sections: int


class AudiobookRenderer:
    def __init__(
        self,
        engine: SilmaDesktopEngine,
        cancel: Event,
        progress: Callable[[int, str], None] | None = None,
        log: Callable[[str], None] | None = None,
    ):
        self.engine = engine
        self.cancel = cancel
        self.progress = progress or (lambda percent, message: None)
        self.log = log or (lambda message: None)

    def run(self, request: RenderRequest) -> RenderResult:
        text = request.text.strip()
        if len(text) < 20:
            raise ValueError("The manuscript contains no readable text.")

        self.progress(1, "Preparing SILMA desktop engine…")
        self.engine.load(lambda message: self.progress(2, message))

        reference = self._reference(request)
        chunks = split_for_narration(text, target_chars_for_steps(request.steps))
        if not chunks:
            raise ValueError("No narration sections were created.")
        if request.preview_only:
            chunks = chunks[:1]

        request.output_dir.mkdir(parents=True, exist_ok=True)
        job_id = self._job_id(request, text)
        job_cache = cache_dir() / job_id
        job_cache.mkdir(parents=True, exist_ok=True)
        manifest_path = job_cache / "manifest.json"
        manifest = self._load_manifest(manifest_path)
        manifest.setdefault("version", 1)
        manifest.setdefault("sections", {})

        self.log(
            f"Backend: {self.engine.backend_name} | sections={len(chunks)} | "
            f"steps={request.steps} | speed={request.speed:.2f}x"
        )

        started = time.perf_counter()
        measured_compute = 0.0
        measured_audio = 0.0
        resumed = 0
        chapter_rows: list[dict] = []
        cursor_ms = 0

        for index, raw_chunk in enumerate(chunks):
            self._check_cancelled()
            clean = prepare_for_narration(raw_chunk)
            if not clean:
                continue

            chunk_key = hashlib.sha256(clean.encode("utf-8")).hexdigest()
            chunk_file = job_cache / f"section_{index + 1:05d}.wav"
            cached = manifest["sections"].get(str(index))
            samples: np.ndarray | None = None

            if (
                cached
                and cached.get("text_sha256") == chunk_key
                and chunk_file.is_file()
                and chunk_file.stat().st_size > 44
            ):
                samples = _read_pcm_wav(chunk_file)
                resumed += 1
                self.log(f"Reused cached section {index + 1}/{len(chunks)}")
            else:
                section_start = time.perf_counter()

                def on_step(step: int, step_count: int) -> None:
                    unit = (index + step / max(1, step_count)) / max(1, len(chunks))
                    percent = 4 + int(unit * 90)
                    self.progress(
                        min(94, percent),
                        f"SILMA {index + 1}/{len(chunks)} · refinement "
                        f"{step}/{step_count} · {self.engine.backend_name}",
                    )

                samples = self.engine.synthesize(
                    reference=reference,
                    text=clean,
                    speed=request.speed,
                    nfe_steps=request.steps,
                    cancel=self.cancel,
                    on_step=on_step,
                )
                compute_s = time.perf_counter() - section_start
                audio_s = samples.size / SAMPLE_RATE
                measured_compute += compute_s
                measured_audio += audio_s
                _write_pcm_wav(chunk_file, samples)
                manifest["sections"][str(index)] = {
                    "text_sha256": chunk_key,
                    "sample_count": int(samples.size),
                    "seconds": audio_s,
                }
                self._save_manifest(manifest_path, manifest)

            audio_ms = round(samples.size * 1000 / SAMPLE_RATE)
            pause_ms = _pause_after(clean)
            chapter_rows.append(
                {
                    "index": index + 1,
                    "title": f"Section {index + 1}",
                    "startMs": cursor_ms,
                    "endMs": cursor_ms + audio_ms,
                }
            )
            cursor_ms += audio_ms + pause_ms

            if measured_audio > 0:
                rtf = measured_compute / measured_audio
                average_compute = measured_compute / max(1, (index + 1 - resumed))
                remaining = average_compute * max(0, len(chunks) - index - 1)
                msg = (
                    f"{index + 1}/{len(chunks)} complete · ETA {_format_duration(remaining)} "
                    f"· RTF {rtf:.2f}× · {self.engine.backend_name}"
                )
            else:
                msg = f"{index + 1}/{len(chunks)} complete · cached"
            self.progress(4 + int((index + 1) / len(chunks) * 90), msg)

        self._check_cancelled()
        safe_title = _safe_filename(
            request.title.strip() or ("LATIF Author Narrator preview" if request.preview_only else "LATIF Audiobook")
        )
        if request.preview_only:
            safe_title += " - preview"

        wav_path = request.output_dir / f"{safe_title}.wav.tmp"
        m4a_path = request.output_dir / f"{safe_title}.m4a"
        self.progress(95, "Assembling master audio…")
        self._assemble(chunks, job_cache, wav_path)
        self._check_cancelled()

        self.progress(97, "Encoding AAC-LC M4A…")
        _encode_m4a(wav_path, m4a_path)
        wav_path.unlink(missing_ok=True)

        total_audio_s = cursor_ms / 1000.0
        sidecar_path = request.output_dir / f"{safe_title}.chapters.json"
        sidecar = {
            "schema": "latif-audiobook-chapters",
            "version": 1,
            "bookTitle": request.title or safe_title,
            "narrator": "LATIF Author Narrator",
            "backend": self.engine.backend_name,
            "sampleRate": SAMPLE_RATE,
            "speed": request.speed,
            "f5Steps": request.steps,
            "durationMs": cursor_ms,
            "chapters": chapter_rows,
        }
        sidecar_path.write_text(json.dumps(sidecar, ensure_ascii=False, indent=2), encoding="utf-8")

        elapsed = time.perf_counter() - started
        rtf = measured_compute / measured_audio if measured_audio > 0 else 0.0
        self.progress(100, f"Complete · {m4a_path.name}")
        return RenderResult(
            audio_path=m4a_path,
            sidecar_path=sidecar_path,
            backend=self.engine.backend_name,
            elapsed_seconds=elapsed,
            audio_seconds=total_audio_s,
            realtime_factor=rtf,
            resumed_sections=resumed,
        )

    def _reference(self, request: RenderRequest) -> VoiceReference:
        if request.reference_wav:
            self.progress(3, "Loading custom reference voice…")
            return self.engine.reference_from_wav(request.reference_wav, request.reference_text)
        self.progress(3, "Loading permanent Author Narrator voice…")
        return self.engine.built_in_reference()

    def _assemble(self, chunks: list[str], job_cache: Path, destination: Path) -> None:
        with wave.open(str(destination), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(SAMPLE_RATE)
            for index, raw_chunk in enumerate(chunks):
                self._check_cancelled()
                clean = prepare_for_narration(raw_chunk)
                if not clean:
                    continue
                chunk_file = job_cache / f"section_{index + 1:05d}.wav"
                samples = _read_pcm_wav(chunk_file)
                out.writeframes(samples.astype("<i2", copy=False).tobytes())
                pause_samples = int(SAMPLE_RATE * _pause_after(clean) / 1000)
                if pause_samples > 0:
                    out.writeframes(np.zeros(pause_samples, dtype="<i2").tobytes())

    def _job_id(self, request: RenderRequest, text: str) -> str:
        voice = str(request.reference_wav or "builtin-author-narrator")
        payload = (
            f"v3.3|{request.steps}|{request.speed:.4f}|{voice}|"
            f"{request.reference_text}|{text}"
        )
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]

    @staticmethod
    def _load_manifest(path: Path) -> dict:
        if not path.is_file():
            return {}
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    @staticmethod
    def _save_manifest(path: Path, payload: dict) -> None:
        temp = path.with_suffix(".json.tmp")
        temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(path)

    def _check_cancelled(self) -> None:
        if self.cancel.is_set():
            raise GenerationCancelled()


def _write_pcm_wav(path: Path, samples: np.ndarray) -> None:
    temp = path.with_suffix(".wav.tmp")
    with wave.open(str(temp), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(np.asarray(samples, dtype="<i2").tobytes())
    temp.replace(path)


def _read_pcm_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != SAMPLE_RATE:
            raise ValueError(f"Invalid cached WAV: {path.name}")
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").copy()


def _encode_m4a(source_wav: Path, destination: Path) -> None:
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    command = [
        ffmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(source_wav),
        "-vn",
        "-c:a",
        "aac",
        "-b:a",
        "128k",
        "-ar",
        str(SAMPLE_RATE),
        "-ac",
        "1",
        str(destination),
    ]
    subprocess.run(command, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if not destination.is_file() or destination.stat().st_size < 1024:
        raise RuntimeError("M4A encoder produced no usable output")


def _pause_after(text: str) -> int:
    if text.endswith(("؟", "!")):
        return 180
    if text.endswith((".", "…", "؛")):
        return 140
    return 70


def _safe_filename(value: str) -> str:
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip(" .")
    return value[:150] or "LATIF Audiobook"


def _format_duration(seconds: float) -> str:
    seconds = max(0, int(seconds))
    hours, remainder = divmod(seconds, 3600)
    minutes, _ = divmod(remainder, 60)
    return f"{hours}h {minutes}m" if hours else f"{minutes}m"
