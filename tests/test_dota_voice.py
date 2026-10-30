import json
from array import array
import sys
import tempfile
import threading
import time
import types
import unittest
import unittest.mock
import zipfile
from pathlib import Path

from dota_voice import paths
from dota_voice.config import ConfigError, load_profiles, load_settings, parse_profile, parse_settings
from dota_voice.engine import PAUSE, Event, VoiceEngine, build_matcher
from dota_voice.recognizer import Hypothesis
from dota_voice.keys import KeyBlocker, parse_combo
from dota_voice.matcher import Matcher, normalize
from dota_voice.recognizer import SilenceMeter, download_model, split_word, vocab_form, word_variants

ROOT = Path(__file__).resolve().parent.parent


def default_profiles():
    profiles, errors = load_profiles(ROOT / "profiles")
    assert not errors, errors
    return profiles


class KeysTest(unittest.TestCase):
    def test_single_key_and_modifier(self):
        self.assertEqual(parse_combo("Q"), ((), "q"))
        self.assertEqual(parse_combo("ctrl + r"), (("ctrl",), "r"))
        self.assertEqual(parse_combo("f1"), ((), "f1"))
        self.assertEqual(parse_combo("4"), ((), "4"))

    def test_rejects_sequences_and_unknown_keys(self):
        for bad in ["q+w", "ctrl+ctrl+q", "", "ctrl+", "щ", "mouse1"]:
            with self.assertRaises(ValueError, msg=bad):
                parse_combo(bad)


class KeyBlockerTest(unittest.TestCase):
    Q, R, SPACE = 0x10, 0x13, 0x39

    def test_blocks_physical_presses_of_voice_keys(self):
        b = KeyBlocker(lambda key, mods: key in {"q", "space"})
        self.assertTrue(b.decide(self.Q, False, True, False))
        self.assertTrue(b.decide(self.Q, False, False, False), "отпускание заглушённой клавиши тоже глушим")
        self.assertTrue(b.decide(self.SPACE, False, True, False))
        self.assertFalse(b.decide(self.R, False, True, False), "клавиши без голосовой команды работают")

    def test_program_own_presses_pass(self):
        b = KeyBlocker(lambda key, mods: True)
        self.assertFalse(b.decide(self.Q, False, True, True))
        self.assertFalse(b.decide(self.Q, False, False, True))

    def test_key_pressed_before_blocking_is_released_normally(self):
        blocking = {"on": False}
        b = KeyBlocker(lambda key, mods: blocking["on"])
        self.assertFalse(b.decide(self.Q, False, True, False))
        blocking["on"] = True
        self.assertFalse(b.decide(self.Q, False, False, False), "иначе игра «залипнет» на нажатой Q")

    def test_key_blocked_on_press_stays_blocked_on_release(self):
        blocking = {"on": True}
        b = KeyBlocker(lambda key, mods: blocking["on"])
        self.assertTrue(b.decide(self.Q, False, True, False))
        blocking["on"] = False
        self.assertTrue(b.decide(self.Q, False, False, False))

    def test_extended_flag_distinguishes_keys(self):
        b = KeyBlocker(lambda key, mods: key == "up")
        self.assertTrue(b.decide(0x48, True, True, False))
        self.assertFalse(b.decide(0x48, False, True, False), "8 на цифровом блоке — не стрелка")

    @unittest.skipUnless(sys.platform == "win32", "системный перехват клавиатуры есть только в Windows")
    def test_windows_hook_installs_and_removes(self):
        b = KeyBlocker(lambda key, mods: False)
        b.start()
        self.assertTrue(b._thread.is_alive())
        b.stop()
        self.assertIsNone(b._thread)


class MatcherTest(unittest.TestCase):
    def setUp(self):
        self.m = Matcher({"первый скилл": "Q", "первый предмет": "Z1",
                          "один": "Z", "два": "X", "ульта": "R"})

    def test_normalize(self):
        self.assertEqual(normalize("  Четвёртый, [unk] СКИЛЛ! "), "четвертый скилл")

    def test_exact_and_sequence(self):
        self.assertEqual(self.m.parse("первый скилл"), [("первый скилл", "Q")])
        self.assertEqual([v for _, v in self.m.parse("[unk] один два")], ["Z", "X"])
        self.assertEqual([v for _, v in self.m.parse("первый предмет ульта")], ["Z1", "R"])

    def test_fuzzy_and_garbage(self):
        self.assertEqual(self.m.parse("ульты"), [("ульта", "R")])
        self.assertEqual(self.m.parse("[unk]"), [])
        self.assertEqual(self.m.parse("привет как дела"), [])
        self.assertEqual(self.m.parse("первый"), [])


