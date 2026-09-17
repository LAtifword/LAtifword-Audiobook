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
