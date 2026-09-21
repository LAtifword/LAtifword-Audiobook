"""Backend contract/checkpoint tests. Fake PCM is test-only, never a voice fallback."""
import io
import tempfile
import threading
import time
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import numpy as np
from fastapi.testclient import TestClient
import server


def reference_wav(seconds=3):
    output = io.BytesIO()
    with wave.open(output, 'wb') as stream:
        stream.setparams((1, 2, 24000, 0, 'NONE', 'not compressed'))
        stream.writeframes(np.zeros(seconds * 24000, dtype=np.int16).tobytes())
    return output.getvalue()


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.paths = patch.multiple(server, ROOT=root, JOBS=root / 'jobs', VOICES=root / 'voices', MODELS=root / 'models')
        self.paths.start()
        # Suppress real inference only for these unit tests.
        self.worker = patch.object(server, 'worker', lambda: None)
        self.worker.start()
        self.client = TestClient(server.app)
        self.client.__enter__()
        self.client.get('/')
        self.headers = {'X-Latif-Studio': '1'}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        server.ACTIVE.clear()
        self.worker.stop()
        self.paths.stop()
        self.directory.cleanup()

    def voice(self, seconds=3):
        return self.client.post('/api/voices', headers=self.headers,
            files={'audio': ('ref.wav', reference_wav(seconds), 'audio/wav')},
            data={'name': 'Narrator', 'transcript': 'هذا نص التجربة.', 'consent': 'yes'})

    def job(self):
        voice = self.voice().json()['id']
        with patch.object(server, 'assets_ready', return_value=(True, '')):
            response = self.client.post('/api/jobs', headers=self.headers,
                json={'text': 'مرحبا بالعالم. ' * 40, 'voice': voice, 'title': 'Test'})
        self.assertEqual(response.status_code, 200, response.text)
        return server.read_job(response.json()['id'])

    def test_auth_and_csrf(self):
        self.assertEqual(self.client.post('/api/jobs', json={}).status_code, 403)
        self.assertEqual(self.client.post('/api/jobs', headers={**self.headers, 'Origin': 'https://evil.example'}, json={}).status_code, 403)
        self.assertEqual(self.client.get('/api/status', headers={'Host': 'evil.example'}).status_code, 400)
        self.client.cookies.clear()
        self.assertEqual(self.client.get('/api/status').status_code, 403)

    def test_reference_not_silently_truncated(self):
        self.assertEqual(self.voice(16).status_code, 400)
        self.assertEqual(self.voice(1).status_code, 400)
        self.assertEqual(self.voice(3).status_code, 200)

    def test_full_text_preserved(self):
        job = self.job()
        self.assertEqual(' '.join(job['chunks']).split(), ('مرحبا بالعالم. ' * 40).split())
        self.assertGreater(job['total'], 1)
        self.assertEqual(self.client.get('/api/jobs/'+job['id']+'/audio').status_code, 409)

    def test_pause_resume(self):
        job = self.job()
        base = '/api/jobs/'+job['id']
        self.assertEqual(self.client.post(base+'/pause', headers=self.headers).status_code, 200)
        self.assertEqual(server.read_job(job['id'])['state'], 'paused')
        self.assertEqual(self.client.post(base+'/resume', headers=self.headers).status_code, 200)
        self.assertEqual(server.read_job(job['id'])['state'], 'queued')

    def test_missing_assets_are_not_ready(self):
        self.assertFalse(self.client.get('/api/status').json()['ready'])

    def test_text_import(self):
        response = self.client.post('/api/import', headers=self.headers,
            files={'book': ('book.txt', 'الفصل الأول\nنص الكتاب'.encode(), 'text/plain')})
        self.assertEqual(response.status_code, 200)
        self.assertIn('الفصل الأول', response.json()['text'])

    def test_checkpoint_and_real_mp3_export(self):
        job = self.job()
        event = threading.Event()
        server.ACTIVE[job['id']] = event
        class FakeEngine:
            sample_rate = 24000
            calls = 0
            def load(self): pass
            def custom_reference(self, *args): return None
            def synthesize(self, *args, **kwargs):
                self.calls += 1
                if self.calls == 1: event.set()
                return (np.sin(np.arange(2400)*0.12)*1000).astype(np.int16)
        engine = FakeEngine()
        server.render(job, engine)
        self.assertEqual(server.read_job(job['id'])['state'], 'paused')
        self.assertTrue((server.JOBS/job['id']/'000000.wav').exists())
        event.clear()
        server.render(job, engine)
        self.assertEqual(server.read_job(job['id'])['state'], 'completed')
        self.assertEqual(engine.calls, job['total'])
        response = self.client.get('/api/jobs/'+job['id']+'/audio')
        self.assertEqual(response.status_code, 200)
        self.assertGreater(len(response.content), 100)


if __name__ == '__main__':
    unittest.main()
