# Backward-compatible forwarder for unified trace CLI
& "$PSScriptRoot\..\trace.ps1" release @args; exit $LASTEXITCODE
