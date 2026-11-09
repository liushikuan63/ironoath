[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('find','stop')][string]$Action,
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Owner,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$JarName,
    [int]$ProcessId
)

$ErrorActionPreference = 'Stop'
$portArgument = "--server.port=$Port"
$ownerArgument = "-Dcodex.runtime.owner=$Owner"
$matches = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -and
    $_.CommandLine.IndexOf($JarName,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    $_.CommandLine.IndexOf($portArgument,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    $_.CommandLine.IndexOf($ownerArgument,[StringComparison]::OrdinalIgnoreCase) -ge 0
})

if ($Action -eq 'find') {
    $matches | ForEach-Object { $_.ProcessId }
    exit 0
}

if ($null -eq $ProcessId -or $ProcessId -lt 1) {
    throw 'stop requires a positive ProcessId'
}
$owned = @($matches | Where-Object { $_.ProcessId -eq $ProcessId })
if ($owned.Count -eq 0) {
    if ($null -eq (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId")) { exit 0 }
    throw "PID $ProcessId ownership changed; refusing to stop it"
}
if ($owned.Count -ne 1) {
    throw "PID $ProcessId ownership is ambiguous; refusing to stop it"
}

Stop-Process -Id $ProcessId -Force -ErrorAction Stop
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if ($null -eq (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId")) { exit 0 }
    Start-Sleep -Milliseconds 100
}
throw "owned backend PID $ProcessId did not exit after Stop-Process"
