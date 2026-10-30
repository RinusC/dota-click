"""Где лежат настройки, профили и модель — и при запуске из исходников, и в собранном .exe."""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

FROZEN = getattr(sys, "frozen", False)


def app_dir() -> Path:
    """Папка с пользовательскими файлами: рядом с .exe или корень репозитория."""
    if FROZEN:
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def bundled_dir() -> Path:
    """Папка с файлами, вшитыми в сборку (копии по умолчанию)."""
    return Path(getattr(sys, "_MEIPASS", app_dir()))


def settings_path() -> Path:
    return app_dir() / "settings.toml"


def profiles_dir() -> Path:
    return app_dir() / "profiles"


def ensure_user_files() -> None:
    """Если рядом с .exe нет настроек или профилей — кладёт туда стандартные."""
    src, dst = bundled_dir(), app_dir()
    if src == dst:
        return
    if not (dst / "settings.toml").exists() and (src / "settings.toml").exists():
        shutil.copy2(src / "settings.toml", dst / "settings.toml")
    (dst / "profiles").mkdir(exist_ok=True)
    for profile in (src / "profiles").glob("*.toml"):
        if not (dst / "profiles" / profile.name).exists():
            shutil.copy2(profile, dst / "profiles" / profile.name)


def _state_path() -> Path:
    return app_dir() / "app_state.json"


def load_state() -> dict:
    """Что пользователь выбрал в окне программы в прошлый раз."""
    try:
        return json.loads(_state_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_state(state: dict) -> None:
    try:
        _state_path().write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass
