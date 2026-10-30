"""Офлайн-распознавание речи с микрофона (Vosk)."""

from __future__ import annotations

import json
import queue
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import Iterable, Iterator

MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"


def download_model(target: Path, url: str = MODEL_URL) -> None:
    """Скачивает и распаковывает модель в папку target."""
    if target.exists():
        raise FileExistsError(f"папка {target} уже существует — удалите её, чтобы скачать заново")
    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "model.zip"
        print(f"Скачиваю {url} ...")
        urllib.request.urlretrieve(url, archive)
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(tmp)
        unpacked = [p for p in Path(tmp).iterdir() if p.is_dir()]
        if len(unpacked) != 1:
            raise RuntimeError("неожиданная структура архива модели")
        shutil.move(str(unpacked[0]), target)
    print(f"Модель сохранена в {target}")


def word_variants(word: str) -> list[str]:
    """Варианты написания с «е»/«ё» — словари моделей пишут по-разному."""
    plain = word.replace("ё", "е")
    variants = [word, plain]
    variants += [plain[:i] + "ё" + plain[i + 1:] for i, c in enumerate(plain) if c == "е"]
    return list(dict.fromkeys(variants))


def vocab_form(model, phrase: str) -> tuple[str | None, list[str]]:
    """Фраза в написании словаря модели и список слов, которых в словаре нет."""
    words, missing = [], []
    for word in phrase.lower().split():
        form = next((v for v in word_variants(word) if model.vosk_model_find_word(v) != -1), None)
        if form is None:
            missing.append(word)
        else:
            words.append(form)
    return (None, missing) if missing else (" ".join(words), [])


class VoiceListener:
    def __init__(self, model_dir: Path, phrases: Iterable[str],
                 device: str | int | None, use_grammar: bool) -> None:
        if not model_dir.is_dir():
            raise FileNotFoundError(
                f"Не найдена модель распознавания в {model_dir}.\n"
                f"Скачайте её командой: python -m dota_voice --download-model")
        import sounddevice as sd
        import vosk

        vosk.SetLogLevel(-1)
        self._sd = sd
        self._vosk = vosk
        self.model = vosk.Model(str(model_dir))
        self.device = device
        self.sample_rate = int(sd.query_devices(device, "input")["default_samplerate"])

        self.grammar: list[str] = []
        self.unknown: dict[str, list[str]] = {}
        for phrase in phrases:
            form, missing = vocab_form(self.model, phrase)
            if form:
                self.grammar.append(form)
            else:
                self.unknown[phrase] = missing
        self.use_grammar = use_grammar and bool(self.grammar)

    def _recognizer(self):
        if self.use_grammar:
            # [unk] поглощает посторонние слова, чтобы они не превращались в команды.
            grammar = json.dumps(sorted(set(self.grammar)) + ["[unk]"], ensure_ascii=False)
            return self._vosk.KaldiRecognizer(self.model, self.sample_rate, grammar)
        return self._vosk.KaldiRecognizer(self.model, self.sample_rate)

    def listen(self) -> Iterator[str]:
        audio: queue.Queue[bytes] = queue.Queue()

        def callback(indata, frames, time, status):
            audio.put(bytes(indata))

        rec = self._recognizer()
        with self._sd.RawInputStream(samplerate=self.sample_rate, blocksize=4000,
                                     device=self.device, dtype="int16",
                                     channels=1, callback=callback):
            while True:
                if rec.AcceptWaveform(audio.get()):
                    text = json.loads(rec.Result()).get("text", "")
                    if text:
                        yield text
