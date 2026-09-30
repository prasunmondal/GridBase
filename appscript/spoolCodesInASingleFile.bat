@echo off
setlocal EnableDelayedExpansion

set "OUTPUT=all_code.txt"

:: Clear output file if it already exists
if exist "%OUTPUT%" del "%OUTPUT%"

for /r %%F in (*.js *.ts *.jsx *.tsx *.java *.cs *.cpp *.c *.h *.hpp *.py *.go *.rs *.php *.html *.css *.scss *.json *.xml *.yaml *.yml *.sql *.bat *.ps1) do (
    echo %%~fF | findstr /i /c:"\.idea\" >nul
    if errorlevel 1 (
        echo %%~fF>>"%OUTPUT%"
        findstr /r /v "^$" "%%F">>"%OUTPUT%"
        echo.>>"%OUTPUT%"
        echo.>>"%OUTPUT%"
    )
)

echo Done. Output written to %OUTPUT%
pause