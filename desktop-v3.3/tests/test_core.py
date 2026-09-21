latif-voice-studio-v3.3-desktop
import unittest

from latif_voice_studio.book_parser import clean_text, split_for_narration, target_chars_for_steps
from latif_voice_studio.renderer import _pause_after, _safe_filename


class CoreTests(unittest.TestCase):
    def test_chunker_preserves_text(self):
        text = "مرحبا بالعالم. هذه جملة ثانية؟ وهذه جملة ثالثة طويلة قليلا؛ ثم النهاية."
        chunks = split_for_narration(text, 30)
        self.assertGreaterEqual(len(chunks), 2)
        self.assertTrue(all(chunk.strip() for chunk in chunks))

    def test_clean_text(self):
        self.assertEqual(clean_text("a   b\n\n\n c"), "a b\n\n c")

    def test_step_chunk_sizes(self):
        self.assertLess(target_chars_for_steps(8), target_chars_for_steps(32))

    def test_pause_policy(self):
        self.assertEqual(_pause_after("سؤال؟"), 180)
        self.assertEqual(_pause_after("نهاية."), 140)
        self.assertEqual(_pause_after("تابع"), 70)

    def test_safe_filename(self):
        self.assertNotIn(":", _safe_filename("Book: One"))


if __name__ == "__main__":
    unittest.main()
=======
from pathlib import Path

from latif_voice_studio.audio import safe_filename
from latif_voice_studio.books import chunks_for_book, clean_text, detect_chapters, split_for_narration
from latif_voice_studio.engine import prepare_arabic_text


def test_clean_text_and_arabic_spacing() -> None:
    assert clean_text("a  \n\n\n b") == "a\n\nb"
    assert prepare_arabic_text("مرحبا  ،  كيف حالك ؟") == "مرحبا، كيف حالك؟"


def test_chapter_detection_and_chunking() -> None:
    text = "الفصل الأول\nهذه جملة عربية. هذه جملة ثانية.\nالفصل الثاني\nنهاية قصيرة."
    chapters = detect_chapters(text, "Book")
    assert [chapter.title for chapter in chapters] == ["الفصل الأول", "الفصل الثاني"]
    chunks = chunks_for_book(text, "Book", max_chars=45)
    assert len(chunks) >= 2
    assert chunks[0].chapter_title == "الفصل الأول"
    assert all(len(item.text) <= 45 for item in chunks)


def test_long_sentence_is_bounded() -> None:
    text = " ".join(["كلمة"] * 100)
    chunks = split_for_narration(text, max_chars=60)
    assert len(chunks) > 1
    assert all(len(chunk) <= 60 for chunk in chunks)


def test_safe_filename() -> None:
    assert safe_filename('A<B>:C/"D"') == "A_B_C_D"
 mainLAtifword
