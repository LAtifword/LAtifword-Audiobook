from __future__ import annotations

import json
import mimetypes
import os
import shutil
import tempfile
import time
import traceback
import uuid
from dataclasses import asdict, dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Event, Lock, Thread
from urllib.parse import parse_qs, unquote, urlparse

from latif_voice_studio.audio import ChapterTiming, M4aStreamWriter, safe_filename
from latif_voice_studio.books import chunks_for_book, read_book
from latif_voice_studio.engine import GenerationCancelled, SilmaDesktopEngine

HOST = "127.0.0.1"
PORT = 8765
ALLOWED_ORIGINS = {
    "https://latif-brain.wordeco-ltd.chatgpt.site",
    "http://localhost:3000",
    "http://localhost:4173",
}
MAX_UPLOAD = 250 * 1024 * 1024


@dataclass
class Job:
    id: str
    title: str
    author: str
    source_path: Path
    preview_only: bool
    speed: float
    steps: int
    backend: str
    dml_device_id: int
    state: str = "queued"
    status: str = "Queued"
    detail: str = ""
    progress: int = 0
    active_backend: str = ""
    output_path: Path | None = None
    error: str | None = None
    created_at: float = field(default_factory=time.time)
    cancel_event: Event = field(default_factory=Event, repr=False)

    def public(self) -> dict:
        return {
            "id": self.id, "title": self.title, "author": self.author,
            "state": self.state, "status": self.status, "detail": self.detail,
            "progress": self.progress, "activeBackend": self.active_backend,
            "error": self.error, "createdAt": self.created_at,
            "hasAudio": bool(self.output_path and self.output_path.exists()),
            "hasChapters": bool(self.output_path and self.output_path.with_suffix(".chapters.json").exists()),
        }


_JOBS: dict[str, Job] = {}
_JOBS_LOCK = Lock()
_RENDER_LOCK = Lock()


def _pause_ms(text: str) -> int:
    clean = text.rstrip()
    if clean.endswith(("؟", "!", "?")): return 160
    if clean.endswith((".", "…", "؛")): return 125
    if clean.endswith(("،", ",", ":")): return 75
    return 55


