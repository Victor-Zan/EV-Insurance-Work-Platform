param([Parameter(Mandatory=$true)][string]$EnvironmentFile)
$ErrorActionPreference='Stop'
# Only allow-listed map variables are injected into the backend. Never echo values.
$mapVariables=@('MAP_PROVIDER','AMAP_WEB_SERVICE_KEY','AMAP_MAX_CALLS_PER_PROCESS','AMAP_REQUESTS_PER_SECOND','AMAP_BUDGET_RUN_ID','MAP_TEST_SESSION_ENABLED','AMAP_JS_ENABLED','AMAP_JS_API_KEY','AMAP_JS_SECURITY_CODE')
foreach($mapLine in Get-Content -LiteralPath $EnvironmentFile){
    if([string]::IsNullOrWhiteSpace($mapLine) -or $mapLine.TrimStart().StartsWith('#')){continue}
    if($mapLine -notmatch '^([A-Z0-9_]+)=(.*)$' -or $matches[1] -notin $mapVariables){throw 'Map environment file contains an unsupported entry'}
    [Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim(),'Process')
}
