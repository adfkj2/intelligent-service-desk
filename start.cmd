@echo off
setlocal
title ISD - Intelligent Service Desk
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\isd.ps1" %*
set "ISD_EXIT_CODE=%ERRORLEVEL%"
if not "%ISD_EXIT_CODE%"=="0" (
  echo.
  echo ISD could not complete the operation. Read the message above.
  pause
)
exit /b %ISD_EXIT_CODE%
