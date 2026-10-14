#requires -Version 7.0
param(
    [string]$JavaHome='C:\Program Files\Android\Android Studio\jbr',
    [Parameter(Mandatory=$true)][string]$TestEnvironmentFile,
    [Parameter(Mandatory=$true)][string]$DockerExe,
    [switch]$BackendOnly
)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stage=Split-Path -Parent $repo
$report=Join-Path $stage ('local-validation/workflow-fixes-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $report -Force | Out-Null
$resolved=(Resolve-Path -LiteralPath $TestEnvironmentFile).Path
if(-not $resolved.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Environment must remain in second-stage directory'}
$config=@{}
Get-Content -LiteralPath $resolved | ForEach-Object {if($_ -match '^([A-Z_]+)=(.*)$'){$config[$matches[1]]=$matches[2].Trim()}}
if($config['POSTGRES_DB'] -ne 'ev_insurance_test' -or $config['POSTGRES_PORT'] -ne '25432' -or $config['POSTGRES_USER'] -ne 'phase5_audit'){throw 'Refusing to test against a shared database'}
$env:JAVA_HOME=$JavaHome
$env:MAVEN_USER_HOME=Join-Path $stage '.tool-cache/maven'
$env:TEST_DB_URL='jdbc:postgresql://127.0.0.1:25432/ev_insurance_test'
$env:TEST_DB_USERNAME=$config['POSTGRES_USER']
$env:TEST_DB_PASSWORD=$config['POSTGRES_PASSWORD']
$env:TEST_MINIO_ENDPOINT='http://127.0.0.1:19000'
$env:TEST_MINIO_ACCESS_KEY=$config['MINIO_ROOT_USER']
$env:TEST_MINIO_SECRET_KEY=$config['MINIO_ROOT_PASSWORD']
$env:TEST_MINIO_BUCKET='phase5-api-tests'
$results=[Collections.Generic.List[object]]::new()
function Check([string]$Name,[string]$Directory,[string]$Program,[string[]]$Arguments){
    $log=Join-Path $report ($Name+'.log')
    Push-Location -LiteralPath $Directory
    try{& $Program @Arguments *> $log;$code=$LASTEXITCODE}
    catch{$_.Exception.Message | Out-File -LiteralPath $log -Append;$code=-1}
    finally{Pop-Location}
    $status=if($code -eq 0){'PASS'}else{'FAIL'}
    $results.Add([pscustomobject]@{check=$Name;status=$status;exitCode=$code;log=$log})
    $results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $report 'results.json') -Encoding utf8
    Write-Host "$Name : $status"
}
try{
    Check 'compose-config' $repo $DockerExe @('compose','--project-name','ev-insurance-phase5-audit','--env-file',$resolved,'-f','infra/docker-compose.yml','config','--quiet')
    Check 'backend-verify' (Join-Path $repo 'backend') '.\mvnw.cmd' @('-B','-ntp',('-Dmaven.repo.local='+(Join-Path $stage '.tool-cache/m2')),('-Dvalidation.build.directory='+(Join-Path $report 'backend-build')),'verify','-Ppostgres-it')
    if(-not $BackendOnly){foreach($app in @('admin-web','h5-web')){foreach($gate in @('lint','typecheck','test','build')){Check ($app+'-'+$gate) (Join-Path $repo $app) 'npm.cmd' @('run',$gate)}}}
}finally{Remove-Item Env:TEST_DB_PASSWORD,Env:TEST_MINIO_SECRET_KEY -ErrorAction SilentlyContinue}
Write-Host ('Report: '+$report)
if($results.status -contains 'FAIL'){exit 1}
