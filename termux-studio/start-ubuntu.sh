#!/usr/bin/env bash
set -euo pipefail
studio_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export LATIF_THREADS="${LATIF_THREADS:-2}"
export OMP_NUM_THREADS="$LATIF_THREADS"
export OPENBLAS_NUM_THREADS=1
export HF_HUB_OFFLINE=1
export TRANSFORMERS_OFFLINE=1
if [ ! -x "$studio_dir/.venv/bin/python" ]; then
  echo 'Install first with bash termux-studio/install-ubuntu.sh'; exit 1
fi
echo 'Open http://127.0.0.1:8765 on this phone. Keep this session open.'
exec "$studio_dir/.venv/bin/python" "$studio_dir/server.py"
