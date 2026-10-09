#requires -Version 7.0
param([string]$JavaHome='C:\Program Files\Android\Android Studio\jbr',[Parameter(Mandatory=$true)][string]$TestEnvironmentFile,[Parameter(Mandatory=$true)][string]$DockerExe)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$report=Join-Path $stage 'local-validation'
$config=@{}
Get-Content -LiteralPath $TestEnvironmentFile|ForEach-Object {if($_ -match '^([A-Z_]+)=(.*)$'){$config[$matches[1]]=$matches[2].Trim()}}
if($config['POSTGRES_DB'] -ne 'ev_insurance_test' -or $config['POSTGRES_PORT'] -ne '25432' -or $config['POSTGRES_USER'] -ne 'phase5_audit'){throw 'Only isolated validation database permitted'}
$env:DB_URL='jdbc:postgresql://127.0.0.1:25432/ev_insurance_test?currentSchema=phase5_http_'+[guid]::NewGuid().ToString('N')
$env:DB_USERNAME=$config['POSTGRES_USER'];$env:DB_PASSWORD=$config['POSTGRES_PASSWORD']
$env:MINIO_ENDPOINT='http://127.0.0.1:19000';$env:MINIO_ACCESS_KEY=$config['MINIO_ROOT_USER'];$env:MINIO_SECRET_KEY=$config['MINIO_ROOT_PASSWORD'];$env:MINIO_BUCKET='phase5-http-tests'
$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Fill($bytes);$env:JWT_SECRET_BASE64=[Convert]::ToBase64String($bytes);$env:JWT_TTL_SECONDS='1800';$env:BACKEND_PORT='18080';$env:SPRING_PROFILES_ACTIVE='dev'
$devPassword=[guid]::NewGuid().ToString();foreach($role in @('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER')){[Environment]::SetEnvironmentVariable('DEV_'+$role+'_PASSWORD',$devPassword,'Process')}
$base='http://127.0.0.1:18080/api/v1'
$results=[Collections.Generic.List[object]]::new()
$process=$null
function Start-Backend {
  $script:process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList '-jar',('"'+(Join-Path $repo 'backend/target/backend-0.0.1-SNAPSHOT.jar')+'"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $report 'phase5-http-runtime.log') -RedirectStandardError (Join-Path $report 'phase5-http-runtime-error.log')
  for($i=0;$i -lt 60;$i++){if($script:process.HasExited){throw 'Backend exited; inspect runtime logs'};try {if((Invoke-RestMethod "$base/health").data.status -eq 'UP'){return}}catch{};Start-Sleep -Seconds 1};throw 'Backend readiness timeout'
}
function Login([string]$username,[string]$portal){(Invoke-RestMethod "$base/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$username;password=$devPassword;portal=$portal}|ConvertTo-Json)).data.accessToken}
function Post([string]$path,$body){(Invoke-RestMethod "$base/$path" -Method Post -Headers $script:headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 20)).data}
try {
  Start-Backend
  $token=Login 'dev_customer_service' 'ADMIN';$script:headers=@{Authorization='Bearer '+$token}
  $regions=(Invoke-RestMethod "$base/pricing/regions?page=1&size=100" -Headers $headers).data.records
  $region=($regions|Where-Object {$_.code -eq 'DEV-DISTRICT'}|Select-Object -First 1).id
  if(-not $region){throw 'Dev district missing'}
  $draft=Post 'work-orders/drafts' @{insuranceCompany='合成测试保司';claimNo='HTTP-'+[guid]::NewGuid();ownerName='合成车主';ownerPhone='13800001234';vehicleBrand='合成品牌';vehicleModel='测试型号';vehicleVin='HTTP-'+[guid]::NewGuid();accidentAt=[DateTimeOffset]::UtcNow.ToString('o');accidentRegionId=$region;accidentDescription='合成脱敏API验收'}
  $id=$draft.id
  $script:headers['Idempotency-Key']=[guid]::NewGuid().ToString();$order=Post "work-orders/$id/submit" @{confirmPossibleDuplicate=$false}
  $content=[Text.Encoding]::UTF8.GetBytes("%PDF-1.7`nsynthetic privacy-safe material`n%%EOF")
  $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token);$client.DefaultRequestHeaders.Add('Idempotency-Key',[guid]::NewGuid().ToString())
  $form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new($content);$part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf');$form.Add($part,'file','synthetic.pdf');$form.Add([Net.Http.StringContent]::new('NOTICE'),'category')
  $response=$client.PostAsync("$base/materials/cases/$id",$form).GetAwaiter().GetResult();if(-not $response.IsSuccessStatusCode){throw ('Upload failed '+$response.StatusCode)}
  $file=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data;$form.Dispose();$client.Dispose()
  $download=Join-Path $report 'phase5-http-download.pdf';Invoke-WebRequest "$base/materials/$($file.id)/download" -Headers $headers -OutFile $download
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($content));if((Get-FileHash -LiteralPath $download).Hash -ne $hash){throw 'Downloaded bytes mismatch'}
  $results.Add(@{check='real_http_upload_download';status='PASS';fileId=$file.id;hash=$hash})
  $ownerToken=Login 'dev_owner' 'H5';$deny=Invoke-WebRequest "$base/materials/$($file.id)/download" -Headers @{Authorization='Bearer '+$ownerToken} -SkipHttpErrorCheck;if([int]$deny.StatusCode -ne 403){throw 'Owner document access was not denied'};$results.Add(@{check='owner_document_denied';status='PASS'})
  $job=Post 'ocr' @{fileId=$file.id;simulateFailure=$true}
  for($i=0;$i -lt 30;$i++){$job=(Invoke-RestMethod "$base/ocr/$($job.id)" -Headers $headers).data;if($job.state -eq 'FAILED'){break};Start-Sleep -Milliseconds 500};if($job.state -ne 'FAILED'){throw 'Expected first mock OCR failure'}
  $job=Post "ocr/$($job.id)/retry" @{}
  for($i=0;$i -lt 30;$i++){$job=(Invoke-RestMethod "$base/ocr/$($job.id)" -Headers $headers).data;if($job.state -eq 'SUCCEEDED'){break};Start-Sleep -Milliseconds 500};if($job.state -ne 'SUCCEEDED'){throw 'OCR retry did not succeed'};if($job.candidate.PSObject.Properties.Name -notcontains 'confidence' -or $null -ne $job.candidate.confidence -or -not $job.candidate.mock -or -not $job.candidate.candidateOnly){throw 'Mock candidate envelope/unknown confidence invalid'}
  $headers['Idempotency-Key']=[guid]::NewGuid().ToString();$job=Post "ocr/$($job.id)/review" @{expectedVersion=0;candidate=@{claimNo='人工复核合成候选';mock=$true;candidateOnly=$true}}
  $job=Post "ocr/$($job.id)/confirm" @{expectedVersion=1};if(-not $job.confirmed){throw 'Human snapshot not confirmed'}
  $results.Add(@{check='scheduled_ocr_failure_retry_review_confirm';status='PASS';jobId=$job.id;attempts=$job.attempts})
  Stop-Process -Id $process.Id;$process.WaitForExit();$process=$null
  & $DockerExe compose --project-name ev-insurance-phase5-audit --env-file $TestEnvironmentFile -f (Join-Path $repo 'infra/docker-compose.yml') restart minio *> (Join-Path $report 'phase5-minio-restart.log');if($LASTEXITCODE -ne 0){throw 'Isolated MinIO restart failed'}
  for($i=0;$i -lt 30;$i++){try{Invoke-WebRequest 'http://127.0.0.1:19000/minio/health/ready'|Out-Null;break}catch{Start-Sleep -Seconds 1}}
  Copy-Item (Join-Path $report 'phase5-http-runtime.log') (Join-Path $report 'phase5-http-before-restart.log')
  Start-Backend
  $token=Login 'dev_customer_service' 'ADMIN';$headers.Authorization='Bearer '+$token
  Invoke-WebRequest "$base/materials/$($file.id)/download" -Headers $headers -OutFile $download
  if((Get-FileHash -LiteralPath $download).Hash -ne $hash){throw 'Object missing or changed after restart'}
  $after=(Invoke-RestMethod "$base/ocr/$($job.id)" -Headers $headers).data;if(-not $after.confirmed -or $after.reviewVersion -ne 1){throw 'OCR snapshot did not persist'}
  $results.Add(@{check='backend_minio_restart_persistence';status='PASS';schema=$env:DB_URL.Split('=')[-1]})
} catch {$results.Add(@{check='http_chain';status='FAIL';reason=$_.Exception.Message});throw}
finally {if($process -and -not $process.HasExited){Stop-Process -Id $process.Id};$results|ConvertTo-Json -Depth 8|Set-Content (Join-Path $report 'phase5-http-results.json') -Encoding utf8;foreach($name in @('DB_PASSWORD','MINIO_SECRET_KEY','JWT_SECRET_BASE64','DEV_ADMIN_PASSWORD','DEV_CUSTOMER_SERVICE_PASSWORD','DEV_REPAIR_SHOP_PASSWORD','DEV_OWNER_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
$results|ForEach-Object {Write-Output ($_.check+': '+$_.status)}
