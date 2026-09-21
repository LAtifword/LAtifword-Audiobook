#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v termux-wake-lock >/dev/null; then termux-wake-lock; fi
trap 'command -v termux-wake-unlock >/dev/null && termux-wake-unlock || true' EXIT
proot-distro login latif-voice --bind "$repo_dir:/opt/latif-studio" -- bash /opt/latif-studio/termux-studio/start-ubuntu.sh
