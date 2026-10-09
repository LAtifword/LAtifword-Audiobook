"""Explicit installation-only download. Never imported by the studio server."""
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'desktop-v3.3'))
from latif_voice_studio.engine import SilmaDesktopEngine

HASHES = {
    'F5_Preprocess.onnx': '8d7a43fbd05dc3176f171436e36d9832610fbb811cd7cdc2bcbd19c8d56fdf87',
    'model.onnx': '6b253b3512a7f9974089ca1231aff08537db44d51a6cb0a0fb89d562c624c888',
    'F5_Decode.onnx': '4fd5ab10d6edf0e6882252c30857dadbf01d4f2b233f9163ed7522d5184a50e8',
    'config.json': '310cee6a464b1d4de6056131432d4d92a7d61e8f26e2fe4d65fbd1a267d827e1',
    'default_ref.wav': 'b6b88232c3b851a3d9833c66349c71b2527de346b467ccba944809bad776c7ae',
    'vocab.txt': '5c2ffc48802a52bbdf715dacf1d6519d3fee96e391aef690261963a692b8e661',
}

def sha256(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()

def main():
    root = Path(os.environ.get('LATIF_DATA', str(Path.home() / '.local/share/latif-studio')))
    destination = Path(os.environ.get('LATIF_MODELS', str(root / 'models')))
    destination.mkdir(parents=True, exist_ok=True)
    base = 'https://huggingface.co/OpenVoiceOS/phoonnx-f5tts/resolve/e2c3f3757931b95994d0ecc2f8628c22c1e20450/silma-tts-v1/'
    manifest = {}
    for name, expected in SilmaDesktopEngine()._required_files().items():
        path = destination / name
        url = base + name
        if name == 'default_ref.wav':
            url = 'https://huggingface.co/spaces/silma-ai/silma-tts-v1-demo/resolve/main/ar.ref.24k.wav'
        if not path.exists() or path.stat().st_size != expected:
            print('Downloading', name, flush=True)
            temporary = path.with_suffix('.download')
            with urllib.request.urlopen(url, timeout=120) as source, temporary.open('wb') as output:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)
            if temporary.stat().st_size != expected:
                raise RuntimeError(f'{name}: unexpected model revision or incomplete download; not installed')
            if sha256(temporary) != HASHES[name]:
                raise RuntimeError(f'{name}: downloaded file checksum mismatch; not installed')
            temporary.replace(path)
        digest = sha256(path)
        if digest != HASHES[name]:
            raise RuntimeError(f'{name}: existing file checksum mismatch; preserve it separately before reinstalling')
        manifest[name] = {'bytes': expected, 'sha256': digest, 'source': url}
        print('Verified size and SHA-256:', name, flush=True)
    (destination / 'installed-manifest.json').write_text(json.dumps(manifest, indent=2))
    print('Models installed. Inference now needs no internet.')

if __name__ == '__main__':
    main()
