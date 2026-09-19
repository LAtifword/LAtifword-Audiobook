from __future__ import annotations

import sys
import time
import traceback
from pathlib import Path
from threading import Event

from PySide6.QtCore import QThread, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from latif_voice_studio.audio import ChapterTiming, M4aStreamWriter, safe_filename
from latif_voice_studio.books import chunks_for_book, read_book
from latif_voice_studio.engine import GenerationCancelled, SilmaDesktopEngine
from latif_voice_studio.bridge import start_bridge


APP_VERSION = "3.3.0"


def _format_seconds(seconds: float) -> str:
    seconds = max(0, int(seconds))
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h {minutes:02d}m"
    if minutes:
        return f"{minutes}m {secs:02d}s"
    return f"{secs}s"


def _pause_ms(text: str) -> int:
    clean = text.rstrip()
    if clean.endswith(("؟", "!", "?")):
        return 160
    if clean.endswith((".", "…", "؛")):
        return 125
    if clean.endswith(("،", ",", ":")):
        return 75
    return 55


class RenderWorker(QThread):
    status = Signal(str)
    detail = Signal(str)
    provider = Signal(str)
    progress = Signal(int)
    completed = Signal(str)
    failed = Signal(str)
    cancelled_signal = Signal()

    def __init__(self, settings: dict) -> None:
        super().__init__()
        self.settings = settings
        self.cancel_event = Event()

    def cancel(self) -> None:
        self.cancel_event.set()

    def run(self) -> None:  # noqa: C901
        writer: M4aStreamWriter | None = None
        engine: SilmaDesktopEngine | None = None
        try:
            input_path_text = self.settings.get("input_path", "").strip()
            pasted = self.settings.get("pasted_text", "").strip()
            title = self.settings.get("title", "").strip() or "LATIF Audiobook"
            author = self.settings.get("author", "").strip()
            if input_path_text:
                input_path = Path(input_path_text)
                self.status.emit(f"Reading {input_path.name}…")
                text = read_book(input_path)
                fallback_title = input_path.stem
            elif pasted:
                text = pasted
                fallback_title = title
            else:
                raise ValueError("Choose a book or paste narration text first")

            steps = int(self.settings.get("steps", 32))
            max_chars = 175 if steps <= 8 else 195 if steps <= 16 else 210 if steps <= 24 else 220
            chunks = chunks_for_book(text, fallback_title=fallback_title, max_chars=max_chars)
            if not chunks:
                raise ValueError("No narration chunks could be created")
            preview_only = bool(self.settings.get("preview_only", False))
            if preview_only:
                chunks = chunks[:1]

            engine = SilmaDesktopEngine(
                backend=self.settings.get("backend", "auto"),
                dml_device_id=int(self.settings.get("dml_device_id", 0)),
            )
            engine.load(progress=self.status.emit)
            self.provider.emit(engine.backend_name)

            custom_voice = self.settings.get("custom_voice", "").strip()
            custom_transcript = self.settings.get("custom_transcript", "").strip()
            if custom_voice:
                self.status.emit("Loading custom reference voice…")
                reference = engine.custom_reference(Path(custom_voice), custom_transcript)
                narrator = "Custom reference voice"
            else:
                self.status.emit("Loading LATIF Author Narrator…")
                reference = engine.built_in_reference()
                narrator = "LATIF Author Narrator"

            output_dir = Path.home() / "Music" / "LATIF Audiobooks"
            suffix = "-PREVIEW" if preview_only else ""
            output_path = output_dir / f"{safe_filename(title)}{suffix}.m4a"
            writer = M4aStreamWriter(
                output_path=output_path,
                sample_rate=engine.sample_rate,
                bitrate=128_000,
                title=title,
                author=author,
                narrator=narrator,
            )

            speed = float(self.settings.get("speed", 0.90))
            total = len(chunks)
            started = time.perf_counter()
            chapter_timings: list[ChapterTiming] = []
            current_chapter = chunks[0].chapter_title
            chapter_start = 0

            for index, chunk in enumerate(chunks):
                if self.cancel_event.is_set():
                    raise GenerationCancelled()
                if chunk.chapter_title != current_chapter:
                    chapter_timings.append(
                        ChapterTiming(current_chapter, chapter_start, writer.duration_ms)
                    )
                    current_chapter = chunk.chapter_title
                    chapter_start = writer.duration_ms

                self.status.emit(
                    f"Narrating section {index + 1}/{total} · {engine.backend_name}"
                )
                synth_started = time.perf_counter()

                def on_step(step: int, count: int) -> None:
                    fraction = (index + (step / max(1, count))) / total
                    self.progress.emit(min(99, int(fraction * 100)))
                    self.detail.emit(
                        f"Section {index + 1}/{total} · F5 refinement {step}/{count}"
                    )

                audio = engine.synthesize(
                    reference=reference,
                    text=chunk.text,
                    speed=speed,
                    nfe_steps=steps,
                    cancel_event=self.cancel_event,
                    on_step=on_step,
                )
                synth_elapsed = time.perf_counter() - synth_started
                audio_seconds = max(0.001, audio.size / engine.sample_rate)
                rtf = synth_elapsed / audio_seconds
                writer.write_pcm16(audio)
                writer.write_silence(_pause_ms(chunk.text))
                del audio

                elapsed = time.perf_counter() - started
                average = elapsed / (index + 1)
                eta = average * (total - index - 1)
                self.detail.emit(
                    f"{index + 1}/{total} complete · RTF {rtf:.2f}× · ETA {_format_seconds(eta)}"
                )
                self.progress.emit(int(((index + 1) / total) * 100))

            chapter_timings.append(
                ChapterTiming(current_chapter, chapter_start, writer.duration_ms)
            )
            final_path = writer.finish(chapter_timings)
            writer = None
            self.progress.emit(100)
            self.status.emit("Audiobook complete")
            self.completed.emit(str(final_path))
        except GenerationCancelled:
            if writer:
                writer.abort()
            self.cancelled_signal.emit()
        except Exception as exc:  # noqa: BLE001
            if writer:
                writer.abort()
            traceback.print_exc()
            self.failed.emit(f"{type(exc).__name__}: {exc}")
        finally:
            if engine:
                engine.close()


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.worker: RenderWorker | None = None
        self.last_output: Path | None = None
        self.setWindowTitle(f"LATIF Voice Studio Desktop {APP_VERSION}")
        self.resize(1040, 820)
        self._build_ui()
        self._apply_theme()

    def _build_ui(self) -> None:
        central = QWidget()
        root = QVBoxLayout(central)
        root.setContentsMargins(24, 20, 24, 20)
        root.setSpacing(14)

        title = QLabel("LATIF VOICE STUDIO 3.3 · DESKTOP")
        title.setObjectName("hero")
        subtitle = QLabel(
            "Offline SILMA F5 audiobook production · Windows GPU/DirectML + CPU fallback"
        )
        subtitle.setObjectName("muted")
        root.addWidget(title)
        root.addWidget(subtitle)

        source_box = QGroupBox("Book / manuscript")
        source_layout = QGridLayout(source_box)
        self.book_path = QLineEdit()
        self.book_path.setPlaceholderText("PDF, EPUB, DOCX or TXT")
        choose_book = QPushButton("Choose book")
        choose_book.clicked.connect(self._choose_book)
        source_layout.addWidget(self.book_path, 0, 0)
        source_layout.addWidget(choose_book, 0, 1)
        self.pasted_text = QPlainTextEdit()
        self.pasted_text.setPlaceholderText("Or paste Arabic / English narration text here…")
        self.pasted_text.setMinimumHeight(150)
        source_layout.addWidget(self.pasted_text, 1, 0, 1, 2)
        root.addWidget(source_box)

        settings_row = QHBoxLayout()
        output_box = QGroupBox("Audiobook")
        output_form = QFormLayout(output_box)
        self.output_title = QLineEdit("LATIF Audiobook")
        self.author = QLineEdit()
        self.author.setPlaceholderText("Optional")
        self.speed = QDoubleSpinBox()
        self.speed.setRange(0.82, 1.08)
        self.speed.setSingleStep(0.01)
        self.speed.setDecimals(2)
        self.speed.setValue(0.90)
        self.quality = QComboBox()
        self.quality.addItem("Studio · 32 steps", 32)
        self.quality.addItem("High · 24 steps", 24)
        self.quality.addItem("Balanced · 16 steps", 16)
        self.quality.addItem("Fast preview · 8 steps", 8)
        output_form.addRow("Title", self.output_title)
        output_form.addRow("Author", self.author)
        output_form.addRow("Narration speed", self.speed)
        output_form.addRow("F5 quality", self.quality)
        settings_row.addWidget(output_box, 1)

        hardware_box = QGroupBox("Desktop acceleration")
        hardware_form = QFormLayout(hardware_box)
        self.backend = QComboBox()
        self.backend.addItem("Auto · DirectML GPU → CPU", "auto")
        self.backend.addItem("DirectML GPU", "directml")
        self.backend.addItem("CPU only", "cpu")
        self.dml_id = QSpinBox()
        self.dml_id.setRange(0, 7)
        self.dml_id.setValue(0)
        self.backend_label = QLabel("Not loaded")
        hardware_form.addRow("Backend", self.backend)
        hardware_form.addRow("GPU adapter ID", self.dml_id)
        hardware_form.addRow("Active", self.backend_label)
        settings_row.addWidget(hardware_box, 1)
        root.addLayout(settings_row)

        voice_box = QGroupBox("Narrator")
        voice_layout = QGridLayout(voice_box)
        self.custom_voice = QLineEdit()
        self.custom_voice.setPlaceholderText("Optional WAV reference; blank = LATIF Author Narrator")
        choose_voice = QPushButton("Choose WAV")
        choose_voice.clicked.connect(self._choose_voice)
        self.custom_transcript = QLineEdit()
        self.custom_transcript.setPlaceholderText("Exact transcript of the custom reference WAV")
        voice_layout.addWidget(self.custom_voice, 0, 0)
        voice_layout.addWidget(choose_voice, 0, 1)
        voice_layout.addWidget(self.custom_transcript, 1, 0, 1, 2)
        voice_note = QLabel("Use a custom voice only when you have permission to use that recording.")
        voice_note.setObjectName("muted")
        voice_layout.addWidget(voice_note, 2, 0, 1, 2)
        root.addWidget(voice_box)

        actions = QHBoxLayout()
        self.preview_button = QPushButton("Render one-section preview")
        self.preview_button.clicked.connect(lambda: self._start_render(True))
        self.render_button = QPushButton("Generate full audiobook")
        self.render_button.setObjectName("primary")
        self.render_button.clicked.connect(lambda: self._start_render(False))
        self.cancel_button = QPushButton("Cancel")
        self.cancel_button.setEnabled(False)
        self.cancel_button.clicked.connect(self._cancel)
        self.open_button = QPushButton("Open last output")
        self.open_button.setEnabled(False)
        self.open_button.clicked.connect(self._open_last)
        actions.addWidget(self.preview_button)
        actions.addWidget(self.render_button)
        actions.addWidget(self.cancel_button)
        actions.addWidget(self.open_button)
        root.addLayout(actions)

        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.status = QLabel("Ready · no cloud, account or API required")
        self.detail = QLabel("Output: Music\\LATIF Audiobooks")
        self.detail.setObjectName("muted")
        root.addWidget(self.progress)
        root.addWidget(self.status)
        root.addWidget(self.detail)
        self.setCentralWidget(central)

    def _apply_theme(self) -> None:
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #0b0d10; color: #f4f1e8; font-size: 14px; }
            QGroupBox { border: 1px solid #30343b; border-radius: 10px; margin-top: 10px; padding: 12px; font-weight: 600; }
            QGroupBox::title { subcontrol-origin: margin; left: 12px; padding: 0 6px; color: #d7b568; }
            QLineEdit, QPlainTextEdit, QComboBox, QSpinBox, QDoubleSpinBox { background: #15191f; border: 1px solid #343a43; border-radius: 7px; padding: 7px; selection-background-color: #6f5b2a; }
            QPushButton { background: #20252c; border: 1px solid #3a414b; border-radius: 7px; padding: 9px 13px; }
            QPushButton:hover { background: #2a3038; }
            QPushButton#primary { background: #8b6d2d; color: white; border-color: #b18b39; font-weight: 700; }
            QPushButton:disabled { color: #6f747b; }
            QLabel#hero { font-size: 25px; font-weight: 800; color: #e2bf6a; }
            QLabel#muted { color: #9ca3ad; }
            QProgressBar { border: 1px solid #343a43; border-radius: 6px; background: #15191f; text-align: center; }
            QProgressBar::chunk { background: #9a7831; border-radius: 5px; }
            """
        )

    def _choose_book(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose audiobook source",
            "",
            "Books (*.pdf *.epub *.docx *.txt);;All files (*.*)",
        )
        if path:
            self.book_path.setText(path)
            if self.output_title.text().strip() in {"", "LATIF Audiobook"}:
                self.output_title.setText(Path(path).stem)

    def _choose_voice(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choose reference WAV", "", "WAV (*.wav)")
        if path:
            self.custom_voice.setText(path)

    def _settings(self, preview_only: bool) -> dict:
        return {
            "input_path": self.book_path.text(),
            "pasted_text": self.pasted_text.toPlainText(),
            "title": self.output_title.text(),
            "author": self.author.text(),
            "speed": self.speed.value(),
            "steps": self.quality.currentData(),
            "backend": self.backend.currentData(),
            "dml_device_id": self.dml_id.value(),
            "custom_voice": self.custom_voice.text(),
            "custom_transcript": self.custom_transcript.text(),
            "preview_only": preview_only,
        }

    def _start_render(self, preview_only: bool) -> None:
        if self.worker and self.worker.isRunning():
            return
        if not self.book_path.text().strip() and not self.pasted_text.toPlainText().strip():
            QMessageBox.warning(self, "Nothing to narrate", "Choose a book or paste text first.")
            return
        if self.custom_voice.text().strip() and not self.custom_transcript.text().strip():
            QMessageBox.warning(
                self,
                "Reference transcript required",
                "Enter the exact transcript spoken in the custom WAV.",
            )
            return
        self.progress.setValue(0)
        self.backend_label.setText("Loading…")
        self._set_running(True)
        self.worker = RenderWorker(self._settings(preview_only))
        self.worker.status.connect(self.status.setText)
        self.worker.detail.connect(self.detail.setText)
        self.worker.provider.connect(self.backend_label.setText)
        self.worker.progress.connect(self.progress.setValue)
        self.worker.completed.connect(self._completed)
        self.worker.failed.connect(self._failed)
        self.worker.cancelled_signal.connect(self._cancelled)
        self.worker.finished.connect(lambda: self._set_running(False))
        self.worker.start()

    def _set_running(self, running: bool) -> None:
        self.preview_button.setEnabled(not running)
        self.render_button.setEnabled(not running)
        self.cancel_button.setEnabled(running)

    def _cancel(self) -> None:
        if self.worker:
            self.status.setText("Cancelling after the current neural operation…")
            self.worker.cancel()

    def _completed(self, path: str) -> None:
        self.last_output = Path(path)
        self.open_button.setEnabled(True)
        self.status.setText("Complete · audio + chapter sidecar saved")
        self.detail.setText(path)

    def _failed(self, message: str) -> None:
        self.status.setText("Render failed")
        self.detail.setText(message)
        QMessageBox.critical(self, "LATIF Voice Studio", message)

    def _cancelled(self) -> None:
        self.status.setText("Render cancelled")
        self.detail.setText("Temporary output was removed safely")
        self.progress.setValue(0)

    def _open_last(self) -> None:
        if self.last_output and self.last_output.exists():
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.last_output)))

    def closeEvent(self, event) -> None:  # noqa: ANN001
        if self.worker and self.worker.isRunning():
            answer = QMessageBox.question(
                self,
                "Render in progress",
                "Cancel the active render and exit?",
            )
            if answer != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
            self.worker.cancel()
            self.worker.wait(5000)
        event.accept()


def main() -> int:
    bridge = start_bridge()
    app = QApplication(sys.argv)
    app.setApplicationName("LATIF Voice Studio Desktop")
    app.setApplicationVersion(APP_VERSION)
    window = MainWindow()
    window.show()
    exit_code = app.exec()
    bridge.shutdown()
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
