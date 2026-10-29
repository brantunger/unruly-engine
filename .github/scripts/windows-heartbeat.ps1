# A heartbeat for the build-windows job that leaves evidence even when the runner dies. Six windows-latest jobs
# (2026-10-14 to 2026-10-24) sat in Build & Test until GitHub cancelled them at 20 + 5 minutes; in the two that had
# the watchdog (windows-hang-dump.ps1), the step outlived its own 17-minute timeout, which a working runner enforces
# however hung its JVMs are. So the runner, or its VM, had stopped: it kept no log, ran no upload step, and the dumps
# on its disk went with it. Only what leaves the VM while it still runs survives.
#
# So this posts to GitHub while the build runs. It creates a check run named "Windows heartbeat" on the commit and,
# every IntervalSeconds, rewrites its text with one more line: the time, the free physical memory and commit charge,
# CPU, the disk queues, the JVMs and their memory, the newest JUnit result written, and the busiest processes. When
# windows-hang-dump.ps1 writes its process lists, they go up too. A stall then shows as a check run left in progress:
# its last line says when the VM stopped answering and what it looked like on the way there.
#
# It never fails the job. Without a token it posts nothing; with one that can't write checks (a pull request from a
# fork gets a read-only one), GitHub refuses its one POST, which it logs, and it creates no check run. Either way its
# lines still go to Log. It stops when the file Stop exists, or after MaxMinutes, marking the check run neutral with a
# last line and a summary that says which. MaxMinutes is 30, past the job's cancel at 20 + 5 minutes, so it never ends
# a live job's heartbeat; it only bounds one left running on a VM whose step never reached its finally.
param(
    [string]$Repository = $env:GITHUB_REPOSITORY,
    [string]$Sha = $env:HEARTBEAT_SHA,
    [string]$Token = $env:GH_TOKEN,
    [string]$ApiUrl = ($env:GITHUB_API_URL ? $env:GITHUB_API_URL : 'https://api.github.com'),
    [int]$IntervalSeconds = 60,
    [int]$MaxMinutes = 30,
    [string]$HangDir = 'build/reports/hang',
    [string]$Stop = (Join-Path $env:RUNNER_TEMP 'heartbeat.stop'),
    [string]$Log = (Join-Path $env:RUNNER_TEMP 'heartbeat.log')
)

$ErrorActionPreference = 'Continue'
$started = Get-Date
$lines = [System.Collections.Generic.List[string]]::new()
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}
$title = "run $env:GITHUB_RUN_ID attempt $env:GITHUB_RUN_ATTEMPT, job $env:GITHUB_JOB"

function Post([string]$Method, [string]$Path, [hashtable]$Body) {
    if (-not $Token) { return $null }
    try {
        Invoke-RestMethod -Method $Method -Uri "$ApiUrl/repos/$Repository/$Path" -Headers $headers `
            -Body ($Body | ConvertTo-Json -Depth 5) -ContentType 'application/json' -TimeoutSec 20
    } catch {
        "$(Get-Date -Format o): $Method $Path failed: $_" | Add-Content $Log
        $null
    }
}

# The check run's text is at most 65535 characters. The newest 120 lines come first, cut at their start if they pass
# 65,000 characters, so the newest always stay; the hang lists after them get what room is left.
function Text {
    $text = ($lines | Select-Object -Last 120) -join "`n"
    if ($text.Length -gt 65000) { $text = $text.Substring($text.Length - 65000) }
    $hang = ''
    if (Test-Path $HangDir) {
        foreach ($file in Get-ChildItem $HangDir -Filter '*-processes.txt' -ErrorAction SilentlyContinue) {
            $hang += "`n`n$($file.Name):`n" + (Get-Content $file.FullName -Raw -ErrorAction SilentlyContinue)
        }
    }
    $hang = $hang.Substring(0, [math]::Min($hang.Length, 65000 - $text.Length))
    '```' + "`n" + $text + $hang + "`n" + '```'
}

# GitHub's REST API documents its timestamps as YYYY-MM-DDTHH:MM:SSZ, so no fractional seconds; the invariant
# culture keeps the ':' a colon, which some cultures' time separator isn't (fi-FI gives 22.53.14).
function Utc([datetime]$Time) {
    $Time.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', [cultureinfo]::InvariantCulture)
}

function Sample {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime
    $disks = (Get-CimInstance Win32_PerfFormattedData_PerfDisk_LogicalDisk | Where-Object Name -Match '^[A-Z]:$' |
        ForEach-Object { "$($_.Name) q$($_.CurrentDiskQueueLength) idle$($_.PercentIdleTime)%" }) -join ' '
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    $javaMb = [math]::Round(($java | Measure-Object WorkingSet64 -Sum).Sum / 1MB)
    $result = Get-ChildItem -Path '*/build/test-results/test/TEST-*.xml' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1
    $top = (Get-Process | Sort-Object CPU -Descending | Select-Object -First 5 | ForEach-Object {
            "$($_.ProcessName)[$($_.Id)] $([math]::Round($_.CPU))s $([math]::Round($_.WorkingSet64 / 1MB))MB"
        }) -join ', '
    $newest = $result ?
        "$($result.Directory.Parent.Parent.Parent.Name)/$($result.Name) at " +
            $result.LastWriteTime.ToString('HH:mm:ss', [cultureinfo]::InvariantCulture) :
        '-'
    # The invariant culture, as in Utc, so a line reads the same on any runner.
    [string]::Format([cultureinfo]::InvariantCulture,
        '{0:HH:mm:ss} +{1:mm\:ss} free {2} MB, commit {3}/{4} MB, cpu {5}%, {6}, {7} java {8} MB, last {9}; {10}',
        (Get-Date), ((Get-Date) - $started), [math]::Round($os.FreePhysicalMemory / 1KB),
        [math]::Round(($os.TotalVirtualMemorySize - $os.FreeVirtualMemory) / 1KB),
        [math]::Round($os.TotalVirtualMemorySize / 1KB), $cpu, $disks, $java.Count, $javaMb, $newest, $top)
}

$inProgress = 'In progress: a heartbeat of the windows-latest build. ' +
    'Left in progress after the job ended, either its step never reached its finally, or the final post failed: ' +
    'then the step''s printed log usually ends with `stopped`.'
$check = Post 'POST' 'check-runs' @{
    name = 'Windows heartbeat'; head_sha = $Sha; status = 'in_progress'; started_at = (Utc $started)
    output = @{ title = $title; summary = $inProgress; text = '' }
}
while (-not (Test-Path $Stop) -and ((Get-Date) - $started).TotalMinutes -lt $MaxMinutes) {
    $line = Sample
    $lines.Add($line)
    $line | Add-Content $Log
    if ($check) {
        Post 'PATCH' "check-runs/$($check.id)" @{
            output = @{ title = $title; summary = $inProgress; text = (Text) }
        } | Out-Null
    }
    $deadline = (Get-Date).AddSeconds($IntervalSeconds)
    while (-not (Test-Path $Stop) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
}
$summary = (Test-Path $Stop) ?
    'The step reached its finally, which stopped the heartbeat; this check is evidence only, never a gate.' :
    "Ran out its $MaxMinutes minutes without the step's stop file: the step never reached its finally."
$line = Sample
$lines.Add($line)
$line | Add-Content $Log
if ($check) {
    Post 'PATCH' "check-runs/$($check.id)" @{
        status = 'completed'; conclusion = 'neutral'; completed_at = (Utc (Get-Date))
        output = @{ title = $title; summary = $summary; text = (Text) }
    } | Out-Null
}
"$(Get-Date -Format o): stopped" | Add-Content $Log
