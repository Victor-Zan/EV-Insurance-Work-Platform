param([Parameter(Mandatory=$true)][string]$EnvironmentFile)
$ErrorActionPreference='Stop'
# Only backend map variables are accepted. Never echo values.
$mapVariables=@('MAP_PROVIDER','AMAP_WEB_SERVICE_KEY','AMAP_MAX_CALLS_PER_PROCESS','AMAP_REQUESTS_PER_SECOND')
foreach($mapLine in Get-Content -LiteralPath $EnvironmentFile){
    if([string]::IsNullOrWhiteSpace($mapLine) -or $mapLine.TrimStart().StartsWith('#')){continue}
    if($mapLine -notmatch '^([A-Z0-9_]+)=(.*)$' -or $matches[1] -notin $mapVariables){throw 'Map environment file contains an unsupported entry'}
    [Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim(),'Process')
}
