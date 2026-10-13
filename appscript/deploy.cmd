@echo off
setlocal EnableExtensions

REM =============================================
REM Configuration
REM =============================================

set "PROJECT_DIR=D:\Projects\GridBase\appscript"

REM Empty = create new deployment.
REM Existing ID = update that deployment.
set "DEPLOYMENT_ID_TO_USE="

set "DEPLOY_LOG=%TEMP%\clasp-deploy-%RANDOM%.log"
set "DEPLOYMENTS_FILE=%TEMP%\clasp-list-%RANDOM%.log"

cd /d "%PROJECT_DIR%" || (
    echo ERROR: Cannot access project directory.
    exit /b 1
)

echo ==================================================
echo Project   : %PROJECT_DIR%
echo Date      : %DATE%
echo Time      : %TIME%
echo ==================================================

REM =============================================
REM Check clasp
REM =============================================

echo.
echo Checking clasp...

call npx --yes @google/clasp -v
if errorlevel 1 (
    echo ERROR: clasp is unavailable.
    exit /b 1
)

REM =============================================
REM Push latest code
REM =============================================

echo.
echo Pushing latest code...

call npx --yes @google/clasp push
if errorlevel 1 (
    echo ERROR: Push failed. Deployment cancelled.
    exit /b 1
)

REM =============================================
REM Create or update deployment
REM =============================================

if not "%DEPLOYMENT_ID_TO_USE%"=="" goto update_deployment

echo.
echo Creating new deployment...

call npx --yes @google/clasp deploy -d "%DATE% %TIME%" > "%DEPLOY_LOG%" 2>&1
set "DEPLOY_EXIT=%ERRORLEVEL%"
type "%DEPLOY_LOG%"

if "%DEPLOY_EXIT%"=="0" goto deployment_success

goto check_limit

:update_deployment

echo.
echo Updating existing deployment...

call npx --yes @google/clasp deploy --deploymentId "%DEPLOYMENT_ID_TO_USE%" -d "%DATE% %TIME%" > "%DEPLOY_LOG%" 2>&1
set "DEPLOY_EXIT=%ERRORLEVEL%"
type "%DEPLOY_LOG%"

if "%DEPLOY_EXIT%"=="0" goto deployment_success

REM =============================================
REM Handle deployment limit only
REM =============================================

:check_limit

findstr /i /c:"Scripts may only have up to 20 versioned deployments at a time" "%DEPLOY_LOG%" >nul

if errorlevel 1 (
    echo.
    echo ERROR: Deployment failed for another reason.
    goto failed
)

echo.
echo Deployment limit reached.
echo Looking for the oldest-numbered versioned deployment...

call npx --yes @google/clasp deployments > "%DEPLOYMENTS_FILE%" 2>&1
if errorlevel 1 (
    type "%DEPLOYMENTS_FILE%"
    echo ERROR: Could not list deployments.
    goto failed
)

REM Select the lowest numbered versioned deployment.
REM Never select the deployment currently being updated.

set "OLD_DEPLOYMENT_ID="

for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "$text = Get-Content -Raw '%DEPLOYMENTS_FILE%'; $exclude = '%DEPLOYMENT_ID_TO_USE%'; $items = [regex]::Matches($text, '(?m)^\s*-\s+(AKfy[a-zA-Z0-9_-]+)\s+@(\d+)\b') | ForEach-Object { [pscustomobject]@{ Id = $_.Groups[1].Value; Version = [int]$_.Groups[2].Value } }; $item = $items | Where-Object { $_.Id -ne $exclude } | Sort-Object Version | Select-Object -First 1; if ($item) { $item.Id }"`) do set "OLD_DEPLOYMENT_ID=%%I"

if "%OLD_DEPLOYMENT_ID%"=="" (
    echo ERROR: Could not safely identify an old deployment.
    echo No deployment was deleted.
    goto failed
)

echo.
echo Oldest-numbered deployment: %OLD_DEPLOYMENT_ID%
echo Removing this deployment to free a slot...

call npx --yes @google/clasp undeploy "%OLD_DEPLOYMENT_ID%"
if errorlevel 1 (
    echo ERROR: Could not remove the old deployment.
    goto failed
)

REM =============================================
REM Retry once
REM =============================================

echo.
echo Retrying deployment...

if not "%DEPLOYMENT_ID_TO_USE%"=="" goto retry_update

call npx --yes @google/clasp deploy -d "%DATE% %TIME%" > "%DEPLOY_LOG%" 2>&1
goto check_retry

:retry_update

call npx --yes @google/clasp deploy --deploymentId "%DEPLOYMENT_ID_TO_USE%" -d "%DATE% %TIME%" > "%DEPLOY_LOG%" 2>&1

:check_retry

set "DEPLOY_EXIT=%ERRORLEVEL%"
type "%DEPLOY_LOG%"

if not "%DEPLOY_EXIT%"=="0" (
    echo ERROR: Deployment retry failed.
    goto failed
)

:deployment_success

echo.
echo Deployment command completed successfully.

if not "%DEPLOYMENT_ID_TO_USE%"=="" (
    set "DEPLOYMENT_ID=%DEPLOYMENT_ID_TO_USE%"
    goto show_url
)

echo.
echo Current deployments:
call npx --yes @google/clasp deployments

echo.
echo Copy the appropriate deployment ID into
echo DEPLOYMENT_ID_TO_USE at the top of this file
echo to reuse a stable Web App URL on future runs.

goto cleanup

:show_url

set "WEBAPP_URL=https://script.google.com/macros/s/%DEPLOYMENT_ID%/exec"

echo.
echo ==================================================
echo Deployment ID : %DEPLOYMENT_ID%
echo Web App URL   : %WEBAPP_URL%
echo ==================================================

echo %WEBAPP_URL% | clip
if errorlevel 1 (
    echo Could not copy URL to clipboard.
) else (
    echo URL copied to clipboard.
)

goto cleanup

:failed

echo.
echo Deployment failed. Review the output above.
del "%DEPLOY_LOG%" 2>nul
del "%DEPLOYMENTS_FILE%" 2>nul
exit /b 1

:cleanup

del "%DEPLOY_LOG%" 2>nul
del "%DEPLOYMENTS_FILE%" 2>nul

echo.
echo Finished.
pause
endlocal