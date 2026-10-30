"""Основной цикл: микрофон → фраза → одно нажатие. Общий для окна и консоли."""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .config import Command, Profile, Settings
from .keys import active_window_title, make_sender
from .matcher import Matcher, normalize

PAUSE, RESUME = "pause", "resume"


@dataclass
class Event:
    """Что произошло. kind: ready, command, unrecognized, paused, resumed,
    skipped (пауза или окно игры не активно), error, stopped."""
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
        from .recognizer import VoiceListener

        try:
            self._sender = self._sender or make_sender(self.dry_run)
            listener = VoiceListener(self.model_dir, self.profile.all_phrases(),
                                     self.device, self.settings.use_grammar)
            self._matcher = build_matcher(self.profile, listener.aliases)
            self._display = {normalize(a): o for a, o in listener.aliases.items()}
            mode = "по списку фраз" if listener.use_grammar else "свободное распознавание"
            self.on_event(Event("ready", mode, unknown=listener.unknown))
            for text in listener.listen(self._stop):
                self.handle(text)
        except Exception as e:  # noqa: BLE001 — любую ошибку показываем пользователю
            self.on_event(Event("error", str(e) or type(e).__name__))
        finally:
            self.on_event(Event("stopped"))

    def handle(self, text: str) -> None:
        hits = self._matcher.parse(text)
        if not hits:
            if self.settings.show_unrecognized and normalize(text):
                self.on_event(Event("unrecognized", normalize(text)))
            return
        hold = self.settings.key_hold_ms / 1000
        for phrase, target in hits:
            phrase = self._display.get(phrase, phrase)
            if target == PAUSE:
                self.set_paused(True)
            elif target == RESUME:
                self.set_paused(False)
            elif self.paused:
                self.on_event(Event("skipped", f"«{phrase}» — голосовые команды на паузе", target))
            elif not self._game_focused():
                self.on_event(Event("skipped", f"«{phrase}» — окно {self.profile.name} не активно", target))
            else:
                self._sender.press(target.mods, target.key, hold)
                self.on_event(Event("command", phrase, target))

    def _game_focused(self) -> bool:
        if not self.settings.only_when_game_focused or self.dry_run:
            return True
        title = active_window_title()
        return title is None or self.profile.window_title.lower() in title.lower()
