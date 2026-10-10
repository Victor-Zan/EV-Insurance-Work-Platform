#requires -Version 7.0
param([string]$JavaHome='C:\Program Files\Android\Android Studio\jbr',[Parameter(Mandatory=$true)][string]$TestEnvironmentFile,[Parameter(Mandatory=$true)][string]$DockerExe,[string]$JarPath='..\local-validation\phase8-delivery-build\backend-0.0.1-SNAPSHOT.jar')
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$stage=Split-Path -Parent $repo
$report=Join-Path $stage 'local-validation'
$resolvedEnv=(Resolve-Path -LiteralPath $TestEnvironmentFile).Path
if(-not $resolvedEnv.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Environment file must remain in second-stage directory'}
$config=@{}
Get-Content -LiteralPath $TestEnvironmentFile|ForEach-Object {if($_ -match '^([A-Z_]+)=(.*)$'){$config[$matches[1]]=$matches[2].Trim()}}
if($config['POSTGRES_DB'] -ne 'ev_insurance_test' -or $config['POSTGRES_PORT'] -ne '25432' -or $config['POSTGRES_USER'] -ne 'phase5_audit'){throw 'Only isolated validation database permitted'}
$env:DB_URL='jdbc:postgresql://127.0.0.1:25432/ev_insurance_test?currentSchema=phase8_http_'+[guid]::NewGuid().ToString('N')
$env:DB_USERNAME=$config['POSTGRES_USER'];$env:DB_PASSWORD=$config['POSTGRES_PASSWORD']
$env:MINIO_ENDPOINT='http://127.0.0.1:19000';$env:MINIO_ACCESS_KEY=$config['MINIO_ROOT_USER'];$env:MINIO_SECRET_KEY=$config['MINIO_ROOT_PASSWORD'];$env:MINIO_BUCKET='phase8-http-tests'
$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Fill($bytes);$env:JWT_SECRET_BASE64=[Convert]::ToBase64String($bytes);$env:JWT_TTL_SECONDS='1800';$env:BACKEND_PORT='18080';$env:SPRING_PROFILES_ACTIVE='dev'
$devPassword=[guid]::NewGuid().ToString();foreach($role in @('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER')){[Environment]::SetEnvironmentVariable('DEV_'+$role+'_PASSWORD',$devPassword,'Process')}
$base='http://127.0.0.1:18080/api/v1'
$results=[Collections.Generic.List[object]]::new()
$process=$null
function Start-Backend {
  $script:process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList '-jar',('"'+(Join-Path $repo $JarPath)+'"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $report 'phase8-http-runtime.log') -RedirectStandardError (Join-Path $report 'phase8-http-runtime-error.log')
  for($i=0;$i -lt 60;$i++){if($script:process.HasExited){throw 'Backend exited; inspect runtime logs'};try {if((Invoke-RestMethod "$base/health").data.status -eq 'UP'){return}}catch{};Start-Sleep -Seconds 1};throw 'Backend readiness timeout'
}
function Login([string]$username,[string]$portal){(Invoke-RestMethod "$base/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$username;password=$devPassword;portal=$portal}|ConvertTo-Json)).data.accessToken}
function Post([string]$path,$body){(Invoke-RestMethod "$base/$path" -Method Post -Headers $script:headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 20)).data}
function Post-Token([string]$token,[string]$path,$body){$script:headers=@{Authorization='Bearer '+$token;'Idempotency-Key'=[guid]::NewGuid().ToString()};Post $path $body}
function Quote([string]$token,[string]$id){(Invoke-RestMethod "$base/quotations/cases/$id" -Headers @{Authorization='Bearer '+$token}).data}
function Assert-Status([string]$token,[string]$path,[int]$status,$body=$null){$headers=@{Authorization='Bearer '+$token;'Idempotency-Key'=[guid]::NewGuid().ToString()};if($null -eq $body){$response=Invoke-WebRequest "$base/$path" -Headers $headers -SkipHttpErrorCheck}else{$response=Invoke-WebRequest "$base/$path" -Method Post -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 20) -SkipHttpErrorCheck};if([int]$response.StatusCode -ne $status){throw ("Unexpected HTTP status for "+$path+': '+[int]$response.StatusCode)}}
function Upload-Loss([string]$token,[string]$id,[string]$replace=''){
  $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token);$client.DefaultRequestHeaders.Add('Idempotency-Key',[guid]::NewGuid().ToString());$form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new([Text.Encoding]::UTF8.GetBytes("%PDF-1.7`nSYNTHETIC LOSS MATERIAL`n%%EOF"));$part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/pdf');$form.Add($part,'file','loss-synthetic.pdf');$form.Add([Net.Http.StringContent]::new('LOSS_ASSESSMENT'),'category');if($replace){$form.Add([Net.Http.StringContent]::new($replace),'replace')};try{$response=$client.PostAsync("$base/materials/cases/$id",$form).GetAwaiter().GetResult();if(-not $response.IsSuccessStatusCode){throw ('Loss upload failed '+$response.StatusCode)};return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data}finally{$form.Dispose();$client.Dispose()}}
function Upload-Photo([string]$token,[string]$id,[int]$assignment){
  $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token);$client.DefaultRequestHeaders.Add('Idempotency-Key',[guid]::NewGuid().ToString())
  $form=[Net.Http.MultipartFormDataContent]::new();$bytes=[Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
  $part=[Net.Http.ByteArrayContent]::new($bytes);$part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('image/png');$form.Add($part,'file','completion-synthetic.png');$form.Add([Net.Http.StringContent]::new('COMPLETION_PHOTO'),'category');$form.Add([Net.Http.StringContent]::new($assignment.ToString()),'assignmentVersion')
  try{$response=$client.PostAsync("$base/materials/cases/$id",$form).GetAwaiter().GetResult();if(-not $response.IsSuccessStatusCode){throw ('Photo upload failed '+$response.StatusCode)};return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data}finally{$form.Dispose();$client.Dispose()}
}
function Confirm-Both([string]$cs,[string]$id){foreach($kind in @('INSURER','CUSTOMER_SERVICE')){$c=Quote $cs $id;$null=Post-Token $cs "quotations/cases/$id/confirmations/$kind" @{expectedVersion=$c.version}}}
try {
  $resolvedJar=[IO.Path]::GetFullPath((Join-Path $repo $JarPath));if(-not $resolvedJar.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'JAR must stay in second-stage directory'}
  Start-Backend
  $cs=Login 'dev_customer_service' 'ADMIN';$shop=Login 'dev_repair_shop' 'H5';$owner=Login 'dev_owner' 'H5';$admin=Login 'dev_admin' 'ADMIN'
  $schema=$env:DB_URL.Split('=')[-1];if($schema -notmatch '^phase8_http_[a-f0-9]{32}$'){throw 'Unexpected isolated schema'}
  $dockerStart=[Diagnostics.ProcessStartInfo]::new($DockerExe);$dockerStart.UseShellExecute=$false;$dockerStart.CreateNoWindow=$true;$dockerStart.RedirectStandardOutput=$true;$dockerStart.RedirectStandardError=$true
  $sql="UPDATE ${schema}.owner_profile SET contact_phone='13800001234' WHERE user_id=(SELECT id FROM ${schema}.app_user WHERE username='dev_owner')"
  foreach($argument in @('compose','--project-name','ev-insurance-phase5-audit','--env-file',$resolvedEnv,'-f',(Join-Path $repo 'infra/docker-compose.yml'),'exec','-T','postgres','psql','-U','phase5_audit','-d','ev_insurance_test','-v','ON_ERROR_STOP=1','-c',$sql)){$dockerStart.ArgumentList.Add($argument)}
  $bindingProcess=[Diagnostics.Process]::Start($dockerStart);$bindingOutput=$bindingProcess.StandardOutput.ReadToEnd();$bindingError=$bindingProcess.StandardError.ReadToEnd();$bindingProcess.WaitForExit()
  ($bindingOutput+$bindingError)|Set-Content -LiteralPath (Join-Path $report 'phase8-http-owner-binding.log') -Encoding utf8
  if($bindingProcess.ExitCode -ne 0){throw 'Isolated synthetic owner setup failed; inspect binding log'}
  $region=((Invoke-RestMethod "$base/pricing/regions?page=1&size=100" -Headers @{Authorization='Bearer '+$cs}).data.records|Where-Object code -eq 'DEV-DISTRICT'|Select-Object -First 1).id
  $draft=Post-Token $cs 'work-orders/drafts' @{insuranceCompany='合成阶段8保司';claimNo='HTTP8-'+[guid]::NewGuid();ownerName='合成车主';ownerPhone='13800001234';vehicleBrand='合成品牌';vehicleModel='测试型号';vehicleVin='VIN8-'+[guid]::NewGuid();accidentAt=[DateTimeOffset]::UtcNow.ToString('o');accidentRegionId=$region;accidentDescription='阶段8合成API验收'};$id=$draft.id
  $null=Post-Token $cs "work-orders/$id/submit" @{confirmPossibleDuplicate=$false}
  $shopId=((Invoke-RestMethod "$base/work-orders/$id/eligible-shops?size=100" -Headers @{Authorization='Bearer '+$cs}).data.records|Where-Object code -eq 'DEV-SHOP'|Select-Object -First 1).id
  $order=Post-Token $cs "work-orders/$id/dispatch" @{shopId=$shopId;confirmPossibleDuplicate=$false};$assignment=$order.currentAssignment.assignmentVersion
  $null=Post-Token $shop "work-orders/$id/accept" @{assignmentVersion=$assignment};$null=Post-Token $shop "work-orders/$id/arrive" @{assignmentVersion=$assignment}
  $c=Quote $shop $id;$c=Post-Token $shop "quotations/cases/$id/raw-quotes" @{expectedVersion=$c.version;assignmentVersion=$assignment;lines=@(@{description='合成配件';quantity=2;unitPrice='30.00'},@{description='合成工时';quantity=1;unitPrice='40.00'},@{description='合成零金额行';quantity=5;unitPrice='0.00'})}
  $null=Post-Token $cs "materials/cases/$id/missing-notice" @{reason='合成验证材料后补'}
  $c=Post-Token $cs "quotations/cases/$id/formal-quotes" @{expectedVersion=$c.version;rawQuoteId=$c.rawQuote.id;mode='FIXED_AMOUNT';fixedAmount='10.00'};$fixedId=$c.formalQuote.id
  if($c.formalQuote.total -ne '110.00' -or $c.formalQuote.lines[0].externalAmount -ne '66.00' -or $c.formalQuote.lines[2].externalAmount -ne '0.00'){throw 'Fixed markup or allocation mismatch'}
  $c=Post-Token $cs "quotations/cases/$id/formal-quotes" @{expectedVersion=$c.version;rawQuoteId=$c.rawQuote.id;mode='PERCENTAGE';percentage='1.23'};if($c.formalQuote.total -ne '101.23' -or $c.rawQuote.total -ne '100.00'){throw 'Percentage or raw snapshot mismatch'}
  $results.Add(@{check='raw_fixed_percentage_immutable_snapshots';status='PASS';caseId=$id})
  Assert-Status $owner "quotations/cases/$id" 403
  $shopView=Quote $shop $id;if($shopView.PSObject.Properties.Name -contains 'formalQuote' -or $shopView.PSObject.Properties.Name -contains 'assessment'){throw 'Shop received internal pricing/assessment fields'}
  foreach($token in @($shop,$owner)){Assert-Status $token "quotations/cases/$id/formal-quotes/$fixedId/export" 403}
  $csv=Invoke-WebRequest "$base/quotations/cases/$id/formal-quotes/$fixedId/export" -Headers @{Authorization='Bearer '+$cs};if($csv.Content -match 'markup|original' -or $csv.Content -notmatch '110.00'){throw 'External export contains internal fields or wrong historical total'}
  $results.Add(@{check='role_field_and_external_export_isolation';status='PASS'})
  $loss=Upload-Loss $cs $id;$c=Quote $cs $id;$c=Post-Token $cs "quotations/cases/$id/assessments" @{expectedVersion=$c.version;amount='105.00'}
  $c=Post-Token $cs "quotations/cases/$id/confirmations/INSURER" @{expectedVersion=$c.version};Assert-Status $shop "quotations/cases/$id/start-repair" 409 @{expectedVersion=$c.version;assignmentVersion=$assignment}
  $c=Post-Token $cs "quotations/cases/$id/confirmations/CUSTOMER_SERVICE" @{expectedVersion=$c.version};if(-not $c.authorized){throw 'Four prerequisites did not authorize repair'}
  $newLoss=Upload-Loss $cs $id $loss.id;$c=Quote $cs $id;if($c.authorized -or $c.gate.insurerConfirmationRecorded -or $c.gate.customerServiceConfirmationRecorded){throw 'Material replacement did not invalidate old confirmations'}
  Confirm-Both $cs $id;$c=Quote $shop $id;$started=Post-Token $shop "quotations/cases/$id/start-repair" @{expectedVersion=$c.version;assignmentVersion=$assignment};if($started.status -ne 'REPAIRING'){throw 'Repair state transition failed'}
  Assert-Status $cs "quotations/cases/$id/assessments" 409 @{expectedVersion=$started.version;amount='999.00'}
  $results.Add(@{check='four_gate_invalidation_reconfirm_start_and_freeze';status='PASS'})
  $history=(Invoke-RestMethod "$base/quotations/cases/$id/history?type=EVENT&page=1&size=100" -Headers @{Authorization='Bearer '+$cs}).data;if($history.records.kind -notcontains 'AUTHORIZATION_REVOKED' -or $history.records.kind -notcontains 'REPAIR_STARTED'){throw 'Authorization audit trail missing'}
  $repairBody=@{expectedVersion=0;assignmentVersion=$assignment;note='实际HTTP合成维修进度';photoIds=@()}
  $script:headers=@{Authorization='Bearer '+$shop;'Idempotency-Key'=[guid]::NewGuid().ToString()};$null=Post "repairs/cases/$id/progress" $repairBody;$null=Post "repairs/cases/$id/progress" $repairBody
  $progress=(Invoke-RestMethod "$base/repairs/cases/$id/progress" -Headers @{Authorization='Bearer '+$owner}).data;if($progress.total -ne 1){throw 'Duplicate progress was persisted'}
  Assert-Status $shop "repairs/cases/$id/completion" 400 @{expectedVersion=1;assignmentVersion=$assignment;photoIds=@()}
  $photo=Upload-Photo $shop $id $assignment;$completed=Post-Token $cs "repairs/cases/$id/completion" @{expectedVersion=1;assignmentVersion=$assignment;photoIds=@($photo.id)}
  if($completed.status -ne 'WAITING_OWNER_CONFIRMATION' -or $completed.completion.photos[0].id -ne $photo.id){throw 'Completion evidence snapshot mismatch'}
  Assert-Status $cs "materials/$($photo.id)/void" 409 @{}
  $ownerView=(Invoke-RestMethod "$base/repairs/cases/$id" -Headers @{Authorization='Bearer '+$owner}).data
  if(($ownerView|ConvertTo-Json -Depth 10) -match 'markup|originalTotal|objectKey|sha256'){throw 'Owner received internal fields'}
  $download=Invoke-WebRequest "$base/materials/$($photo.id)/download" -Headers @{Authorization='Bearer '+$owner};if($download.RawContentLength -ne $photo.size){throw 'Private photo download size mismatch'}
  $results.Add(@{check='repair_progress_idempotency_completion_evidence_freeze_owner_download';status='PASS';caseId=$id;photoId=$photo.id})
  $received=Post-Token $cs "repairs/cases/$id/receipt" @{expectedVersion=2};if($received.status -ne 'COMPLETED'){throw 'Customer service receipt did not complete work order'}
  $reviewed=Post-Token $owner "repairs/cases/$id/review" @{expectedVersion=3;text='真实HTTP车主原评价';score=5}
  $withdrawn=Post-Token $cs "repairs/cases/$id/receipt/withdraw" @{expectedVersion=4;reason='合成收车纠错'};if($withdrawn.status -ne 'WAITING_OWNER_CONFIRMATION' -or $withdrawn.review.eligibleForCurrentRating){throw 'Withdrawal did not deactivate original rating'}
  Assert-Status $cs "materials/$($photo.id)/void" 409 @{}
  $again=Post-Token $owner "repairs/cases/$id/receipt" @{expectedVersion=5};if($again.review.eligibleForCurrentRating){throw 'Historical rating became active without correction'}
  Assert-Status $owner "repairs/cases/$id/review" 409 @{expectedVersion=6;score=4}
  $corrected=Post-Token $cs "repairs/cases/$id/review/corrections" @{expectedVersion=6;text='客服追加纠正';score=4;reason='再次收车人工复核'}
  if(-not $corrected.review.eligibleForCurrentRating -or $corrected.review.original.score -ne 5 -or $corrected.review.current.score -ne 4){throw 'Original rating or correction eligibility mismatch'}
  $results.Add(@{check='cs_receipt_withdraw_owner_rereceipt_review_original_and_correction';status='PASS'})
  $complaintBody=@{description='真实HTTP独立投诉';photoIds=@($photo.id)};$script:headers=@{Authorization='Bearer '+$owner;'Idempotency-Key'=[guid]::NewGuid().ToString()};$complaint=Post "complaints/cases/$id" $complaintBody;$null=Post "complaints/cases/$id" $complaintBody
  $complaintId=$complaint.id;$null=Post-Token $admin "complaints/$complaintId/handle" @{expectedVersion=0;status='PROCESSING';publicNote='公开跟进说明';internalNote='HTTP_PRIVATE_NOTE'}
  $null=Post-Token $cs "complaints/$complaintId/corrections" @{expectedVersion=1;publicNote='客服追加纠正说明'}
  $null=Post-Token $admin "complaints/$complaintId/handle" @{expectedVersion=2;status='RESOLVED';publicNote='已解决'};$null=Post-Token $admin "complaints/$complaintId/handle" @{expectedVersion=3;status='CLOSED';publicNote='关闭归档'}
  foreach($token in @($owner,$shop)){$history=(Invoke-RestMethod "$base/complaints/$complaintId/history" -Headers @{Authorization='Bearer '+$token}).data;if(($history|ConvertTo-Json -Depth 10) -match 'HTTP_PRIVATE_NOTE|internalNote'){throw 'Complaint internal note leaked'}}
  $caseComplaints=(Invoke-RestMethod "$base/complaints/cases/$id" -Headers @{Authorization='Bearer '+$owner}).data;if($caseComplaints.total -ne 1){throw 'Duplicate complaint create persisted'}
  $afterComplaint=(Invoke-RestMethod "$base/repairs/cases/$id" -Headers @{Authorization='Bearer '+$owner}).data;if($afterComplaint.status -ne 'COMPLETED' -or $afterComplaint.version -ne 7 -or $afterComplaint.review.current.score -ne 4){throw 'Complaint changed repair or rating'}
  $results.Add(@{check='complaint_completed_case_idempotency_history_public_internal_isolation';status='PASS';complaintId=$complaintId})
  function Funds([string]$token){(Invoke-RestMethod "$base/funds/cases/$id" -Headers @{Authorization='Bearer '+$token}).data}
  $f=Post-Token $cs "funds/cases/$id/targets" @{expectedVersion=0;direction='RECEIVE';amount='120.00';reason='合成应收版本'}
  $f=Post-Token $cs "funds/cases/$id/targets" @{expectedVersion=1;direction='PAY';amount='80.00';reason='合成应付版本'}
  $firstNumber='HTTP-R-'+[guid]::NewGuid();$entryBody=@{expectedVersion=2;direction='RECEIVE';transactionNo=$firstNumber;amount='40.00';occurredAt='2026-10-10T02:00:00Z';note='HTTP_FUND_PRIVATE'}
  $script:headers=@{Authorization='Bearer '+$cs;'Idempotency-Key'=[guid]::NewGuid().ToString()};$firstEntry=Post "funds/cases/$id/entries" $entryBody;$null=Post "funds/cases/$id/entries" $entryBody
  Assert-Status $cs "funds/cases/$id/entries" 409 @{expectedVersion=3;direction='PAY';transactionNo='NO-PAY-'+[guid]::NewGuid();amount='1.00';occurredAt='2026-10-10T02:00:00Z';note='SYNTHETIC'}
  $results.Add(@{check='partial_receipt_replay_and_insurer_settlement_gate';status='PASS'})
  $todos=(Invoke-RestMethod "$base/notifications/todos?caseId=$id&size=100" -Headers @{Authorization='Bearer '+$cs}).data.records;$collect=$todos|Where-Object kind -eq 'COLLECT_FUNDS'|Select-Object -First 1
  if(-not $collect){throw 'Partial receipt todo missing'}
  $null=Post-Token $cs 'notifications/deadlines' @{taskKey=$collect.taskKey;expectedVersion=0;deadline='2026-01-01T12:00:00+08:00';reason='合成客服约定期限'}
  $todos=(Invoke-RestMethod "$base/notifications/todos?caseId=$id&size=100" -Headers @{Authorization='Bearer '+$cs}).data.records;if(-not ($todos|Where-Object taskKey -eq $collect.taskKey|Select-Object -First 1).overdue){throw 'Deadline overdue label missing'}
  $null=Post-Token $cs 'notifications/deadlines' @{taskKey=$collect.taskKey;expectedVersion=1;deadline=$null;reason='合成清除期限'}
  $results.Add(@{check='manual_optional_deadline_history_and_overdue_label_only';status='PASS'})
  $case=(Invoke-RestMethod "$base/work-orders/$id" -Headers @{Authorization='Bearer '+$cs}).data
  $flowA='HTTP-IMPORT-A-'+[guid]::NewGuid();$flowB='HTTP-IMPORT-B-'+[guid]::NewGuid();$lineA="$flowA,RECEIVE,$($case.claimNo),$($case.businessNo),60.00,2026-10-10T02:00:00Z,SYNTHETIC"
  $importText="transactionNo,direction,claimNo,businessNo,amount,occurredAt,note`n$lineA`n$lineA`n$flowB,RECEIVE,$($case.claimNo),NO-MATCH,20.00,2026-10-10T02:00:00Z,SYNTHETIC`n"
  $importFile=Join-Path $report 'phase8-synthetic-ledger.csv';[IO.File]::WriteAllText($importFile,$importText,[Text.UTF8Encoding]::new($false))
  $preview=(Invoke-RestMethod "$base/funds/imports" -Method Post -Headers @{Authorization='Bearer '+$cs} -Form @{file=Get-Item -LiteralPath $importFile}).data;$batchId=$preview.id
  $null=Post-Token $cs "funds/imports/$batchId/confirm" @{};$rows=(Invoke-RestMethod "$base/funds/imports/$batchId/rows" -Headers @{Authorization='Bearer '+$cs}).data.records
  if($rows[0].status -ne 'RECORDED' -or $rows[1].status -ne 'DUPLICATE' -or $rows[2].status -ne 'NEEDS_REVIEW'){throw 'Import row isolation or conflict classification failed'}
  $null=Post-Token $cs "funds/imports/$batchId/rows/4/resolve" @{expectedVersion=0;caseId=$id;reason='合成纸质流水核对后确认原工单号录错'};$null=Post-Token $cs "funds/imports/$batchId/confirm" @{};$null=Post-Token $cs "funds/imports/$batchId/confirm" @{}
  $f=Funds $cs;if($f.receivable.net -ne '120.00' -or $f.receivable.status -ne 'SETTLED'){throw 'Import total or repeated confirmation mismatch'}
  $source=Invoke-WebRequest "$base/funds/imports/$batchId/source" -Headers @{Authorization='Bearer '+$cs};if($source.RawContentLength -ne ([Text.Encoding]::UTF8.GetByteCount($importText))){throw 'Private import source mismatch'}
  foreach($token in @($admin,$owner,$shop)){Assert-Status $token "funds/imports/$batchId/source" 403}
  $results.Add(@{check='private_import_row_results_manual_matching_and_no_double_accounting';status='PASS';batchId=$batchId})
  $payment=Post-Token $cs "funds/cases/$id/entries" @{expectedVersion=$f.version;direction='PAY';transactionNo='HTTP-P-'+[guid]::NewGuid();amount='80.00';occurredAt='2026-10-10T02:00:00Z';note='HTTP_FUND_PRIVATE'}
  $f=Funds $cs;$null=Post-Token $cs "funds/cases/$id/entries/$($payment.entryId)/reverse" @{expectedVersion=$f.version;reason='合成资金纠错'}
  $f=Funds $cs;$null=Post-Token $cs "funds/cases/$id/entries" @{expectedVersion=$f.version;direction='PAY';transactionNo='HTTP-P-CORRECTED-'+[guid]::NewGuid();amount='80.00';occurredAt='2026-10-10T02:00:00Z';note='HTTP_FUND_PRIVATE'}
  $f=Funds $cs;if($f.payable.net -ne '80.00' -or $f.payable.status -ne 'SETTLED'){throw 'Reversal and replacement payment mismatch'}
  Assert-Status $owner "funds/cases/$id" 403;Assert-Status $owner "funds/cases/$id/export" 403
  $shopFunds=Funds $shop;if($shopFunds.PSObject.Properties.Name -contains 'receivable'){throw 'Shop received insurer receipt fields'}
  $shopCsv=Invoke-WebRequest "$base/funds/cases/$id/export" -Headers @{Authorization='Bearer '+$shop};if($shopCsv.Content -match 'RECEIVE|HTTP_FUND_PRIVATE|transactionNo|markup|ownerPhone'){throw 'Shop export field leak'}
  $results.Add(@{check='payment_reversal_history_and_scoped_export';status='PASS'})
  Assert-Status $cs 'notifications/deadlines' 409 @{taskKey=$collect.taskKey;expectedVersion=2;deadline=$null;reason='已处理待办不能再修改'}
  $deadlineHistory=(Invoke-RestMethod "$base/notifications/deadlines/history?taskKey=$([Uri]::EscapeDataString($collect.taskKey))" -Headers @{Authorization='Bearer '+$cs}).data;if($deadlineHistory.total -ne 2){throw 'Deadline history missing'}
  $settings=(Invoke-RestMethod "$base/notifications/settings" -Headers @{Authorization='Bearer '+$owner}).data;if($settings.automaticTimeoutEnabled -or $settings.smsEnabled -or $settings.escalationEnabled){throw 'Automatic reminders or SMS enabled unexpectedly'}
  $inbox=(Invoke-RestMethod "$base/notifications?size=100" -Headers @{Authorization='Bearer '+$owner}).data.records;if(($inbox|ConvertTo-Json -Depth 10) -match 'amount|internalNote|HTTP_FUND_PRIVATE|FUNDS_UPDATED|PAY_UPDATED|markup'){throw 'Owner notification leaked internal fields'}
  $notice=$inbox|Where-Object caseId -eq $id|Select-Object -First 1;if(-not $notice){throw 'Bound owner station notification missing'};$noticeId=$notice.id
  $null=Post-Token $owner "notifications/$noticeId/read" @{};$null=Post-Token $owner "notifications/$noticeId/read" @{};Assert-Status $shop "notifications/$noticeId/read" 403 @{}
  $results.Add(@{check='station_notification_scope_read_dedup_and_disabled_automation';status='PASS';notificationId=$noticeId})
  Stop-Process -Id $process.Id;$process.WaitForExit();$process=$null
  Copy-Item (Join-Path $report 'phase8-http-runtime.log') (Join-Path $report 'phase8-http-before-restart.log')
  Start-Backend
  $cs=Login 'dev_customer_service' 'ADMIN';$after=Quote $cs $id;if($after.status -ne 'COMPLETED' -or $after.formalQuote.total -ne '101.23' -or $after.assessment.amount -ne '105.00'){throw 'Price/assessment snapshots did not survive restart'}
  $owner=Login 'dev_owner' 'H5';$afterRepair=(Invoke-RestMethod "$base/repairs/cases/$id" -Headers @{Authorization='Bearer '+$owner}).data
  if($afterRepair.completion.photos[0].id -ne $photo.id -or $afterRepair.version -ne 7){throw 'Repair snapshot did not survive restart'}
  $download=Invoke-WebRequest "$base/materials/$($photo.id)/download" -Headers @{Authorization='Bearer '+$owner};if($download.RawContentLength -ne $photo.size){throw 'Persisted photo download mismatch'}
  if($afterRepair.review.original.score -ne 5 -or $afterRepair.review.current.score -ne 4 -or -not $afterRepair.review.eligibleForCurrentRating){throw 'Review history did not survive restart'}
  $afterComplaint=(Invoke-RestMethod "$base/complaints/$complaintId" -Headers @{Authorization='Bearer '+$owner}).data;if($afterComplaint.status -ne 'CLOSED' -or $afterComplaint.description -ne '真实HTTP独立投诉'){throw 'Complaint original and state did not survive restart'}
  $f=Funds $cs;if($f.receivable.net -ne '120.00' -or $f.payable.net -ne '80.00'){throw 'Ledger did not survive restart'}
  $history=(Invoke-RestMethod "$base/funds/cases/$id/entries" -Headers @{Authorization='Bearer '+$cs}).data;if($history.total -ne 5){throw 'Reversal replaced original payment history'}
  $source=Invoke-WebRequest "$base/funds/imports/$batchId/source" -Headers @{Authorization='Bearer '+$cs};if($source.RawContentLength -ne ([Text.Encoding]::UTF8.GetByteCount($importText))){throw 'Private import did not survive restart'}
  $deadlineHistory=(Invoke-RestMethod "$base/notifications/deadlines/history?taskKey=$([Uri]::EscapeDataString($collect.taskKey))" -Headers @{Authorization='Bearer '+$cs}).data;if($deadlineHistory.total -ne 2){throw 'Deadline history did not survive restart'}
  $inbox=(Invoke-RestMethod "$base/notifications?size=100" -Headers @{Authorization='Bearer '+$owner}).data.records;$persistedNotice=$inbox|Where-Object id -eq $noticeId|Select-Object -First 1;if(-not $persistedNotice.read){throw 'Personal notification read state did not survive restart'}
  $results.Add(@{check='station_notice_read_and_deadline_history_restart';status='PASS'})
  $results.Add(@{check='funds_and_private_import_restart_persistence';status='PASS';caseId=$id;batchId=$batchId})
  $results.Add(@{check='jar_restart_persists_repair_evidence_receipts_reviews_complaints_and_amounts';status='PASS';schema=$env:DB_URL.Split('=')[-1]})
} catch {$results.Add(@{check='phase8_http_chain';status='FAIL';reason=$_.Exception.Message});throw}
finally {if($process -and -not $process.HasExited){Stop-Process -Id $process.Id};$results|ConvertTo-Json -Depth 8|Set-Content (Join-Path $report 'phase8-http-results.json') -Encoding utf8;foreach($name in @('DB_PASSWORD','MINIO_SECRET_KEY','JWT_SECRET_BASE64','DEV_ADMIN_PASSWORD','DEV_CUSTOMER_SERVICE_PASSWORD','DEV_REPAIR_SHOP_PASSWORD','DEV_OWNER_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
$results|ForEach-Object {Write-Output ($_.check+': '+$_.status)}
