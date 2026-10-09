param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$TestEnvironmentFile,
    [string]$BuildDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$stage = Split-Path -Parent $repo
$reportDir = Join-Path $stage 'local-validation'
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
$resolvedEnv = (Resolve-Path -LiteralPath $TestEnvironmentFile).Path
if (-not $resolvedEnv.StartsWith($stage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test environment file must be inside the specified second-stage directory.'
}
$config = @{}
Get-Content -LiteralPath $resolvedEnv | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $config[$matches[1]] = $matches[2].Trim() }
}
# This helper intentionally targets only this task's isolated Compose database.
if ($config['POSTGRES_DB'] -ne 'ev_insurance_test' -or $config['POSTGRES_PORT'] -ne '25432' -or
    $config['POSTGRES_USER'] -ne 'phase5_audit' -or [string]::IsNullOrWhiteSpace($config['POSTGRES_PASSWORD'])) {
    throw 'Refusing to test against a shared database. Use the isolated phase5 audit configuration.'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome is not a JDK.' }
$env:JAVA_HOME = $JavaHome
$env:MAVEN_USER_HOME = Join-Path $stage '.tool-cache/maven'
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:25432/ev_insurance_test'
$env:TEST_DB_USERNAME = $config['POSTGRES_USER']
$env:TEST_DB_PASSWORD = $config['POSTGRES_PASSWORD']
$env:TEST_MINIO_ENDPOINT = 'http://127.0.0.1:19000'
$env:TEST_MINIO_ACCESS_KEY = $config['MINIO_ROOT_USER']
$env:TEST_MINIO_SECRET_KEY = $config['MINIO_ROOT_PASSWORD']
$env:TEST_MINIO_BUCKET = 'phase5-api-tests'
$results = [Collections.Generic.List[object]]::new()
function Invoke-Check([string]$Name, [string]$Directory, [string]$Program, [string[]]$Arguments) {
    $log = Join-Path $reportDir ($Name + '.log')
    Push-Location -LiteralPath $Directory
    try {
        & $Program @Arguments *> $log
        $code = $LASTEXITCODE
        $results.Add([pscustomobject]@{check=$Name;status=$(if($code -eq 0){'PASS'}else{'FAIL'});exitCode=$code;log=$log})
    } catch {
        $_.Exception.Message | Out-File -LiteralPath $log -Append
        $results.Add([pscustomobject]@{check=$Name;status='FAIL';exitCode=-1;log=$log})
    } finally { Pop-Location }
    Write-Output ($Name + ': ' + $results[$results.Count-1].status)
}
try {
    $buildArgs=@()
    if($BuildDirectory){
        $resolvedBuild=[IO.Path]::GetFullPath((Join-Path $repo $BuildDirectory))
        if(-not $resolvedBuild.StartsWith($reportDir+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Isolated build directory must stay below second-stage local-validation'}
        $buildArgs+=('-Dvalidation.build.directory='+$resolvedBuild)
    }
    $localRepo = '-Dmaven.repo.local=' + (Join-Path $stage '.tool-cache/m2')
    Invoke-Check 'backend-verify' (Join-Path $repo 'backend') '.\mvnw.cmd' (@('-B','-ntp',$localRepo,'clean','verify','-Ppostgres-it')+$buildArgs)
    foreach ($app in @('admin-web','h5-web')) {
        foreach ($gate in @('lint','typecheck','test','build')) {
            Invoke-Check ($app + '-' + $gate) (Join-Path $repo $app) 'npm.cmd' @('run',$gate)
        }
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportDir 'baseline-results.json') -Encoding utf8
} finally { Remove-Item Env:TEST_DB_PASSWORD,Env:TEST_MINIO_SECRET_KEY -ErrorAction SilentlyContinue }
if ($results.status -contains 'FAIL') { exit 1 }
