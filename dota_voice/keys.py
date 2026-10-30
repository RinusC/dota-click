"""Отправка нажатий клавиш через штатный API операционной системы.

Работает так же, как экранная клавиатура Windows или программа для
голосового ввода: ОС получает обычное событие «клавиша нажата / отпущена».
Никакого доступа к процессу игры, её памяти или файлам здесь нет.
"""

from __future__ import annotations

import sys
import time

MODIFIERS = ("ctrl", "shift", "alt")

# Скан-коды (set 1). Игры на DirectX/SDL надёжнее принимают именно их,
# а не виртуальные коды клавиш.
SCANCODES: dict[str, int] = {
    "esc": 0x01,
    "1": 0x02, "2": 0x03, "3": 0x04, "4": 0x05, "5": 0x06,
    "6": 0x07, "7": 0x08, "8": 0x09, "9": 0x0A, "0": 0x0B,
    "-": 0x0C, "=": 0x0D, "backspace": 0x0E, "tab": 0x0F,
    "q": 0x10, "w": 0x11, "e": 0x12, "r": 0x13, "t": 0x14,
    "y": 0x15, "u": 0x16, "i": 0x17, "o": 0x18, "p": 0x19,
    "[": 0x1A, "]": 0x1B, "enter": 0x1C, "ctrl": 0x1D,
    "a": 0x1E, "s": 0x1F, "d": 0x20, "f": 0x21, "g": 0x22,
    "h": 0x23, "j": 0x24, "k": 0x25, "l": 0x26, ";": 0x27,
    "'": 0x28, "`": 0x29, "shift": 0x2A, "\\": 0x2B,
    "z": 0x2C, "x": 0x2D, "c": 0x2E, "v": 0x2F, "b": 0x30,
    "n": 0x31, "m": 0x32, ",": 0x33, ".": 0x34, "/": 0x35,
    "alt": 0x38, "space": 0x39,
    "f1": 0x3B, "f2": 0x3C, "f3": 0x3D, "f4": 0x3E, "f5": 0x3F,
    "f6": 0x40, "f7": 0x41, "f8": 0x42, "f9": 0x43, "f10": 0x44,
    "f11": 0x57, "f12": 0x58,
    "home": 0x47, "up": 0x48, "pageup": 0x49, "left": 0x4B,
    "right": 0x4D, "end": 0x4F, "down": 0x50, "pagedown": 0x51,
    "insert": 0x52, "delete": 0x53,
}

# Клавиши из «серого» блока требуют флага расширенной клавиши.
EXTENDED = {"home", "up", "pageup", "left", "right", "end", "down",
            "pagedown", "insert", "delete"}


def parse_combo(text: str) -> tuple[tuple[str, ...], str]:
    """Разбирает «q», «ctrl+q» и т.п. в (модификаторы, клавиша).

    Разрешена ровно одна основная клавиша: одна фраза = одно нажатие.
    """
    parts = [p.strip().lower() for p in text.split("+")]
    if not parts or any(not p for p in parts):
        raise ValueError(f"не понимаю клавишу {text!r}")
    *mods, key = parts
    for mod in mods:
        if mod not in MODIFIERS:
            raise ValueError(
                f"в {text!r} можно комбинировать только с ctrl/shift/alt, "
                f"а не с {mod!r} (одна фраза = одно нажатие)")
    if len(set(mods)) != len(mods):
        raise ValueError(f"модификатор повторяется в {text!r}")
    if key not in SCANCODES:
        raise ValueError(f"неизвестная клавиша {key!r} в {text!r}")
    return tuple(mods), key


class WindowsSender:
    """SendInput со скан-кодами — стандартный путь Windows."""

    def __init__(self) -> None:
        import ctypes
        from ctypes import wintypes

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                        ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                        ("wParamH", wintypes.WORD)]

        class _UNION(ctypes.Union):
            _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _UNION)]

        self._ctypes = ctypes
        self._INPUT = INPUT
        self._KEYBDINPUT = KEYBDINPUT
        self._user32 = ctypes.WinDLL("user32", use_last_error=True)
        self._user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        self._user32.SendInput.restype = wintypes.UINT

    def _key(self, name: str, up: bool) -> None:
        INPUT_KEYBOARD, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_SCANCODE = 1, 0x1, 0x2, 0x8
        flags = KEYEVENTF_SCANCODE
        if name in EXTENDED:
            flags |= KEYEVENTF_EXTENDEDKEY
        if up:
            flags |= KEYEVENTF_KEYUP
        event = self._INPUT(type=INPUT_KEYBOARD,
                            ki=self._KEYBDINPUT(wVk=0, wScan=SCANCODES[name], dwFlags=flags))
        if self._user32.SendInput(1, self._ctypes.byref(event), self._ctypes.sizeof(event)) != 1:
            raise OSError(self._ctypes.get_last_error(), "SendInput не сработал")

    def press(self, mods: tuple[str, ...], key: str, hold: float) -> None:
        for mod in mods:
            self._key(mod, up=False)
        self._key(key, up=False)
        time.sleep(hold)
        self._key(key, up=True)
        for mod in reversed(mods):
            self._key(mod, up=True)


class PynputSender:
    """Linux/macOS: те же системные события через pynput."""

    def __init__(self) -> None:
        from pynput.keyboard import Controller, Key, KeyCode

        self._kb = Controller()
        special = {
            "esc": Key.esc, "backspace": Key.backspace, "tab": Key.tab,
            "enter": Key.enter, "ctrl": Key.ctrl, "shift": Key.shift,
            "alt": Key.alt, "space": Key.space, "home": Key.home,
            "up": Key.up, "pageup": Key.page_up, "left": Key.left,
            "right": Key.right, "end": Key.end, "down": Key.down,
            "pagedown": Key.page_down, "insert": Key.insert, "delete": Key.delete,
        }
        special.update({f"f{i}": getattr(Key, f"f{i}") for i in range(1, 13)})
        self._map = {name: special.get(name) or KeyCode.from_char(name) for name in SCANCODES}

    def press(self, mods: tuple[str, ...], key: str, hold: float) -> None:
        for mod in mods:
            self._kb.press(self._map[mod])
        self._kb.press(self._map[key])
        time.sleep(hold)
        self._kb.release(self._map[key])
        for mod in reversed(mods):
            self._kb.release(self._map[mod])


class DrySender:
    """Ничего не нажимает — для проверки распознавания."""

    def press(self, mods: tuple[str, ...], key: str, hold: float) -> None:
        pass


def make_sender(dry_run: bool = False):
    if dry_run:
        return DrySender()
    if sys.platform == "win32":
        return WindowsSender()
    return PynputSender()


def active_window_title() -> str | None:
    """Заголовок окна в фокусе (только Windows), иначе None."""
    if sys.platform != "win32":
        return None
    import ctypes

    user32 = ctypes.windll.user32
    hwnd = user32.GetForegroundWindow()
    length = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(hwnd, buf, length + 1)
    return buf.value
