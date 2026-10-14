param([switch]$NoBrowser)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'EV-Insurance-Work-Platform/scripts/Manage-Website.ps1') -Action Status -NoBrowser:$NoBrowser
