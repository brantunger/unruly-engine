# A watchdog for the Build & Test step on windows-latest. The step starts it before ./gradlew and stops it when
# gradlew returns. If gradlew is still running when AfterMinutes is up, it writes, for every JVM still running (the
# Gradle daemon, its test workers, the child JVMs the tests launch): its command line, so the file says which JVM it
# is, and a thread dump; with a list of the biggest processes and the free memory, so a stalled runner shows too. A
# second set AgainAfterSeconds later tells a hang from slowness: threads that haven't moved are stuck. Then it kills
# the JVMs, so gradlew returns, the step fails on its own, and the upload steps that follow can save the files.
#
# Why: a job that only hits its 20-minute timeout leaves nothing behind. Four windows-latest jobs did before this
# watchdog (2026-10-14, 2026-10-15, 2026-10-17 twice): each sat in Build & Test until GitHub cancelled it, and none ran
# its upload steps or kept a log. Two more did with it (2026-10-18, 2026-10-24): there the runner itself had stopped,
# which is why windows-heartbeat.ps1 posts what it sees while the VM still runs (docs/contributing/windows-ci-hangs.md).
#
# ProcessId limits it to the JVMs given, for a trial beside other JVMs; several pids bind only when called in-process.
param(
    [double]$AfterMinutes = 14,
    [int]$AgainAfterSeconds = 60,
    [string]$Out = 'build/reports/hang',
    [int[]]$ProcessId = @()
)

$ErrorActionPreference = 'Continue'
Start-Sleep -Seconds ([int]($AfterMinutes * 60))
New-Item -ItemType Directory -Force $Out | Out-Null

function Jvms {
    if ($ProcessId.Count -gt 0) {
        Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    } else {
        Get-Process java -ErrorAction SilentlyContinue
    }
}

# One round: every jcmd at once, against every JVM, and one deadline for them all, so a round costs at most RoundSeconds
# however many JVMs there are and however many can't answer an attach request. Sequential bounded calls would add up:
# eight stuck JVMs at a minute each is the step's timeout, with nothing written.
function Dump([string]$Label, [int]$RoundSeconds) {
    $jvms = @(Jvms)
    $os = Get-CimInstance Win32_OperatingSystem
    $summary = Join-Path $Out "$Label-processes.txt"
    "$(Get-Date -Format o): $($jvms.Count) JVM(s); free physical memory " +
        "$([math]::Round($os.FreePhysicalMemory / 1KB)) MB of $([math]::Round($os.TotalVisibleMemorySize / 1KB)) MB" |
        Set-Content $summary
    Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 25 Id, ProcessName,
        @{n = 'WorkingSetMB'; e = { [math]::Round($_.WorkingSet64 / 1MB) } },
        @{n = 'CpuSeconds'; e = { [math]::Round($_.CPU) } }, StartTime |
        Format-Table -AutoSize | Out-String -Width 200 | Add-Content $summary
    $calls = @()
    foreach ($jvm in $jvms) {
        $file = Join-Path $Out "$Label-jvm-$($jvm.Id)"
        foreach ($call in @(@('VM.command_line', "$file-command.txt"), @('Thread.print', '-l', "$file-threads.txt"))) {
            $target = $call[-1]
            $calls += [pscustomobject]@{
                File    = $target
                Process = Start-Process jcmd -ArgumentList (@($jvm.Id) + $call[0..($call.Count - 2)]) -NoNewWindow `
                    -PassThru -RedirectStandardOutput $target -RedirectStandardError "$target.err"
            }
        }
    }
    $deadline = (Get-Date).AddSeconds($RoundSeconds)
    while ((Get-Date) -lt $deadline -and @($calls | Where-Object { -not $_.Process.HasExited }).Count -gt 0) {
        Start-Sleep -Milliseconds 500
    }
    foreach ($call in $calls | Where-Object { -not $_.Process.HasExited }) {
        $call.Process.Kill()
        "jcmd didn't answer in $RoundSeconds s" | Add-Content $call.File
    }
}

# At most 45 s + AgainAfterSeconds + 45 s after AfterMinutes: with the step's defaults, done by about 16:35 into the
# step, before its 17-minute timeout.
Dump 'first' 45
Start-Sleep -Seconds $AgainAfterSeconds
Dump 'second' 45
foreach ($jvm in @(Jvms)) {
    try { $jvm.Kill($true) } catch {
        "couldn't kill $($jvm.Id): $_" | Add-Content (Join-Path $Out 'second-processes.txt')
    }
}
"$(Get-Date -Format o): killed the JVMs" | Add-Content (Join-Path $Out 'second-processes.txt')
