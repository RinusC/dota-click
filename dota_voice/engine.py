"""Основной цикл: микрофон → фраза → одно нажатие. Общий для окна и консоли."""

from __future__ import annotations

import queue
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .config import Command, Profile, Settings
from .keys import active_window_title, make_sender
from .matcher import Matcher, normalize
from .recognizer import Hypothesis, VoiceListener

PAUSE, RESUME = "pause", "resume"
STABLE_SECONDS = 0.15  # сколько промежуточная гипотеза должна не меняться, чтобы ей поверить


@dataclass
class Event:
    """Что произошло. kind: ready, partial (что слышно прямо сейчас), command,
    unrecognized, paused, resumed, skipped (пауза или окно игры не активно), error, stopped."""
    kind: str
    text: str = ""
    command: Command | None = None
    unknown: dict[str, list[str]] = field(default_factory=dict)


def build_matcher(profile: Profile, aliases: dict[str, str] | None = None) -> Matcher:
    """aliases: другое написание фразы (как её слышит распознаватель) → фраза из профиля."""
    phrases: dict[str, Command | str] = {}
    for cmd in profile.commands:
        for p in cmd.phrases:
            phrases[p] = cmd
    for p in profile.pause_phrases:
        phrases[p] = PAUSE
    for p in profile.resume_phrases:
        phrases[p] = RESUME
    for alias, original in (aliases or {}).items():
        phrases.setdefault(alias, phrases[original])
    return Matcher(phrases)


class VoiceEngine:
    """Слушает микрофон и превращает фразы в нажатия.

    Нажатия выполняет отдельный поток по очереди: если быстро сказать
    «один два», сработают обе команды в том порядке, в каком сказаны,
    а распознавание при этом не ждёт, пока клавиша будет нажата и отпущена.

    Быстрая реакция: фраза считается законченной после reaction_delay_ms тишины
    (Vosk сам ждёт ~0,8 с), а если сразу за командой звучит следующая, первая
    выполняется, не дожидаясь паузы, — как только её распознавание устоялось.
    """

    def __init__(self, settings: Settings, profile: Profile, model_dir: Path,
                 on_event: Callable[[Event], None], dry_run: bool = False,
                 device: str | int | None = None) -> None:
        self.settings = settings
        self.profile = profile
        self.model_dir = model_dir
        self.on_event = on_event
        self.dry_run = dry_run
        self.device = device if device is not None else settings.device
        self.paused = False
        self._matcher = build_matcher(profile)
        self._display: dict[str, str] = {}
        self._sender = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._keys: queue.Queue[tuple[str, Command] | None] = queue.Queue()
        self._key_thread: threading.Thread | None = None
        # Текущая (ещё не законченная) фраза: сколько команд из неё уже выполнено
        # и с какого момента держится неизменной каждая следующая.
        self._fired = 0
        self._candidate: tuple[int, str] | None = None
        self._candidate_since = 0.0
        self._last_partial = ""

    # --- управление ---

    def start(self) -> None:
        """Запуск в фоновом потоке (для окна)."""
        self._stop.clear()
        self._thread = threading.Thread(target=self.run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout=3)

    @property
    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    def set_paused(self, paused: bool) -> None:
        if paused != self.paused:
            self.paused = paused
            self.on_event(Event(PAUSE + "d" if paused else RESUME + "d"))

    # --- работа ---

    def run(self) -> None:
        """Слушает микрофон, пока не вызовут stop(). Блокирует текущий поток."""

        try:
            self._sender = self._sender or make_sender(self.dry_run)
            listener = VoiceListener(self.model_dir, self.profile.all_phrases(),
                                     self.device, self.settings.use_grammar)
            self._matcher = build_matcher(self.profile, listener.aliases)
            self._display = {normalize(a): o for a, o in listener.aliases.items()}
            self.start_key_worker()
            mode = "по списку фраз" if listener.use_grammar else "свободное распознавание"
            if self.settings.fast_reaction:
                mode += ", быстрая реакция"
            self.on_event(Event("ready", mode, unknown=listener.unknown))
            endpoint = self.settings.reaction_delay_ms / 1000 if self.settings.fast_reaction else None
            for hypothesis in listener.listen(self._stop, endpoint):
                self.handle(hypothesis)
        except Exception as e:  # noqa: BLE001 — любую ошибку показываем пользователю
            self.on_event(Event("error", str(e) or type(e).__name__))
        finally:
            self.stop_key_worker()
            self.on_event(Event("stopped"))

    def handle(self, h: Hypothesis) -> None:
        if h.final:
            self._finish(h.text)
            return
        if h.text != self._last_partial:
            self._last_partial = h.text
            self.on_event(Event("partial", normalize(h.text)))
        if not self.settings.fast_reaction:
            return
        # Команды, за которыми уже звучит следующее слово, выполняем сразу, когда
        # гипотеза продержалась STABLE_SECONDS. Последнюю команду фразы — только по
        # законченному результату: сразу после конца слова промежуточный текст бывает неточным.
        n_tokens, hits = self._matcher.parse_tokens(h.text)
        while self._fired < len(hits):
            phrase, target, end = hits[self._fired]
            if (self._fired, phrase) != self._candidate:
                self._candidate, self._candidate_since = (self._fired, phrase), h.time
            if (end == n_tokens or h.time - self._candidate_since < STABLE_SECONDS
                    or self._matcher.is_open(phrase)):
                return
            self._fired += 1
            self._dispatch(phrase, target)

    def _finish(self, text: str) -> None:
        """Фраза закончена: выполняем то, что не успели выполнить по ходу речи."""
        hits = self._matcher.parse(text)
        if not hits and not self._fired and self.settings.show_unrecognized and normalize(text):
            self.on_event(Event("unrecognized", normalize(text)))
        for phrase, target in hits[self._fired:]:
            self._dispatch(phrase, target)
        self._fired = 0
        self._candidate = None
        self._last_partial = ""
        self.on_event(Event("partial", ""))

    def _dispatch(self, phrase: str, target) -> None:
        phrase = self._display.get(phrase, phrase)
        if target == PAUSE:
            self.set_paused(True)
        elif target == RESUME:
            self.set_paused(False)
        elif self.paused:
            self.on_event(Event("skipped", f"«{phrase}» — голосовые команды на паузе", target))
        else:
            self._keys.put((phrase, target))

    # --- очередь нажатий ---

    def start_key_worker(self) -> None:
        self._sender = self._sender or make_sender(self.dry_run)
        self._key_thread = threading.Thread(target=self._press_keys, daemon=True)
        self._key_thread.start()

    def stop_key_worker(self) -> None:
        """Останавливает очередь; ещё не нажатые клавиши после «Стоп» не нажимаются."""
        if not self._key_thread:
            return
        try:
            while True:
                self._keys.get_nowait()
        except queue.Empty:
            pass
        self._keys.put(None)
        self._key_thread.join(timeout=2)
        self._key_thread = None

    def _press_keys(self) -> None:
        hold = self.settings.key_hold_ms / 1000
        gap = self.settings.key_gap_ms / 1000
        while (item := self._keys.get()) is not None:
            phrase, cmd = item
            if not self._game_focused():
                self.on_event(Event("skipped", f"«{phrase}» — окно {self.profile.name} не активно", cmd))
                continue
            self._sender.press(cmd.mods, cmd.key, hold)
            self.on_event(Event("command", phrase, cmd))
            if gap and not self._keys.empty():
                time.sleep(gap)

    def _game_focused(self) -> bool:
        if not self.settings.only_when_game_focused or self.dry_run:
            return True
        title = active_window_title()
        return title is None or self.profile.window_title.lower() in title.lower()
