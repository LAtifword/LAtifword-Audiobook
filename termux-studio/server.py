"""Phone-local studio. No hosted inference, CDN, telemetry, or runtime download."""
from __future__ import annotations

import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import uuid
import wave
from contextlib import asynccontextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'desktop-v3.3'))
from latif_voice_studio.engine import SilmaDesktopEngine, GenerationCancelled
from latif_voice_studio.books import read_book, split_for_narration
from fastapi import FastAPI, HTTPException, Request, UploadFile, File
from fastapi.responses import FileResponse, JSONResponse
from starlette.middleware.trustedhost import TrustedHostMiddleware

ROOT = Path(os.environ.get('LATIF_DATA', str(Path.home() / '.local/share/latif-studio')))
MODELS = Path(os.environ.get('LATIF_MODELS', str(ROOT / 'models')))
JOBS = ROOT / 'jobs'
VOICES = ROOT / 'voices'
LOCK = threading.RLock()
WAKE = threading.Event()
STOP = threading.Event()
ACTIVE = {}
TOKEN = secrets.token_urlsafe(32)
MAX_UPLOAD = 32 * 1024 * 1024


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    temporary.replace(path)


def read_job(identifier):
    if len(identifier) != 32 or any(c not in '0123456789abcdef' for c in identifier):
        raise HTTPException(404, 'Unknown job')
    path = JOBS / identifier / 'job.json'
    if not path.is_file():
        raise HTTPException(404, 'Unknown job')
    return json.loads(path.read_text(encoding='utf-8'))


def update(identifier, **fields):
    with LOCK:
        job = read_job(identifier)
        job.update(fields)
        save(JOBS / identifier / 'job.json', job)
        return job


def assets_ready():
    try:
        SilmaDesktopEngine(model_dir=MODELS, backend='cpu').verify_assets()
        return True, ''
    except Exception as exc:
        return False, str(exc)


def render(job, engine):
    identifier = job['id']
    directory = JOBS / identifier
    cancel = ACTIVE[identifier]
    try:
        update(identifier, state='loading', message='Loading local SILMA model')
        engine.load()
        voice = VOICES / job['voice']
        reference = engine.custom_reference(voice / 'reference.wav', (voice / 'transcript.txt').read_text())
        chunks = job['chunks']
        started = time.monotonic()
        for index, text in enumerate(chunks):
            if cancel.is_set():
                raise GenerationCancelled()
            clip = directory / f'{index:06d}.wav'
            if clip.exists():
                with wave.open(str(clip)) as check:
                    if check.getnframes() <= 0:
                        raise ValueError('Invalid checkpoint audio')
                continue
            update(identifier, state='rendering', completed=index, message=f'Section {index + 1} / {len(chunks)}')
            # This exported graph carries its own fixed integration schedule.
            # Always complete all 32 steps; truncating it is not a quality mode.
            pcm = engine.synthesize(reference, text, speed=job['speed'], nfe_steps=32, cancel_event=cancel)
            temporary = clip.with_suffix('.partial')
            with wave.open(str(temporary), 'wb') as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(engine.sample_rate)
                output.writeframes(pcm.tobytes())
            temporary.replace(clip)
            update(identifier, completed=index + 1, elapsed=round(time.monotonic() - started, 1))
        if cancel.is_set():
            raise GenerationCancelled()
        update(identifier, state='exporting', message='Joining all sections; exporting MP3')
        # Chunk files are deterministic generated names, never user filenames.
        listing = directory / 'clips.txt'
        listing.write_text(''.join(f"file '{i:06d}.wav'\n" for i in range(len(chunks))))
        target = directory / 'audiobook.partial.mp3'
        with (directory / 'export.log').open('wb') as log:
            process = subprocess.Popen(['ffmpeg', '-nostdin', '-y', '-v', 'error', '-f', 'concat', '-safe', '1',
                '-i', str(listing), '-c:a', 'libmp3lame', '-b:a', '128k', '-metadata', f"title={job['title']}",
                str(target)], stdout=log, stderr=log)
            while process.poll() is None:
                if cancel.wait(0.2):
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                    raise GenerationCancelled()
            if process.returncode:
                raise RuntimeError('FFmpeg export failed; see export.log in the job folder')
        if not target.exists() or target.stat().st_size < 100:
            raise RuntimeError('Export returned no audio')
        target.replace(directory / 'audiobook.mp3')
        update(identifier, state='completed', completed=len(chunks), message='Full audiobook ready')
    except GenerationCancelled:
        update(identifier, state='paused', message='Paused; finished sections retained')
    except Exception as exc:
        update(identifier, state='failed', message=f'{type(exc).__name__}: {exc}')


