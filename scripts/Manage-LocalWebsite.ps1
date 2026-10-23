param([ValidateSet('Start','Watch','Status','Stop','InstallAutoStart')][string]$Action='Start')
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$runtime=Join-Path $repo 'tmp/local-website'
$statePath=Join-Path $runtime 'processes.json'
$enabledPath=Join-Path $runtime 'enabled'
$scriptPath=$PSCommandPath
$taskName='EVInsurance-LocalWebsite'
$java=Join-Path $env:USERPROFILE '.jdks/corretto-21.0.11/bin/java.exe'
$jar=Join-Path $runtime 'backend.jar'
$node=(Get-Command node.exe -ErrorAction Stop).Source
$docker=(Get-Command docker.exe -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

function Ready([string]$Url){
    try {
        $headers=@{}
        $target=[Uri]$Url
        if($target.Port -in @(5173,5191) -and $target.AbsolutePath.StartsWith('/api/')){$headers['Origin']=$target.GetLeftPart([UriPartial]::Authority)}
        return (Invoke-WebRequest -Uri $Url -Headers $headers -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200
    } catch {return $false}
}
function Docker-Ready {
    $ErrorActionPreference='Continue'
    & $docker info --format '{{.ServerVersion}}' *> $null
    return $LASTEXITCODE -eq 0
}
function Login-Ready {
    try {
        Invoke-WebRequest -Uri 'http://127.0.0.1:5173/api/v1/auth/sign-in' -Method Post -ContentType 'application/json' -Body '{}' -Headers @{Origin='http://127.0.0.1:5173'} -UseBasicParsing -TimeoutSec 3 | Out-Null
        return $false
    } catch {
        return $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400
    }
}
function Start-Dependencies {
    $ErrorActionPreference='Continue'
    & $docker compose --env-file (Join-Path $repo '.env') -f (Join-Path $repo 'infra/docker-compose.yml') start --wait --wait-timeout 30 *> (Join-Path $runtime 'dependencies.log')
    return $LASTEXITCODE -eq 0
}
function Read-State {
    if(Test-Path -LiteralPath $statePath){return Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json}
    return [pscustomobject]@{watch=$null;backend=$null;admin=$null}
}
function Owned($Record,[string]$Needle){
    if(-not $Record){return $false}
    $row=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Record.pid)
    return $row -and $row.CommandLine -and $row.CommandLine.IndexOf($Needle,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        [Math]::Abs(($row.CreationDate.ToUniversalTime()-[DateTime]::Parse($Record.startedUtc).ToUniversalTime()).TotalSeconds) -lt 2
}
function Record($Process){
    return [pscustomobject]@{pid=$Process.Id;startedUtc=$Process.StartTime.ToUniversalTime().ToString('o')}
}
function Stop-Owned($Record,[string]$Needle){
    if(Owned $Record $Needle){Stop-Process -Id $Record.pid -ErrorAction Stop}
}
function Save-State($State){
    $State | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($statePath+'.new') -Encoding UTF8
    Move-Item -LiteralPath ($statePath+'.new') -Destination $statePath -Force
}
function Report {
    [pscustomobject]@{
        Backend=Ready 'http://127.0.0.1:8080/actuator/health'
        Admin=Ready 'http://127.0.0.1:5173/'
        AdminApi=Ready 'http://127.0.0.1:5173/api/v1/health'
        LoginApi=Login-Ready
        PreviewApi=Ready 'http://127.0.0.1:5191/api/v1/health'
        WatchRunning=Owned (Read-State).watch $scriptPath
        AutoRecoveryEnabled=Test-Path -LiteralPath $enabledPath
    }
}
if($Action -eq 'Status'){Report;return}
if($Action -eq 'InstallAutoStart'){
    $existing=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if($existing -and -not $existing.Actions.Arguments.Contains($scriptPath)){throw 'Task name belongs to another workspace.'}
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $hiddenLauncher=Join-Path $PSScriptRoot 'Watch-LocalWebsite.vbs'
    $taskAction=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32/wscript.exe') -Argument ('//B //Nologo "'+$hiddenLauncher+'" "'+$scriptPath+'"') -WorkingDirectory $repo
    $triggers=@((New-ScheduledTaskTrigger -AtLogOn -User $user),(New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)))
    $principal=New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $triggers -Principal $principal -Settings $settings -Description 'Keep this local EV insurance website available after sign-in; uses existing data and no stored password.' -Force | Out-Null
    Write-Output 'Current-user sign-in and once-per-minute recovery installed. Run Start to enable, Stop to disable recovery.'
    return
}
if($Action -eq 'Stop'){
    Remove-Item -LiteralPath $enabledPath -Force -ErrorAction SilentlyContinue
    $state=Read-State
    Stop-Owned $state.watch $scriptPath
    Stop-Owned $state.backend $jar
    Stop-Owned $state.admin (Join-Path $repo 'admin-web/node_modules/vite/bin/vite.js')
    Write-Output 'Stopped only this launcher owned processes. Docker and all data are retained.'
    return
}
if($Action -eq 'Start'){
    if(-not(Test-Path -LiteralPath $jar)){throw 'Verified backend missing: tmp/local-website/backend.jar. See docs/LOCAL_RUNTIME.md.'}
    if(-not(Test-Path -LiteralPath $java)){throw 'JDK21 missing. See docs/LOCAL_RUNTIME.md.'}
    New-Item -ItemType File -Path $enabledPath -Force | Out-Null
    if(-not(Owned (Read-State).watch $scriptPath)){
        $task=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if($task -and $task.Actions.Arguments.Contains($scriptPath)){
            Start-ScheduledTask -TaskName $taskName
        }else{
            $shell=(Get-Command powershell.exe).Source
            Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$scriptPath+'"'),'-Action','Watch') -WindowStyle Hidden | Out-Null
        }
    }
    $deadline=(Get-Date).AddSeconds(120)
    do {
        if((Ready 'http://127.0.0.1:8080/actuator/health') -and (Ready 'http://127.0.0.1:5173/api/v1/health')){
            if(-not(Login-Ready)){throw 'Unified login API is unavailable. Rebuild and update tmp/local-website/backend.jar to match the frontend; do not reset account passwords.'}
            Report;return
        }
        Start-Sleep -Seconds 2
    }while((Get-Date) -lt $deadline)
    throw 'Services are not ready. Check tmp/local-website/watch.log and backend logs. Do not repeatedly click login.'
}
if(-not(Test-Path -LiteralPath $enabledPath)){return}
try {$lock=[IO.File]::Open((Join-Path $runtime 'watch.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
catch [IO.IOException] {return}
$state=Read-State
$state.watch=Record (Get-Process -Id $PID)
Save-State $state
$lastDockerAttempt=[DateTime]::MinValue
$backendFailures=0
$backendGrace=[DateTime]::MinValue
$lastMessage=''
try {
    while(Test-Path -LiteralPath $enabledPath){
        try {
            if(-not(Docker-Ready)){
                if(((Get-Date)-$lastDockerAttempt).TotalSeconds -ge 300){
                    $lastDockerAttempt=Get-Date
                    $desktop=Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'
                    if(-not(Get-Process 'com.docker.backend' -ErrorAction SilentlyContinue)){
                        Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
                    }
                }
                throw 'Docker engine is unavailable; waiting for it before starting the backend.'
            }
            if(-not(Ready 'http://127.0.0.1:9000/minio/health/live') -or -not(Ready 'http://127.0.0.1:8080/actuator/health')){
                if(-not(Start-Dependencies)){throw 'Existing database/storage containers are not ready; no containers or volumes are recreated.'}
            }
            if(-not(Ready 'http://127.0.0.1:8080/actuator/health')){
                if(Owned $state.backend $jar){
                    $backendFailures++
                    if((Get-Date) -gt $backendGrace -and $backendFailures -ge 3){
                        Stop-Owned $state.backend $jar
                        $state.backend=$null
                        Save-State $state
                    }
                }
                if(-not(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) -and -not(Owned $state.backend $jar)){
                    Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {
                        if($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim().Trim('"').Trim("'"),'Process')}
                    }
                    $env:SERVER_ADDRESS='127.0.0.1'
                    $flywayArguments=@('--spring.flyway.target=28')
                    $legacyLocations=Join-Path $runtime 'flyway-legacy-v25'
                    if(Test-Path -LiteralPath (Join-Path $runtime 'legacy-v25.enabled')){
                        if(-not(Test-Path -LiteralPath (Join-Path $legacyLocations 'V25.1__amap_dispatch_safety.sql'))){throw 'Legacy V25 migration bundle missing; do not start against canonical migrations.'}
                        $flywayArguments+=('--spring.flyway.locations=filesystem:'+$legacyLocations.Replace('\','/'))
                    }
                    $process=Start-Process -FilePath $java -ArgumentList (@('-jar',('"'+$jar+'"'))+$flywayArguments) -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend-error.log')
                    $state.backend=Record $process
                    $backendGrace=(Get-Date).AddSeconds(120)
                    $backendFailures=0
                    Save-State $state
                }
                throw 'Backend is starting or unavailable; frontend startup is waiting for database health.'
            }
            $backendFailures=0
            if(-not(Ready 'http://127.0.0.1:5173/')){
                if(-not(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue) -and -not(Owned $state.admin (Join-Path $repo 'admin-web/node_modules/vite/bin/vite.js'))){
                    $vite=Join-Path $repo 'admin-web/node_modules/vite/bin/vite.js'
                    $process=Start-Process -FilePath $node -ArgumentList @(('"'+$vite+'"'),'--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory (Join-Path $repo 'admin-web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'admin.log') -RedirectStandardError (Join-Path $runtime 'admin-error.log')
                    $state.admin=Record $process
                    Save-State $state
                }
                throw 'Admin frontend is starting or port 5173 belongs to another process; no unknown process is stopped.'
            }
            if(-not(Ready 'http://127.0.0.1:5173/api/v1/health')){throw 'Admin API proxy or browser Origin is unavailable. Check CORS_ALLOWED_ORIGINS for http://127.0.0.1:5173.'}
            if(-not(Login-Ready)){throw 'Unified login API is unavailable. Update the verified backend runtime to match the frontend; account passwords are not the cause.'}
            $message='Backend, database and admin page are ready.'
        }catch {$message=$_.Exception.Message}
        if($message -ne $lastMessage){
            ((Get-Date -Format o)+' '+$message) | Add-Content -LiteralPath (Join-Path $runtime 'watch.log')
            $lastMessage=$message
        }
        Start-Sleep -Seconds 10
    }
}finally {$lock.Dispose()}
