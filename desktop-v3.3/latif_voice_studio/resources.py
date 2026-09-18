from __future__ import annotations

import os
import sys
from pathlib import Path

APP_NAME = "LATIF Voice Studio 3.3 Desktop"


def bundle_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parents[1]


def model_dir() -> Path:
    return bundle_root() / "assets" / "silma-f5"


def app_data_dir() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    path = base / "LATIF Voice Studio"
    path.mkdir(parents=True, exist_ok=True)
    return path


def cache_dir() -> Path:
    path = app_data_dir() / "cache"
    path.mkdir(parents=True, exist_ok=True)
    return path
