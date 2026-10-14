param([ValidateSet('Start','Stop','Status')][string]$Action='Start',[switch]$NoBrowser,
    [ValidateRange(30,600)][int]$FrontendStartupSeconds=300)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$local=Join-Path $stage 'local-validation'
$registry=Join-Path $local 'website-processes.json'
$runner=Join-Path $PSScriptRoot 'Run-WebsiteService.ps1'
$docker=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
if(-not(Test-Path -LiteralPath $docker)){$docker=(Get-Command docker.exe -ErrorAction Stop).Source}
$services=@(
    @{Name='backend';Port=8080;Url='http://localhost:8080/api/v1/health'},
    @{Name='admin-web';Port=5173;Url='http://localhost:5173'},
    @{Name='h5-web';Port=5174;Url='http://localhost:5174'}
)
function Http-Ready([string]$Url){try{return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200}catch{return $false}}
function Owned-Process($Record){
    $processRow=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Record.pid)
    if(-not $processRow){return $null}
    $started=([DateTime]$Record.startedUtc).ToUniversalTime()
    if($processRow.Name -notin @('powershell.exe','pwsh.exe') -or
       $processRow.CommandLine.IndexOf($runner,[StringComparison]::OrdinalIgnoreCase) -lt 0 -or
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
        foreach($url in @('http://localhost:5173/api/v1/health','http://localhost:5174/api/v1/health')){[pscustomobject]@{Service='API proxy';Url=$url;HTTP=$(if(Http-Ready $url){'UP'}else{'DOWN'})}}
        return
    }
    if($Action -eq 'Stop'){
        foreach($record in $records){
            if(Owned-Process $record){
                & taskkill.exe /PID $record.pid /T /F
                if($LASTEXITCODE -ne 0){throw ('Could not stop owned service '+$record.service)}
            }else{Write-Host ('Already stopped or identity changed; no process killed: '+$record.service)}
        }
        '[]' | Set-Content -LiteralPath $registry -Encoding utf8
        Write-Host 'Only registered website process trees were stopped. PostgreSQL / MinIO and their data remain.'
        return
    }
    foreach($required in @((Join-Path $local '.env'),(Join-Path $local '.env.phase5'),$runner)){
        if(-not(Test-Path -LiteralPath $required)){throw "Required local file missing: $required"}
    }
    & $docker info --format '{{.ServerVersion}}' *> $null
    if($LASTEXITCODE -ne 0){
        $desktop=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe'
        Start-Process -FilePath $desktop -WindowStyle Hidden
        $limit=(Get-Date).AddSeconds(120)
        do{Start-Sleep -Seconds 2;& $docker info --format '{{.ServerVersion}}' *> $null}while($LASTEXITCODE -ne 0 -and (Get-Date) -lt $limit)
        if($LASTEXITCODE -ne 0){throw 'Docker Desktop is not ready'}
    }
    & $docker compose --project-name ev-insurance-phase5-audit --env-file (Join-Path $local '.env') -f (Join-Path $repo 'infra/docker-compose.yml') up -d --wait
    if($LASTEXITCODE -ne 0){throw 'PostgreSQL / MinIO startup failed'}
    $run=Join-Path $local ('website-background-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $shell=if(Get-Command pwsh.exe -ErrorAction SilentlyContinue){(Get-Command pwsh.exe).Source}else{(Get-Command powershell.exe).Source}
    $records=@($records | Where-Object {Owned-Process $_})
    foreach($service in $services){
        if(Http-Ready $service.Url){Write-Host ('Reusing healthy service: '+$service.Name);continue}
        if(Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue){throw "Port $($service.Port) is occupied; no unrelated process was stopped"}
        $existing=@($records | Where-Object {$_.service -eq $service.Name})
        if($existing.Count){throw "Registered service $($service.Name) is running but unhealthy. Check its logs; use Stop before restarting"}
        $stdout=Join-Path $run ($service.Name+'.log')
        $stderr=Join-Path $run ($service.Name+'-error.log')
        # ShellExecute starts a separate hidden console without inheriting the
        # caller's capture pipes. The worker handles its own file redirection.
        $process=Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"'),'-Service',$service.Name,'-LogDirectory',('"'+$run+'"')) -WindowStyle Hidden -PassThru
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
    Write-Host 'Admin / customer service: http://localhost:5173'
    Write-Host 'Repair shop / owner: http://localhost:5174'
    Write-Host ('Process registry: '+$registry)
    Write-Host 'Startup complete. You may close this terminal. Use Manage-Website.ps1 -Action Stop to stop registered services.'
    if(-not $NoBrowser){Start-Process 'http://localhost:5173';Start-Process 'http://localhost:5174'}
}finally{$lock.Dispose()}
