# Starts the Android emulator (if not already running), builds and installs the debug app, and launches it.
# Usage: .\run-emulator.ps1 [-Avd Pixel35] [-ColdBoot] [-Logcat] [-NoFit]
param(
    [string]$Avd = "",
    [switch]$ColdBoot,
    [switch]$Logcat,
    [switch]$NoFit    # leave the emulator window at its default size and position
)

$ErrorActionPreference = "Stop"
$package = "org.pandastic.relay"
$androidDir = Join-Path $PSScriptRoot "android"

# Locate the SDK: ANDROID_HOME, ANDROID_SDK_ROOT, then the default install location.
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { throw "Android SDK not found. Set ANDROID_HOME or install it to $env:LOCALAPPDATA\Android\Sdk." }
$env:ANDROID_HOME = $sdk
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
foreach ($tool in $adb, $emulator) { if (-not (Test-Path $tool)) { throw "Missing $tool. Install it with sdkmanager." } }

# The emulator ignores -scale and can open taller than the screen or off its top edge, hiding buttons.
# Resize it to ~80% of the screen height (keeping the aspect ratio) and center it, leaving room for the taskbar.
function Set-EmulatorWindowSize {
    if (-not ("EmuWin" -as [type])) {
        Add-Type @"
using System; using System.Runtime.InteropServices;
public class EmuWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int hh, uint flags);
    [DllImport("user32.dll")] public static extern bool SystemParametersInfo(int a, int b, out RECT r, int c);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    public static IntPtr FindWindow(uint[] pids) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            uint pid; GetWindowThreadProcessId(h, out pid);
            RECT r;
            if (Array.IndexOf(pids, pid) >= 0 && IsWindowVisible(h) && GetWindowRect(h, out r) && r.R - r.L > 200) found = h;
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
"@
    }
    [void][EmuWin]::SetProcessDPIAware()
    $pids = [uint32[]]@(Get-Process | Where-Object { $_.Name -like "qemu-system*" -or $_.Name -like "emulator*" } | ForEach-Object { $_.Id })
    $hwnd = [IntPtr]::Zero
    for ($i = 0; $i -lt 30 -and $hwnd -eq [IntPtr]::Zero; $i++) {
        $hwnd = [EmuWin]::FindWindow($pids)
        if ($hwnd -eq [IntPtr]::Zero) { Start-Sleep -Seconds 1 }
    }
    if ($hwnd -eq [IntPtr]::Zero) { Write-Warning "Emulator window not found; skipping resize."; return }
    $work = New-Object EmuWin+RECT; [void][EmuWin]::SystemParametersInfo(0x30, 0, [ref]$work, 0)
    $win = New-Object EmuWin+RECT; [void][EmuWin]::GetWindowRect($hwnd, [ref]$win)
    $workW = $work.R - $work.L; $workH = $work.B - $work.T
    $newH = [int]($workH * 0.80)
    $newW = [int](($win.R - $win.L) * $newH / ($win.B - $win.T))
    $x = $work.L + [int](($workW - $newW) / 2)
    $y = $work.T + [int]($workH * 0.04)
    [void][EmuWin]::SetWindowPos($hwnd, [IntPtr]::Zero, $x, $y, $newW, $newH, 0x0004)  # SWP_NOZORDER
}

function Get-EmulatorSerial {
    & $adb devices | ForEach-Object { if ($_ -match '^(emulator-\d+)\s+device$') { $Matches[1] } } | Select-Object -First 1
}

# Start the emulator unless one is already running.
$serial = Get-EmulatorSerial
if ($serial) {
    Write-Host "Emulator already running: $serial"
    if (-not $NoFit) { Set-EmulatorWindowSize }
} else {
    $avds = @(& $emulator -list-avds | Where-Object { $_ })
    if (-not $avds) { throw "No AVDs found. Create one with avdmanager (see README)." }
    if (-not $Avd) { $Avd = if ($avds -contains "Pixel35") { "Pixel35" } else { $avds[0] } }
    if ($avds -notcontains $Avd) { throw "AVD '$Avd' not found. Available: $($avds -join ', ')" }
    $emuArgs = @("-avd", $Avd, "-no-snapshot-save")
    if ($ColdBoot) { $emuArgs += "-no-snapshot-load" }
    Write-Host "Starting emulator $Avd..."
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -WindowStyle Hidden | Out-Null  # hides emulator.exe's log console, not the device window
    if (-not $NoFit) { Set-EmulatorWindowSize }
    & $adb wait-for-device
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        $booted = "$(& $adb shell getprop sys.boot_completed 2>$null)".Trim() -eq "1"
    } until ($booted -or (Get-Date) -gt $deadline)
    if (-not $booted) { throw "Emulator did not finish booting within 5 minutes." }
    $serial = Get-EmulatorSerial
    Write-Host "Emulator booted: $serial"
}

# Build and install the debug APK.
Write-Host "Building and installing debug app..."
& (Join-Path $androidDir "gradlew.bat") -p $androidDir installDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }

# Wake the screen, clear any stray screen on top, then launch the app fresh.
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $serial shell wm dismiss-keyguard
& $adb -s $serial shell input keyevent KEYCODE_HOME
& $adb -s $serial shell am force-stop $package
& $adb -s $serial shell am start -n "$package/.MainActivity" | Out-Null
Write-Host "Launched $package on $serial"

if ($Logcat) {
    Start-Sleep -Seconds 1
    $appPid = "$(& $adb -s $serial shell pidof -s $package)".Trim()
    if ($appPid) { & $adb -s $serial logcat -v color --pid=$appPid }
}
