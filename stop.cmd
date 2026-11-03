@echo off
call "%~dp0start.cmd" -Action Stop %*
exit /b %ERRORLEVEL%
