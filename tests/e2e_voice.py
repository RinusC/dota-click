"""Проверка на настоящей модели: синтезированная речь → распознавание → очередь нажатий.

Запуск (нужны espeak-ng и модель в папке model/):
    python tests/e2e_voice.py model

Для каждого сценария печатает, какие клавиши нажаты и через сколько миллисекунд
после конца речи — в быстром и в обычном режиме.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import threading
import time
import types
import wave
from array import array
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEBUG = "--debug" in sys.argv
sys.path.insert(0, str(ROOT))

from dota_voice.config import load_profiles, load_settings  # noqa: E402
from dota_voice.engine import VoiceEngine  # noqa: E402

# (что сказать — части через паузу в секундах, какие клавиши ожидаем)
SCENARIOS = [
    (["ульта"], ["r"]),
    (["один"], ["z"]),
    (["один два"], ["z", "x"]),
    (["первый скилл", 0.3, "три"], ["q", "c"]),
    (["второй скилл"], ["w"]),
    (["телепорт"], ["t"]),
]


def speak(text: str, path: Path) -> tuple[array, int]:
    subprocess.run(["espeak-ng", "-v", "ru", "-s", "160", "-w", str(path), text], check=True)
    with wave.open(str(path)) as w:
        return array("h", w.readframes(w.getnframes())), w.getframerate()


def build_audio(parts, tmp: Path) -> tuple[array, int, float]:
    """Склеивает фразы и паузы; возвращает звук, частоту и момент конца речи (с)."""
    audio, rate = array("h"), 22050
    for i, part in enumerate(parts):
        if isinstance(part, str):
            chunk, rate = speak(part, tmp / f"{i}.wav")
            audio.extend(chunk)
        else:
            audio.extend(array("h", [0]) * int(part * rate))
    loud = [i for i, v in enumerate(audio) if abs(v) > 800]
    speech_end = (loud[-1] + 1) / rate if loud else 0.0
    audio.extend(array("h", [0]) * int(2.0 * rate))
    return audio, rate, speech_end


def run(profile, settings, audio: array, rate: int, debug: bool = False):
    fed = threading.Event()

    class Stream:
        def __init__(self, callback, blocksize, **kw):
            self.callback, self.blocksize = callback, blocksize

        def __enter__(self):
            def feed():
                data = audio.tobytes()
                step = self.blocksize * 2
                for i in range(0, len(data), step):
                    self.callback(data[i:i + step], self.blocksize, None, None)
                fed.set()
            threading.Thread(target=feed, daemon=True).start()
            return self

        def __exit__(self, *a):
            return False

    def unsupported(**kw):
        raise ValueError("только родная частота")

    sd = types.SimpleNamespace(query_devices=lambda dev, kind: {"default_samplerate": float(rate)},
                               check_input_settings=unsupported, RawInputStream=Stream)
    sys.modules["sounddevice"] = sd

    fired, errors = [], []
    engine = VoiceEngine(settings, profile, ROOT / "model", lambda e: errors.append(e.text) if e.kind == "error" else None,
                         dry_run=True)
    now = {"t": 0.0}
    handle, dispatch = engine.handle, engine._dispatch
    heard = []
    engine.handle = lambda h: (now.__setitem__("t", h.time), heard.append(h), handle(h))
    engine._dispatch = lambda phrase, target: (fired.append((target.key_text, now["t"])), dispatch(phrase, target))
    engine.start()
    fed.wait(30)
    time.sleep(3)
    engine.stop()
    if errors:
        raise RuntimeError(errors)
    if debug:
        for h in heard[:80]:
            print(f"    {h.time:5.2f} {'FINAL' if h.final else 'part '} {h.text!r} тишина {h.quiet:.2f}")
    return fired


def main() -> int:
    profiles, _ = load_profiles(ROOT / "profiles")
    profile = profiles["dota2"]
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        for parts, expected in SCENARIOS:
            audio, rate, speech_end = build_audio(parts, Path(tmp))
            line = [" + ".join(str(p) for p in parts).ljust(28)]
            for fast in (True, False):
                settings = load_settings(ROOT / "settings.toml")
                settings.fast_reaction = fast
                fired = run(profile, settings, audio, rate, debug=DEBUG and fast and parts == ["один"])
                keys = [k for k, _ in fired]
                delay = (fired[-1][1] - speech_end) * 1000 if fired else float("nan")
                mark = "OK " if keys == expected else "НЕ ТО"
                ok &= keys == expected
                line.append(f"{'быстро' if fast else 'обычно'}: {mark} {','.join(keys) or '—':6} {delay:5.0f} мс")
            print(" | ".join(line), flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