class ConfigTest(unittest.TestCase):
    def test_default_settings_are_valid(self):
        settings = load_settings(ROOT / "settings.toml")
        self.assertIsNone(settings.device)
        self.assertEqual(settings.model_dir(ROOT), ROOT / "model")

    def test_dota_profile(self):
        dota = default_profiles()["dota2"]
        self.assertEqual(dota.name, "Dota 2")
        keys = {c.key_text: c for c in dota.commands}
        for key in "qwedfr" + "zxcvbn":
            self.assertIn(key, keys)
        m = build_matcher(dota)
        self.assertEqual(m.parse("ульта")[0][1].key, "r")
        self.assertEqual(m.parse("первый скилл")[0][1].key, "q")
        self.assertEqual(m.parse("один")[0][1].key, "z")
        self.assertEqual(m.parse("голос пауза")[0][1], PAUSE)

    def test_deadlock_profile(self):
        deadlock = default_profiles()["deadlock"]
        self.assertEqual(deadlock.window_title, "Deadlock")
        m = build_matcher(deadlock)
        expected = {"первый скилл": "1", "второй скилл": "2", "третий скилл": "3", "ульта": "4",
                    "один": "z", "два": "x", "три": "c", "четыре": "v",
                    "удар": "q", "блок": "f", "перезарядка": "r"}
        for phrase, key in expected.items():
            self.assertEqual(m.parse(phrase)[0][1].key, key, phrase)

    def test_key_list_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_profile({"command": [{"key": ["q", "w"], "phrases": ["комбо"]}]}, "x")

    def test_duplicate_phrase_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_profile({"command": [{"key": "q", "phrases": ["один"]},
                                       {"key": "w", "phrases": ["Один"]}]}, "x")

    def test_unknown_setting_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_settings({"settings": {"auto_combo": True}})

    def test_broken_profile_does_not_hide_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "good.toml").write_text('[[command]]\nkey = "q"\nphrases = ["а"]\n', encoding="utf-8")
            Path(tmp, "bad.toml").write_text('[[command]]\nkey = "q+w"\nphrases = ["б"]\n', encoding="utf-8")
            profiles, errors = load_profiles(Path(tmp))
        self.assertEqual(list(profiles), ["good"])
        self.assertEqual(len(errors), 1)
        self.assertIn("bad.toml", errors[0])


