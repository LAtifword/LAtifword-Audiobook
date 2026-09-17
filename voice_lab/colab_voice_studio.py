#!/usr/bin/env python3
"""Interactive Google Colab UI for SILMA audiobook generation.

The UI only orchestrates the existing generator and packager. It does not
apply audio editing, fades, normalization, or effects.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import gradio as gr

ROOT = Path(__file__).resolve().parent
GENERATOR = ROOT / "generate_audiobook.py"
PACKAGER = ROOT / "package_m4a.py"
DEFAULT_REF_TEXT = "ويدقق النظر في القرآن الكريم وسائر الكتب السماوية ويتبع مسالك الرسل العظام عليهم الصلاة والسلام."


def run_command(command: list[str]) -> str:
    completed = subprocess.run(command, text=True, capture_output=True)
    output = (completed.stdout + "\n" + completed.stderr).strip()
    if completed.returncode:
        raise RuntimeError(output or f"Command failed: {' '.join(command)}")
    return output


def generate(
    manuscript: str | None,
    reference_audio: str | None,
    reference_text: str,
    cover: str | None,
    chapters: str | None,
    title: str,
    author: str,
    narrator: str,
    description: str,
    codec: str,
    bitrate: str,
    seed: str,
    progress=gr.Progress(track_tqdm=True),
):
    if not manuscript or not reference_audio:
        raise gr.Error("Please upload both the manuscript text and reference WAV.")
    if not title.strip():
        raise gr.Error("Please enter a book title.")

    work = Path(tempfile.mkdtemp(prefix="latif-colab-"))
    output = work / "audiobook_output"
    text_path = work / "manuscript.txt"
    ref_path = work / "reference.wav"
    shutil.copy2(manuscript, text_path)
    shutil.copy2(reference_audio, ref_path)
    cover_path = None
    chapters_path = None
    if cover:
        cover_path = work / Path(cover).name
        shutil.copy2(cover, cover_path)
    if chapters:
        chapters_path = work / "chapters.json"
        shutil.copy2(chapters, chapters_path)
        try:
            json.loads(chapters_path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise gr.Error(f"Invalid chapters.json: {exc}") from exc

    progress(0.05, desc="Preparing SILMA voice generation")
    command = [
        "python", str(GENERATOR),
        "--text", str(text_path),
        "--reference-audio", str(ref_path),
        "--reference-text", reference_text or DEFAULT_REF_TEXT,
        "--output-dir", str(output),
    ]
    if seed.strip():
        command += ["--seed", seed.strip()]
    generation_log = run_command(command)

    progress(0.80, desc="Packaging M4A with metadata")
    m4a = output / "audiobook_master.m4a"
    command = [
        "python", str(PACKAGER),
        "--wav", str(output / "audiobook_master.wav"),
        "--output", str(m4a),
        "--title", title,
        "--author", author,
        "--narrator", narrator,
        "--description", description,
        "--codec", codec,
    ]
    if codec == "aac":
        command += ["--bitrate", bitrate]
    if cover_path:
        command += ["--cover", str(cover_path)]
    if chapters_path:
        command += ["--chapters-json", str(chapters_path)]
    packaging_log = run_command(command)

    progress(1.0, desc="Finished")
    clips = sorted(output.glob("clip_*.wav"))
    summary = (
        "Generation complete. Audio was kept pure: no fade-in, fade-out, normalization, "
        "EQ, denoise, music, or crossfade.\n\n"
        f"Generated clips: {len(clips)}\n\n{generation_log}\n\n{packaging_log}"
    )
    return summary, str(output / "audiobook_master.wav"), str(m4a), [str(p) for p in clips]


with gr.Blocks(title="LATIF AI Voice Studio") as demo:
    gr.Markdown(
        """# LATIF AI Voice Studio\n\n"
        "Upload an Arabic manuscript and an authorized reference WAV. The app generates SILMA audiobook clips and packages a tagged M4A.\n\n"
        "**Pure audio mode:** no fades, normalization, EQ, denoise, music, or crossfades."""
    )
    with gr.Row():
        manuscript = gr.File(label="Manuscript TXT", file_types=[".txt"], type="filepath")
        reference_audio = gr.File(label="Reference WAV", file_types=[".wav"], type="filepath")
        cover = gr.File(label="Optional cover", file_types=[".jpg", ".jpeg", ".png"], type="filepath")
        chapters = gr.File(label="Optional chapters.json", file_types=[".json"], type="filepath")
    reference_text = gr.Textbox(label="Exact reference transcript", value=DEFAULT_REF_TEXT, lines=3, rtl=True)
    with gr.Row():
        title = gr.Textbox(label="Book title", value="LATIF Audiobook")
        author = gr.Textbox(label="Author")
        narrator = gr.Textbox(label="Narrator", value="LATIF Author Narrator")
    description = gr.Textbox(label="Description", value="AI-generated audiobook for private author review", lines=2)
    with gr.Row():
        codec = gr.Radio(["aac", "alac"], value="aac", label="M4A codec")
        bitrate = gr.Dropdown(["96k", "128k", "192k", "256k"], value="128k", label="AAC bitrate")
        seed = gr.Textbox(label="Optional seed")
    run = gr.Button("Generate Audiobook", variant="primary")
    status = gr.Textbox(label="Status", lines=8)
    with gr.Row():
        wav = gr.File(label="Master WAV")
        m4a = gr.File(label="Tagged M4A")
    clips = gr.Files(label="Individual WAV clips")
    run.click(
        generate,
        inputs=[manuscript, reference_audio, reference_text, cover, chapters, title, author, narrator, description, codec, bitrate, seed],
        outputs=[status, wav, m4a, clips],
    )

if __name__ == "__main__":
    demo.queue(max_size=2).launch(share=True, debug=True)
