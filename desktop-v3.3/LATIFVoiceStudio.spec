# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_all

onnx_datas, onnx_binaries, onnx_hidden = collect_all("onnxruntime")
av_datas, av_binaries, av_hidden = collect_all("av")

datas = onnx_datas + av_datas + [("models", "models")]
binaries = onnx_binaries + av_binaries
hiddenimports = onnx_hidden + av_hidden

analysis = Analysis(
    ["latif_voice_studio/app.py"],
    pathex=["."],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter"],
    noarchive=False,
)

pyz = PYZ(analysis.pure)

exe = EXE(
    pyz,
    analysis.scripts,
    [],
    exclude_binaries=True,
    name="LATIF-Voice-Studio-3.3",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)

collection = COLLECT(
    exe,
    analysis.binaries,
    analysis.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="LATIF-Voice-Studio-3.3",
)
