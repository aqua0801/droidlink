<#
.SYNOPSIS
    Builds the signed Android APK and the Windows installer for a release, and optionally the
    Linux packages through WSL.

.DESCRIPTION
    Output goes to ./release/ (gitignored):
        DroidLink-<version>.apk
        DroidLink-<version>-windows-x64.exe
        DroidLink-<version>-linux-x64.deb / .tar.gz   (with -Linux)
        LICENSE, THIRD_PARTY_NOTICES.md, SHA256SUMS.txt

    The version comes from gradle.properties (droidlink.version / droidlink.versionCode).
    Release signing needs keystore.properties in the repo root (see README, "Releasing").

.PARAMETER Publish
    Also create the GitHub release with the gh CLI, using docs/releases/v<version>.md as notes.

.PARAMETER Linux
    Also build the Linux packages in WSL (scripts/package-linux.sh), from a clean export of
    HEAD. Needs a WSL distribution with a JDK 17+, dpkg-deb, fakeroot and the Android SDK.

.PARAMETER WslDistro
    WSL distribution for -Linux; the default distribution if omitted.

.PARAMETER AllowDirty
    Build even if the working tree has uncommitted changes (not recommended for a release).

.EXAMPLE
    ./scripts/release.ps1
    ./scripts/release.ps1 -Linux -Publish
#>
param(
    [switch]$Publish,
    [switch]$Linux,
    [string]$WslDistro,
    [switch]$AllowDirty,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Windows PowerShell 5.1 turns any stderr line from a native tool into an error under 'Stop';
# Gradle and apksigner print harmless warnings there. Judge native tools by exit code only.
function Native([scriptblock]$command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command } finally { $ErrorActionPreference = $previous }
}

function Fail($message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }
function Step($message) { Write-Host "`n==> $message" -ForegroundColor Cyan }

function Read-Properties($path) {
    $map = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([^#=][^=]*?)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2] -replace '\\(.)', '$1' }
        }
    }
    $map
}

# ---- Checks ---------------------------------------------------------------------------------

$props = Read-Properties (Join-Path $root 'gradle.properties')
$version = $props['droidlink.version']
$versionCode = $props['droidlink.versionCode']
if (-not $version) { Fail 'droidlink.version is missing from gradle.properties' }
$tag = "v$version"

if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    Fail 'keystore.properties not found in the repo root; the APK would be unsigned.'
}

$dirty = Native { git status --porcelain }
if ($dirty -and -not $AllowDirty) {
    Fail "Uncommitted changes. Commit first so the release matches a commit, or pass -AllowDirty.`n$dirty"
}

# Gradle and the wrapper need a JDK: JAVA_HOME, -JavaHome, java on PATH, or Android Studio's JBR.
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    ) + @(Resolve-Path 'C:\*\Android Studio\jbr' -ErrorAction SilentlyContinue | ForEach-Object Path)
    $found = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
    if (-not $found) { Fail 'No JDK found. Set JAVA_HOME or pass -JavaHome <path to a JDK 17+>.' }
    $env:JAVA_HOME = $found
}
if ($env:JAVA_HOME) { Write-Host "Using JDK: $env:JAVA_HOME" }

Write-Host "Releasing DroidLink $version (versionCode $versionCode)"

# ---- Build ----------------------------------------------------------------------------------

Step 'Running tests'
Native { & .\gradlew.bat test }
if ($LASTEXITCODE -ne 0) { Fail 'Tests failed.' }

Step 'Building signed APK and Windows installer'
Native { & .\gradlew.bat :androidApp:assembleRelease :desktopApp:packageExe }
if ($LASTEXITCODE -ne 0) { Fail 'Build failed.' }

$apk = Join-Path $root 'androidApp\build\outputs\apk\release\androidApp-release.apk'
if (-not (Test-Path $apk)) { Fail "APK not found at $apk (is release signing configured?)" }
$exe = Get-ChildItem (Join-Path $root 'desktopApp\build\compose\binaries\main\exe') -Filter '*.exe' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $exe) { Fail 'Installer (.exe) not found.' }

# ---- Verify the APK signature ---------------------------------------------------------------

Step 'Verifying APK signature'
$local = Read-Properties (Join-Path $root 'local.properties')
$sdk = if ($local['sdk.dir']) { $local['sdk.dir'] } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($apksigner) {
    $certs = Native { & $apksigner verify --print-certs $apk 2>&1 } | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { Fail "APK signature check failed:`n$certs" }
    if ($certs -match 'CN=Android Debug') { Fail 'APK is signed with the debug key.' }
    $certs | Select-String 'Signer #1 certificate (DN|SHA-256)' | ForEach-Object { Write-Host "  $($_.Line)" }
} else {
    Write-Host '  apksigner not found; skipping signature check.' -ForegroundColor Yellow
}

# ---- Collect --------------------------------------------------------------------------------

Step 'Collecting files in release/'
$out = Join-Path $root 'release'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null

$apkOut = Join-Path $out "DroidLink-$version.apk"
$exeOut = Join-Path $out "DroidLink-$version-windows-x64.exe"
Copy-Item $apk $apkOut
Copy-Item $exe.FullName $exeOut
Copy-Item (Join-Path $root 'LICENSE'), (Join-Path $root 'THIRD_PARTY_NOTICES.md') $out

if ($Linux) {
    Step 'Building Linux packages in WSL'
    if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) { Fail 'wsl not found; -Linux needs WSL.' }
    $distro = @(if ($WslDistro) { '-d', $WslDistro })
    $toWsl = { param($path) (Native { & wsl @distro -e wslpath -a ($path -replace '\\', '/') }) | Select-Object -Last 1 }
    $repoWsl = & $toWsl $root
    $outWsl = & $toWsl $out
    # --export builds from a clean copy of HEAD inside the Linux filesystem (see the script).
    Native { & wsl @distro -e bash "$repoWsl/scripts/package-linux.sh" --export $outWsl }
    if ($LASTEXITCODE -ne 0) { Fail 'Linux packaging failed.' }
}

$sums = Get-ChildItem $out -File | Where-Object Name -ne 'SHA256SUMS.txt' | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name
}
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'), $sums)

Get-ChildItem $out -File | ForEach-Object { '  {0,-40} {1,8:N1} MB' -f $_.Name, ($_.Length / 1MB) }

# ---- Publish (optional) ---------------------------------------------------------------------

$notes = Join-Path $root "docs\releases\$tag.md"
if ($Publish) {
    Step "Publishing $tag on GitHub"
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail 'gh CLI not found (https://cli.github.com/).' }
    if (-not (Test-Path $notes)) { Fail "Release notes not found: $notes" }
    if (-not (Native { git tag --list $tag })) { Native { git tag $tag; git push origin $tag } }
    $files = Get-ChildItem $out -File | ForEach-Object FullName
    Native { gh release create $tag @files --title "DroidLink $version" --notes-file $notes --verify-tag }
    if ($LASTEXITCODE -ne 0) { Fail 'gh release create failed.' }
} else {
    Write-Host "`nDone. To publish: ./scripts/release.ps1 -Publish" -ForegroundColor Green
    Write-Host "(or upload release/* on GitHub, with $notes as the description)"
}
