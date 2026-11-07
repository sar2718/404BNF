# 404BNF Windows setup (PowerShell 5.1+). Run from anywhere:
# powershell -ExecutionPolicy Bypass -File .\setup.ps1
[CmdletBinding()]
param(
    [switch]$SkipFfmpegInstall,
    [switch]$SkipModels,
    [switch]$SkipOfflineCheck
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Run-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed (code $LASTEXITCODE): $File $($Arguments -join ' ')" }
}

function Has-MediaTools {
    return (($null -ne (Get-Command ffmpeg -ErrorAction SilentlyContinue)) -and
            ($null -ne (Get-Command ffprobe -ErrorAction SilentlyContinue)))
}

function Refresh-Path {
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $env:Path = "$machinePath;$userPath;$env:Path"
}

try {
    Write-Host '[1/5] Checking Python 3.13...'
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($null -ne $py) {
        $pythonCmd = $py.Source
        $pythonArgs = @('-3.13')
    } else {
        $p = Get-Command python -ErrorAction SilentlyContinue
        if ($null -eq $p) { throw 'Python 3.13 missing. Install Python 3.13 and rerun.' }
        $pythonCmd = $p.Source
        $pythonArgs = @()
    }
    $version = (& $pythonCmd @pythonArgs -c 'import sys; print(sys.version_info.major, sys.version_info.minor, sep=chr(46))')
    if ($LASTEXITCODE -ne 0 -or "$version".Trim() -ne '3.13') {
        throw "Python 3.13 required, found: $version"
    }
    if (-not (Test-Path -LiteralPath '.venv\Scripts\python.exe')) {
        Run-Checked $pythonCmd ($pythonArgs + @('-m','venv','.venv'))
    }
    $venvPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
    $venvVersion = (& $venvPython -c 'import sys; print(sys.version_info.major, sys.version_info.minor, sep=chr(46))')
    if ($LASTEXITCODE -ne 0 -or "$venvVersion".Trim() -ne '3.13') {
        throw 'Existing .venv is not Python 3.13. Rename it and rerun setup.ps1.'
    }

    Write-Host '[2/5] Installing Windows Python 3.13 dependency lock...'
    Run-Checked $venvPython @('-m','pip','install','--disable-pip-version-check','-r','requirements-win-py313.lock')

    Write-Host '[3/5] Checking ffmpeg / ffprobe...'
    if (-not (Has-MediaTools)) {
        if ($SkipFfmpegInstall) { throw 'FFmpeg/FFprobe not in PATH (SkipFfmpegInstall given).' }
        $winget = Get-Command winget -ErrorAction SilentlyContinue
        if ($null -eq $winget) { throw 'FFmpeg missing. Install it via winget install --id Gyan.FFmpeg -e and rerun.' }
        Run-Checked $winget.Source @('install','--id','Gyan.FFmpeg','-e',
                                     '--accept-package-agreements','--accept-source-agreements')
        Refresh-Path
        if (-not (Has-MediaTools)) {
            $packages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
            if (Test-Path -LiteralPath $packages) {
                $exes = Get-ChildItem -LiteralPath $packages -Recurse -Filter 'ffmpeg.exe' -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.FullName -match 'Gyan.FFmpeg' } | Select-Object -First 1
                if ($exes) { $env:Path = "$($exes.Directory.FullName);$env:Path" }
            }
        }
        if (-not (Has-MediaTools)) {
            throw 'FFmpeg installed but current session cannot resolve both programs. Restart PyCharm terminal and rerun.'
        }
    }
    Write-Host '[OK] FFmpeg and FFprobe available.'

    Write-Host '[4/5] Installing verified ONNX model files...'
    if (-not $SkipModels) {
        # Model installation needs network; a prior HF_HUB_OFFLINE=1 must not block it.
        $env:HF_HUB_OFFLINE = '0'
        Run-Checked $venvPython @('tools\model_setup.py','install')
    } else {
        Write-Host '[SKIP] Model download skipped.'
    }

    Write-Host '[5/5] Validating offline model loading...'
    if (-not $SkipModels) {
        $verifyArgs = @('tools\model_setup.py','verify')
        if (-not $SkipOfflineCheck) { $verifyArgs += '--check-inference' }
        Run-Checked $venvPython $verifyArgs
    }
    Write-Host ''
    Write-Host '[READY] 404BNF ML Core installed.'
    Write-Host 'Test: .\.venv\Scripts\python.exe run.py --audio "C:\real\recording.wav" --output output\recording.json'
} catch {
    Write-Error "Setup failed: $($_.Exception.Message)"
    exit 1
}
