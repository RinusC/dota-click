"""Загрузка и проверка settings.toml и профилей игр (profiles/*.toml)."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
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
    only_when_game_focused: bool = True
    key_hold_ms: int = 40
    use_grammar: bool = True
    show_unrecognized: bool = True

    def model_dir(self, base_dir: Path) -> Path:
        path = Path(self.model_path)
        return path if path.is_absolute() else base_dir / path


@dataclass
class Profile:
    id: str
    name: str
    window_title: str
    commands: list[Command]
    pause_phrases: tuple[str, ...] = ()
    resume_phrases: tuple[str, ...] = ()
    path: Path | None = None

    def all_phrases(self) -> list[str]:
        result = [p for c in self.commands for p in c.phrases]
        return result + list(self.pause_phrases) + list(self.resume_phrases)


class ConfigError(ValueError):
    pass


def _read_toml(path: Path) -> dict:
    try:
        with open(path, "rb") as f:
            return tomllib.load(f)
    except tomllib.TOMLDecodeError as e:
        raise ConfigError(f"{path.name}: ошибка синтаксиса: {e}") from None


def _phrases(raw, where: str) -> tuple[str, ...]:
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list) or not raw or not all(isinstance(p, str) and normalize(p) for p in raw):
        raise ConfigError(f"{where}: phrases должен быть непустым списком строк")
    return tuple(raw)


def parse_settings(data: dict) -> Settings:
    raw = data.get("settings", {})
    unknown = set(raw) - set(Settings.__dataclass_fields__)
    if unknown:
        raise ConfigError(f"[settings]: неизвестные параметры {sorted(unknown)}")
    settings = Settings(**raw)
    if settings.device == "":
        settings.device = None
    if not 10 <= settings.key_hold_ms <= 200:
        raise ConfigError("[settings] key_hold_ms должен быть от 10 до 200")
    return settings


def parse_profile(data: dict, profile_id: str, path: Path | None = None) -> Profile:
    game = data.get("game", {})
    name = game.get("name") or profile_id
    window_title = game.get("window_title") or name

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
        raise ConfigError("в профиле нет ни одной [[command]]")

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

    return Profile(profile_id, name, window_title, commands, pause, resume, path)


def load_settings(path: Path) -> Settings:
    return parse_settings(_read_toml(path)) if path.exists() else Settings()


def load_profile(path: Path) -> Profile:
    try:
        return parse_profile(_read_toml(path), path.stem, path)
    except ConfigError as e:
        raise ConfigError(f"{path.name}: {e}") from None


def load_profiles(directory: Path) -> tuple[dict[str, Profile], list[str]]:
    """Все профили из папки; сломанные не мешают остальным, ошибки возвращаются списком."""
    profiles, errors = {}, []
    for path in sorted(directory.glob("*.toml")):
        try:
            profiles[path.stem] = load_profile(path)
        except (OSError, ConfigError) as e:
            errors.append(str(e))
    return profiles, errors
