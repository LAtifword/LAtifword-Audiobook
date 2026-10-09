#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v pkg >/dev/null; then
  echo 'Run this script from Termux, not from inside Ubuntu.'; exit 1
fi
pkg install -y proot-distro
if ! proot-distro login latif-voice -- /bin/true 2>/dev/null; then
  proot-distro install ubuntu:24.04 --name latif-voice
fi
proot-distro login latif-voice --bind "$repo_dir:/opt/latif-studio" -- bash /opt/latif-studio/termux-studio/install-ubuntu.sh
echo 'Ready. Start with: bash termux-studio/start-termux.sh'
