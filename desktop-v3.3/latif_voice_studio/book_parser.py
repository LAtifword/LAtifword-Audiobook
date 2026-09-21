from __future__ import annotations

import re
from pathlib import Path


def read_book(path: str | Path) -> str:
    path = Path(path)
    ext = path.suffix.lower()
    if ext == ".txt":
        return _read_text(path)
    if ext == ".pdf":
        from pypdf import PdfReader
        return "\n\n".join((page.extract_text() or "") for page in PdfReader(str(path)).pages)
    if ext == ".docx":
        from docx import Document
        doc = Document(str(path))
        return "\n".join(p.text for p in doc.paragraphs)
    if ext == ".epub":
        from ebooklib import epub, ITEM_DOCUMENT
        from bs4 import BeautifulSoup
        book = epub.read_epub(str(path))
        parts = []
        for item in book.get_items_of_type(ITEM_DOCUMENT):
            parts.append(BeautifulSoup(item.get_content(), "html.parser").get_text(" ", strip=True))
        return "\n\n".join(parts)
    raise ValueError(f"Unsupported manuscript type: {ext or 'unknown'}")


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-16", "utf-8", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", errors="replace")


def clean_text(text: str) -> str:
    text = text.replace("\u00a0", " ").replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def prepare_for_narration(text: str) -> str:
    text = clean_text(text)
    text = re.sub(r"https?://\S+|www\.\S+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def split_for_narration(text: str, target_chars: int = 220) -> list[str]:
    text = clean_text(text)
    if not text:
        return []
    sentences = re.split(r"(?<=[\.!؟!؛…])\s+|\n+", text)
    chunks: list[str] = []
    current = ""
    for sentence in (s.strip() for s in sentences if s.strip()):
        if len(sentence) > target_chars * 2:
            pieces = re.split(r"(?<=[،,:؛;])\s+", sentence)
        else:
            pieces = [sentence]
        for piece in pieces:
            if not piece:
                continue
            candidate = f"{current} {piece}".strip() if current else piece
            if current and len(candidate) > target_chars:
                chunks.append(current.strip())
                current = piece
            else:
                current = candidate
    if current.strip():
        chunks.append(current.strip())
    return chunks


def target_chars_for_steps(steps: int) -> int:
    if steps <= 8:
        return 160
    if steps <= 12:
        return 180
    if steps <= 16:
        return 200
    if steps <= 24:
        return 210
    return 220
