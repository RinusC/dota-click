"""Офлайн-распознавание речи с микрофона (Vosk)."""

from __future__ import annotations

import json
import queue
import shutil
import tempfile
import threading
import urllib.request
import zipfile
import math
from array import array
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Iterator

from .matcher import normalize

MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
MODEL_RATE = 16000   # частота, на которой обучена модель: без пересчёта звук обрабатывается быстрее
CHUNK_SECONDS = 0.05  # порция звука: чем меньше, тем чаще обновляется промежуточный результат


QUIET_LEVEL = 100     # громкость (RMS), ниже которой звук точно тишина


@dataclass
class Hypothesis:
    """Что распознаватель услышал к моменту time (секунды звука с начала прослушивания).

    final=False — промежуточный результат, пока человек говорит;
    final=True — фраза закончена (распознаватель услышал длинную паузу).
    quiet — сколько секунд подряд в микрофоне тишина (0 — человек сейчас говорит).
    """
    final: bool
    text: str
    time: float
    quiet: float = 0.0


class SilenceMeter:
    """Отличает речь от тишины по громкости, подстраиваясь под фоновый шум микрофона."""

    def __init__(self) -> None:
        self.floor: float | None = None
        self.quiet = 0.0

    def feed(self, data: bytes, seconds: float) -> float:
        samples = array("h", data)
        level = math.sqrt(sum(v * v for v in samples) / len(samples)) if samples else 0.0
        if self.floor is None or level < self.floor:
            self.floor = level if self.floor is None else (self.floor + level) / 2
        else:
            self.floor = self.floor * 0.995 + level * 0.005
        self.quiet = self.quiet + seconds if level < max(self.floor * 2.5, QUIET_LEVEL) else 0.0
        return self.quiet


def download_model(target: Path, url: str = MODEL_URL,
                   progress: Callable[[float], None] | None = None) -> None:
    """Скачивает и распаковывает модель в папку target. progress получает долю 0..1."""
    if target.exists():
        raise FileExistsError(f"папка {target} уже существует — удалите её, чтобы скачать заново")

    def hook(blocks: int, block_size: int, total: int) -> None:
        if progress and total > 0:
            progress(min(1.0, blocks * block_size / total))

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "model.zip"
        print(f"Скачиваю {url} ...")
        urllib.request.urlretrieve(url, archive, hook)
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


def _known(model, word: str) -> str | None:
    return next((v for v in word_variants(word) if model.vosk_model_find_word(v) != -1), None)


def split_word(model, word: str) -> str | None:
    """Незнакомое слово как два знакомых, звучащих так же: «ульта» → «ульт а».

    Распознаватель услышит «ульт а», а программа поймёт это как «ульта».
    """
    for cut in range(len(word) - 1, 2, -1):
        head, tail = _known(model, word[:cut]), _known(model, word[cut:])
        if head and tail:
            return f"{head} {tail}"
    return None


def vocab_form(model, phrase: str) -> tuple[str | None, list[str]]:
    """Фраза в написании словаря модели и список слов, которых в словаре нет."""
    words, missing = [], []
    for word in phrase.lower().split():
        form = _known(model, word) or split_word(model, word)
        if form is None:
            missing.append(word)
        else:
            words.append(form)
    return (None, missing) if missing else (" ".join(words), [])


_models: dict[Path, object] = {}


def load_model(model_dir: Path):
    """Загружает модель Vosk (один раз за запуск программы)."""
    if not model_dir.is_dir():
        raise FileNotFoundError(
            f"Не найдена модель распознавания в {model_dir}.\n"
            f"Скачайте её кнопкой «Скачать модель» или командой: python -m dota_voice --download-model")
    model_dir = model_dir.resolve()
    if model_dir not in _models:
        import vosk

        vosk.SetLogLevel(-1)
        _models[model_dir] = vosk.Model(str(model_dir))
    return _models[model_dir]


def input_devices() -> list[tuple[int, str]]:
    """Микрофоны (номер, название) того же звукового API, что и микрофон по умолчанию."""
    import sounddevice as sd

    try:
        hostapi = sd.query_devices(kind="input")["hostapi"]
    except (ValueError, sd.PortAudioError):
        hostapi = None
    return [(i, d["name"]) for i, d in enumerate(sd.query_devices())
            if d["max_input_channels"] > 0 and (hostapi is None or d["hostapi"] == hostapi)]


class VoiceListener:
    def __init__(self, model_dir: Path, phrases: Iterable[str],
                 device: str | int | None, use_grammar: bool) -> None:
        self.model = load_model(model_dir)
        import sounddevice as sd
        import vosk

        self._sd = sd
        self._vosk = vosk
        self.device = device
        self.sample_rate = self._pick_rate()

        self.grammar: list[str] = []
        self.unknown: dict[str, list[str]] = {}
        # Как фраза будет звучать для распознавателя, если её пришлось записать иначе.
        self.aliases: dict[str, str] = {}
        for phrase in phrases:
            form, missing = vocab_form(self.model, phrase)
            if form:
                self.grammar.append(form)
                if normalize(form) != normalize(phrase):
                    self.aliases[form] = phrase
            else:
                self.unknown[phrase] = missing
        self.use_grammar = use_grammar and bool(self.grammar)

    def _pick_rate(self) -> int:
        try:
            self._sd.check_input_settings(device=self.device, samplerate=MODEL_RATE,
                                          channels=1, dtype="int16")
            return MODEL_RATE
        except Exception:  # noqa: BLE001 — микрофон не умеет 16 кГц, берём его родную частоту
            return int(self._sd.query_devices(self.device, "input")["default_samplerate"])

    def _recognizer(self):
        if self.use_grammar:
            # [unk] поглощает посторонние слова, чтобы они не превращались в команды.
            grammar = json.dumps(sorted(set(self.grammar)) + ["[unk]"], ensure_ascii=False)
            return self._vosk.KaldiRecognizer(self.model, self.sample_rate, grammar)
        return self._vosk.KaldiRecognizer(self.model, self.sample_rate)

    def listen(self, stop: threading.Event | None = None) -> Iterator[Hypothesis]:
        stop = stop or threading.Event()
        audio: queue.Queue[bytes] = queue.Queue()

        def callback(indata, frames, time, status):
            audio.put(bytes(indata))

        rec = self._recognizer()
        meter = SilenceMeter()
        samples = 0
        last_partial = ""
        with self._sd.RawInputStream(samplerate=self.sample_rate,
                                     blocksize=int(self.sample_rate * CHUNK_SECONDS),
                                     device=self.device, dtype="int16", channels=1,
                                     latency="low", callback=callback):
            while not stop.is_set():
                try:
                    data = audio.get(timeout=0.2)
                except queue.Empty:
                    continue
                count = len(data) // 2
                samples += count
                now = samples / self.sample_rate
                quiet = meter.feed(data, count / self.sample_rate)
                if rec.AcceptWaveform(data):
                    last_partial = ""
                    yield Hypothesis(True, json.loads(rec.Result()).get("text", ""), now, quiet)
                else:
                    # Обычный (не пословный) промежуточный результат: Vosk обновляет его
                    # на каждой порции звука, а пословный — с запаздыванием.
                    text = json.loads(rec.PartialResult()).get("partial", "")
                    if text or last_partial:
                        last_partial = text
                        yield Hypothesis(False, text, now, quiet)
