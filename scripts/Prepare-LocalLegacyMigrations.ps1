param([Parameter(Mandatory=$true)][string]$Jar,[Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
# Only for the existing local instance whose V25 is allow_repeated_formal_claims.
# Keep that applied migration and append the canonical map V25 as V25.1.
if(Test-Path -LiteralPath $Destination){throw 'Destination already exists; preserve existing migration bundles.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar))
try{
    $legacy=$archive.GetEntry('BOOT-INF/classes/db/local-legacy/V25__allow_repeated_formal_claims.sql')
    $map=$archive.GetEntry('BOOT-INF/classes/db/migration/V25__amap_dispatch_safety.sql')
    if(-not $legacy -or -not $map){throw 'Verified JAR lacks the local V25 compatibility resources.'}
    New-Item -ItemType Directory -Path $Destination | Out-Null
    foreach($entry in $archive.Entries){
        if($entry.FullName -notmatch '^BOOT-INF/classes/db/(migration|dev)/(V[^/]+\.sql)$'){continue}
        $name=$matches[2]
        if($name -eq 'V25__amap_dispatch_safety.sql'){$name='V25.1__amap_dispatch_safety.sql'}
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $Destination $name))
    }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($legacy,(Join-Path $Destination $legacy.Name))
}finally{$archive.Dispose()}
Write-Output 'Prepared local V25 compatibility migrations from the verified JAR; no database was changed.'
