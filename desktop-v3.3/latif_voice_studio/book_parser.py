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
    for enc in ("utf-8-sig", "utf-16", "utf-8", "cp1256", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", errors="replace")


def clean_text(text: str) -> str:
    text = text.replace("\u00a0", " ").replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def prepare_for_narration(text: str) -> str:
    text = clean_text(text)
    text = re.sub(r"https?://\S+|www\.\S+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _split_long_piece(value: str, target_chars: int) -> list[str]:
    """Split only when necessary, preferring Arabic/Latin clause boundaries."""
    if len(value) <= int(target_chars * 1.55):
        return [value]
    clauses = [item.strip() for item in re.split(r"(?<=[،,:؛;])\s+", value) if item.strip()]
    if len(clauses) <= 1:
        clauses = [value]
    output: list[str] = []
    current = ""
    for clause in clauses:
        candidate = f"{current} {clause}".strip() if current else clause
        if current and len(candidate) > int(target_chars * 1.25):
            output.append(current)
            current = clause
        else:
            current = candidate
    if current:
        output.append(current)
    return output


def _rebalance_short_chunks(chunks: list[str], target_chars: int) -> list[str]:
    """Avoid many tiny F5 calls, which sound like repeated narrator restarts."""
    if len(chunks) < 2:
        return chunks
    minimum = max(55, int(target_chars * 0.33))
    maximum = int(target_chars * 1.30)
    balanced: list[str] = []
    index = 0
    while index < len(chunks):
        current = chunks[index].strip()
        if len(current) < minimum and index + 1 < len(chunks):
            merged = f"{current} {chunks[index + 1].strip()}".strip()
            if len(merged) <= maximum:
                balanced.append(merged)
                index += 2
                continue
        if len(current) < minimum and balanced:
            merged = f"{balanced[-1]} {current}".strip()
            if len(merged) <= maximum:
                balanced[-1] = merged
                index += 1
                continue
        balanced.append(current)
        index += 1
    return balanced


def split_for_narration(text: str, target_chars: int = 220) -> list[str]:
    text = clean_text(text)
    if not text:
        return []

    sentences = [
        item.strip()
        for item in re.split(r"(?<=[\.!؟!؛…])\s+|\n+", text)
        if item.strip()
    ]
    chunks: list[str] = []
    current = ""
    for sentence in sentences:
        for piece in _split_long_piece(sentence, target_chars):
            candidate = f"{current} {piece}".strip() if current else piece
            if current and len(candidate) > target_chars:
                chunks.append(current.strip())
                current = piece
            else:
                current = candidate
    if current.strip():
        chunks.append(current.strip())
    return _rebalance_short_chunks(chunks, target_chars)


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
