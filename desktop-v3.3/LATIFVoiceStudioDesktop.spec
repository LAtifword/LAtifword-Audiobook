# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs

project_root = Path(SPECPATH)
assets = project_root / "assets" / "silma-f5"

datas = [(str(assets), "assets/silma-f5")]
datas += collect_data_files("imageio_ffmpeg")
binaries = collect_dynamic_libs("onnxruntime")

hiddenimports = [
    "onnxruntime.capi._pybind_state",
    "onnxruntime.capi.onnxruntime_pybind11_state",
    "ebooklib",
    "bs4",
]

a = Analysis(
    [str(project_root / "run_desktop.py")],
    pathex=[str(project_root)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=1,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="LATIF Voice Studio 3.3 Desktop",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="LATIF Voice Studio 3.3 Desktop",
)
