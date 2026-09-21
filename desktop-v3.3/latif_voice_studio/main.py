from __future__ import annotations

import sys
import traceback
from pathlib import Path
from threading import Event

from PySide6.QtCore import QObject, QThread, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QDragEnterEvent, QDropEvent
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from .book_parser import read_book
from .engine import AUTHOR_SPEED, GenerationCancelled, SilmaDesktopEngine
from .renderer import AudiobookRenderer, RenderRequest

SUPPORTED = {".pdf", ".docx", ".epub", ".txt"}


class RenderWorker(QObject):
    progress = Signal(int, str)
    log = Signal(str)
    complete = Signal(str, str, str)
    failed = Signal(str)
    finished = Signal()

    def __init__(
        self,
        manuscript: Path,
        title: str,
        output_dir: Path,
        steps: int,
        preview: bool,
        backend: str | None,
        custom_wav: Path | None,
        custom_text: str,
        cancel: Event,
    ):
        super().__init__()
        self.manuscript = manuscript
        self.title = title
        self.output_dir = output_dir
        self.steps = steps
        self.preview = preview
        self.backend = backend
        self.custom_wav = custom_wav
        self.custom_text = custom_text
        self.cancel = cancel

    @Slot()
    def run(self) -> None:
        engine = SilmaDesktopEngine(force_backend=self.backend)
        try:
            self.progress.emit(0, "Reading manuscript…")
            text = read_book(self.manuscript)
            renderer = AudiobookRenderer(
                engine=engine,
                cancel=self.cancel,
                progress=lambda p, m: self.progress.emit(p, m),
                log=lambda m: self.log.emit(m),
            )
            result = renderer.run(
                RenderRequest(
                    title=self.title,
                    text=text,
                    output_dir=self.output_dir,
                    steps=self.steps,
                    speed=AUTHOR_SPEED,
                    preview_only=self.preview,
                    reference_wav=self.custom_wav,
                    reference_text=self.custom_text,
                )
            )
            summary = (
                f"{result.backend} · elapsed {result.elapsed_seconds / 60:.1f} min · "
                f"RTF {result.realtime_factor:.2f}× · resumed {result.resumed_sections}"
            )
            self.complete.emit(str(result.audio_path), str(result.sidecar_path), summary)
        except GenerationCancelled:
            self.failed.emit("Cancelled")
        except Exception as exc:
            self.log.emit(traceback.format_exc())
            self.failed.emit(f"{exc.__class__.__name__}: {exc}")
        finally:
            engine.close()
            self.finished.emit()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("LATIF Voice Studio 3.3 Desktop")
        self.resize(980, 760)
        self.setAcceptDrops(True)
        self.cancel_event = Event()
        self.thread: QThread | None = None
        self.worker: RenderWorker | None = None
        self.last_output: Path | None = None
        self._build_ui()

    def _build_ui(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        layout.setSpacing(14)

        title = QLabel("LATIF VOICE STUDIO 3.3 · DESKTOP")
        title.setObjectName("hero")
        subtitle = QLabel(
            "Offline SILMA F5 audiobook studio · Author Narrator · 24 kHz · GPU/CPU"
        )
        subtitle.setObjectName("subtle")
        layout.addWidget(title)
        layout.addWidget(subtitle)

        form = QFormLayout()

        file_row = QHBoxLayout()
        self.file_edit = QLineEdit()
        self.file_edit.setPlaceholderText("Drop or select PDF / DOCX / EPUB / TXT")
        browse = QPushButton("Select manuscript")
        browse.clicked.connect(self.pick_manuscript)
        file_row.addWidget(self.file_edit, 1)
        file_row.addWidget(browse)
        form.addRow("Manuscript", file_row)

        self.title_edit = QLineEdit()
        self.title_edit.setPlaceholderText("Audiobook title")
        form.addRow("Title", self.title_edit)

        out_row = QHBoxLayout()
        self.output_edit = QLineEdit(str(Path.home() / "Music" / "LATIF Audiobooks"))
        out_browse = QPushButton("Output folder")
        out_browse.clicked.connect(self.pick_output)
        out_row.addWidget(self.output_edit, 1)
        out_row.addWidget(out_browse)
        form.addRow("Output", out_row)

        self.steps_combo = QComboBox()
        self.steps_combo.addItem("Studio · 32 steps (same quality target as 3.2.1)", 32)
        self.steps_combo.addItem("High · 24 steps", 24)
        self.steps_combo.addItem("Balanced · 16 steps", 16)
        self.steps_combo.addItem("Fast · 12 steps", 12)
        self.steps_combo.addItem("Preview · 8 steps", 8)
        form.addRow("F5 refinement", self.steps_combo)

        self.backend_combo = QComboBox()
        self.backend_combo.addItem("Auto · GPU first, CPU fallback", None)
        self.backend_combo.addItem("DirectML GPU", "directml")
        self.backend_combo.addItem("CPU only", "cpu")
        form.addRow("Compute backend", self.backend_combo)

        fixed = QLabel("0.90× fixed · Literary Author Narrator pacing")
        fixed.setObjectName("good")
        form.addRow("Narration", fixed)

        self.custom_check = QCheckBox("Use a custom WAV reference voice")
        self.custom_check.toggled.connect(self._toggle_custom)
        form.addRow("Voice", self.custom_check)

        ref_row = QHBoxLayout()
        self.ref_edit = QLineEdit()
        self.ref_edit.setEnabled(False)
        ref_browse = QPushButton("Select WAV")
        ref_browse.setEnabled(False)
        ref_browse.clicked.connect(self.pick_reference)
        self.ref_browse = ref_browse
        ref_row.addWidget(self.ref_edit, 1)
        ref_row.addWidget(ref_browse)
        form.addRow("Reference WAV", ref_row)

        self.ref_text = QLineEdit()
        self.ref_text.setPlaceholderText("Exact transcript spoken in the reference WAV")
        self.ref_text.setEnabled(False)
        form.addRow("Reference transcript", self.ref_text)

        layout.addLayout(form)

        buttons = QHBoxLayout()
        self.preview_btn = QPushButton("Test first section")
        self.preview_btn.clicked.connect(lambda: self.start_render(True))
        self.render_btn = QPushButton("Render full audiobook")
        self.render_btn.setObjectName("primary")
        self.render_btn.clicked.connect(lambda: self.start_render(False))
        self.cancel_btn = QPushButton("Cancel")
        self.cancel_btn.setEnabled(False)
        self.cancel_btn.clicked.connect(self.cancel_render)
        self.open_btn = QPushButton("Open last output")
        self.open_btn.setEnabled(False)
        self.open_btn.clicked.connect(self.open_last_output)
        buttons.addWidget(self.preview_btn)
        buttons.addWidget(self.render_btn)
        buttons.addWidget(self.cancel_btn)
        buttons.addWidget(self.open_btn)
        layout.addLayout(buttons)

        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        layout.addWidget(self.progress)

        self.status = QLabel("Ready · model and processing remain local on this PC")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setPlaceholderText("Render log")
        layout.addWidget(self.log, 1)

        self.setStyleSheet(
            """
            QMainWindow, QWidget { background: #0c0d0f; color: #f4f1e8; font-size: 14px; }
            QLabel#hero { font-size: 26px; font-weight: 700; color: #e4c36a; }
            QLabel#subtle { color: #9fa3aa; }
            QLabel#good { color: #9dd4a5; font-weight: 600; }
            QLineEdit, QPlainTextEdit, QComboBox {
                background: #16181c; border: 1px solid #343840; border-radius: 7px;
                padding: 8px; color: #f4f1e8;
            }
            QPushButton {
                background: #252830; border: 1px solid #3b4049; border-radius: 7px;
                padding: 9px 13px;
            }
            QPushButton:hover { background: #30343d; }
            QPushButton#primary { background: #b79237; color: #0c0d0f; font-weight: 700; }
            QPushButton:disabled { color: #666a70; background: #17191d; }
            QProgressBar { border: 1px solid #343840; border-radius: 6px; text-align: center; }
            QProgressBar::chunk { background: #b79237; }
            """
        )

    def _toggle_custom(self, enabled: bool) -> None:
        self.ref_edit.setEnabled(enabled)
        self.ref_browse.setEnabled(enabled)
        self.ref_text.setEnabled(enabled)

    @Slot()
    def pick_manuscript(self) -> None:
        file_name, _ = QFileDialog.getOpenFileName(
            self,
            "Select manuscript",
            str(Path.home()),
            "Books (*.pdf *.docx *.epub *.txt)",
        )
        if file_name:
            self._set_manuscript(Path(file_name))

    @Slot()
    def pick_output(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "Select output folder", self.output_edit.text())
        if folder:
            self.output_edit.setText(folder)

    @Slot()
    def pick_reference(self) -> None:
        file_name, _ = QFileDialog.getOpenFileName(
            self, "Select reference WAV", str(Path.home()), "WAV audio (*.wav)"
        )
        if file_name:
            self.ref_edit.setText(file_name)

    def _set_manuscript(self, path: Path) -> None:
        if path.suffix.lower() not in SUPPORTED:
            QMessageBox.warning(self, "Unsupported file", "Use PDF, DOCX, EPUB, or TXT.")
            return
        self.file_edit.setText(str(path))
        if not self.title_edit.text().strip():
            self.title_edit.setText(path.stem)

    def dragEnterEvent(self, event: QDragEnterEvent) -> None:
        urls = event.mimeData().urls()
        if urls and Path(urls[0].toLocalFile()).suffix.lower() in SUPPORTED:
            event.acceptProposedAction()

    def dropEvent(self, event: QDropEvent) -> None:
        urls = event.mimeData().urls()
        if urls:
            self._set_manuscript(Path(urls[0].toLocalFile()))
            event.acceptProposedAction()

    def start_render(self, preview: bool) -> None:
        manuscript = Path(self.file_edit.text().strip())
        if not manuscript.is_file():
            QMessageBox.warning(self, "Manuscript required", "Select a manuscript first.")
            return
        output_dir = Path(self.output_edit.text().strip())
        custom_wav = None
        custom_text = ""
        if self.custom_check.isChecked():
            custom_wav = Path(self.ref_edit.text().strip())
            custom_text = self.ref_text.text().strip()
            if not custom_wav.is_file() or not custom_text:
                QMessageBox.warning(
                    self,
                    "Reference incomplete",
                    "Select a WAV and enter the exact transcript spoken in it.",
                )
                return

        self.cancel_event = Event()
        self._set_running(True)
        self.progress.setValue(0)
        self.log.clear()
        self.last_output = None
        self.open_btn.setEnabled(False)

        self.thread = QThread(self)
        self.worker = RenderWorker(
            manuscript=manuscript,
            title=self.title_edit.text().strip() or manuscript.stem,
            output_dir=output_dir,
            steps=int(self.steps_combo.currentData()),
            preview=preview,
            backend=self.backend_combo.currentData(),
            custom_wav=custom_wav,
            custom_text=custom_text,
            cancel=self.cancel_event,
        )
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.progress.connect(self.on_progress)
        self.worker.log.connect(self.log.appendPlainText)
        self.worker.complete.connect(self.on_complete)
        self.worker.failed.connect(self.on_failed)
        self.worker.finished.connect(self.thread.quit)
        self.worker.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.thread.deleteLater)
        self.thread.finished.connect(lambda: self._set_running(False))
        self.thread.start()

    @Slot(int, str)
    def on_progress(self, percent: int, message: str) -> None:
        self.progress.setValue(max(0, min(100, percent)))
        self.status.setText(message)
        if message:
            self.log.appendPlainText(message)

    @Slot(str, str, str)
    def on_complete(self, audio_path: str, sidecar_path: str, summary: str) -> None:
        self.last_output = Path(audio_path)
        self.open_btn.setEnabled(True)
        self.progress.setValue(100)
        self.status.setText(f"Complete · {summary}")
        self.log.appendPlainText(f"Audio: {audio_path}")
        self.log.appendPlainText(f"Chapters: {sidecar_path}")

    @Slot(str)
    def on_failed(self, message: str) -> None:
        self.status.setText(message)
        if message != "Cancelled":
            QMessageBox.critical(self, "Render failed", message)

    @Slot()
    def cancel_render(self) -> None:
        self.cancel_event.set()
        self.status.setText("Cancelling after the current ONNX operation…")

    @Slot()
    def open_last_output(self) -> None:
        if self.last_output and self.last_output.exists():
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.last_output)))

    def _set_running(self, running: bool) -> None:
        self.preview_btn.setEnabled(not running)
        self.render_btn.setEnabled(not running)
        self.cancel_btn.setEnabled(running)


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("LATIF Voice Studio 3.3 Desktop")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
