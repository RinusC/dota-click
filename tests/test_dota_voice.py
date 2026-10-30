import contextlib
import io
import json
import sys
import tempfile
import types
import unittest
import unittest.mock
import urllib.parse
import zipfile
from pathlib import Path

from dota_voice.__main__ import DEFAULT_CONFIG, PAUSE, build_matcher, run
from dota_voice.config import ConfigError, load_config, parse_config
from dota_voice.keys import parse_combo
from dota_voice.matcher import Matcher, normalize
from dota_voice.recognizer import download_model, vocab_form, word_variants


class KeysTest(unittest.TestCase):
    def test_single_key_and_modifier(self):
        self.assertEqual(parse_combo("Q"), ((), "q"))
        self.assertEqual(parse_combo("ctrl + r"), (("ctrl",), "r"))
        self.assertEqual(parse_combo("f1"), ((), "f1"))

    def test_rejects_sequences_and_unknown_keys(self):
        for bad in ["q+w", "ctrl+ctrl+q", "", "ctrl+", "щ", "mouse1"]:
            with self.assertRaises(ValueError, msg=bad):
                parse_combo(bad)


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
    def test_default_config_is_valid(self):
        config = load_config(DEFAULT_CONFIG)
        keys = {c.key_text: c for c in config.commands}
        for key in "qwedfr" + "zxcvbn":
            self.assertIn(key, keys)
        self.assertIn("ульта", keys["r"].phrases)
        self.assertIn("первый скилл", keys["q"].phrases)
        self.assertIn("один", keys["z"].phrases)
        m = build_matcher(config)
        self.assertEqual(m.parse("ульта")[0][1].key, "r")
        self.assertEqual(m.parse("один")[0][1].key, "z")
        self.assertEqual(m.parse("голос пауза")[0][1], PAUSE)

    def test_key_list_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_config({"command": [{"key": ["q", "w"], "phrases": ["комбо"]}]}, Path("."))

    def test_duplicate_phrase_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_config({"command": [{"key": "q", "phrases": ["один"]},
                                      {"key": "w", "phrases": ["Один"]}]}, Path("."))

    def test_unknown_setting_is_rejected(self):
        with self.assertRaises(ConfigError):
            parse_config({"settings": {"auto_combo": True},
                          "command": [{"key": "q", "phrases": ["а"]}]}, Path("."))


class FakeModel:
    def __init__(self, words):
        self.words = set(words)

    def vosk_model_find_word(self, word):
        return 1 if word in self.words else -1


class RecognizerTest(unittest.TestCase):
    def test_word_variants(self):
        self.assertIn("четвёртый", word_variants("четвертый"))
        self.assertIn("четвертый", word_variants("четвёртый"))

    def test_vocab_form(self):
        model = FakeModel({"первый", "четвёртый", "навык"})
        self.assertEqual(vocab_form(model, "Четвертый навык"), ("четвёртый навык", []))
        self.assertEqual(vocab_form(model, "первый скилл"), (None, ["скилл"]))

    def test_download_model_unpacks_into_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "m.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("vosk-model-small-ru-0.22/am/final.mdl", "x")
            target = Path(tmp) / "model"
            download_model(target, "file://" + urllib.parse.quote(str(archive)))
            self.assertTrue((target / "am" / "final.mdl").is_file())


class RunLoopTest(unittest.TestCase):
    """Полный цикл с поддельными микрофоном и распознавателем."""

    def test_spoken_phrases_press_keys(self):
        utterances = ["ульта", "[unk]", "голос пауза", "один",
                      "голос продолжить", "один два", "первый навык"]
        created = {}

        class Recognizer:
            def __init__(self, model, rate, grammar=None):
                created["grammar"] = json.loads(grammar)
                self.left = list(utterances)
            def AcceptWaveform(self, data):
                return True
            def Result(self):
                if not self.left:
                    raise KeyboardInterrupt
                return json.dumps({"text": self.left.pop(0)})

        class Stream:
            def __init__(self, callback, **kw):
                for _ in range(len(utterances) + 1):
                    callback(b"\0" * 8, 4, None, None)
            def __enter__(self):
                return self
            def __exit__(self, *a):
                return False

        vosk = types.SimpleNamespace(
            SetLogLevel=lambda level: None, KaldiRecognizer=Recognizer,
            Model=lambda path: FakeModel(set(" ".join(
                p for c in load_config(DEFAULT_CONFIG).commands for p in c.phrases).split())
                - {"скилл"} | {"голос", "пауза", "продолжить", "выключить", "включить"}))
        sd = types.SimpleNamespace(query_devices=lambda dev, kind: {"default_samplerate": 44100.0},
                                   RawInputStream=Stream)
        pressed = []
        sender = types.SimpleNamespace(press=lambda mods, key, hold: pressed.append((mods, key)))

        config = load_config(DEFAULT_CONFIG)
        with tempfile.TemporaryDirectory() as tmp:
            config.settings.model_path = tmp
            with unittest.mock.patch.dict(sys.modules, {"vosk": vosk, "sounddevice": sd}), \
                 unittest.mock.patch("dota_voice.__main__.make_sender", lambda dry: sender), \
                 contextlib.redirect_stdout(io.StringIO()) as out:
                with self.assertRaises(KeyboardInterrupt):
                    run(config, dry_run=True)

        self.assertEqual(pressed, [((), "r"), ((), "z"), ((), "x"), ((), "q")])
        self.assertIn("ульта", created["grammar"])
        self.assertNotIn("первый скилл", created["grammar"])
        self.assertIn("«первый скилл» — неизвестные слова: скилл", out.getvalue())


if __name__ == "__main__":
    unittest.main()
