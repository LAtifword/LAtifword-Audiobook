#!/usr/bin/env python3
"""Package a WAV audiobook as M4A/MP4 with metadata, cover art, and chapters.

No fades, normalization, EQ, denoise, or other audio edits are performed.
AAC is lossy; ALAC is lossless and preserves the WAV samples inside M4A.
Requires ffmpeg and ffprobe on PATH.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def run(command: list[str]) -> None:
    print("$", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def ffmetadata_escape(value: str) -> str:
    return (
        str(value)
        .replace("\\", "\\\\")
        .replace("=", "\\=")
        .replace(";", "\\;")
        .replace("#", "\\#")
        .replace("\n", "\\n")
    )


def load_chapters(path: Path | None) -> list[dict]:
    if path is None:
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    chapters = data.get("chapters", data) if isinstance(data, (dict, list)) else []
    if not isinstance(chapters, list):
        raise ValueError("chapters.json must contain a 'chapters' array")
    result = []
    for index, chapter in enumerate(chapters, start=1):
        start = int(chapter.get("startMs", chapter.get("start_ms", 0)))
        end_value = chapter.get("endMs", chapter.get("end_ms"))
        end = int(end_value) if end_value is not None else None
        title = str(chapter.get("title", f"Chapter {index}")).strip()
        if not title or start < 0 or (end is not None and end <= start):
            raise ValueError(f"Invalid chapter {index}: {chapter}")
        result.append({"title": title, "start": start, "end": end})
    return result


def write_ffmetadata(path: Path, metadata: dict, chapters: list[dict]) -> None:
    lines = [";FFMETADATA1"]
    for key, value in metadata.items():
        if value is not None and str(value) != "":
            lines.append(f"{key}={ffmetadata_escape(value)}")
    for chapter in chapters:
        lines.extend([
            "[CHAPTER]",
            "TIMEBASE=1/1000",
            f"START={chapter['start']}",
            f"END={chapter['end'] if chapter['end'] is not None else chapter['start'] + 1}",
            f"title={ffmetadata_escape(chapter['title'])}",
        ])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wav", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cover", type=Path)
    parser.add_argument("--chapters-json", type=Path)
    parser.add_argument("--title", required=True)
    parser.add_argument("--author", default="")
    parser.add_argument("--album", default="")
    parser.add_argument("--narrator", default="")
    parser.add_argument("--year", default="")
    parser.add_argument("--genre", default="Audiobook")
    parser.add_argument("--description", default="")
    parser.add_argument("--codec", choices=["aac", "alac"], default="aac")
    parser.add_argument("--bitrate", default="128k", help="AAC bitrate, e.g. 128k")
    args = parser.parse_args()

    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg was not found on PATH")
    if not args.wav.is_file():
        raise SystemExit(f"Input WAV not found: {args.wav}")
    if args.cover and not args.cover.is_file():
        raise SystemExit(f"Cover image not found: {args.cover}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    chapters = load_chapters(args.chapters_json)
    metadata = {
        "title": args.title,
        "artist": args.author,
        "album": args.album or args.title,
        "album_artist": args.author,
        "composer": args.narrator,
        "genre": args.genre,
        "date": args.year,
        "comment": args.description,
        "description": args.description,
    }

    with tempfile.TemporaryDirectory(prefix="latif-m4a-") as temp:
        ffmeta = Path(temp) / "metadata.txt"
        write_ffmetadata(ffmeta, metadata, chapters)
        temp_output = args.output.with_suffix(args.output.suffix + ".tmp")
        temp_output.unlink(missing_ok=True)

        command = [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(args.wav),
            "-f", "ffmetadata", "-i", str(ffmeta),
        ]
        if args.cover:
            command += ["-i", str(args.cover)]
        command += ["-map", "0:a:0"]
        if args.cover:
            command += ["-map", "2:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic"]
        if args.codec == "alac":
            command += ["-c:a", "alac"]
        else:
            command += ["-c:a", "aac", "-b:a", args.bitrate, "-profile:a", "aac_low"]
        command += [
            "-map_metadata", "1",
            "-map_chapters", "1",
            "-movflags", "+faststart",
            str(temp_output),
        ]
        run(command)
        if not temp_output.is_file() or temp_output.stat().st_size == 0:
            raise RuntimeError("ffmpeg produced an empty M4A")
        temp_output.replace(args.output)

    run([
        "ffprobe", "-v", "error", "-show_entries",
        "format=format_name,duration:stream=codec_name,sample_rate,channels:chapter=start_time,end_time,tag:title",
        "-of", "default=noprint_wrappers=1", str(args.output),
    ])
    print(f"Created: {args.output}")


if __name__ == "__main__":
    main()