def worker():
    engine = SilmaDesktopEngine(model_dir=MODELS, backend='cpu')
    engine.worker_threads = max(1, min(8, int(os.environ.get('LATIF_THREADS', '2'))))
    try:
        while not STOP.is_set():
            with LOCK:
                pending = [json.loads(p.read_text()) for p in JOBS.glob('*/job.json')]
                pending = sorted((j for j in pending if j['state'] == 'queued'), key=lambda j: j['created'])
                job = pending[0] if pending else None
                if job:
                    ACTIVE[job['id']] = threading.Event()
            if job:
                render(job, engine)
                with LOCK:
                    ACTIVE.pop(job['id'], None)
            else:
                engine.close_sessions()
                WAKE.wait(1)
                WAKE.clear()
    finally:
        engine.close()


@asynccontextmanager
async def lifespan(app):
    JOBS.mkdir(parents=True, exist_ok=True)
    VOICES.mkdir(parents=True, exist_ok=True)
    STOP.clear()
    for path in JOBS.glob('*/job.json'):
        job = json.loads(path.read_text())
        if job['state'] in {'loading', 'rendering', 'exporting', 'queued'}:
            update(job['id'], state='paused', message='Interrupted; resume saved sections')
    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    yield
    STOP.set()
    for event in list(ACTIVE.values()):
        event.set()
    WAKE.set()
    thread.join(timeout=5)


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=['127.0.0.1', 'localhost', 'testserver'])


@app.middleware('http')
async def local_access(request: Request, call_next):
    if request.url.path != '/' and request.cookies.get('latif_session') != TOKEN:
        return JSONResponse({'detail': 'Open the studio home page first'}, status_code=403)
    if request.method not in {'GET', 'HEAD'}:
        try:
            length = int(request.headers.get('content-length', '0'))
        except ValueError:
            return JSONResponse({'detail': 'Invalid content length'}, status_code=400)
        if length < 0 or length > MAX_UPLOAD + 65536:
            return JSONResponse({'detail': 'Upload too large'}, status_code=413)
        if request.headers.get('x-latif-studio') != '1':
            return JSONResponse({'detail': 'Studio request header required'}, status_code=403)
        if request.headers.get('origin') not in {None, str(request.base_url).rstrip('/')}:
            return JSONResponse({'detail': 'External origin blocked'}, status_code=403)
    response = await call_next(request)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Referrer-Policy'] = 'no-referrer'
    response.headers['Cache-Control'] = 'no-store'
    response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self'; frame-ancestors 'none'"
    return response


@app.get('/')
def home():
    response = FileResponse(Path(__file__).with_name('index.html'))
    response.set_cookie('latif_session', TOKEN, httponly=True, samesite='strict')
    return response


@app.get('/api/status')
def status():
    ready, detail = assets_ready()
    return {'ready': ready, 'detail': detail, 'engine': 'SILMA F5 · CPU · 32 steps',
            'free_gb': round(shutil.disk_usage(ROOT).free / 1024 ** 3, 1),
            'voices': [{'id': p.name, 'name': json.loads((p / 'voice.json').read_text())['name']}
                       for p in VOICES.iterdir() if (p / 'voice.json').exists()]}


async def store_upload(upload, path):
    total = 0
    try:
        with path.open('wb') as stream:
            while data := await upload.read(1024 * 1024):
                total += len(data)
                if total > MAX_UPLOAD:
                    raise HTTPException(413, 'Maximum upload size: 32 MB')
                stream.write(data)
    finally:
        await upload.close()
    if not total:
        raise HTTPException(400, 'Empty upload')


@app.post('/api/voices')
async def add_voice(request: Request):
    form = await request.form()
    if form.get('consent') != 'yes':
        await form.close()
        raise HTTPException(400, 'Confirm permission to use the voice')
    transcript = str(form.get('transcript', '')).strip()
    if not transcript or len(transcript) > 3000:
        await form.close()
        raise HTTPException(400, 'Provide the exact reference transcript, up to 3000 characters')
    upload = form.get('audio')
    if not hasattr(upload, 'read'):
        raise HTTPException(400, 'Reference WAV required')
    identifier = uuid.uuid4().hex
    directory = VOICES / identifier
    directory.mkdir()
    path = directory / 'reference.wav'
    try:
        await store_upload(upload, path)
        with wave.open(str(path)) as audio:
            duration = audio.getnframes() / audio.getframerate()
            if audio.getsampwidth() != 2 or audio.getnchannels() not in (1, 2) or not 2 <= duration <= 15:
                raise ValueError('Use a 2–15 second mono/stereo 16-bit PCM WAV; no silent truncation')
        (directory / 'transcript.txt').write_text(transcript, encoding='utf-8')
        save(directory / 'voice.json', {'name': str(form.get('name', 'My voice'))[:100]})
    except Exception as exc:
        shutil.rmtree(directory)  # Only this newly-created failed upload.
        if isinstance(exc, HTTPException):
            raise
        raise HTTPException(400, str(exc)) from exc
    return {'id': identifier}


