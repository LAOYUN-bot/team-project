# =============================================================================
#  A02 AI 职业导航与终身学习伙伴系统 —— 一键启动脚本（Windows / PowerShell）
#
#  用法：
#     .\run.ps1                 # 编译并启动（默认 http://localhost:8080）
#     .\run.ps1 -Port 8081      # 指定端口
#     .\run.ps1 -SkipBuild      # 跳过编译，直接启动（改了代码就别用）
#     .\run.ps1 -Profile mysql  # 使用 MySQL（需先建库，见 README）
#
#  说明：本机为离线环境（无外网），脚本固定使用本地 Maven 仓库中已存在的
#        插件版本，并以 classpath 方式启动，避免依赖 spring-boot-maven-plugin 打包。
# =============================================================================
param(
    [int]$Port = 8080,
    [switch]$SkipBuild,
    [string]$Profile = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

# ---------- 1. 定位 JDK 17 ----------
function Resolve-Jdk17 {
    $candidates = @()
    if ($env:A02_JAVA_HOME) { $candidates += $env:A02_JAVA_HOME }
    if ($env:JAVA_HOME)      { $candidates += $env:JAVA_HOME }
    $candidates += @("D:\Java17", "C:\Program Files\Java\jdk-17", "C:\Program Files\Eclipse Adoptium\jdk-17*")
    foreach ($c in $candidates) {
        $resolved = Get-ChildItem -Path $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c -like "*jdk-17*" -and $resolved) { $c = $resolved.FullName }
        if (Test-Path (Join-Path $c "bin\java.exe")) { return $c }
    }
    return $null
}

$javaHome = Resolve-Jdk17
if (-not $javaHome) {
    Write-Host "[错误] 未找到 JDK 17。请设置环境变量 A02_JAVA_HOME 指向 JDK 17 安装目录。" -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $javaHome
$javaExe = Join-Path $javaHome "bin\java.exe"
Write-Host "[1/4] JDK 17 : $javaHome" -ForegroundColor Cyan

# ---------- 2. 编译 ----------
if (-not $SkipBuild) {
    Write-Host "[2/4] 编译中 (mvn -o -q compile)…" -ForegroundColor Cyan
    & mvn -o -B -q compile -f (Join-Path $projectRoot "pom.xml")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[错误] 编译失败。" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[2/4] 跳过编译" -ForegroundColor DarkGray
}

# ---------- 3. 生成运行时 classpath ----------
Write-Host "[3/4] 解析依赖 classpath…" -ForegroundColor Cyan
$cpFile = Join-Path $projectRoot "target\cp.txt"
& mvn -o -B -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath `
    "-Dmdep.outputFile=$cpFile" -f (Join-Path $projectRoot "pom.xml")
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) {
    Write-Host "[错误] 依赖解析失败。" -ForegroundColor Red
    exit 1
}
$classpath = (Join-Path $projectRoot "target\classes") + ";" + ((Get-Content $cpFile -Raw).Trim())

# ---------- 4. 启动 ----------
$springArgs = @("-Dserver.port=$Port", "-Dfile.encoding=UTF-8")
if ($Profile -ne "") { $springArgs += "-Dspring.profiles.active=$Profile" }

Write-Host "[4/4] 启动服务…" -ForegroundColor Green
Write-Host ""
Write-Host "  演示地址 : http://localhost:$Port" -ForegroundColor Yellow
Write-Host "  演示账号 : demo / demo1234      （普通用户）" -ForegroundColor Yellow
Write-Host "  管理账号 : admin / admin1234    （用于 RBAC 403 演示）" -ForegroundColor Yellow
Write-Host "  H2 控制台: http://localhost:$Port/h2-console" -ForegroundColor DarkGray
Write-Host "  按 Ctrl+C 停止" -ForegroundColor DarkGray
Write-Host ""

Push-Location $projectRoot
try {
    & $javaExe ($springArgs + @("-cp", $classpath, "cn.zjnu.a02.A02Application"))
} finally {
    Pop-Location
}
