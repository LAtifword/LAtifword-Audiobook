from __future__ import annotations

import html
import re
import zipfile
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree as ET

from pypdf import PdfReader


@dataclass(frozen=True)
class Chapter:
    title: str
    text: str


@dataclass(frozen=True)
class NarrationChunk:
    chapter_title: str
    text: str


class _HTMLTextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        if data.strip():
            self.parts.append(data)

    def handle_starttag(self, tag: str, attrs) -> None:  # noqa: ANN001
        if tag.lower() in {"p", "div", "br", "h1", "h2", "h3", "li"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in {"p", "div", "h1", "h2", "h3", "li"}:
            self.parts.append("\n")

    def text(self) -> str:
        return html.unescape("".join(self.parts))


def clean_text(text: str) -> str:
    text = text.replace("\x00", " ").replace("\ufeff", " ")
    text = re.sub(r"[\t\x0b\f\r ]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _read_pdf(path: Path) -> str:
    reader = PdfReader(str(path))
    return "\n\n".join((page.extract_text() or "") for page in reader.pages)


def _read_docx(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        data = archive.read("word/document.xml")
    root = ET.fromstring(data)
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    paragraphs: list[str] = []
    for paragraph in root.iter(namespace + "p"):
        pieces: list[str] = []
        for node in paragraph.iter():
            if node.tag == namespace + "t" and node.text:
                pieces.append(node.text)
            elif node.tag == namespace + "tab":
                pieces.append(" ")
            elif node.tag == namespace + "br":
                pieces.append("\n")
        value = "".join(pieces).strip()
        if value:
            paragraphs.append(value)
    return "\n\n".join(paragraphs)


def _read_epub(path: Path) -> str:
    parts: list[tuple[str, str]] = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            lower = name.lower()
            if not lower.endswith((".xhtml", ".html", ".htm")):
                continue
            raw = archive.read(name).decode("utf-8", errors="ignore")
            parser = _HTMLTextExtractor()
            parser.feed(raw)
            text = clean_text(parser.text())
            if len(text) > 40:
                parts.append((name, text))
    if not parts:
        raise ValueError("No readable chapters were found in EPUB")
    parts.sort(key=lambda item: item[0])
    return "\n\n".join(text for _, text in parts)


def _read_text(path: Path) -> str:
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "cp1256", "windows-1252"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def read_book(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        value = _read_pdf(path)
    elif suffix == ".docx":
        value = _read_docx(path)
    elif suffix == ".epub":
        value = _read_epub(path)
    else:
        value = _read_text(path)
    value = clean_text(value)
    if not value:
        raise ValueError("The selected book does not contain readable text")
    return value


_HEADING = re.compile(
    r"^(?:الفصل|الباب|الجزء|المقدمة|مقدمة|الخاتمة|خاتمة|chapter|part|prologue|epilogue)\b.*$",
    re.IGNORECASE,
)


def detect_chapters(text: str, fallback_title: str = "Audiobook") -> list[Chapter]:
    lines = [line.strip() for line in clean_text(text).splitlines()]
    chapters: list[Chapter] = []
    title = fallback_title
    body: list[str] = []

    def flush() -> None:
        nonlocal body
        value = clean_text("\n".join(body))
        if value:
            chapters.append(Chapter(title=title, text=value))
        body = []

    for line in lines:
        if line and len(line) <= 100 and _HEADING.match(line):
            flush()
            title = line
        else:
            body.append(line)
    flush()
    if not chapters:
        return [Chapter(title=fallback_title, text=clean_text(text))]
    return chapters


def split_for_narration(text: str, max_chars: int = 220) -> list[str]:
    normalized = clean_text(text)
    if not normalized:
        return []
    sentences = [
        item.strip()
        for item in re.split(r"(?<=[.!?؟؛…])\s+|\n+", normalized)
        if item.strip()
    ]
    output: list[str] = []
    current = ""
    for sentence in sentences:
        if len(sentence) > max_chars:
            if current:
                output.append(current)
                current = ""
            start = 0
            while start < len(sentence):
                end = min(start + max_chars, len(sentence))
                if end < len(sentence):
                    space = sentence.rfind(" ", start + max_chars // 2, end + 1)
                    if space > start:
                        end = space
                piece = sentence[start:end].strip()
                if piece:
                    output.append(piece)
                start = end
                while start < len(sentence) and sentence[start].isspace():
                    start += 1
        elif not current:
            current = sentence
        elif len(current) + 1 + len(sentence) <= max_chars:
            current += " " + sentence
        else:
            output.append(current)
            current = sentence
    if current:
        output.append(current)
    return output


def chunks_for_book(
    text: str,
    fallback_title: str,
    max_chars: int = 220,
) -> list[NarrationChunk]:
    chunks: list[NarrationChunk] = []
    for chapter in detect_chapters(text, fallback_title=fallback_title):
        for piece in split_for_narration(chapter.text, max_chars=max_chars):
            chunks.append(NarrationChunk(chapter_title=chapter.title, text=piece))
    return chunks
