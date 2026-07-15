# Renti Agent v2 全栈启动脚本
# 用法: powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
$ErrorActionPreference = "Continue"

$root = "C:\Files\Rentti"
$mvn = "$root\.tools\apache-maven-3.9.9\bin\mvn.cmd"
$mvnSettings = "$root\.tools\maven-settings.xml"

# 1. PostgreSQL（127.0.0.1:5432/postgres，系统服务）
& C:\PostgreSQL\16\bin\pg_isready.exe -h 127.0.0.1 -p 5432 -U postgres *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[1/4] PostgreSQL(5432) 未就绪，请先启动 PostgreSQL 服务后重试" -ForegroundColor Yellow
} else {
    Write-Host "[1/4] PostgreSQL 已在运行"
}

# 2. Spring Boot 后端 (8080)
Write-Host "[2/4] 启动 Spring Boot 后端 (端口 8080)..."
Start-Process -WindowStyle Minimized powershell -ArgumentList "-Command", `
    "cd '$root\renti-agent-backend'; & '$mvn' -s '$mvnSettings' spring-boot:run *>&1 | Tee-Object '$root\renti-agent-backend\backend.log'"

# 3. Python Agent 服务 (8001)
Write-Host "[3/4] 启动 Python Agent 服务 (端口 8001)..."
Start-Process -WindowStyle Minimized powershell -ArgumentList "-Command", `
    "cd '$root\renti-agent-backend\agent-service'; uv run uvicorn app.main:app --host 127.0.0.1 --port 8001 *>&1 | Tee-Object agent-service.log"

# 4. 前端 (5173)
Write-Host "[4/4] 启动前端 Vite (端口 5173)..."
Start-Process -WindowStyle Minimized powershell -ArgumentList "-Command", `
    "cd '$root\renti-agent-front'; npm run dev *>&1 | Tee-Object frontend.log"

Write-Host ""
Write-Host "全部服务已拉起："
Write-Host "  前端        http://127.0.0.1:5173"
Write-Host "  后端 API    http://127.0.0.1:8080/api/health"
Write-Host "  Agent 服务  http://127.0.0.1:8001/health"
Write-Host "  管理后台    http://127.0.0.1:5173/admin/login  (admin / admin123)"
