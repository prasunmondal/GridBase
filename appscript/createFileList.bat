@echo off
setlocal

set "OUTPUT=filelist.txt"

if exist "%OUTPUT%" del "%OUTPUT%"

for /r %%F in (
    *.js
    *.ts
    *.jsx
    *.tsx
    *.java
    *.cs
    *.cpp
    *.c
    *.h
    *.hpp
    *.py
    *.go
    *.rs
    *.php
    *.html
    *.css
    *.scss
    *.json
    *.xml
    *.yaml
    *.yml
    *.sql
    *.bat
    *.ps1
) do (
    echo %%~fF>>"%OUTPUT%"
)

echo File list saved to %OUTPUT%