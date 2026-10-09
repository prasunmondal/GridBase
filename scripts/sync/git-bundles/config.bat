@echo off

REM ============================================================
REM                    GIT BUNDLE CONFIGURATION
REM ============================================================

REM ------------------------------------------------------------
REM UPLOAD CONFIGURATION
REM ------------------------------------------------------------

REM Git project from which the bundle will be created
set "UPLOAD_PROJECT_DIR=C:\Projects\GridBase"

REM Branch/ref to bundle
set "UPLOAD_BRANCH_NAME=main"

set "UPLOAD_BUNDLE_DIR=C:\Projects"

REM ------------------------------------------------------------
REM DOWNLOAD CONFIGURATION
REM ------------------------------------------------------------

REM Git project where the bundle will be applied
set "DOWNLOAD_PROJECT_DIR=D:\Projects\GridBase"

REM Directory where the bundle will be downloaded
set "DOWNLOAD_BUNDLE_DIR=C:\Users\prasu\Downloads"

REM Branch/ref to apply from the bundle
set "DOWNLOAD_BRANCH_NAME=main"


REM ------------------------------------------------------------
REM COMMON CONFIGURATION
REM ------------------------------------------------------------

REM Google Drive folder containing the bundle
set "DRIVE_URL=https://drive.google.com/drive/folders/1CQALHSr4E1dE0kINLfbejbKKfUmuDitE"