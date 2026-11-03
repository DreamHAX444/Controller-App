# Backward-compatible forwarder for unified trace CLI
& "$PSScriptRoot\..\trace.ps1" deploy @args; exit $LASTEXITCODE
