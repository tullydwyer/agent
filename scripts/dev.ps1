param(
    [ValidateSet('doctor', 'build', 'install', 'run')]
    [string]$Action = 'doctor',
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# Do not change the machine's PATH, JAVA_HOME, or SDK installation permanently.
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools\adb.exe')) }
$sdkDirectory = $sdkCandidates | Select-Object -First 1
if (-not $sdkDirectory) { throw 'Install Android SDK Platform-Tools in Android Studio, or set ANDROID_HOME.' }

$javaCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Android\Android Studio\jbr'
) | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'release')) -and
    ((Get-Content -LiteralPath (Join-Path $_ 'release') -Raw) -match 'JAVA_VERSION="(?:17|21)(?:\.|"|-)' )
}
$javaDirectory = $javaCandidates | Select-Object -First 1
if (-not $javaDirectory) { throw 'Set JAVA_HOME to a compatible JDK (17 or 21).' }
$env:JAVA_HOME = $javaDirectory
$env:ANDROID_HOME = $sdkDirectory
$adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'

function Invoke-Adb {
    & $adbPath @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed (exit $LASTEXITCODE)." }
}

Push-Location $projectRoot
try {
    if ($Action -eq 'doctor') {
        Write-Output "Android SDK: $sdkDirectory"
        Write-Output "Java: $javaDirectory"
        & (Join-Path $javaDirectory 'bin\java.exe') -version
        Invoke-Adb devices -l
        return
    }

    if ($Action -eq 'build') {
        & .\gradlew.bat assembleDebug lintDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build/checks failed (exit $LASTEXITCODE)." }
        return
    }

    $deviceLines = @(Invoke-Adb devices)
    $connectedSerials = @($deviceLines | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
    })
    if (-not $Serial) {
        if ($connectedSerials.Count -ne 1) {
            throw 'Connect exactly one authorized device, or pass -Serial. Run doctor to inspect USB authorization.'
        }
        $Serial = $connectedSerials[0]
    }
    if ($Serial -notin $connectedSerials) { throw 'The selected device is disconnected or not authorized.' }

    if ($Action -eq 'install') {
        $apkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the APK first: .\scripts\dev.ps1 build' }
        Invoke-Adb -s $Serial install -r $apkPath
    } else {
        Invoke-Adb -s $Serial shell am start -W -n dev.pixelagent/.MainActivity
    }
} finally {
    Pop-Location
}
