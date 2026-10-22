param([Parameter(Mandatory=$true)][string]$Server, [string]$Serial = 'emulator-5556')
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a test emulator for account fixtures' }
$endpoint = [Uri]$Server
if ($endpoint.Host -ne '127.0.0.1') { throw 'Expected the local native-test API' }
$adbPath = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
& $adbPath -s $Serial reverse tcp:8097 "tcp:$($endpoint.Port)"
if ($LASTEXITCODE -ne 0) { throw 'Cannot forward the test API' }
try {
    $result = & $adbPath -s $Serial shell am instrument -w -e archive true -e push true -e profile_server http://127.0.0.1:8097 app.neochat.test/app.neochat.ui.InteractionInstrumentation
    Write-Output $result
    if (($result -join "`n") -notmatch '(?m)^OK:' -or ($result -join "`n") -match 'FAIL:') { throw 'Android native email scenario failed' }
} finally {
    & $adbPath -s $Serial reverse --remove tcp:8097
}
