@echo off

REM ============================================================
REM                    GIT BUNDLE CONFIGURATION
REM ============================================================

REM ------------------------------------------------------------
REM PROJECT (detected automatically)
REM ------------------------------------------------------------
REM This folder (code-sync) lives inside the repository it syncs, so the
REM repository is its parent folder and the repository name is that
REM folder's name (e.g. C:\Projects\GridBase\code-sync -> GridBase).
REM The bundle is named after it: <REPO_NAME>.bundle

for %%I in ("%~dp0..") do set "REPO_ROOT=%%~fI"
for %%I in ("%REPO_ROOT%") do set "REPO_NAME=%%~nxI"

REM ------------------------------------------------------------
REM UPLOAD CONFIGURATION
REM ------------------------------------------------------------

REM Git project from which the bundle will be created
set "UPLOAD_PROJECT_DIR=%REPO_ROOT%"

REM Branch/ref to bundle
set "UPLOAD_BRANCH_NAME=main"

set "UPLOAD _BUNDLE_DIR=C:\Projects"

REM ------------------------------------------------------------
REM DOWNLOAD CONFIGURATION
REM ------------------------------------------------------------

REM Git project where the bundle will be applied
set "DOWNLOAD_PROJECT_DIR=%REPO_ROOT%"

REM Directory where the bundle will be downloaded
set "DOWNLOAD_BUNDLE_DIR=C:\Users\prasu\Downloads"

REM Branch/ref to apply from the bundle
set "DOWNLOAD_BRANCH_NAME=main"


REM ------------------------------------------------------------
REM COMMON CONFIGURATION
REM ------------------------------------------------------------

REM Google Drive folder containing the bundle
set "DRIVE_URL=https://drive.google.com/drive/folders/1CQALHSr4E1dE0kINLfbejbKKfUmuDitE"