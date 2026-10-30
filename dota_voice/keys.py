"""Отправка нажатий клавиш через штатный API операционной системы.

Работает так же, как экранная клавиатура Windows или программа для
голосового ввода: ОС получает обычное событие «клавиша нажата / отпущена».
Никакого доступа к процессу игры, её памяти или файлам здесь нет.
"""

from __future__ import annotations

import sys
import threading
import time
from typing import Callable

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


# Скан-код (и признак «серой» клавиши) → имя клавиши.
_BY_SCANCODE = {(code, name in EXTENDED): name for name, code in SCANCODES.items()}


class KeyBlocker:
    """Не пропускает с физической клавиатуры клавиши, которыми сейчас управляет голос.

    Это обычный системный перехват клавиатуры Windows (так работают программы
    переназначения клавиш и push-to-talk), в игру он не вмешивается. Нажатия самой
    программы (SendInput) Windows помечает как программные — они проходят.

    should_block(имя клавиши, зажатые модификаторы) решает, глушить ли клавишу прямо сейчас.
    """

    def __init__(self, should_block: Callable[[str, frozenset[str]], bool]) -> None:
        self._should_block = should_block
        self._swallowed: set[tuple[int, bool]] = set()  # заглушённые нажатия, ждём их отпускания
        self._thread: threading.Thread | None = None
        self._thread_id = 0
        self._error: Exception | None = None

    def decide(self, scancode: int, extended: bool, down: bool, injected: bool,
               mods: frozenset[str] = frozenset()) -> bool:
        """True — событие клавиатуры надо проглотить."""
        if injected:
            return False
        code = (scancode, extended)
        if not down:
            # Отпускание глушим только у тех клавиш, чьё нажатие заглушили,
            # иначе игра «залипнет» на нажатой клавише.
            if code in self._swallowed:
                self._swallowed.discard(code)
                return True
            return False
        name = _BY_SCANCODE.get(code)
        if name and self._should_block(name, mods):
            self._swallowed.add(code)
            return True
        return False

    def start(self) -> None:
        if sys.platform != "win32":
            raise OSError("блокировка клавиатуры работает только в Windows")
        ready = threading.Event()
        self._thread = threading.Thread(target=self._loop, args=(ready,), daemon=True)
        self._thread.start()
        ready.wait(5)
        if self._error:
            raise self._error

    def stop(self) -> None:
        if self._thread and self._thread_id:
            import ctypes
            WM_QUIT = 0x0012
            ctypes.windll.user32.PostThreadMessageW(self._thread_id, WM_QUIT, 0, 0)
            self._thread.join(timeout=2)
        self._thread = None
        self._thread_id = 0

    def _loop(self, ready: threading.Event) -> None:
        import ctypes
        from ctypes import wintypes

        user32 = ctypes.WinDLL("user32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        LRESULT = ctypes.c_ssize_t
        HOOKPROC = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)

        class KBDLLHOOKSTRUCT(ctypes.Structure):
            _fields_ = [("vkCode", wintypes.DWORD), ("scanCode", wintypes.DWORD),
                        ("flags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        user32.SetWindowsHookExW.argtypes = (ctypes.c_int, HOOKPROC, wintypes.HINSTANCE, wintypes.DWORD)
        user32.SetWindowsHookExW.restype = wintypes.HHOOK
        user32.CallNextHookEx.argtypes = (wintypes.HHOOK, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)
        user32.CallNextHookEx.restype = LRESULT
        user32.UnhookWindowsHookEx.argtypes = (wintypes.HHOOK,)
        user32.GetAsyncKeyState.argtypes = (ctypes.c_int,)
        user32.GetAsyncKeyState.restype = ctypes.c_short
        user32.GetMessageW.argtypes = (ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT)
        kernel32.GetModuleHandleW.argtypes = (wintypes.LPCWSTR,)
        kernel32.GetModuleHandleW.restype = wintypes.HMODULE

        WH_KEYBOARD_LL, HC_ACTION = 13, 0
        WM_KEYDOWN, WM_SYSKEYDOWN = 0x0100, 0x0104
        LLKHF_EXTENDED, LLKHF_INJECTED = 0x01, 0x10
        MOD_VKS = (("ctrl", 0x11), ("shift", 0x10), ("alt", 0x12))

        def proc(code, wparam, lparam):
            if code == HC_ACTION:
                try:
                    k = ctypes.cast(lparam, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
                    mods = frozenset(m for m, vk in MOD_VKS if user32.GetAsyncKeyState(vk) & 0x8000)
                    if self.decide(k.scanCode, bool(k.flags & LLKHF_EXTENDED),
                                   wparam in (WM_KEYDOWN, WM_SYSKEYDOWN), bool(k.flags & LLKHF_INJECTED), mods):
                        return 1
                except Exception:  # noqa: BLE001 — ошибка в перехвате не должна ломать клавиатуру
                    pass
            return user32.CallNextHookEx(None, code, wparam, lparam)

        self._proc = HOOKPROC(proc)  # ссылка нужна, иначе сборщик мусора удалит обработчик
        hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc, kernel32.GetModuleHandleW(None), 0)
        if not hook:
            self._error = OSError(ctypes.get_last_error(), "не удалось включить блокировку клавиатуры")
            ready.set()
            return
        self._thread_id = kernel32.GetCurrentThreadId()
        ready.set()
        msg = wintypes.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            pass
        user32.UnhookWindowsHookEx(hook)
