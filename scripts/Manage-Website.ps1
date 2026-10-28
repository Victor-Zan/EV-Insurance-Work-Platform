param([ValidateSet('Start','Stop','Status')][string]$Action='Start',[switch]$NoBrowser,
    [ValidateRange(30,600)][int]$FrontendStartupSeconds=300)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$local=Join-Path $stage 'local-validation'
$registry=Join-Path $local 'website-processes.json'
$runner=Join-Path $PSScriptRoot 'Run-WebsiteService.ps1'
$hiddenRunner=Join-Path $PSScriptRoot 'Run-WebsiteService.vbs'
$docker=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
if(-not(Test-Path -LiteralPath $docker)){$docker=(Get-Command docker.exe -ErrorAction Stop).Source}
$dockerDesktopCandidates=@(
    (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe'),
    (Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe')
)
$services=@(
    @{Name='backend';Port=8080;Url='http://localhost:8080/api/v1/health'},
    @{Name='admin-web';Port=5173;Url='http://127.0.0.1:5173'},
    @{Name='h5-web';Port=5174;Url='http://127.0.0.1:5174'}
)
function Http-Ready([string]$Url){try{return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200}catch{return $false}}
function Docker-Ready {
    # A non-zero exit code from docker.exe is the normal signal that Desktop is
    # still starting.  Under PowerShell 7, native-command error preference can
    # otherwise turn it into a terminating exception before $LASTEXITCODE is read.
    $previousErrorActionPreference=$ErrorActionPreference
    $hasNativeCommandPreference=Test-Path -LiteralPath 'Variable:PSNativeCommandUseErrorActionPreference'
    if($hasNativeCommandPreference){$previousNativeCommandPreference=$PSNativeCommandUseErrorActionPreference}
    try{
        $ErrorActionPreference='Continue'
        if($hasNativeCommandPreference){$PSNativeCommandUseErrorActionPreference=$false}
        & $docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    }finally{
        if($hasNativeCommandPreference){$PSNativeCommandUseErrorActionPreference=$previousNativeCommandPreference}
        $ErrorActionPreference=$previousErrorActionPreference
    }
}
function Start-DockerDesktop {
    if(Get-Process 'com.docker.backend' -ErrorAction SilentlyContinue){return}
    $desktop=$dockerDesktopCandidates | Where-Object {Test-Path -LiteralPath $_ -PathType Leaf} | Select-Object -First 1
    if(-not $desktop){throw "Docker Desktop executable was not found. Checked: $($dockerDesktopCandidates -join '; ')"}
    Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
    Write-Host 'Docker engine is unavailable; starting Docker Desktop...'
}
function Start-DetachedWorker([string]$Shell,[string]$Service,[string]$LogDirectory){
    # The GUI host avoids delegating a console to Windows Terminal. The host
    # owns bounded worker recovery and is the registered process-tree root.
    $taskName='EVInsurance-Website-'+$Service
    $existingTask=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if($existingTask -and -not(@($existingTask.Actions | Where-Object {$_.Arguments -and ($_.Arguments.Contains($runner) -or $_.Arguments.Contains($hiddenRunner))}).Count)){
        throw "Scheduled task belongs to another workspace: $taskName"
    }
    $arguments='//B //Nologo "'+$hiddenRunner+'" "'+$Shell+'" '+$Service+' "'+$LogDirectory+'"'
    $action=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32/wscript.exe') -Argument $arguments -WorkingDirectory $repo
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $principal=New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings -Description "On-demand detached worker for the local EV insurance website ($Service)." -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName
    $limit=(Get-Date).AddSeconds(10)
    do{
        $row=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.CommandLine -and
            $_.CommandLine.IndexOf($runner,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $_.CommandLine.IndexOf(('-Service '+$Service),[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $_.CommandLine.IndexOf($LogDirectory,[StringComparison]::OrdinalIgnoreCase) -ge 0
        } | Select-Object -First 1
        if($row){
            $hostRow=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$row.ParentProcessId)
            if(-not $hostRow -or $hostRow.Name -ne 'wscript.exe' -or -not $hostRow.CommandLine.Contains($hiddenRunner)){throw "Worker has no matching hidden host: $Service"}
            return Get-Process -Id ([int]$hostRow.ProcessId) -ErrorAction Stop
        }
        Start-Sleep -Milliseconds 200
    }while((Get-Date) -lt $limit)
    throw "Scheduled worker did not start: $Service"
}
function Owned-Process($Record){
    $processRow=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Record.pid)
    if(-not $processRow){return $null}
    $started=([DateTime]$Record.startedUtc).ToUniversalTime()
    $matchesRunner=($processRow.Name -in @('powershell.exe','pwsh.exe') -and $processRow.CommandLine -and $processRow.CommandLine.Contains($runner)) -or
        ($processRow.Name -eq 'wscript.exe' -and $processRow.CommandLine -and $processRow.CommandLine.Contains($hiddenRunner))
    if(-not $matchesRunner -or
       [Math]::Abs(($processRow.CreationDate.ToUniversalTime()-$started).TotalSeconds) -gt 1){return $null}
    return $processRow
}
New-Item -ItemType Directory -Path $local -Force | Out-Null
$lock=[IO.File]::Open((Join-Path $local '.website-start.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try{
    $records=@()
    if(Test-Path -LiteralPath $registry){
        # Windows PowerShell 5 returns a JSON array as one pipeline object.
        $decoded=Get-Content -LiteralPath $registry -Raw | ConvertFrom-Json
        foreach($entry in $decoded){$records+=$entry}
    }
    if($Action -eq 'Status'){
        foreach($service in $services){[pscustomobject]@{Service=$service.Name;Url=$service.Url;HTTP=$(if(Http-Ready $service.Url){'UP'}else{'DOWN'})}}
        foreach($url in @('http://127.0.0.1:5173/api/v1/health','http://127.0.0.1:5174/api/v1/health')){[pscustomobject]@{Service='API proxy';Url=$url;HTTP=$(if(Http-Ready $url){'UP'}else{'DOWN'})}}
        return
    }
    if($Action -eq 'Stop'){
        # Stop the registered root with its descendants before cancelling the
        # scheduler instance. Cancelling the host alone can orphan its workers.
        foreach($record in $records){
            if(Owned-Process $record){
                & taskkill.exe /PID $record.pid /T /F
                if($LASTEXITCODE -ne 0 -and (Owned-Process $record)){throw ('Could not stop owned service '+$record.service)}
            }else{Write-Host ('Already stopped or identity changed; no process killed: '+$record.service)}
        }
        foreach($service in $services){
            $taskName='EVInsurance-Website-'+$service.Name
            $task=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            if($task -and @($task.Actions | Where-Object {$_.Arguments -and ($_.Arguments.Contains($runner) -or $_.Arguments.Contains($hiddenRunner))}).Count){
                Stop-ScheduledTask -TaskName $taskName
            }
        }
        '[]' | Set-Content -LiteralPath $registry -Encoding utf8
        Write-Host 'Only registered website process trees were stopped. PostgreSQL / MinIO and their data remain.'
        return
    }
    foreach($required in @((Join-Path $local '.env'),(Join-Path $local '.env.phase5'),$runner,$hiddenRunner)){
        if(-not(Test-Path -LiteralPath $required)){throw "Required local file missing: $required"}
    }
    if(-not(Docker-Ready)){
        Start-DockerDesktop
        Write-Host 'Waiting up to 180 seconds for Docker Desktop to become ready...'
        $limit=(Get-Date).AddSeconds(180)
        do{Start-Sleep -Seconds 2}while(-not(Docker-Ready) -and (Get-Date) -lt $limit)
        if(-not(Docker-Ready)){throw 'Docker Desktop is not ready after 180 seconds. Check Docker Desktop and its WSL backend.'}
    }
    & $docker compose --project-name ev-insurance-phase5-audit --env-file (Join-Path $local '.env') -f (Join-Path $repo 'infra/docker-compose.yml') up -d --wait
    if($LASTEXITCODE -ne 0){throw 'PostgreSQL / MinIO startup failed'}
    $run=Join-Path $local ('website-background-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    # Use the system Windows PowerShell path so on-demand tasks do not depend
    # on Codex, a developer shell, or another user's temporary pwsh runtime.
    $shell=(Get-Command powershell.exe -ErrorAction Stop).Source
    $records=@($records | Where-Object {Owned-Process $_})
    foreach($service in $services){
        if(Http-Ready $service.Url){Write-Host ('Reusing healthy service: '+$service.Name);continue}
        if(Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue){throw "Port $($service.Port) is occupied; no unrelated process was stopped"}
        $existing=@($records | Where-Object {$_.service -eq $service.Name})
        if($existing.Count){throw "Registered service $($service.Name) is running but unhealthy. Check its logs; use Stop before restarting"}
        $stdout=Join-Path $run ($service.Name+'.log')
        $stderr=Join-Path $run ($service.Name+'-error.log')
        $process=Start-DetachedWorker $shell $service.Name $run
        $records+= [pscustomobject]@{service=$service.Name;pid=$process.Id;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');stdout=$stdout;stderr=$stderr}
        ConvertTo-Json -InputObject @($records) -Depth 4 | Set-Content -LiteralPath $registry -Encoding utf8
        Write-Host ('Starting independent background service: '+$service.Name)
        # Include PowerShell worker bootstrap, which was measured above 170s
        # on this machine; Vite itself was ready in less than one second.
        $limit=(Get-Date).AddSeconds($(if($service.Name -eq 'backend'){600}else{$FrontendStartupSeconds}))
        while(-not(Http-Ready $service.Url)){
            if($process.HasExited){throw "Service $($service.Name) exited. Check $stdout and $stderr"}
            if((Get-Date) -gt $limit){throw "Service startup timed out. Check $stdout and $stderr"}
            Start-Sleep -Seconds 2
        }
    }
    Write-Host 'Admin / customer service: http://127.0.0.1:5173'
    Write-Host 'Repair shop / owner: http://127.0.0.1:5174'
    Write-Host ('Process registry: '+$registry)
    Write-Host 'Startup complete. You may close this terminal. Use Manage-Website.ps1 -Action Stop to stop registered services.'
    if(-not $NoBrowser){Start-Process 'http://127.0.0.1:5173';Start-Process 'http://127.0.0.1:5174'}
}finally{$lock.Dispose()}
