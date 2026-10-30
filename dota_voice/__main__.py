"""Dota Voice — играй в Dota 2 голосом.

Запуск: python -m dota_voice [--config config.toml] [--dry-run]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .config import Command, Config, ConfigError, load_config
from .keys import active_window_title, make_sender
from .matcher import Matcher, normalize

PAUSE, RESUME = "pause", "resume"
DEFAULT_CONFIG = Path(__file__).resolve().parent.parent / "config.toml"


def build_matcher(config: Config) -> Matcher:
    phrases: dict[str, Command | str] = {}
    for cmd in config.commands:
        for p in cmd.phrases:
            phrases[p] = cmd
    for p in config.pause_phrases:
        phrases[p] = PAUSE
    for p in config.resume_phrases:
        phrases[p] = RESUME
    return Matcher(phrases)


def all_phrases(config: Config) -> list[str]:
    result = [p for c in config.commands for p in c.phrases]
    return result + list(config.pause_phrases) + list(config.resume_phrases)


def print_commands(config: Config, unknown: dict[str, list[str]]) -> None:
    print("Команды:")
    width = max(len(c.key_text) for c in config.commands)
    for cmd in config.commands:
        heard = [p for p in cmd.phrases if p not in unknown]
        print(f"  {cmd.key_text.upper():<{width}}  {cmd.name}: «{'», «'.join(heard) or '—'}»")
    if config.pause_phrases:
        print(f"  Пауза: «{'», «'.join(config.pause_phrases)}»;"
              f" продолжить: «{'», «'.join(config.resume_phrases)}»")
    if unknown:
        print("\nЭтих фраз нет в словаре модели, они не будут распознаваться"
              " (замените на синонимы в config.toml):")
        for phrase, words in unknown.items():
            print(f"  «{phrase}» — неизвестные слова: {', '.join(words)}")


def run(config: Config, dry_run: bool) -> None:
    from .recognizer import VoiceListener

    listener = VoiceListener(config.model_dir, all_phrases(config),
                             config.settings.device, config.settings.use_grammar)
    sender = make_sender(dry_run)
    matcher = build_matcher(config)
    hold = config.settings.key_hold_ms / 1000

    print_commands(config, listener.unknown)
    mode = "по списку фраз" if listener.use_grammar else "свободное распознавание"
    print(f"\nСлушаю микрофон ({mode}).{' Тестовый режим: клавиши не нажимаются.' if dry_run else ''}"
          f" Выход — Ctrl+C.\n")

    paused = False
    for text in listener.listen():
        hits = matcher.parse(text)
        if not hits:
            if config.settings.show_unrecognized and normalize(text):
                print(f"   ? «{text}»")
            continue
        for phrase, target in hits:
            if target == PAUSE:
                paused = True
                print("⏸  Пауза. Скажите «продолжить», чтобы вернуть управление.")
            elif target == RESUME:
                paused = False
                print("▶  Снова слушаю команды.")
            elif paused:
                print(f"   (пауза) «{phrase}»")
            elif (config.settings.only_when_dota_focused and not dry_run
                  and (title := active_window_title()) is not None
                  and "dota 2" not in title.lower()):
                print(f"   «{phrase}» — окно Dota 2 не активно, пропускаю")
            else:
                sender.press(target.mods, target.key, hold)
                print(f"🎤 «{phrase}» → {target.key_text.upper()}  ({target.name})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="dota_voice", description="Голосовое управление для Dota 2")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="путь к config.toml")
    parser.add_argument("--dry-run", action="store_true",
                        help="только показывать распознанные команды, не нажимать клавиши")
    parser.add_argument("--list-devices", action="store_true", help="показать микрофоны и выйти")
    parser.add_argument("--download-model", action="store_true",
                        help="скачать русскую модель распознавания (~45 МБ) и выйти")
    args = parser.parse_args(argv)

    try:
        config = load_config(args.config)
    except (OSError, ConfigError) as e:
        print(f"Ошибка конфига: {e}", file=sys.stderr)
        return 2

    if args.list_devices:
        import sounddevice as sd
        print(sd.query_devices())
        return 0
    if args.download_model:
        from .recognizer import download_model
        download_model(config.model_dir)
        return 0

    try:
        run(config, args.dry_run)
    except KeyboardInterrupt:
        print("\nПока! GG WP")
    except FileNotFoundError as e:
        print(e, file=sys.stderr)
        return 1
    except ModuleNotFoundError as e:
        print(f"Не установлена библиотека {e.name}. Выполните: pip install -r requirements.txt",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
