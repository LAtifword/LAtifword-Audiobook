from pathlib import Path
from threading import Event

import numpy as np

from latif_voice_studio.engine import AUTHOR_SPEED, SilmaDesktopEngine, VoiceReference
from latif_voice_studio.renderer import AudiobookRenderer, RenderRequest, _edge_taper


def test_engine_renderer_contract_is_consistent() -> None:
    assert AUTHOR_SPEED == 0.90
    assert hasattr(SilmaDesktopEngine, "reference_from_wav")


def test_voice_fingerprint_changes_when_pcm_changes() -> None:
    first = VoiceReference(np.asarray([1, 2, 3, 4], dtype=np.int16), "رفع المفتاح ونظر إليه.")
    second = VoiceReference(np.asarray([1, 2, 3, 5], dtype=np.int16), "رفع المفتاح ونظر إليه.")
    assert first.fingerprint != second.fingerprint


def test_voice_fingerprint_normalizes_spacing_only() -> None:
    first = VoiceReference(np.asarray([1, 2, 3, 4], dtype=np.int16), "رفع المفتاح ونظر إليه.")
    second = VoiceReference(np.asarray([1, 2, 3, 4], dtype=np.int16), "رفع  المفتاح ونظر إليه .")
    assert first.fingerprint == second.fingerprint


def test_duration_estimator_is_bounded_for_long_text() -> None:
    reference_samples = 8 * 24_000
    ref_text = "هذا نص عربي مرجعي "
    value = SilmaDesktopEngine._max_duration(
        reference_samples,
        ref_text,
        "كلمة " * 10_000,
        0.90,
    )
    ref_frames = reference_samples // 256 + 1
    assert value < ref_frames * 10


def test_render_cache_key_tracks_actual_voice() -> None:
    class DummyEngine:
        backend_name = "dummy"

    renderer = AudiobookRenderer(DummyEngine(), Event())
    request = RenderRequest(
        title="test",
        text="long enough narration text for a cache-key test",
        output_dir=Path("."),
    )
    first = VoiceReference(np.asarray([1, 2, 3, 4], dtype=np.int16), "مرجع صوتي")
    second = VoiceReference(np.asarray([1, 2, 3, 5], dtype=np.int16), "مرجع صوتي")
    assert renderer._job_id(request, request.text, first) != renderer._job_id(request, request.text, second)


def test_edge_taper_avoids_hard_section_discontinuity() -> None:
    samples = np.full(1_000, 10_000, dtype=np.int16)
    tapered = _edge_taper(samples, milliseconds=4)
    assert tapered[0] == 0
    assert tapered[-1] == 0
    assert tapered[len(tapered) // 2] == 10_000
