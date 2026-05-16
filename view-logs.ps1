# 设置控制台编码为 UTF-8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 获取连接的设备列表
$devices = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\t' } | ForEach-Object {
    ($_ -split '\t')[0]
}

if ($devices.Count -eq 0) {
    Write-Host "没有检测到设备" -ForegroundColor Red
    exit 1
}

# 如果有多个设备,让用户选择
$selectedDevice = $null
if ($devices.Count -gt 1) {
    Write-Host "`n检测到 $($devices.Count) 个设备:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $devices.Count; $i++) {
        $deviceInfo = adb -s $devices[$i] shell getprop ro.product.model 2>$null
        if ($deviceInfo) {
            Write-Host "  [$($i+1)] $($devices[$i]) - $deviceInfo"
        } else {
            Write-Host "  [$($i+1)] $($devices[$i])"
        }
    }
    
    # 默认选择真机(非模拟器)
    $defaultIndex = 0
    for ($i = 0; $i -lt $devices.Count; $i++) {
        if ($devices[$i] -notmatch "emulator-") {
            $defaultIndex = $i
            break
        }
    }
    
    Write-Host "`n请选择设备 [1-$($devices.Count)] (默认: $($defaultIndex+1)): " -NoNewline -ForegroundColor Cyan
    $choice = Read-Host
    
    if ([string]::IsNullOrWhiteSpace($choice)) {
        $selectedDevice = $devices[$defaultIndex]
    } else {
        $index = [int]$choice - 1
        if ($index -ge 0 -and $index -lt $devices.Count) {
            $selectedDevice = $devices[$index]
        } else {
            Write-Host "无效选择,使用默认设备" -ForegroundColor Yellow
            $selectedDevice = $devices[$defaultIndex]
        }
    }
} else {
    $selectedDevice = $devices[0]
}

Write-Host "`n使用设备: $selectedDevice" -ForegroundColor Green
Write-Host "正在读取日志...(最近 500 行)`n" -ForegroundColor Gray

# 读取日志
adb -s $selectedDevice shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | Select-Object -Last 500
