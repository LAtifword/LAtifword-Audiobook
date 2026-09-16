from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path

import av
import numpy as np


@dataclass(frozen=True)
class ChapterTiming:
    title: str
    start_ms: int
    end_ms: int


def safe_filename(value: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', "_", value).strip(" ._")
    return cleaned[:140] or "LATIF Audiobook"


class M4aStreamWriter:
    def __init__(
        self,
        output_path: Path,
        sample_rate: int = 24_000,
        bitrate: int = 128_000,
        title: str = "LATIF Audiobook",
        author: str = "",
        narrator: str = "LATIF Author Narrator",
    ) -> None:
        self.output_path = output_path
        self.sample_rate = int(sample_rate)
        self.bitrate = int(bitrate)
        self.title = title
        self.author = author
        self.narrator = narrator
        self.output_path.parent.mkdir(parents=True, exist_ok=True)
        self.temp_path = output_path.with_suffix(output_path.suffix + ".part")
        if self.temp_path.exists():
            self.temp_path.unlink()
        self.container = av.open(str(self.temp_path), mode="w", format="mp4")
        self.container.metadata["title"] = title
        if author:
            self.container.metadata["artist"] = author
            self.container.metadata["album_artist"] = author
        self.container.metadata["album"] = title
        self.container.metadata["genre"] = "Audiobook"
        self.container.metadata["comment"] = f"Narrator: {narrator} · LATIF Voice Studio 3.3"
        self.stream = self.container.add_stream("aac", rate=self.sample_rate)
        self.stream.bit_rate = self.bitrate
        self.stream.layout = "mono"
        self.resampler = av.AudioResampler(format="fltp", layout="mono", rate=self.sample_rate)
        self.submitted_samples = 0
        self.closed = False

    @property
    def duration_ms(self) -> int:
        return int(self.submitted_samples * 1000 / self.sample_rate)

    def _encode_frame(self, frame: av.AudioFrame) -> None:
        for converted in self.resampler.resample(frame):
            for packet in self.stream.encode(converted):
                self.container.mux(packet)

    def write_pcm16(self, samples: np.ndarray) -> None:
        if self.closed:
            raise RuntimeError("M4A writer is closed")
        pcm = np.asarray(samples, dtype=np.int16).reshape(-1)
        if pcm.size == 0:
            return
        # Bound frame sizes so a long chunk never creates a second giant encoder buffer.
        frame_samples = 4096
        offset = 0
        while offset < pcm.size:
            block = np.ascontiguousarray(pcm[offset : offset + frame_samples])
            frame = av.AudioFrame.from_ndarray(block.reshape(1, -1), format="s16", layout="mono")
            frame.sample_rate = self.sample_rate
            frame.pts = self.submitted_samples
            frame.time_base = Fraction(1, self.sample_rate)
            self._encode_frame(frame)
            self.submitted_samples += block.size
            offset += block.size

    def write_silence(self, duration_ms: int) -> None:
        if duration_ms <= 0:
            return
        remaining = int(self.sample_rate * duration_ms / 1000)
        silence = np.zeros(min(4096, max(1, remaining)), dtype=np.int16)
        while remaining > 0:
            count = min(remaining, silence.size)
            self.write_pcm16(silence[:count])
            remaining -= count

    def finish(self, chapters: list[ChapterTiming] | None = None) -> Path:
        if self.closed:
            return self.output_path
        for converted in self.resampler.resample(None):
            for packet in self.stream.encode(converted):
                self.container.mux(packet)
        for packet in self.stream.encode(None):
            self.container.mux(packet)
        self.container.close()
        self.closed = True
        os.replace(self.temp_path, self.output_path)
        self._write_sidecar(chapters or [])
        return self.output_path

    def _write_sidecar(self, chapters: list[ChapterTiming]) -> None:
        payload = {
            "schema": "latif-audiobook-chapters",
            "version": 1,
            "bookTitle": self.title,
            "author": self.author or None,
            "narrator": self.narrator,
            "sampleRate": self.sample_rate,
            "durationMs": self.duration_ms,
            "chapters": [
                {
                    "index": index + 1,
                    "title": item.title,
                    "startMs": item.start_ms,
                    "endMs": item.end_ms,
                }
                for index, item in enumerate(chapters)
            ],
        }
        sidecar = self.output_path.with_suffix(".chapters.json")
        temp = sidecar.with_suffix(sidecar.suffix + ".tmp")
        temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, sidecar)

    def abort(self) -> None:
        if not self.closed:
            try:
                self.container.close()
            except Exception:  # noqa: BLE001
                pass
            self.closed = True
        if self.temp_path.exists():
            self.temp_path.unlink(missing_ok=True)

    def close(self) -> None:
        if not self.closed:
            self.abort()
