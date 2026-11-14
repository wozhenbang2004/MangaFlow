@echo off
chcp 65001 >nul
setlocal
title MangaFlow 本机更新
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local_channel.ps1" %*
set "MANGAFLOW_LOCAL_RESULT=%ERRORLEVEL%"
if not "%MANGAFLOW_LOCAL_RESULT%"=="0" (
  echo.
  echo 本机更新未完成，原程序和阅读数据仍保留。请查看上方提示。
  pause
)
exit /b %MANGAFLOW_LOCAL_RESULT%
