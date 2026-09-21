"""Opt-in real inference integration check; requires installed models and httpx."""
import json
import resource
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
import server
from latif_voice_studio.engine import DEFAULT_REFERENCE_TEXT


def main():
    models = server.MODELS
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='latif-smoke-') as directory:
        root = Path(directory)
        with patch.multiple(server, ROOT=root, JOBS=root/'jobs', VOICES=root/'voices'), TestClient(server.app) as client:
            assert client.get('/').status_code == 200
            headers = {'X-Latif-Studio': '1'}
            with (models/'default_ref.wav').open('rb') as reference:
                result = client.post('/api/voices', headers=headers,
                    files={'audio': ('reference.wav', reference, 'audio/wav')},
                    data={'consent': 'yes', 'name': 'Official model test reference', 'transcript': DEFAULT_REFERENCE_TEXT})
            assert result.status_code == 200, result.text
            result = client.post('/api/jobs', headers=headers, json={
                'title': 'Integration test', 'voice': result.json()['id'],
                'text': 'مَرْحَبًا بِكُمْ فِي عَالَمِ الْكُتُبِ.', 'preview': False})
            assert result.status_code == 200, result.text
            identifier = result.json()['id']
            last_state = None
            while time.monotonic() - start < 600:
                job = client.get('/api/jobs').json()[0]
                if job['state'] != last_state:
                    print(job['state'], job['message'], flush=True)
                    last_state = job['state']
                if job['state'] in {'failed', 'paused'}:
                    raise RuntimeError(job['message'])
                if job['state'] == 'completed':
                    output = client.get(f'/api/jobs/{identifier}/audio')
                    assert output.status_code == 200 and len(output.content) > 100
                    print(json.dumps({'real_inference': True, 'mp3_bytes': len(output.content),
                        'elapsed_seconds': round(time.monotonic()-start, 2),
                        'peak_rss_kb': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}), flush=True)
                    return
                time.sleep(1)
            raise TimeoutError('Real inference exceeded 10 minutes')


if __name__ == '__main__':
    main()
