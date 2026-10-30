"""Точка входа для сборки DotaVoice.exe (PyInstaller)."""

import sys

from dota_voice.__main__ import main

if __name__ == "__main__":
    sys.exit(main())
