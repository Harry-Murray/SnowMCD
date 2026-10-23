@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-jar.ps1" %*
if errorlevel 1 (
  echo.
  echo 构建失败，请查看上面的错误信息。
  exit /b 1
)
endlocal