def _run(job: Job) -> None:
    writer = None
    engine = None
    with _RENDER_LOCK:
        try:
            job.state = "loading"
            job.status = "Reading manuscript"
            text = read_book(job.source_path)
            max_chars = 175 if job.steps <= 8 else 195 if job.steps <= 16 else 210 if job.steps <= 24 else 220
            chunks = chunks_for_book(text, fallback_title=job.title, max_chars=max_chars)
            if job.preview_only:
                chunks = chunks[:1]
            if not chunks:
                raise ValueError("No narration chunks could be created")

            job.status = "Loading SILMA model"
            engine = SilmaDesktopEngine(backend=job.backend, dml_device_id=job.dml_device_id)
            engine.load(progress=lambda value: setattr(job, "detail", value))
            job.active_backend = engine.backend_name
            reference = engine.built_in_reference()

            output_dir = Path.home() / "Music" / "LATIF Audiobooks"
            suffix = "-PREVIEW" if job.preview_only else ""
            output_path = output_dir / f"{safe_filename(job.title)}{suffix}.m4a"
            writer = M4aStreamWriter(
                output_path=output_path, sample_rate=engine.sample_rate, bitrate=128_000,
                title=job.title, author=job.author, narrator="LATIF Author Narrator",
            )
            job.state = "rendering"
            total = len(chunks)
            started = time.perf_counter()
            chapter_timings: list[ChapterTiming] = []
            current_chapter = chunks[0].chapter_title
            chapter_start = 0

            for index, chunk in enumerate(chunks):
                if job.cancel_event.is_set():
                    raise GenerationCancelled()
                if chunk.chapter_title != current_chapter:
                    chapter_timings.append(ChapterTiming(current_chapter, chapter_start, writer.duration_ms))
                    current_chapter = chunk.chapter_title
                    chapter_start = writer.duration_ms
                job.status = f"Narrating section {index + 1}/{total}"

                def on_step(step: int, count: int) -> None:
                    job.progress = min(99, int(((index + step / max(1, count)) / total) * 100))
                    job.detail = f"Section {index + 1}/{total} · F5 refinement {step}/{count}"

                audio = engine.synthesize(
                    reference=reference, text=chunk.text, speed=job.speed,
                    nfe_steps=job.steps, cancel_event=job.cancel_event, on_step=on_step,
                )
                writer.write_pcm16(audio)
                writer.write_silence(_pause_ms(chunk.text))
                elapsed = time.perf_counter() - started
                eta = (elapsed / (index + 1)) * (total - index - 1)
                job.detail = f"{index + 1}/{total} complete · ETA {int(eta // 60)}m {int(eta % 60):02d}s"
                job.progress = int(((index + 1) / total) * 100)

            chapter_timings.append(ChapterTiming(current_chapter, chapter_start, writer.duration_ms))
            job.output_path = writer.finish(chapter_timings)
            writer = None
            job.progress = 100
            job.state = "completed"
            job.status = "Audiobook complete"
            job.detail = str(job.output_path)
        except GenerationCancelled:
            if writer: writer.abort()
            job.state = "cancelled"
            job.status = "Generation cancelled"
            job.detail = "Temporary output removed"
        except Exception as exc:
            if writer: writer.abort()
            traceback.print_exc()
            job.state = "failed"
            job.status = "Generation failed"
            job.error = f"{type(exc).__name__}: {exc}"
            job.detail = job.error
        finally:
            if engine: engine.close()
            try: job.source_path.unlink(missing_ok=True)
            except OSError: pass


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "LATIFBrainBridge/1.0"

    def _origin(self) -> str:
        return self.headers.get("Origin", "")

    def _cors(self) -> None:
        origin = self._origin()
        if origin in ALLOWED_ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Cache-Control", "no-store")

    def _json(self, status: int, value: dict) -> None:
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _allowed(self) -> bool:
        origin = self._origin()
        return not origin or origin in ALLOWED_ORIGINS

    def do_OPTIONS(self) -> None:
        if not self._allowed():
            self._json(HTTPStatus.FORBIDDEN, {"error": "Origin not allowed"})
            return
        self.send_response(HTTPStatus.NO_CONTENT)
        self._cors()
        self.end_headers()

    def do_GET(self) -> None:
        if not self._allowed():
            self._json(HTTPStatus.FORBIDDEN, {"error": "Origin not allowed"}); return
        parsed = urlparse(self.path)
        if parsed.path == "/v1/health":
            self._json(HTTPStatus.OK, {
                "ok": True, "service": "LATIF Brain Bridge", "version": "1.0",
                "engine": "SILMA F5", "formats": ["pdf", "epub", "docx", "txt"],
                "qualitySteps": [8, 16, 24, 32],
            }); return
        parts = parsed.path.strip("/").split("/")
        if len(parts) >= 3 and parts[:2] == ["v1", "jobs"]:
            job = _JOBS.get(parts[2])
            if not job: self._json(HTTPStatus.NOT_FOUND, {"error": "Job not found"}); return
            if len(parts) == 3:
                self._json(HTTPStatus.OK, job.public()); return
            if len(parts) == 4 and parts[3] in {"audio", "chapters"}:
                path = job.output_path if parts[3] == "audio" else (job.output_path.with_suffix(".chapters.json") if job.output_path else None)
                if not path or not path.exists():
                    self._json(HTTPStatus.NOT_FOUND, {"error": "Output not ready"}); return
                self.send_response(HTTPStatus.OK); self._cors()
                mime = "audio/mp4" if parts[3] == "audio" else "application/json"
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(path.stat().st_size))
                self.send_header("Content-Disposition", f'attachment; filename="{path.name}"')
                self.end_headers()
                with path.open("rb") as handle: shutil.copyfileobj(handle, self.wfile)
                return
        self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

    def do_POST(self) -> None:
        if not self._allowed():
            self._json(HTTPStatus.FORBIDDEN, {"error": "Origin not allowed"}); return
        parsed = urlparse(self.path)
        if parsed.path != "/v1/jobs":
            self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"}); return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_UPLOAD:
            self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Invalid or oversized manuscript"}); return
        query = parse_qs(parsed.query)
        filename = Path(unquote(query.get("filename", ["book.txt"])[0])).name
        suffix = Path(filename).suffix.lower()
        if suffix not in {".pdf", ".epub", ".docx", ".txt"}:
            self._json(HTTPStatus.BAD_REQUEST, {"error": "Supported: PDF, EPUB, DOCX, TXT"}); return
        job_id = uuid.uuid4().hex
        upload_dir = Path(tempfile.gettempdir()) / "latif-brain-bridge"
        upload_dir.mkdir(parents=True, exist_ok=True)
        source = upload_dir / f"{job_id}{suffix}"
        remaining = length
        with source.open("wb") as handle:
            while remaining:
                chunk = self.rfile.read(min(1024 * 1024, remaining))
                if not chunk: break
                handle.write(chunk); remaining -= len(chunk)
        job = Job(
            id=job_id, title=query.get("title", [Path(filename).stem])[0],
            author=query.get("author", [""])[0], source_path=source,
            preview_only=query.get("preview", ["false"])[0].lower() == "true",
            speed=max(0.82, min(1.08, float(query.get("speed", ["0.90"])[0]))),
            steps=int(query.get("steps", ["32"])[0]),
            backend=query.get("backend", ["auto"])[0],
            dml_device_id=int(query.get("device", ["0"])[0]),
        )
        if job.steps not in {8, 16, 24, 32}: job.steps = 32
        with _JOBS_LOCK: _JOBS[job_id] = job
        Thread(target=_run, args=(job,), daemon=True, name=f"latif-job-{job_id[:8]}").start()
        self._json(HTTPStatus.ACCEPTED, job.public())

    def do_DELETE(self) -> None:
        if not self._allowed():
            self._json(HTTPStatus.FORBIDDEN, {"error": "Origin not allowed"}); return
        parts = urlparse(self.path).path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["v1", "jobs"]:
            job = _JOBS.get(parts[2])
            if not job: self._json(HTTPStatus.NOT_FOUND, {"error": "Job not found"}); return
            job.cancel_event.set()
            self._json(HTTPStatus.ACCEPTED, job.public()); return
        self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

    def log_message(self, format: str, *args) -> None:
        return


def start_bridge() -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((HOST, PORT), BridgeHandler)
    Thread(target=server.serve_forever, daemon=True, name="latif-brain-bridge").start()
    return server


def main() -> None:
    server = start_bridge()
    print(f"LATIF Brain Bridge ready at http://{HOST}:{PORT}")
    try:
        while True: time.sleep(3600)
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
