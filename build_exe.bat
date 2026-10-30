@echo off
chcp 65001 >nul
cd /d "%~dp0"
py -3 -m pip install -r requirements.txt pyinstaller || goto :error
py -3 -m PyInstaller --noconfirm --windowed --name DotaVoice --collect-all vosk --add-data "settings.toml;." --add-data "profiles;profiles" app.py || goto :error
xcopy /e /i /y profiles dist\DotaVoice\profiles >nul
copy /y settings.toml dist\DotaVoice\ >nul
if exist model xcopy /e /i /y model dist\DotaVoice\model >nul
echo.
echo Готово: dist\DotaVoice\DotaVoice.exe
pause
exit /b 0
:error
echo Ошибка сборки.
pause
exit /b 1
