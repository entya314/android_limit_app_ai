# Windows PowerShell 5.1 / PowerShell 7対応。解除時間はAPK側に固定し、引数にはしません。
[CmdletBinding()]
param(
    [ValidateSet('Open', 'Status', 'Close')]
    [string]$Action = 'Open'
)

$ErrorActionPreference = 'Stop'

# PowerShell 5.1では、ADBの正常な起動案内(stderr)まで例外扱いになる場合があります。
# stdout/stderrを別々に読み、終了コードを使って判定します。引数はこのスクリプト内の固定値です。
function Invoke-FocusDpcAdb([string[]]$AdbArguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $adbPath
    $info.Arguments = $AdbArguments -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(120000)) {
            $process.Kill()
            throw 'ADB timed out. Check the USB connection and phone state.'
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        }
    } finally {
        $process.Dispose()
    }
}

try {
    # PATHにない場合はAndroid Studioの標準SDKのADBを使います。
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $adbPath = $adbCommand.Source
    } else {
        $adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adbPath)) {
            throw 'ADB not found. Add Android SDK platform-tools to PATH.'
        }
    }

    # -dでUSB接続だけを対象にします。複数接続・未承認・未接続なら変更せず終了します。
    $serialOutput = Invoke-FocusDpcAdb @('-d', 'get-serialno')
    if ($serialOutput.ExitCode -ne 0) {
        throw "Connect exactly one USB phone and authorize USB debugging.`n$($serialOutput.Output)"
    }
    # 初回のADB起動案内があっても、標準的な端末番号1行を取り出します。
    $serial = @($serialOutput.Output -split '\r?\n' | Where-Object { $_ -match '^[A-Za-z0-9_-]+$' })
    if ($serial.Count -ne 1) { throw 'Cannot identify exactly one USB phone.' }
    $serial = $serial[0]
    if ($serial -notmatch '^[A-Za-z0-9_-]+$') { throw 'Cannot identify the USB phone.' }

    Write-Host "USB phone: $serial"
    $method = $Action.ToLowerInvariant()
    $output = Invoke-FocusDpcAdb @('-s', $serial, 'shell', 'content', 'call', '--user', '0',
        '--uri', 'content://io.github.focusdpc.maintenance', '--method', $method)
    $responseText = $output.Output
    if ($output.ExitCode -ne 0 -or $responseText -notmatch 'payload=([A-Za-z0-9+/=]+)') {
        throw "FocusDPC did not return a valid response. Install version 0.1.3-beta or later.`n$responseText"
    }
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
    $response = $json | ConvertFrom-Json
    if ($response.success -ne $true) {
        $help = switch ($response.code) {
            'NOT_OWNER' { 'FocusDPC is not the Device Owner.' }
            'NOT_BLOCKED' { 'Apply policies in FocusDPC before requesting temporary access.' }
            'FAILED_BLOCKED' { 'The request failed. Installation is blocked. Check alarm permissions and the phone state.' }
            'RESTORE_FAILED' { 'Restoration could not be confirmed. Keep the phone connected and run BlockInstall.cmd again.' }
            default { 'Check the phone and run MaintenanceStatus.cmd.' }
        }
        throw "$($response.code): $help"
    }

    switch ($response.code) {
        'OPENED' { Write-Host 'Temporary installation access started. Device Owner and DNS settings remain active.' }
        'OPEN' { Write-Host 'Temporary access is already active. Its original deadline has NOT been extended.' }
        'BLOCKED' { Write-Host 'App installation is BLOCKED.' }
        'UNRESTRICTED' { Write-Host 'Installation is unrestricted. No temporary window is active. Apply policies in FocusDPC.' }
        default { throw "Unexpected response: $($response.code)" }
    }
    if ($response.remainingSeconds -gt 0) {
        Write-Host "Remaining seconds: $($response.remainingSeconds)"
        Write-Host 'The PHONE restores the restriction automatically. USB may be disconnected.'
        Write-Host 'Both new installs and updates are allowed during this window.'
    }
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
