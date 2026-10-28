param(
    [ValidateSet('doctor', 'build', 'install', 'run', 'measure', 'test-phase1', 'test-phase2', 'test-phase4')]
    [string]$Action = 'doctor',
    [string]$Serial,
    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,100}$')]
    [string]$LiveModel
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
        if (-not (Test-Path -LiteralPath '.tools\keyword\assets\keyword\bpe.model')) {
            & (Join-Path $PSScriptRoot 'prepare-keyword.ps1')
        }
        & .\gradlew.bat assembleDebug lintDebug testDebugUnitTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build/checks failed (exit $LASTEXITCODE)." }
        return
    }

    if ($Action -in @('test-phase1', 'test-phase2', 'test-phase4')) {
        & .\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Device test APK build failed.' }
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

    if ($Action -in @('test-phase1', 'test-phase2', 'test-phase4')) {
        # Explicit install/update and instrumentation retain the signed-in app.
        # Gradle connected tests can uninstall it during cleanup; do not use them here.
        Invoke-Adb -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
        Invoke-Adb -s $Serial install -r (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
        $testArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'class')
        if ($Action -eq 'test-phase2') {
            Write-Output 'Local memory/encoder and camera lifecycle tests. Starts camera briefly; no image is uploaded. Keep phone unlocked with a model selected.'
            $testArgs += @('dev.pixelagent.PhaseTwoTest', '-e', 'cameraUi', 'true')
        } elseif ($Action -eq 'test-phase4') {
            Write-Output 'Local hands-free lifecycle tests: quiet room, initially unlocked, signed-in model selected. No intentional inference.'
            $testArgs += 'dev.pixelagent.HandsFreeServiceTest'
        } elseif ($LiveModel) {
            Write-Output 'Live trials send bundled skills and synthetic prompts to api.openai.com using the signed-in ChatGPT plan.'
            $testArgs += @('dev.pixelagent.PhaseOneTest', '-e', 'liveModel', $LiveModel)
        } else {
            $testArgs += 'dev.pixelagent.PhaseOneTest#bundledDiscoveryResourcesPermissionsAndRecreation,dev.pixelagent.PhaseOneTest#malformedAndIncompatiblePackagesAreIsolatedOnAndroid,dev.pixelagent.PhaseOneTest#cancellationAndPartialFailureDoNotExecuteOrReplayOnAndroid'
        }
        $testArgs += 'dev.pixelagent.test/androidx.test.runner.AndroidJUnitRunner'
        # Revoking CAMERA kills the app process. Restore it from the host only
        # after instrumentation has emitted its complete result.
        $restoreCameraDenial = $false
        if ($Action -eq 'test-phase2') {
            $packageState = (Invoke-Adb -s $Serial shell dumpsys package dev.pixelagent) -join "`n"
            if ($packageState -notmatch 'android.permission.CAMERA: granted=(true|false)') { throw 'Cannot determine original camera permission.' }
            $restoreCameraDenial = $Matches[1] -eq 'false'
            if ($restoreCameraDenial) { Invoke-Adb -s $Serial shell pm grant dev.pixelagent android.permission.CAMERA }
        }
        try { $testOutput = @(Invoke-Adb @testArgs) }
        finally {
            if ($restoreCameraDenial) { Invoke-Adb -s $Serial shell pm revoke dev.pixelagent android.permission.CAMERA }
        }
        $testOutput | Write-Output
        if (($testOutput -join "`n") -notmatch 'OK \([0-9]+ tests?\)' -or ($testOutput -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
            throw 'Device tests failed or did not complete.'
        }
    } elseif ($Action -eq 'measure') {
        Write-Output 'Snapshot only. USB charging invalidates discharge measurements.'
        Invoke-Adb -s $Serial shell getprop ro.product.model
        Invoke-Adb -s $Serial shell getprop ro.build.version.release
        Invoke-Adb -s $Serial shell getprop ro.build.version.sdk
        Invoke-Adb -s $Serial shell getconf PAGE_SIZE
        Invoke-Adb -s $Serial shell df -h /data
        Invoke-Adb -s $Serial shell dumpsys battery
        Invoke-Adb -s $Serial shell dumpsys meminfo dev.pixelagent
        foreach ($package in @('com.google.android.tts', 'com.google.android.as', 'com.google.android.googlequicksearchbox')) {
            Write-Output "Speech package: $package"
            Invoke-Adb -s $Serial shell dumpsys package $package | Select-String 'versionName='
        }
    } elseif ($Action -eq 'install') {
        $apkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the APK first: .\scripts\dev.ps1 build' }
        Invoke-Adb -s $Serial install -r $apkPath
    } else {
        Invoke-Adb -s $Serial shell am start -W -n dev.pixelagent/.MainActivity
    }
} finally {
    Pop-Location
}
