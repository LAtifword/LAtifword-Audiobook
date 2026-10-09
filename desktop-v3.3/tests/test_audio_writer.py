import numpy as np

from latif_voice_studio.audio import ChapterTiming, M4aStreamWriter


def test_m4a_writer_produces_file(tmp_path) -> None:
    output = tmp_path / "smoke.m4a"
    writer = M4aStreamWriter(output, sample_rate=24_000, title="Smoke Test")
    tone = np.zeros(2_400, dtype=np.int16)
    writer.write_pcm16(tone)
    writer.write_silence(50)
    final = writer.finish([ChapterTiming("Test", 0, writer.duration_ms)])
    assert final.is_file()
    assert final.stat().st_size > 500
    sidecar = output.with_suffix(".chapters.json")
    assert sidecar.is_file()
