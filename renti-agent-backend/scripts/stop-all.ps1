# Renti Agent v2 全栈停止脚本（对应 scripts\start-all.ps1）
# 用法: powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1          停止全部（含 PostgreSQL）
#       powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -KeepDb  保留 PostgreSQL，只停三个应用服务
param(
    [switch]$KeepDb   # 保留 PostgreSQL 不停止
)
$ErrorActionPreference = "Continue"

$root = "C:\Files\Rentti"
$pgBin = "C:\PostgreSQL\16\bin"

# 按监听端口找到进程，连同其子进程一起结束
function Stop-ByPort {
    param([string]$Label, [int]$Port)
    $owners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 }
    if (-not $owners) {
        Write-Host "  ${Label}（端口 $Port）：未在运行"
        return
    }
    foreach ($procId in $owners) {
        $name = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName
        taskkill /PID $procId /T /F *> $null
        Write-Host "  ${Label}（端口 $Port）：已停止 $name (PID $procId)"
    }
}

Write-Host "[1/3] 停止应用服务..."
Stop-ByPort "前端 Vite" 5173
Stop-ByPort "Python Agent" 8001
Stop-ByPort "Spring Boot 后端" 8080

# start-all 是用最小化 PowerShell 窗口包了一层启动的；若服务还在启动中（尚未监听端口）
# 或启动失败，按端口找不到它们，这里按命令行特征兜底清理，避免留下孤儿窗口。
Write-Host "[2/3] 清理残留启动进程..."
$marker = 'renti-agent-backend|renti-agent-front|spring-boot:run|app\.main:app'
$allowedNames = '^(powershell|pwsh|cmd|java|node|python|uv|uvx|npm)(\.exe)?$'   # 只清理这些，防止误杀编辑器等

# 本脚本自身及其祖先进程（运行脚本的终端等）绝不能杀
$protected = @()
$cursor = $PID
for ($i = 0; $i -lt 10 -and $cursor; $i++) {
    $protected += $cursor
    $cursor = (Get-CimInstance Win32_Process -Filter "ProcessId=$cursor" -ErrorAction SilentlyContinue).ParentProcessId
}

$leftover = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -match $marker -and
    $_.Name -match $allowedNames -and
    $protected -notcontains $_.ProcessId
}
if ($leftover) {
    foreach ($p in $leftover) {
        taskkill /PID $($p.ProcessId) /T /F *> $null
        Write-Host "  已清理 $($p.Name) (PID $($p.ProcessId))"
    }
} else {
    Write-Host "  无残留进程"
}

Write-Host "[3/3] 停止 PostgreSQL..."
& "$pgBin\pg_isready.exe" -h 127.0.0.1 -p 55432 -U renti *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "  未在运行"
} elseif ($KeepDb) {
    Write-Host "  按 -KeepDb 保留运行"
} else {
    & "$pgBin\pg_ctl.exe" -D "$root\renti-agent\.local-postgres\data" stop -m fast
}

# 复核端口，确认停干净
Start-Sleep -Seconds 1
Write-Host ""
$remaining = @()
foreach ($port in 5173, 8001, 8080) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $remaining += $port }
}
if (-not $KeepDb) {
    & "$pgBin\pg_isready.exe" -h 127.0.0.1 -p 55432 -U renti *> $null
    if ($LASTEXITCODE -eq 0) { $remaining += 55432 }
}
if ($remaining) {
    Write-Host "警告：端口 $($remaining -join ', ') 仍被占用，可重跑本脚本或手动检查。"
} else {
    Write-Host "全部服务已停止。"
}
