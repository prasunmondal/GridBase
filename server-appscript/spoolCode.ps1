# ============================================================
# ChatGPT Project Code Dumper
# ============================================================

$Root = (Get-Location).Path
$Output = Join-Path $Root "all_code.txt"

# ------------------------------------------------------------
# File extensions to include
# ------------------------------------------------------------

$Extensions = @(
    ".js",
    ".gs",
    ".json",
    ".html",
    ".css",
    ".scss",
    ".yaml",
    ".yml",
    ".md",
    ".bat",
    ".sh"
)

# ------------------------------------------------------------
# Directories to exclude
# ------------------------------------------------------------

$ExcludedDirectories = @(
    ".git",
    ".idea",
    ".vscode",
    "node_modules",
    "build",
    "dist",
    "out",
    "target",
    "bin",
    "obj",
    "coverage"
)

# ------------------------------------------------------------
# Files to exclude
# ------------------------------------------------------------

$ExcludedFiles = @(
    "all_code.txt",
    "*.min.js",
    "*.min.css",
    "*.map",
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml"
)

# ============================================================
# Find files
# ============================================================

Write-Host "Scanning project..." -ForegroundColor Cyan

$Files = Get-ChildItem `
    -Path $Root `
    -Recurse `
    -File `
    -ErrorAction SilentlyContinue |
    Where-Object {

        # Extension
        $_.Extension.ToLower() -in $Extensions

    } |
    Where-Object {

        # Excluded directories
        $relativePath = $_.FullName.Substring($Root.Length + 1)

        $parts = $relativePath -split '[\\/]'

        -not ($parts | Where-Object {
            $_ -in $ExcludedDirectories
        })

    } |
    Where-Object {

        # Excluded files
        $name = $_.Name

        -not ($ExcludedFiles | Where-Object {
            $name -like $_
        })

    } |
    Sort-Object FullName

# ============================================================
# Create output
# ============================================================

Write-Host "Found $($Files.Count) files." -ForegroundColor Green
Write-Host "Writing $Output..." -ForegroundColor Cyan

$OutputLines = [System.Collections.Generic.List[string]]::new()

# ============================================================
# Header
# ============================================================

$OutputLines.Add("============================================================")
$OutputLines.Add("PROJECT CODE DUMP")
$OutputLines.Add("============================================================")
$OutputLines.Add("ROOT: $Root")
$OutputLines.Add("GENERATED: $(Get-Date)")
$OutputLines.Add("FILES: $($Files.Count)")
$OutputLines.Add("============================================================")
$OutputLines.Add("")

# ============================================================
# Project structure
# ============================================================

$OutputLines.Add("============================================================")
$OutputLines.Add("PROJECT FILES")
$OutputLines.Add("============================================================")
$OutputLines.Add("")

foreach ($File in $Files) {

    $RelativePath = $File.FullName.Substring($Root.Length + 1)
    $RelativePath = $RelativePath.Replace("\", "/")

    $OutputLines.Add($RelativePath)
}

$OutputLines.Add("")
$OutputLines.Add("============================================================")
$OutputLines.Add("SOURCE CODE")
$OutputLines.Add("============================================================")
$OutputLines.Add("")

# ============================================================
# Source code
# ============================================================

$Count = 0

foreach ($File in $Files) {

    $Count++

    $RelativePath = $File.FullName.Substring($Root.Length + 1)
    $RelativePath = $RelativePath.Replace("\", "/")

    Write-Host "[$Count/$($Files.Count)] $RelativePath"

    $OutputLines.Add("============================================================")
    $OutputLines.Add("FILE: $RelativePath")
    $OutputLines.Add("SIZE: $($File.Length) bytes")
    $OutputLines.Add("============================================================")
    $OutputLines.Add("")

    try {

        # Read entire file preserving blank lines
        $Content = Get-Content `
            -LiteralPath $File.FullName `
            -Raw `
            -Encoding UTF8

        if ($null -ne $Content) {
            $OutputLines.Add($Content.TrimEnd())
        }

    }
    catch {

        $OutputLines.Add("[ERROR READING FILE]")
        $OutputLines.Add($_.Exception.Message)
    }

    $OutputLines.Add("")
    $OutputLines.Add("")
}

# ============================================================
# Footer
# ============================================================

$OutputLines.Add("============================================================")
$OutputLines.Add("END OF PROJECT")
$OutputLines.Add("============================================================")
$OutputLines.Add("FILES INCLUDED: $($Files.Count)")
$OutputLines.Add("============================================================")

# ============================================================
# Write UTF-8
# ============================================================

$OutputLines |
    Out-File `
        -LiteralPath $Output `
        -Encoding utf8

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "DONE" -ForegroundColor Green
Write-Host "Files included : $($Files.Count)" -ForegroundColor Green
Write-Host "Output         : $Output" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green

Read-Host "Press Enter to exit"