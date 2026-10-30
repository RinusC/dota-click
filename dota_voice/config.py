"""Загрузка и проверка config.toml."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .keys import parse_combo
from .matcher import normalize


@dataclass(frozen=True)
class Command:
    name: str
    key_text: str
    mods: tuple[str, ...]
    key: str
    phrases: tuple[str, ...]


@dataclass
class Settings:
    model_path: str = "model"
    device: str | int | None = None
    only_when_dota_focused: bool = True
    key_hold_ms: int = 40
    use_grammar: bool = True
    show_unrecognized: bool = True


@dataclass
class Config:
    settings: Settings
    commands: list[Command]
    pause_phrases: tuple[str, ...] = ()
    resume_phrases: tuple[str, ...] = ()
    base_dir: Path = field(default_factory=Path.cwd)

    @property
    def model_dir(self) -> Path:
        path = Path(self.settings.model_path)
        return path if path.is_absolute() else self.base_dir / path


class ConfigError(ValueError):
    pass


def _phrases(raw, where: str) -> tuple[str, ...]:
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list) or not raw or not all(isinstance(p, str) and normalize(p) for p in raw):
        raise ConfigError(f"{where}: phrases должен быть непустым списком строк")
    return tuple(raw)


def parse_config(data: dict, base_dir: Path) -> Config:
    raw_settings = data.get("settings", {})
    known = Settings.__dataclass_fields__
    unknown = set(raw_settings) - set(known)
    if unknown:
        raise ConfigError(f"[settings]: неизвестные параметры {sorted(unknown)}")
    settings = Settings(**raw_settings)
    if settings.device == "":
        settings.device = None
    if not 10 <= settings.key_hold_ms <= 200:
        raise ConfigError("[settings] key_hold_ms должен быть от 10 до 200")

    commands = []
    for i, raw in enumerate(data.get("command", []), 1):
        where = f"[[command]] №{i}"
        key_text = raw.get("key")
        if not isinstance(key_text, str):
            raise ConfigError(f"{where}: key должен быть одной клавишей-строкой, "
                              f"например \"q\" (последовательности нажатий запрещены)")
        try:
            mods, key = parse_combo(key_text)
        except ValueError as e:
            raise ConfigError(f"{where}: {e}") from None
        phrases = _phrases(raw.get("phrases"), where)
        commands.append(Command(raw.get("name") or phrases[0], key_text, mods, key, phrases))
    if not commands:
        raise ConfigError("в конфиге нет ни одной [[command]]")

    control = data.get("control", {})
    pause = _phrases(control["pause"], "[control] pause") if "pause" in control else ()
    resume = _phrases(control["resume"], "[control] resume") if "resume" in control else ()

    seen: dict[str, str] = {}
    for owner, phrases in [(c.name, c.phrases) for c in commands] + [("пауза", pause), ("продолжить", resume)]:
        for p in phrases:
            n = normalize(p)
            if n in seen and seen[n] != owner:
                raise ConfigError(f"фраза «{p}» встречается и в «{seen[n]}», и в «{owner}»")
            seen[n] = owner

    return Config(settings, commands, pause, resume, base_dir)


def load_config(path: Path) -> Config:
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except tomllib.TOMLDecodeError as e:
        raise ConfigError(f"{path}: ошибка синтаксиса: {e}") from None
    return parse_config(data, path.resolve().parent)
