#!/usr/bin/env bash
set -euo pipefail
studio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(uname -m)" != aarch64 ] && [ "$(uname -m)" != x86_64 ]; then
  echo 'Supported: ARM64 Ubuntu or x86_64 Linux'; exit 1
fi
if [ "$(id -u)" != 0 ]; then
  echo 'Run this installer inside proot-distro login ubuntu (root).'; exit 1
fi
apt-get update
apt-get install -y python3 python3-venv ffmpeg libgomp1 ca-certificates
python3 -m venv "$studio_dir/.venv"
"$studio_dir/.venv/bin/pip" install --only-binary=:all: -r "$studio_dir/requirements.txt"
"$studio_dir/.venv/bin/python" "$studio_dir/download_models.py"
echo 'Installation finished. Launch with bash termux-studio/start-ubuntu.sh'
