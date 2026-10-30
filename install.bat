@echo off
chcp 65001 >nul
cd /d "%~dp0"
py -3 -m pip install -r requirements.txt || goto :error
if not exist model py -3 -m dota_voice --download-model || goto :error
echo.
echo Готово! Запускайте start.bat
pause
exit /b 0
:error
echo Ошибка установки. Нужен Python 3.11+ с python.org (галочка "Add to PATH").
pause
exit /b 1
