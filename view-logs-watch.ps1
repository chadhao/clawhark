# 设置控制台编码为 UTF-8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 自动选择真机(非模拟器)
$devices = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\t' } | ForEach-Object {
    ($_ -split '\t')[0]
}

$watchDevice = $devices | Where-Object { $_ -notmatch "emulator-" } | Select-Object -First 1

if (-not $watchDevice) {
    Write-Host "未找到真机设备,尝试使用第一个设备..." -ForegroundColor Yellow
    $watchDevice = $devices[0]
}

if (-not $watchDevice) {
    Write-Host "没有检测到设备" -ForegroundColor Red
    exit 1
}

$deviceInfo = adb -s $watchDevice shell getprop ro.product.model 2>$null
Write-Host "使用设备: $watchDevice - $deviceInfo" -ForegroundColor Green
Write-Host "正在读取日志...(最近 500 行)`n" -ForegroundColor Gray

# 读取日志
adb -s $watchDevice shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | Select-Object -Last 500
