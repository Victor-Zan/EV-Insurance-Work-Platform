param([Parameter(Mandatory=$true)][ValidateSet('backend','admin-web','h5-web')][string]$Service,
    [Parameter(Mandatory=$true)][string]$LogDirectory)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$logs=(Resolve-Path -LiteralPath $LogDirectory).Path
$allowed=Join-Path $stage 'local-validation'
if(-not $logs.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Logs must remain in local-validation'}
$stdout=Join-Path $logs ($Service+'.log')
$stderr=Join-Path $logs ($Service+'-error.log')
try{
    & {
        if($Service -eq 'backend'){
            & (Join-Path $PSScriptRoot 'Start-Local.ps1') -EnvironmentFile (Join-Path $stage 'local-validation/.env.phase5') -BuildDirectory '../local-validation/phase12-local-dev-build'
        }else{
            Set-Location (Join-Path $repo $Service)
            $port=if($Service -eq 'admin-web'){5173}else{5174}
            & node.exe (Join-Path (Get-Location) 'node_modules/vite/bin/vite.js') --host 127.0.0.1 --port $port --strictPort
        }
    } 3>&1 4>&1 5>&1 6>&1 1>> $stdout 2>> $stderr
    exit $LASTEXITCODE
}catch{
    $_ | Out-String | Out-File -LiteralPath $stderr -Append
    exit 1
}
