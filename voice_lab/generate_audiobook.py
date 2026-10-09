#!/usr/bin/env python3
"""Generate long-form Arabic audiobook audio with SILMA TTS.

The script intentionally performs no gain normalization, fades, denoise,
compression, music mixing, or other audio editing. It only synthesizes each
text chunk and concatenates the resulting PCM frames into a WAV master.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import wave
from pathlib import Path
from typing import Iterable

from silma_tts.api import SilmaTTS

DEFAULT_REF_TEXT = (
    "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل "
    "العظام عليهم الصلاة والسلام."
)


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def split_text(text: str, max_chars: int = 4200) -> list[str]:
    text = normalize_text(text)
    parts: list[str] = []
    while text:
        if len(text) <= max_chars:
            parts.append(text)
            break
        cut = max(text.rfind("،", 0, max_chars), text.rfind(" ", 0, max_chars))
        if cut < max_chars // 2:
            cut = max_chars
        parts.append(text[:cut].strip())
        text = text[cut:].strip()
    return parts


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def write_concat_wav(paths: Iterable[Path], output: Path) -> None:
    paths = list(paths)
    if not paths:
        raise RuntimeError("No generated WAV clips were found")
    output.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(paths[0]), "rb") as first:
        params = first.getparams()
        frames = [first.readframes(first.getnframes())]
    for path in paths[1:]:
        with wave.open(str(path), "rb") as current:
            if (current.getnchannels(), current.getsampwidth(), current.getframerate()) != (
                params.nchannels, params.sampwidth, params.framerate
            ):
                raise RuntimeError(f"Audio format mismatch in {path}")
            frames.append(current.readframes(current.getnframes()))
    with wave.open(str(output), "wb") as out:
        out.setparams(params)
        for pcm in frames:
            out.writeframes(pcm)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", required=True, type=Path)
    parser.add_argument("--reference-audio", required=True, type=Path)
    parser.add_argument("--reference-text", default=DEFAULT_REF_TEXT)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--max-chars", type=int, default=4200)
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()

    text = normalize_text(args.text.read_text(encoding="utf-8"))
    if not text:
        raise SystemExit("Input text is empty")
    if not args.reference_audio.is_file():
        raise SystemExit(f"Reference audio not found: {args.reference_audio}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    parts = split_text(text, args.max_chars)
    (args.output_dir / "segments.json").write_text(
        json.dumps(
            [{"index": i + 1, "chars": len(p), "text_sha256": sha256(p)} for i, p in enumerate(parts)],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    tts = SilmaTTS()
    clips: list[Path] = []
    for index, part in enumerate(parts, start=1):
        clip = args.output_dir / f"clip_{index:03d}.wav"
        print(f"[{index}/{len(parts)}] synthesizing {len(part)} characters -> {clip}", flush=True)
        tts.infer(
            ref_file=str(args.reference_audio),
            ref_text=args.reference_text,
            gen_text=part,
            file_wave=str(clip),
            seed=args.seed,
            speed=args.speed,
        )
        if not clip.is_file() or clip.stat().st_size == 0:
            raise RuntimeError(f"Synthesis produced no audio: {clip}")
        clips.append(clip)

    master = args.output_dir / "audiobook_master.wav"
    write_concat_wav(clips, master)
    print(f"Wrote pure PCM concatenation: {master}")
    print(f"Generated clips: {len(clips)}")


if __name__ == "__main__":
    main()
