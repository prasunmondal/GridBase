@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM                         LOAD CONFIG
REM ============================================================

call "%~dp0config.bat"

if /I "%~1"=="UPLOAD" goto UPLOAD
if /I "%~1"=="DOWNLOAD" goto DOWNLOAD

echo.
echo ============================================================
echo ERROR: Invalid mode
echo ============================================================
echo.
echo Usage:
echo.
echo     main.bat UPLOAD
echo     main.bat DOWNLOAD
echo.
pause
exit /b 1


REM ============================================================
REM                         UPLOAD
REM ============================================================

:UPLOAD

echo.
echo ============================================================
echo                  GIT BUNDLE - UPLOAD
echo ============================================================
echo.


REM ------------------------------------------------------------
REM Validate upload project
REM ------------------------------------------------------------

if not exist "%UPLOAD_PROJECT_DIR%" (
    echo ERROR: Upload project directory does not exist:
    echo.
    echo %UPLOAD_PROJECT_DIR%
    echo.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Get repository name
REM ------------------------------------------------------------

for %%I in ("%UPLOAD_PROJECT_DIR%") do set "REPO_NAME=%%~nxI"

set "BUNDLE_FILE_NAME=%REPO_NAME%.bundle"
set "UPLOAD_BUNDLE_FILE_PATH=%DOWNLOAD_BUNDLE_DIR%\%BUNDLE_FILE_NAME%"


echo Repository:
echo     %REPO_NAME%
echo.

echo Project:
echo     %UPLOAD_PROJECT_DIR%
echo.

echo Branch:
echo     %UPLOAD_BRANCH_NAME%
echo.

echo Bundle:
echo     %UPLOAD_BUNDLE_FILE_PATH%
echo.


REM ------------------------------------------------------------
REM Switch to project directory
REM ------------------------------------------------------------

echo Switching to project directory...

cd /d "%UPLOAD_PROJECT_DIR%" || (
    echo.
    echo ERROR: Failed to change directory.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Validate Git repository
REM ------------------------------------------------------------

git rev-parse --is-inside-work-tree >nul 2>&1 || (
    echo.
    echo ERROR: Directory is not a Git repository.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Git status
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                         GIT STATUS
echo ============================================================
echo.

git status || goto GIT_ERROR


REM ------------------------------------------------------------
REM Show current commit
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                    CURRENT COMMIT
echo ============================================================
echo.

git log -1 --oneline --decorate

echo.


REM ------------------------------------------------------------
REM Delete existing bundle
REM ------------------------------------------------------------

if exist "%UPLOAD_BUNDLE_FILE_PATH%" (

    echo.
    echo Existing bundle found:
    echo     %UPLOAD_BUNDLE_FILE_PATH%
    echo.

    echo Removing existing bundle...

    del /f /q "%UPLOAD_BUNDLE_FILE_PATH%" || (
        echo.
        echo ERROR: Failed to delete existing bundle.
        pause
        exit /b 1
    )
)


REM ------------------------------------------------------------
REM Create bundle
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                    CREATING BUNDLE
echo ============================================================
echo.

git bundle create "%UPLOAD_BUNDLE_FILE_PATH%" "%UPLOAD_BRANCH_NAME%" || goto GIT_ERROR


REM ------------------------------------------------------------
REM Verify bundle
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                   VERIFYING BUNDLE
echo ============================================================
echo.

git bundle verify "%UPLOAD_BUNDLE_FILE_PATH%" || (
    echo.
    echo ERROR: Bundle verification failed.
    echo.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Show bundle information
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                  BUNDLE CREATED
echo ============================================================
echo.

echo Repository:
echo     %REPO_NAME%
echo.

echo File:
echo     %UPLOAD_BUNDLE_FILE_PATH%
echo.

for %%A in ("%UPLOAD_BUNDLE_FILE_PATH%") do (
    echo Size:
    echo     %%~zA bytes
)

echo.


REM ------------------------------------------------------------
REM Open Google Drive
REM ------------------------------------------------------------

echo Opening Google Drive folder...

start chrome "%DRIVE_URL%"


REM ------------------------------------------------------------
REM Open Explorer with bundle selected
REM ------------------------------------------------------------

echo Opening File Explorer...

explorer /select,"%UPLOAD_BUNDLE_FILE_PATH%"


echo.
echo ============================================================
echo                UPLOAD READY
echo ============================================================
echo.

echo Replace the existing:

echo     %BUNDLE_FILE_NAME%

echo in the Google Drive folder with the newly created file.

echo.
pause
exit /b 0


REM ============================================================
REM                        DOWNLOAD
REM ============================================================

:DOWNLOAD

echo.
echo ============================================================
echo                 GIT BUNDLE - DOWNLOAD
echo ============================================================
echo.


REM ------------------------------------------------------------
REM Validate download project
REM ------------------------------------------------------------

if not exist "%DOWNLOAD_PROJECT_DIR%" (
    echo ERROR: Download project directory does not exist:
    echo.
    echo %DOWNLOAD_PROJECT_DIR%
    echo.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Get repository name
REM ------------------------------------------------------------

for %%I in ("%DOWNLOAD_PROJECT_DIR%") do set "REPO_NAME=%%~nxI"

set "BUNDLE_FILE_NAME=%REPO_NAME%.bundle"
set "DOWNLOAD_BUNDLE_FILE_PATH=%DOWNLOAD_BUNDLE_DIR%\%BUNDLE_FILE_NAME%"


echo Repository:
echo     %REPO_NAME%
echo.

echo Project:
echo     %DOWNLOAD_PROJECT_DIR%
echo.

echo Branch:
echo     %DOWNLOAD_BRANCH_NAME%
echo.

echo Expected bundle:
echo     %DOWNLOAD_BUNDLE_FILE_PATH%
echo.


REM ------------------------------------------------------------
REM Create download directory if required
REM ------------------------------------------------------------

if not exist "%DOWNLOAD_BUNDLE_DIR%" (

    echo Download directory does not exist.
    echo Creating:
    echo     %DOWNLOAD_BUNDLE_DIR%
    echo.

    mkdir "%DOWNLOAD_BUNDLE_DIR%" || (
        echo.
        echo ERROR: Failed to create download directory.
        pause
        exit /b 1
    )
)


REM ------------------------------------------------------------
REM Remove existing local bundle
REM ------------------------------------------------------------

if exist "%DOWNLOAD_BUNDLE_FILE_PATH%" (

    echo.
    echo Existing local bundle found:
    echo     %DOWNLOAD_BUNDLE_FILE_PATH%
    echo.

    echo Removing old bundle...

    del /f /q "%DOWNLOAD_BUNDLE_FILE_PATH%" || (
        echo.
        echo ERROR: Failed to remove existing bundle.
        pause
        exit /b 1
    )
)


REM ------------------------------------------------------------
REM Open Google Drive
REM ------------------------------------------------------------

echo.
echo Opening Google Drive folder...

start chrome "%DRIVE_URL%"


REM ------------------------------------------------------------
REM Wait for downloaded bundle
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                    WAITING FOR BUNDLE
echo ============================================================
echo.

echo Download:

echo     %BUNDLE_FILE_NAME%

echo.

echo to:

echo     %DOWNLOAD_BUNDLE_DIR%

echo.

echo The script will automatically continue once the file exists.

echo.
echo Press Ctrl+C to cancel.
echo.


:WAIT_FOR_BUNDLE

if exist "%DOWNLOAD_BUNDLE_FILE_PATH%" goto BUNDLE_FOUND

timeout /t 3 /nobreak >nul

goto WAIT_FOR_BUNDLE


REM ------------------------------------------------------------
REM Bundle found
REM ------------------------------------------------------------

:BUNDLE_FOUND

echo.
echo ============================================================
echo                    BUNDLE FOUND
echo ============================================================
echo.

echo File:
echo     %DOWNLOAD_BUNDLE_FILE_PATH%
echo.


REM ------------------------------------------------------------
REM Verify bundle
REM ------------------------------------------------------------

echo Verifying Git bundle...

git bundle verify "%DOWNLOAD_BUNDLE_FILE_PATH%" || (
    echo.
    echo ============================================================
    echo ERROR: INVALID BUNDLE
    echo ============================================================
    echo.
    echo The downloaded bundle could not be verified.
    echo.
    echo Delete the file and download it again.
    echo.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Switch to download project
REM ------------------------------------------------------------

echo.
echo Switching to project directory...

cd /d "%DOWNLOAD_PROJECT_DIR%" || (
    echo.
    echo ERROR: Failed to change directory.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Validate Git repository
REM ------------------------------------------------------------

git rev-parse --is-inside-work-tree >nul 2>&1 || (
    echo.
    echo ERROR: Download directory is not a Git repository.
    pause
    exit /b 1
)


REM ------------------------------------------------------------
REM Fetch bundle
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                    FETCHING BUNDLE
echo ============================================================
echo.

git switch "%DOWNLOAD_BRANCH_NAME%" || (
    echo.
    echo ERROR: Failed to switch to branch %DOWNLOAD_BRANCH_NAME%.
    pause
    exit /b 1
)

git fetch "%DOWNLOAD_BUNDLE_FILE_PATH%" "%DOWNLOAD_BRANCH_NAME%" || goto GIT_ERROR


REM ------------------------------------------------------------
REM Show incoming commits
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                   INCOMING COMMITS
echo ============================================================
echo.

git log HEAD..FETCH_HEAD --oneline --decorate

echo.


REM ------------------------------------------------------------
REM Check whether there are commits to merge
REM ------------------------------------------------------------

git diff --quiet HEAD FETCH_HEAD >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================================
    echo                 NO CHANGES TO APPLY
    echo ============================================================
    echo.
    echo Your local repository is already up to date.
    echo.
    pause
    exit /b 0
)


REM ------------------------------------------------------------
REM Ask before merge
REM ------------------------------------------------------------

echo.

choice /C YN /M "Apply these commits to the current branch"

if errorlevel 2 (
    echo.
    echo Merge cancelled.
    echo.
    pause
    exit /b 0
)


REM ------------------------------------------------------------
REM Merge bundle
REM ------------------------------------------------------------

echo.
echo ============================================================
echo                     APPLYING BUNDLE
echo ============================================================
echo.

git merge FETCH_HEAD || goto GIT_ERROR


REM ------------------------------------------------------------
REM Success
REM ------------------------------------------------------------

echo.
echo ============================================================
echo             BUNDLE APPLIED SUCCESSFULLY
echo ============================================================
echo.

echo Repository:
echo     %REPO_NAME%
echo.

echo Bundle:
echo     %BUNDLE_FILE_NAME%
echo.

echo Branch:
echo     %DOWNLOAD_BRANCH_NAME%
echo.

pause
exit /b 0


REM ============================================================
REM                         GIT ERROR
REM ============================================================

:GIT_ERROR

echo.
echo ============================================================
echo                    GIT COMMAND FAILED
echo ============================================================
echo.

echo Please review the Git error above and retry.

echo.
pause
exit /b 1