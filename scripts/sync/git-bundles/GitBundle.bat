@echo off
setlocal EnableExtensions

echo.
echo ============================================================
echo                     GIT BUNDLE TOOL
echo ============================================================
echo.

echo Select an operation:
echo.
echo     1. Upload
echo     2. Download
echo.

choice /C 12 /N /M "Enter your choice [1-2]: "

if errorlevel 2 goto DOWNLOAD
if errorlevel 1 goto UPLOAD

exit /b 1


:UPLOAD

echo.
echo Starting UPLOAD...
echo.

call "%~dp0main.bat" UPLOAD

exit /b %ERRORLEVEL%


:DOWNLOAD

echo.
echo Starting DOWNLOAD...
echo.

call "%~dp0main.bat" DOWNLOAD

exit /b %ERRORLEVEL%