class PathsTest(unittest.TestCase):
    def test_user_files_are_copied_next_to_exe(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe_dir = Path(tmp)
            with unittest.mock.patch.object(paths, "app_dir", lambda: exe_dir), \
                 unittest.mock.patch.object(paths, "bundled_dir", lambda: ROOT):
                paths.ensure_user_files()
                paths.save_state({"game": "deadlock"})
                self.assertEqual(paths.load_state(), {"game": "deadlock"})
            self.assertTrue((exe_dir / "settings.toml").is_file())
            self.assertTrue((exe_dir / "profiles" / "deadlock.toml").is_file())


class FakeModel:
    def __init__(self, words):
        self.words = set(words)

    def vosk_model_find_word(self, word):
        return 1 if word in self.words else -1


class RecognizerTest(unittest.TestCase):
    def test_silence_meter(self):
        meter = SilenceMeter()
        noise = array("h", [30, -25, 40, -35] * 200).tobytes()
        voice = array("h", [3000, -2800, 2500, -3100] * 200).tobytes()
        for _ in range(10):
            meter.feed(noise, 0.05)
        self.assertAlmostEqual(meter.quiet, 0.5)
        self.assertEqual(meter.feed(voice, 0.05), 0.0)
        meter.feed(noise, 0.05)
        self.assertAlmostEqual(meter.feed(noise, 0.05), 0.1)

    def test_word_variants(self):
        self.assertIn("четвёртый", word_variants("четвертый"))
        self.assertIn("четвертый", word_variants("четвёртый"))

    def test_vocab_form(self):
        model = FakeModel({"первый", "четвёртый", "навык"})
        self.assertEqual(vocab_form(model, "Четвертый навык"), ("четвёртый навык", []))
        self.assertEqual(vocab_form(model, "первый скилл"), (None, ["скилл"]))

    def test_unknown_word_is_split_into_known_ones(self):
        model = FakeModel({"ульт", "а", "у", "ультимейт", "прокачать"})
        self.assertEqual(split_word(model, "ульта"), "ульт а")
        self.assertEqual(vocab_form(model, "прокачать ульту"), ("прокачать ульт у", []))
        self.assertEqual(vocab_form(model, "ультимейта"), ("ультимейт а", []))
        self.assertIsNone(split_word(model, "парирование"))

    def test_download_model_unpacks_into_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "m.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("vosk-model-small-ru-0.22/am/final.mdl", "x" * 10000)
            target = Path(tmp) / "model"
            progress = []
            download_model(target, archive.as_uri(), progress.append)
            self.assertTrue((target / "am" / "final.mdl").is_file())
            self.assertEqual(progress[-1], 1.0)


class EngineTest(unittest.TestCase):
    """Полный цикл с поддельными микрофоном и распознавателем.

    script — что распознаватель выдаёт на каждую порцию звука (50 мс): строка — законченная
    фраза, список слов — промежуточный результат. loud — номера порций с речью, остальное тишина.
    """

    def run_engine(self, profile, script, expected_presses, vocab_missing=(), vocab_extra=(), settings=None,
                   loud=()):
        created = {}
        script = list(script) + ["конец"]

        class Recognizer:
            def __init__(self, model, rate, grammar=None):
                created["grammar"] = json.loads(grammar)
                created["rate"] = rate
                self.left = list(script)
                self.current = None

            def AcceptWaveform(self, data):
                self.current = self.left.pop(0) if self.left else []
                return isinstance(self.current, str)

            def Result(self):
                return json.dumps({"text": self.current})

            def PartialResult(self):
                return json.dumps({"partial": " ".join(self.current)}, ensure_ascii=False)

            def FinalResult(self):
                created["forced_final"] = " ".join(self.current)
                return json.dumps({"text": " ".join(self.current)}, ensure_ascii=False)

        class Stream:
            def __init__(self, callback, samplerate, blocksize, **kw):
                created["blocksize"] = blocksize
                voice = array("h", [3000, -3000] * (blocksize // 2)).tobytes()
                for i in range(len(script) + 1):
                    callback(voice if i in loud else b"\0" * blocksize * 2, blocksize, None, None)

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        words = {w for p in profile.all_phrases() for w in p.split()} - set(vocab_missing) | set(vocab_extra)
        vosk = types.SimpleNamespace(SetLogLevel=lambda level: None, KaldiRecognizer=Recognizer,
                                     Model=lambda path: FakeModel(words))
        sd = types.SimpleNamespace(query_devices=lambda dev, kind: {"default_samplerate": 44100.0},
                                   check_input_settings=lambda **kw: None, RawInputStream=Stream)
        pressed, events = [], []
        sender = types.SimpleNamespace(press=lambda mods, key, hold: pressed.append((mods, key)))
        done = threading.Event()

        def on_event(e: Event):
            events.append(e)
            if e.kind in ("stopped", "error") or (e.kind == "unrecognized" and e.text == "конец"):
                done.set()

        settings = settings or load_settings(ROOT / "settings.toml")
        with tempfile.TemporaryDirectory() as tmp, \
             unittest.mock.patch.dict(sys.modules, {"vosk": vosk, "sounddevice": sd}), \
             unittest.mock.patch("dota_voice.engine.make_sender", lambda dry: sender), \
             unittest.mock.patch("dota_voice.recognizer._models", {}):
            engine = VoiceEngine(settings, profile, Path(tmp), on_event, dry_run=True)
            engine.start()
            self.assertTrue(done.wait(5))
            deadline = time.monotonic() + 2
            while len(pressed) < len(expected_presses) and time.monotonic() < deadline:
                time.sleep(0.01)
            engine.stop()
            self.assertFalse(engine.running)
        self.assertEqual(pressed, expected_presses)
        return events, created

    def test_silence_ends_phrase_without_waiting_for_vosk(self):
        profile = default_profiles()["dota2"]
        # Распознаватель слышит «ульт», в микрофоне тишина. Сам Vosk объявил бы конец фразы
        # поздно (и пустым), но через reaction_delay_ms тишины программа завершает фразу сама.
        script = [["ульт"]] * 12 + [[], [], ""]
        events, created = self.run_engine(profile, script, [((), "r")], loud=range(3))
        self.assertEqual(created["forced_final"], "ульт")
        self.assertEqual(created["rate"], 16000)
        self.assertEqual(created["blocksize"], 800)
        self.assertIn(("partial", "ульт"), [(e.kind, e.text) for e in events])

    def test_dota_phrases_press_keys(self):
        profile = default_profiles()["dota2"]
        events, created = self.run_engine(
            profile, ["ульта", "[unk]", "голос пауза", "один", "голос продолжить",
                      "один два", "первый навык"],
            [((), "r"), ((), "z"), ((), "x"), ((), "q")], vocab_missing={"скилл"})
        kinds = [e.kind for e in events]
        self.assertEqual(kinds[0], "ready")
        self.assertIn("paused", kinds)
        self.assertIn("skipped", kinds)
        self.assertEqual(events[0].unknown["первый скилл"], ["скилл"])
        self.assertIn("ульта", created["grammar"])
        self.assertNotIn("первый скилл", created["grammar"])

    def test_deadlock_phrases_press_keys(self):
        profile = default_profiles()["deadlock"]
        self.run_engine(profile, ["ульта", "блок", "два"], [((), "4"), ((), "f"), ((), "x")])

    def test_split_phrase_is_understood_as_original(self):
        profile = default_profiles()["dota2"]
        events, created = self.run_engine(
            profile, ["ульт а", "прокачать ульт у"], [((), "r"), (("ctrl",), "r")],
            vocab_missing={"ульта", "ульту"}, vocab_extra={"а", "у"})
        self.assertIn("ульт а", created["grammar"])
        self.assertEqual([e.text for e in events if e.kind == "command"], ["ульта", "прокачать ульту"])
        self.assertNotIn("ульта", events[0].unknown)

    def test_missing_model_is_reported_as_error(self):
        events = []
        settings = load_settings(ROOT / "settings.toml")
        engine = VoiceEngine(settings, default_profiles()["dota2"], ROOT / "нет-такой-папки",
                             events.append, dry_run=True)
        engine.run()
        self.assertEqual([e.kind for e in events], ["error", "stopped"])
        self.assertIn("модель", events[0].text.lower())


class QuickReactionTest(unittest.TestCase):
    """Разбор промежуточных результатов: что и когда отправляется в очередь нажатий."""

    def engine(self, profile=None, **settings):
        base = load_settings(ROOT / "settings.toml")
        for name, value in settings.items():
            setattr(base, name, value)
        engine = VoiceEngine(base, profile or default_profiles()["dota2"], ROOT, lambda e: None, dry_run=True)
        return engine

    @staticmethod
    def queued(engine):
        return [cmd.key_text for _, cmd in list(engine._keys.queue)]

    @staticmethod
    def partial(engine, t, text, quiet=0.0):
        engine.handle(Hypothesis(False, text, t, quiet))

    def test_last_phrase_waits_for_final_result(self):
        # Сразу после конца слова промежуточный текст бывает неточным — по нему не жмём.
        e = self.engine()
        for t in (0.4, 0.6, 0.8):
            self.partial(e, t, "герой", quiet=t)
        self.assertEqual(self.queued(e), [])
        e.handle(Hypothesis(True, "один", 1.0, quiet=0.3))
        self.assertEqual(self.queued(e), ["z"])

    def test_word_still_being_spoken_is_not_fired(self):
        # Пока человек говорит «пятый скилл», распознаватель сначала слышит «пять».
        e = self.engine()
        self.partial(e, 0.45, "пять")
        self.partial(e, 0.65, "пятый")
        self.partial(e, 0.95, "пятый скилл")
        e.handle(Hypothesis(True, "пятый скилл", 1.3, quiet=0.3))
        self.assertEqual(self.queued(e), ["f"])

    def test_two_phrases_in_one_breath_fire_in_order(self):
        e = self.engine()
        self.partial(e, 0.45, "один")
        self.partial(e, 0.55, "один два")
        self.assertEqual(self.queued(e), [], "гипотеза «один» ещё слишком свежая")
        self.partial(e, 0.65, "один два")
        self.assertEqual(self.queued(e), ["z"], "за «один» уже звучит следующее слово")
        e.handle(Hypothesis(True, "один два", 1.1))
        self.assertEqual(self.queued(e), ["z", "x"], "законченная фраза добавляет только «два»")

    def test_final_result_fires_what_partials_missed(self):
        e = self.engine()
        self.partial(e, 0.45, "один два")
        self.partial(e, 0.65, "один два")
        e.handle(Hypothesis(True, "один два три", 1.5))
        self.assertEqual(self.queued(e), ["z", "x", "c"])

    def test_slow_mode_waits_for_final(self):
        e = self.engine(fast_reaction=False)
        for t in (0.5, 0.7, 0.9):
            self.partial(e, t, "один два")
        self.assertEqual(self.queued(e), [])
        e.handle(Hypothesis(True, "один два", 1.5))
        self.assertEqual(self.queued(e), ["z", "x"])

    def test_phrase_that_starts_a_longer_one_waits_for_final(self):
        profile = parse_profile({"control": {"pause": ["стоп голос"]},
                                 "command": [{"key": "s", "phrases": ["стоп"]},
                                             {"key": "z", "phrases": ["один"]}]}, "x")
        e = self.engine(profile)
        for t in (0.5, 0.7, 0.9):
            self.partial(e, t, "стоп один")
        self.assertEqual(self.queued(e), [])
        e.handle(Hypothesis(True, "стоп один", 1.5))
        self.assertEqual(self.queued(e), ["s", "z"])

    def test_disabled_command_is_not_pressed(self):
        e = self.engine()
        e.set_disabled({"Предмет 2"})
        e.handle(Hypothesis(True, "один два три", 1.0))
        self.assertEqual(self.queued(e), ["z", "c"])
        e.set_disabled(set())
        e.handle(Hypothesis(True, "два", 2.0))
        self.assertEqual(self.queued(e), ["z", "c", "x"])

    def test_only_enabled_voice_keys_are_blocked(self):
        e = self.engine()
        e.set_disabled({"Ульта"})
        self.assertTrue(e.should_block("q"))
        self.assertTrue(e.should_block("q", frozenset({"shift"})), "Shift+Q — тоже Q")
        self.assertFalse(e.should_block("r"), "отключённая команда — клавиша работает с клавиатуры")
        self.assertTrue(e.should_block("r", frozenset({"ctrl"})), "Ctrl+R («прокачать ульту») включена")
        self.assertIn("ctrl+r", e.blocked_keys)
        self.assertNotIn("r", e.blocked_keys)
        self.assertFalse(e.should_block("g"), "клавиша без команды")
        e.set_paused(True)
        self.assertFalse(e.should_block("q"), "на паузе клавиатура работает как обычно")
        e.set_paused(False)
        with unittest.mock.patch("dota_voice.engine.active_window_title", lambda: "Discord"):
            self.assertFalse(e.should_block("q"), "в других окнах клавиатура работает как обычно")
        with unittest.mock.patch("dota_voice.engine.active_window_title", lambda: "Dota 2"):
            self.assertTrue(e.should_block("q"))

    def test_key_queue_presses_in_order_with_gap(self):
        e = self.engine(key_gap_ms=30, key_hold_ms=10)
        presses = []
        e._sender = types.SimpleNamespace(press=lambda mods, key, hold: presses.append((key, time.perf_counter())))
        e.handle(Hypothesis(True, "один два три", 1.0))
        e.start_key_worker()
        deadline = time.monotonic() + 2
        while len(presses) < 3 and time.monotonic() < deadline:
            time.sleep(0.01)
        e.stop_key_worker()
        self.assertEqual([k for k, _ in presses], ["z", "x", "c"])
        gaps = [b - a for (_, a), (_, b) in zip(presses, presses[1:])]
        self.assertTrue(all(g >= 0.028 for g in gaps), gaps)

    def test_stop_drops_presses_not_yet_made(self):
        e = self.engine()
        e.handle(Hypothesis(True, "один два три", 1.0))
        e._key_thread = threading.Thread(target=lambda: None)
        e._key_thread.start()
        e.stop_key_worker()
        self.assertTrue(e._keys.empty() or list(e._keys.queue) == [None])


if __name__ == "__main__":
    unittest.main()
