"""Окно программы: выбор игры и микрофона, старт/пауза, список команд и журнал."""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

from . import paths
from .config import ConfigError, Profile, Settings, load_profiles, load_settings
from .engine import Event, VoiceEngine

BG = "#15171c"
PANEL = "#1f232b"
FIELD = "#2a2f39"
FG = "#e8e6e3"
MUTED = "#8b919c"
GREEN = "#4caf50"
YELLOW = "#e0b341"
RED = "#e05545"
ACCENTS = {"dota2": "#d4432f", "deadlock": "#c9a227"}
DEFAULT_ACCENT = "#4a90d9"
FONT = "Segoe UI"
DEFAULT_MIC = "По умолчанию"


def open_path(path) -> None:
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 — открываем свой файл/папку в проводнике
    else:
        subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", str(path)])


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        # (движок-источник или None, событие): события старого движка после перезапуска отбрасываются.
        self.events: queue.Queue[tuple[object, Event]] = queue.Queue()
        self.engine: VoiceEngine | None = None
        self.engine_token: object | None = None
        self.state = paths.load_state()
        self.settings = Settings()
        self.profiles: dict[str, Profile] = {}
        self.devices: dict[str, int] = {}
        self._flash_job = None

        root.title("Dota Voice")
        root.geometry("860x600")
        root.minsize(720, 480)
        root.configure(bg=BG)
        self._style()
        self._build()
        self.reload_config()
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self._poll)

    # ---------- оформление ----------

    def _style(self) -> None:
        s = ttk.Style(self.root)
        s.theme_use("clam")
        s.configure(".", background=BG, foreground=FG, fieldbackground=FIELD,
                    bordercolor=PANEL, lightcolor=PANEL, darkcolor=PANEL, font=(FONT, 10))
        s.configure("TFrame", background=BG)
        s.configure("Panel.TFrame", background=PANEL)
        s.configure("TLabel", background=BG, foreground=FG)
        s.configure("Muted.TLabel", foreground=MUTED)
        s.configure("Panel.TLabel", background=PANEL, foreground=MUTED, font=(FONT, 9, "bold"))
        s.configure("Title.TLabel", font=(FONT, 18, "bold"))
        s.configure("TButton", background=FIELD, foreground=FG, padding=(12, 6), borderwidth=0)
        s.map("TButton", background=[("disabled", PANEL), ("active", "#373d49")],
              foreground=[("disabled", MUTED)])
        s.configure("Accent.TButton", font=(FONT, 10, "bold"))
        s.configure("TCheckbutton", background=BG, foreground=FG)
        s.map("TCheckbutton", background=[("active", BG)])
        s.configure("TCombobox", arrowcolor=FG, padding=4)
        s.map("TCombobox", fieldbackground=[("readonly", FIELD)], foreground=[("readonly", FG)],
              selectbackground=[("readonly", FIELD)], selectforeground=[("readonly", FG)])
        self.root.option_add("*TCombobox*Listbox.background", FIELD)
        self.root.option_add("*TCombobox*Listbox.foreground", FG)
        self.root.option_add("*TCombobox*Listbox.selectBackground", "#373d49")
        s.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=FG,
                    rowheight=26, borderwidth=0)
        s.configure("Treeview.Heading", background=FIELD, foreground=MUTED, borderwidth=0,
                    font=(FONT, 9, "bold"))
        s.map("Treeview", background=[("selected", "#373d49")])
        s.configure("Horizontal.TProgressbar", background=GREEN, troughcolor=FIELD)
        s.configure("Vertical.TScrollbar", background=FIELD, troughcolor=PANEL, arrowcolor=MUTED,
                    borderwidth=0)

    def _accent(self) -> str:
        return ACCENTS.get(self.current_profile_id() or "", DEFAULT_ACCENT)

    def _apply_accent(self) -> None:
        accent = self._accent()
        s = ttk.Style(self.root)
        s.configure("Accent.TButton", background=accent, foreground="#ffffff")
        s.map("Accent.TButton", background=[("active", accent), ("disabled", PANEL)])
        self.title_bar.configure(bg=accent)

    # ---------- виджеты ----------

    def _build(self) -> None:
        self.title_bar = tk.Frame(self.root, height=4, bg=DEFAULT_ACCENT)
        self.title_bar.pack(fill="x")

        top = ttk.Frame(self.root, padding=(16, 12, 16, 4))
        top.pack(fill="x")
        ttk.Label(top, text="Dota Voice", style="Title.TLabel").pack(side="left")
        ttk.Label(top, text="  голосовое управление · Dota 2 · Deadlock",
                  style="Muted.TLabel").pack(side="left", pady=(6, 0))
        self.status_text = ttk.Label(top, text="Остановлено", style="Muted.TLabel")
        self.status_text.pack(side="right")
        self.status_dot = tk.Canvas(top, width=14, height=14, bg=BG, highlightthickness=0)
        self.status_dot.pack(side="right", padx=6)
        self._dot = self.status_dot.create_oval(2, 2, 12, 12, fill=MUTED, outline="")

        row = ttk.Frame(self.root, padding=(16, 6))
        row.pack(fill="x")
        ttk.Label(row, text="Игра").pack(side="left")
        self.game_box = ttk.Combobox(row, state="readonly", width=14)
        self.game_box.pack(side="left", padx=(6, 18))
        self.game_box.bind("<<ComboboxSelected>>", lambda e: self.on_game_changed())
        ttk.Label(row, text="Микрофон").pack(side="left")
        self.mic_box = ttk.Combobox(row, state="readonly", width=36)
        self.mic_box.pack(side="left", padx=6, fill="x", expand=True)
        self.mic_box.bind("<<ComboboxSelected>>", lambda e: self.on_mic_changed())

        row = ttk.Frame(self.root, padding=(16, 6))
        row.pack(fill="x")
        self.start_btn = ttk.Button(row, text="▶  Старт", style="Accent.TButton",
                                    command=self.toggle_start, width=12)
        self.start_btn.pack(side="left")
        self.pause_btn = ttk.Button(row, text="⏸  Пауза", command=self.toggle_pause,
                                    state="disabled", width=12)
        self.pause_btn.pack(side="left", padx=8)
        self.dry_run = tk.BooleanVar(value=bool(self.state.get("dry_run", False)))
        ttk.Checkbutton(row, text="Тест: только показывать, клавиши не нажимать",
                        variable=self.dry_run, command=self.on_dry_run_changed).pack(side="left", padx=8)

        hero = tk.Frame(self.root, bg=PANEL)
        hero.pack(fill="x", padx=16, pady=(8, 8))
        self.last_label = tk.Label(hero, text="Скажите команду — например, «ульта»",
                                   bg=PANEL, fg=MUTED, font=(FONT, 20, "bold"), pady=14)
        self.last_label.pack(fill="x")

        body = ttk.Frame(self.root, padding=(16, 0))
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=3)
        body.columnconfigure(1, weight=2)
        body.rowconfigure(0, weight=1)

        left = ttk.Frame(body, style="Panel.TFrame")
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        ttk.Label(left, text="КОМАНДЫ", style="Panel.TLabel", padding=(10, 8)).pack(anchor="w")
        self.tree = ttk.Treeview(left, columns=("key", "name", "phrases"), show="headings")
        for col, title, width, stretch in [("key", "Клавиша", 70, False), ("name", "Действие", 185, False),
                                           ("phrases", "Что сказать", 260, True)]:
            self.tree.heading(col, text=title, anchor="w")
            self.tree.column(col, width=width, stretch=stretch, anchor="w")
        self.tree.tag_configure("control", foreground=MUTED)
        scroll = ttk.Scrollbar(left, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y", pady=(0, 1))
        self.tree.pack(fill="both", expand=True, padx=(1, 0), pady=(0, 1))

        right = ttk.Frame(body, style="Panel.TFrame")
        right.grid(row=0, column=1, sticky="nsew")
        ttk.Label(right, text="ЖУРНАЛ", style="Panel.TLabel", padding=(10, 8)).pack(anchor="w")
        self.log = tk.Text(right, bg=PANEL, fg=FG, relief="flat", wrap="word", height=10, width=30,
                           font=(FONT, 9), padx=10, pady=4, state="disabled",
                           highlightthickness=0, insertbackground=FG)
        self.log.pack(fill="both", expand=True)
        for tag, color in [("ok", GREEN), ("warn", YELLOW), ("err", RED), ("muted", MUTED)]:
            self.log.tag_configure(tag, foreground=color)

        bottom = ttk.Frame(self.root, padding=(16, 10))
        bottom.pack(fill="x")
        self.model_btn = ttk.Button(bottom, text="⬇  Скачать модель (45 МБ)", command=self.download_model)
        ttk.Button(bottom, text="Изменить команды", command=self.open_profile).pack(side="left")
        ttk.Button(bottom, text="Обновить", command=self.reload_config).pack(side="left", padx=8)
        ttk.Button(bottom, text="Папка программы", command=lambda: open_path(paths.app_dir())).pack(side="left")
        self.progress = ttk.Progressbar(bottom, length=160, maximum=1.0)

    # ---------- данные ----------

    def current_profile_id(self) -> str | None:
        name = self.game_box.get()
        return next((pid for pid, p in self.profiles.items() if p.name == name), None)

    def current_profile(self) -> Profile | None:
        pid = self.current_profile_id()
        return self.profiles.get(pid) if pid else None

    def reload_config(self) -> None:
        self.stop_engine()
        try:
            self.settings = load_settings(paths.settings_path())
        except (OSError, ConfigError) as e:
            self.settings = Settings()
            self.write(f"Ошибка в settings.toml: {e}. Использую настройки по умолчанию.", "err")
        self.profiles, errors = load_profiles(paths.profiles_dir())
        for error in errors:
            self.write(f"Ошибка в профиле {error}", "err")

        names = [p.name for p in self.profiles.values()]
        self.game_box.configure(values=names)
        wanted = self.profiles.get(self.state.get("game", ""))
        self.game_box.set(wanted.name if wanted else (names[0] if names else ""))
        self._fill_devices()
        self._update_model_button()
        self.on_game_changed(remember=False)
        if not self.profiles:
            self.write(f"Нет ни одного профиля игры в {paths.profiles_dir()}", "err")

    def _fill_devices(self) -> None:
        self.devices = {}
        try:
            from .recognizer import input_devices
            self.devices = {name: index for index, name in input_devices()}
        except Exception as e:  # noqa: BLE001 — без звуковой библиотеки окно всё равно откроется
            self.write(f"Не удалось получить список микрофонов: {e}", "err")
        values = [DEFAULT_MIC] + list(self.devices)
        self.mic_box.configure(values=values)
        saved = self.state.get("device_name")
        self.mic_box.set(saved if saved in self.devices else DEFAULT_MIC)

    def _model_dir(self):
        return self.settings.model_dir(paths.app_dir())

    def _update_model_button(self) -> None:
        if self._model_dir().is_dir():
            self.model_btn.pack_forget()
        else:
            self.model_btn.pack(side="right")
            self.write("Модель распознавания ещё не скачана — нажмите «Скачать модель».", "warn")

    def _fill_commands(self, unknown: dict[str, list[str]] | None = None) -> None:
        self.tree.delete(*self.tree.get_children())
        profile = self.current_profile()
        if not profile:
            return
        unknown = unknown or {}
        for cmd in profile.commands:
            heard = [p for p in cmd.phrases if p not in unknown] or ["— (нет распознаваемых фраз)"]
            self.tree.insert("", "end", values=(cmd.key_text.upper(), cmd.name, ", ".join(heard)))
        if profile.pause_phrases:
            self.tree.insert("", "end", tags=("control",),
                             values=("—", "Пауза голоса", ", ".join(profile.pause_phrases)))
        if profile.resume_phrases:
            self.tree.insert("", "end", tags=("control",),
                             values=("—", "Продолжить", ", ".join(profile.resume_phrases)))

    def _remember(self) -> None:
        self.state.update(game=self.current_profile_id() or "",
                          device_name=self.mic_box.get() if self.mic_box.get() in self.devices else "",
                          dry_run=self.dry_run.get())
        paths.save_state(self.state)

    # ---------- реакции на кнопки ----------

    def on_game_changed(self, remember: bool = True) -> None:
        self._apply_accent()
        self._fill_commands()
        if remember:
            self._remember()
        self._restart_if_running()

    def on_mic_changed(self) -> None:
        self._remember()
        self._restart_if_running()

    def on_dry_run_changed(self) -> None:
        self._remember()
        self._restart_if_running()

    def _restart_if_running(self) -> None:
        if self.engine and self.engine.running:
            self.stop_engine()
            self.start_engine()

    def toggle_start(self) -> None:
        if self.engine and self.engine.running:
            self.stop_engine()
        else:
            self.start_engine()

    def start_engine(self) -> None:
        profile = self.current_profile()
        if not profile:
            self.write("Выберите игру.", "err")
            return
        if not self._model_dir().is_dir():
            self.write("Сначала скачайте модель распознавания.", "err")
            return
        device = self.devices.get(self.mic_box.get())
        token = object()
        self.engine = VoiceEngine(self.settings, profile, self._model_dir(),
                                  lambda e: self.events.put((token, e)),
                                  dry_run=self.dry_run.get(), device=device)
        self.engine_token = token
        self.engine.start()
        self.start_btn.configure(text="■  Стоп")
        self.set_status("Загружаю модель…", YELLOW)

    def stop_engine(self) -> None:
        if self.engine:
            engine, self.engine = self.engine, None
            engine.stop()
        self.start_btn.configure(text="▶  Старт")
        self.pause_btn.configure(state="disabled", text="⏸  Пауза")
        self.set_status("Остановлено", MUTED)

    def toggle_pause(self) -> None:
        if self.engine:
            self.engine.set_paused(not self.engine.paused)

    def open_profile(self) -> None:
        profile = self.current_profile()
        open_path(profile.path if profile and profile.path else paths.profiles_dir())
        self.write("После изменения файла нажмите «Обновить».", "muted")

    def download_model(self) -> None:
        self.model_btn.configure(state="disabled")
        self.progress.pack(side="right", padx=8)
        target = self._model_dir()

        def work() -> None:
            from .recognizer import download_model
            try:
                download_model(target, progress=lambda f: self.events.put((None, Event("download", str(f)))))
                self.events.put((None, Event("download_done")))
            except Exception as e:  # noqa: BLE001
                self.events.put((None, Event("download_error", str(e))))

        threading.Thread(target=work, daemon=True).start()
        self.write("Скачиваю модель распознавания…", "muted")

    # ---------- события ----------

    def _poll(self) -> None:
        try:
            while True:
                source, event = self.events.get_nowait()
                if source is None or (self.engine and source is self.engine_token):
                    self.handle(event)
                elif event.kind == "error":
                    self.write(f"Ошибка: {event.text}", "err")
        except queue.Empty:
            pass
        self.root.after(50, self._poll)

    def handle(self, e: Event) -> None:
        if e.kind == "ready":
            self._fill_commands(e.unknown)
            self.pause_btn.configure(state="normal")
            self.set_status("Слушаю", GREEN)
            test = " Тестовый режим: клавиши не нажимаются." if self.engine.dry_run else ""
            self.write(f"Слушаю микрофон — {self.engine.profile.name}, {e.text}.{test}", "ok")
            if e.unknown:
                words = sorted({w for ws in e.unknown.values() for w in ws})
                self.write(f"Модель не знает слов: {', '.join(words)}. Фразы с ними не сработают — "
                           f"говорите другие варианты из таблицы или добавьте свои в «Изменить команды».",
                           "warn")
        elif e.kind == "command":
            self.show_command(f"«{e.text}»  →  {e.command.key_text.upper()}")
            self.write(f"«{e.text}» → {e.command.key_text.upper()}  ({e.command.name})")
        elif e.kind == "unrecognized":
            self.write(f"? «{e.text}»", "muted")
        elif e.kind == "skipped":
            self.write(e.text, "warn")
        elif e.kind == "paused":
            self.pause_btn.configure(text="▶  Продолжить")
            self.set_status("Пауза", YELLOW)
            self.show_command("Пауза", color=YELLOW)
            self.write("Пауза: команды не выполняются.", "warn")
        elif e.kind == "resumed":
            self.pause_btn.configure(text="⏸  Пауза")
            self.set_status("Слушаю", GREEN)
            self.show_command("Снова слушаю", color=GREEN)
            self.write("Снова слушаю команды.", "ok")
        elif e.kind == "error":
            self.write(f"Ошибка: {e.text}", "err")
            self.set_status("Ошибка", RED)
        elif e.kind == "stopped":
            self.engine = None
            self.start_btn.configure(text="▶  Старт")
            self.pause_btn.configure(state="disabled", text="⏸  Пауза")
            if self.status_text.cget("text") != "Ошибка":
                self.set_status("Остановлено", MUTED)
        elif e.kind == "download":
            self.progress["value"] = float(e.text)
        elif e.kind == "download_done":
            self.progress.pack_forget()
            self.model_btn.configure(state="normal")
            self._update_model_button()
            self.write("Модель скачана. Можно нажимать «Старт».", "ok")
        elif e.kind == "download_error":
            self.progress.pack_forget()
            self.model_btn.configure(state="normal")
            self.write(f"Не удалось скачать модель: {e.text}", "err")

    # ---------- вывод ----------

    def set_status(self, text: str, color: str) -> None:
        self.status_text.configure(text=text)
        self.status_dot.itemconfigure(self._dot, fill=color)

    def show_command(self, text: str, color: str | None = None) -> None:
        self.last_label.configure(text=text, fg=color or self._accent())
        if self._flash_job:
            self.root.after_cancel(self._flash_job)
        self._flash_job = self.root.after(1500, lambda: self.last_label.configure(fg=FG))

    def write(self, text: str, tag: str = "") -> None:
        self.log.configure(state="normal")
        self.log.insert("end", time.strftime("%H:%M:%S  "), "muted")
        self.log.insert("end", text + "\n", tag)
        if int(self.log.index("end-1c").split(".")[0]) > 500:
            self.log.delete("1.0", "100.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def close(self) -> None:
        self._remember()
        self.stop_engine()
        self.root.destroy()


def run_gui() -> None:
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (AttributeError, OSError):
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()
