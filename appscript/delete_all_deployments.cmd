@echo off
setlocal EnableExtensions

REM =============================================
REM Configuration
REM =============================================

set "PROJECT_DIR=D:\Projects\GridBase\appscript"

cd /d "%PROJECT_DIR%" || (
    echo ERROR: Cannot access project directory.
    exit /b 1
)

echo ==================================================
echo DELETE ALL GOOGLE APPS SCRIPT DEPLOYMENTS
echo Project: %PROJECT_DIR%
echo ==================================================
echo.
echo WARNING: This will undeploy every deployment
echo listed for this Apps Script project.
echo Web App URLs associated with those deployments
echo may stop working.
echo.

set /p "CONFIRM=Type DELETE to continue: "

if /i not "%CONFIRM%"=="DELETE" (
    echo Operation cancelled.
    exit /b 0
)

echo.
echo Fetching deployments...

set "DEPLOYMENTS_FILE=%TEMP%\clasp-deployments-%RANDOM%.txt"

call npx --yes @google/clasp deployments > "%DEPLOYMENTS_FILE%" 2>&1

if errorlevel 1 (
    type "%DEPLOYMENTS_FILE%"
    del "%DEPLOYMENTS_FILE%" 2>nul
    echo ERROR: Could not retrieve deployments.
    exit /b 1
)

REM =============================================
REM Delete every deployment ID returned by clasp
REM =============================================

set /a DELETED=0
set /a FAILED=0

for /f "usebackq tokens=2" %%A in (`findstr /r /c:"^[ ]*-[ ]*AKfy" "%DEPLOYMENTS_FILE%"`) do (
    echo.
    echo Undeploying: %%A

    call npx --yes @google/clasp undeploy "%%A"

    if errorlevel 1 (
        echo FAILED: %%A
        set /a FAILED+=1
    ) else (
        echo Deleted: %%A
        set /a DELETED+=1
    )
)

del "%DEPLOYMENTS_FILE%" 2>nul

echo.
echo ==================================================
echo Deployment cleanup finished.
echo Successfully removed: %DELETED%
echo Failed:               %FAILED%
echo ==================================================

pause
endlocal