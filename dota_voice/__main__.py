"""Dota Voice — играй в Dota 2 и Deadlock голосом.

Запуск окна:     python -m dota_voice
Консоль:         python -m dota_voice --console --game deadlock [--dry-run]
"""

from __future__ import annotations

import argparse
import sys

from . import paths
from .config import ConfigError, Profile, load_profiles, load_settings
from .engine import Event, VoiceEngine
from .matcher import normalize


def print_commands(profile: Profile, unknown: dict[str, list[str]]) -> None:
    print(f"Игра: {profile.name}\nКоманды:")
    width = max(len(c.key_text) for c in profile.commands)
    for cmd in profile.commands:
        heard = [p for p in cmd.phrases if p not in unknown]
        print(f"  {cmd.key_text.upper():<{width}}  {cmd.name}: «{'», «'.join(heard) or '—'}»")
    if profile.pause_phrases:
        print(f"  Пауза: «{'», «'.join(profile.pause_phrases)}»;"
              f" продолжить: «{'», «'.join(profile.resume_phrases)}»")
    if unknown:
        print("\nЭтих фраз нет в словаре модели, они не будут распознаваться"
              " (замените на синонимы в профиле):")
        for phrase, words in unknown.items():
            print(f"  «{phrase}» — неизвестные слова: {', '.join(words)}")


def console_printer(profile: Profile, dry_run: bool):
    def on_event(e: Event) -> None:
        if e.kind == "ready":
            print_commands(profile, e.unknown)
            print(f"\nСлушаю микрофон ({e.text}).{' Тестовый режим: клавиши не нажимаются.' if dry_run else ''}"
                  f" Выход — Ctrl+C.\n")
        elif e.kind == "command":
            print(f"🎤 «{e.text}» → {e.command.key_text.upper()}  ({e.command.name})")
        elif e.kind == "unrecognized":
            print(f"   ? «{e.text}»")
        elif e.kind == "skipped":
            print(f"   {e.text}")
        elif e.kind == "paused":
            print("⏸  Пауза. Скажите «голос продолжить», чтобы вернуть управление.")
        elif e.kind == "resumed":
            print("▶  Снова слушаю команды.")
        elif e.kind == "error":
            print(f"Ошибка: {e.text}", file=sys.stderr)
    return on_event


def check(settings, profiles: dict[str, Profile]) -> int:
    """Проверка установки: библиотеки, модель и словарь для каждой игры."""
    import sounddevice  # noqa: F401
    import vosk  # noqa: F401

    print(f"Библиотеки: OK (vosk, sounddevice)\nПрофили: {', '.join(p.name for p in profiles.values())}")
    model_dir = settings.model_dir(paths.app_dir())
    if not model_dir.is_dir():
        print(f"Модель: не найдена ({model_dir})")
        return 0
    from .recognizer import load_model, vocab_form

    model = load_model(model_dir)
    print(f"Модель: OK ({model_dir})")
    for profile in profiles.values():
        forms = {p: vocab_form(model, p) for p in profile.all_phrases()}
        unknown = {p: w for p, (_, w) in forms.items() if w}
        print(f"\n[{profile.name}] фраз: {len(forms)}, неизвестны модели: {len(unknown)}")
        for phrase, (form, words) in forms.items():
            if words:
                print(f"  «{phrase}» — не знает: {', '.join(words)}")
            elif normalize(form) != normalize(phrase):
                print(f"  «{phrase}» — распознаётся как «{form}»")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="dota_voice", description="Голосовое управление для Dota 2 и Deadlock")
    parser.add_argument("--console", action="store_true", help="работать в консоли, без окна")
    parser.add_argument("--game", default="dota2", help="профиль игры для консоли: dota2, deadlock, …")
    parser.add_argument("--dry-run", action="store_true",
                        help="только показывать распознанные команды, не нажимать клавиши")
    parser.add_argument("--list-devices", action="store_true", help="показать микрофоны и выйти")
    parser.add_argument("--download-model", action="store_true",
                        help="скачать русскую модель распознавания (~45 МБ) и выйти")
    parser.add_argument("--check", action="store_true", help="проверить установку и словарь и выйти")
    args = parser.parse_args(argv)

    paths.ensure_user_files()
    if not (args.console or args.list_devices or args.download_model or args.check):
        from .gui import run_gui
        run_gui()
        return 0

    try:
        settings = load_settings(paths.settings_path())
    except (OSError, ConfigError) as e:
        print(f"Ошибка в settings.toml: {e}", file=sys.stderr)
        return 2
    profiles, errors = load_profiles(paths.profiles_dir())
    for error in errors:
        print(f"Ошибка в профиле {error}", file=sys.stderr)

    try:
        if args.list_devices:
            import sounddevice as sd
            print(sd.query_devices())
            return 0
        if args.download_model:
            from .recognizer import download_model
            download_model(settings.model_dir(paths.app_dir()))
            return 0
        if args.check:
            return check(settings, profiles) if not errors else 2

        profile = profiles.get(args.game)
        if profile is None:
            print(f"Нет профиля «{args.game}». Есть: {', '.join(profiles) or 'ничего'}", file=sys.stderr)
            return 2
        engine = VoiceEngine(settings, profile, settings.model_dir(paths.app_dir()),
                             console_printer(profile, args.dry_run), args.dry_run)
        engine.run()
        return 1
    except KeyboardInterrupt:
        print("\nПока! GG WP")
        return 0
    except ModuleNotFoundError as e:
        print(f"Не установлена библиотека {e.name}. Выполните: pip install -r requirements.txt",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
