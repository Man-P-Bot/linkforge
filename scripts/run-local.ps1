<#
.SYNOPSIS
    从工作区根目录的 .env 读取环境变量，然后启动 LinkForge 应用。

.DESCRIPTION
    为什么需要这个脚本：应用的数据源只认环境变量 SPRING_DATASOURCE_URL（不分 profile，
    见 docs/DECISIONS.md 与 linkforge-app/src/main/resources/application.yml）。
    Spring Boot **不会**自动读取 .env 文件，而这个 URL 很长且含 `&` 参数，
    每次手敲既容易出错，又容易在不同终端里敲出不一样的值。

    脚本行为：
      1. 读取工作区根目录的 .env（缺失则报错退出，不回退到默认值）；
      2. 跳过空行与 `#` 开头的注释行；
      3. 解析 KEY=VALUE；值两侧的成对引号会被去掉；
      4. 把键值对写进**当前进程**的环境变量（只影响本脚本启动的子进程，不改系统环境）；
      5. 校验必需变量齐全，然后执行 mvnw spring-boot:run。

    ⚠️ 不在脚本内硬编码任何密码：所有值都来自 .env，而 .env 已被 .gitignore 忽略。

.EXAMPLE
    .\scripts\run-local.ps1
    .\scripts\run-local.ps1 -SkipBuild
    .\scripts\run-local.ps1 -PrintEnv
#>
[CmdletBinding()]
param(
    # 打印将要注入的变量名（值做脱敏），便于确认 .env 是否被正确解析，然后退出
    [switch]$PrintEnv
)

$ErrorActionPreference = 'Stop'

# 工作区根目录 = 本脚本所在目录的上一级（scripts/ 的父目录）
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $workspaceRoot '.env'
$mvnw = Join-Path $workspaceRoot 'mvnw.cmd'

function Fail {
    param([string]$Message)
    Write-Host "[run-local] 错误: $Message" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Fail "找不到 $envFile。请先复制模板：Copy-Item .env.example .env"
}

if (-not (Test-Path -LiteralPath $mvnw)) {
    Fail "找不到 $mvnw"
}

# --- 1. 解析 .env ---------------------------------------------------------
$settings = [ordered]@{}
$lineNumber = 0

foreach ($rawLine in (Get-Content -LiteralPath $envFile -Encoding UTF8)) {
    $lineNumber++
    $line = $rawLine.Trim()

    # 跳过空行与注释
    if ($line.Length -eq 0) { continue }
    if ($line.StartsWith('#')) { continue }

    # docker compose 也支持 `export KEY=VALUE` 写法，这里一并容忍
    if ($line.StartsWith('export ')) { $line = $line.Substring(7).Trim() }

    $separatorIndex = $line.IndexOf('=')
    if ($separatorIndex -lt 1) {
        Write-Host "[run-local] 警告: .env 第 $lineNumber 行不是 KEY=VALUE，已忽略" -ForegroundColor Yellow
        continue
    }

    $key = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()

    # 去掉值两侧的成对引号。
    # 注意：这一步必须在「注入环境变量」之前做，否则引号会成为值的一部分——
    # 对含 & 的 JDBC URL 尤其致命，会把整串 URL 变成非法值。
    if ($value.Length -ge 2) {
        $first = $value[0]
        $last = $value[$value.Length - 1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        Write-Host "[run-local] 警告: .env 第 $lineNumber 行的键名 '$key' 不像环境变量名，已忽略" -ForegroundColor Yellow
        continue
    }

    $settings[$key] = $value
}

if ($settings.Count -eq 0) {
    Fail "$envFile 中没有解析到任何变量"
}

# --- 2. 注入当前进程环境变量 ----------------------------------------------
# 只影响本进程及其子进程（mvnw → Java），不修改用户或系统级环境变量。
$injected = @()
foreach ($key in $settings.Keys) {
    Set-Item -Path "Env:$key" -Value $settings[$key]
    $injected += $key
}

# --- 3. 校验必需变量 ------------------------------------------------------
# application.yml 里这些占位符没有默认值，缺失时 Spring 启动会直接失败。
# 在这里提前拦下并指出是哪一个，比让用户去读一长串 Spring 报错更快。
$required = @('SPRING_DATASOURCE_URL', 'MYSQL_USER', 'MYSQL_PASSWORD')
$missing = @($required | Where-Object { -not $settings.Contains($_) -or [string]::IsNullOrWhiteSpace($settings[$_]) })

# --- 4. 输出摘要（脱敏）---------------------------------------------------
function Show-Value {
    param([string]$Key)
    if (-not $settings.Contains($Key)) { return '<未设置>' }
    $value = $settings[$Key]
    if ($Key -match 'PASSWORD|TOKEN|SECRET') {
        if ([string]::IsNullOrEmpty($value)) { return '<空>' }
        return "<已设置，长度 $($value.Length)>"
    }
    return $value
}

Write-Host '[run-local] 已从 .env 读取配置：' -ForegroundColor Cyan
Write-Host "  SPRING_DATASOURCE_URL = $(Show-Value 'SPRING_DATASOURCE_URL')"
Write-Host "  MYSQL_USER            = $(Show-Value 'MYSQL_USER')"
Write-Host "  MYSQL_PASSWORD        = $(Show-Value 'MYSQL_PASSWORD')"
Write-Host "  MYSQL_PORT            = $(Show-Value 'MYSQL_PORT')"
Write-Host "  共注入 $($injected.Count) 个变量"

if ($PrintEnv) {
    Write-Host '[run-local] -PrintEnv 已指定，仅打印变量名后退出：' -ForegroundColor Cyan
    $injected | Sort-Object | ForEach-Object { Write-Host "  $_" }
    exit 0
}

if ($missing.Count -gt 0) {
    Fail ".env 缺少必需变量: $($missing -join ', ')"
}

# 提示端口口径，避免把 3307 和 3306 记混（DR-017）
if ($settings.Contains('SPRING_DATASOURCE_URL') -and $settings['SPRING_DATASOURCE_URL'] -match 'localhost:(\d+)') {
    Write-Host "[run-local] 提示: 从宿主机连接 MySQL 使用端口 $($Matches[1])（容器内端口恒为 3306）"
}

# --- 5. 启动应用 ----------------------------------------------------------
Write-Host '[run-local] 启动: mvnw spring-boot:run -pl linkforge-app' -ForegroundColor Cyan
Push-Location $workspaceRoot
try {
    & $mvnw spring-boot:run -pl linkforge-app
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $exitCode