@app.post('/api/import')
async def import_book(book: UploadFile = File(...)):
    suffix = Path(book.filename or '').suffix.lower()
    if suffix not in {'.txt', '.pdf', '.docx'}:
        raise HTTPException(400, 'This build accepts TXT, text-based PDF and DOCX')
    path = ROOT / f'upload-{uuid.uuid4().hex}{suffix}'
    try:
        await store_upload(book, path)
        text = read_book(path)
        if len(text) > 2_000_000:
            raise ValueError('Book exceeds 2 million characters; import one volume at a time')
        return {'text': text}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(400, str(exc)) from exc
    finally:
        path.unlink(missing_ok=True)


@app.get('/api/jobs')
def list_jobs():
    with LOCK:
        jobs = [json.loads(p.read_text()) for p in JOBS.glob('*/job.json')]
    return [{k: v for k, v in j.items() if k != 'chunks'} for j in sorted(jobs, key=lambda j: j['created'], reverse=True)]


@app.post('/api/jobs')
async def create_job(request: Request):
    if int(request.headers.get('content-length', '0')) > 12_000_000:
        raise HTTPException(413, 'Request too large')
    try:
        value = await request.json()
    except ValueError:
        raise HTTPException(400, 'Invalid JSON')
    if not isinstance(value, dict):
        raise HTTPException(400, 'Expected a JSON object')
    text = str(value.get('text', '')).strip()
    voice = str(value.get('voice', ''))
    if not text or len(text) > 2_000_000:
        raise HTTPException(400, 'Enter 1–2,000,000 characters')
    if len(voice) != 32 or any(c not in '0123456789abcdef' for c in voice) or not (VOICES / voice / 'voice.json').exists():
        raise HTTPException(400, 'Select a saved voice')
    try:
        speed = float(value.get('speed', 0.9))
        if not 0.8 <= speed <= 1.2:
            raise ValueError()
    except (TypeError, ValueError):
        raise HTTPException(400, 'Speed must be 0.8–1.2')
    ready, detail = assets_ready()
    if not ready:
        raise HTTPException(409, detail)
    chunks = split_for_narration(text, max_chars=160)
    preview = value.get('preview') is True
    if preview:
        chunks = chunks[:1]
    identifier = uuid.uuid4().hex
    job = {'id': identifier, 'title': str(value.get('title', 'Untitled'))[:200], 'voice': voice,
           'chunks': chunks, 'total': len(chunks), 'completed': 0, 'speed': speed,
           'state': 'queued', 'created': time.time(), 'preview': preview, 'message': 'Queued'}
    with LOCK:
        if sum(j['state'] in {'queued', 'loading', 'rendering', 'exporting'} for j in list_jobs()) >= 20:
            raise HTTPException(429, 'Queue full: finish or pause a job first')
        (JOBS / identifier).mkdir()
        save(JOBS / identifier / 'job.json', job)
    WAKE.set()
    return {'id': identifier}


@app.post('/api/jobs/{identifier}/{action}')
def action_job(identifier: str, action: str):
    with LOCK:
        job = read_job(identifier)
        if action == 'pause':
            if identifier in ACTIVE:
                ACTIVE[identifier].set()
            elif job['state'] == 'queued':
                update(identifier, state='paused', message='Paused')
        elif action == 'resume' and job['state'] in {'paused', 'failed'}:
            update(identifier, state='queued', message='Resuming saved sections')
            WAKE.set()
        else:
            raise HTTPException(409, 'Action not available')
    return {'ok': True}


@app.get('/api/jobs/{identifier}/audio')
def audio_job(identifier: str):
    job = read_job(identifier)
    if job['state'] != 'completed':
        raise HTTPException(409, 'Full output is not ready')
    return FileResponse(JOBS / identifier / 'audiobook.mp3', media_type='audio/mpeg', filename='latif-audiobook.mp3')


if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=8765, workers=1)
