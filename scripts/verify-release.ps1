[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$properties = Get-Content -LiteralPath $propertiesPath

function Get-ProjectProperty([string] $name) {
    $line = $properties | Where-Object { $_ -match "^$([regex]::Escape($name))=(.*)$" } | Select-Object -First 1
    if ($null -eq $line) {
        throw "Missing $name in gradle.properties"
    }
    return $line.Substring($name.Length + 1).Trim()
}

$modName = Get-ProjectProperty 'mod_name'
$modId = Get-ProjectProperty 'mod_id'
$modVersion = Get-ProjectProperty 'mod_version'
$minecraftVersion = Get-ProjectProperty 'minecraft_version'
$libsDir = Join-Path $projectRoot 'build/libs'
$expectedName = "$modName-$minecraftVersion-$modVersion-all.jar"
$jarPath = Join-Path $libsDir $expectedName

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Release JAR not found: $jarPath"
}
$allJars = @(Get-ChildItem -LiteralPath $libsDir -Filter '*-all.jar' -File)
if ($allJars.Count -ne 1) {
    throw "Expected one release JAR after a clean build; found $($allJars.Count): $($allJars.Name -join ', ')"
}

Add-Type -AssemblyName System.IO.Compression
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $requiredEntries = @(
        'META-INF/mods.toml'
        'META-INF/jarjar/metadata.json'
        "$modId.mixins.json"
        "$modId.eftlm.mixins.json"
        "$modId.refmap.json"
        "$modId/writer_fingerprint.txt"
    )
    foreach ($name in $requiredEntries) {
        $entry = $archive.GetEntry($name)
        if ($null -eq $entry -or $entry.Length -eq 0) {
            throw "Release JAR is missing a non-empty $name"
        }
    }

    function Read-ArchiveText([string] $name) {
        $reader = [System.IO.StreamReader]::new($archive.GetEntry($name).Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }

    $modsToml = Read-ArchiveText 'META-INF/mods.toml'
    if ($modsToml -notmatch "(?m)^modId\s*=\s*`"$([regex]::Escape($modId))`"\s*$" -or
        $modsToml -notmatch "(?m)^version\s*=\s*`"$([regex]::Escape($modVersion))`"\s*$") {
        throw 'Release JAR mod ID or version does not match gradle.properties'
    }

    $fingerprint = Read-ArchiveText "$modId/writer_fingerprint.txt"
    if ($fingerprint -notmatch '(?m)^[0-9a-f]{16}$') {
        throw 'Release JAR has no valid conversion-writer fingerprint'
    }

    $jarJarMetadata = Read-ArchiveText 'META-INF/jarjar/metadata.json' | ConvertFrom-Json
    $zstd = @($jarJarMetadata.jars | Where-Object {
        $_.identifier.group -eq 'com.github.luben' -and $_.identifier.artifact -eq 'zstd-jni'
    })
    if ($zstd.Count -ne 1) {
        throw 'Release JAR must declare exactly one bundled zstd-jni dependency'
    }
    $nestedJar = $archive.GetEntry([string] $zstd[0].path)
    if ($null -eq $nestedJar -or $nestedJar.Length -eq 0) {
        throw 'Release JAR is missing its declared zstd-jni nested JAR'
    }
} finally {
    $archive.Dispose()
}

$hash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "Verified $expectedName (SHA-256: $hash)"
