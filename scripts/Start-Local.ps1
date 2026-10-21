param([Parameter(Mandatory=$true)][string]$EnvironmentFile,[string]$JavaHome='C:\Program Files\Android\Android Studio\jbr',[string]$BuildDirectory,[string]$MapEnvironmentFile)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$resolved=(Resolve-Path -LiteralPath $EnvironmentFile).Path
if(-not $resolved.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Environment file must remain in the second-stage directory'}
Get-Content -LiteralPath $resolved|ForEach-Object {if($_ -match '^([A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim(),'Process')}}
if(-not $MapEnvironmentFile){$MapEnvironmentFile=Join-Path $repo '.env.amap'}
if(Test-Path -LiteralPath $MapEnvironmentFile){& (Join-Path $PSScriptRoot 'Load-AmapEnvironment.ps1') -EnvironmentFile $MapEnvironmentFile}
if(-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))){throw 'JavaHome must point to JDK21'}
$env:JAVA_HOME=$JavaHome
$env:MAVEN_USER_HOME=Join-Path $stage '.tool-cache/maven'
$buildArgs=@()
if($BuildDirectory){
    $resolvedBuild=[IO.Path]::GetFullPath((Join-Path $repo $BuildDirectory))
    $allowed=Join-Path $stage 'local-validation'
    if(-not $resolvedBuild.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'BuildDirectory must remain below second-stage local-validation'}
    $buildArgs+=('-Dvalidation.build.directory='+$resolvedBuild)
}
Push-Location (Join-Path $repo 'backend')
try {& .\mvnw.cmd -B -ntp ('-Dmaven.repo.local='+(Join-Path $stage '.tool-cache/m2')) @buildArgs spring-boot:run;exit $LASTEXITCODE}finally{Pop-Location}
