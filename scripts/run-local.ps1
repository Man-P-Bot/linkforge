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
      5. 校验必需变量齐全（含 SHORTLINK_BASE_URL 的格式校验），然后执行 mvnw spring-boot:run。

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

# SHORTLINK_BASE_URL 的格式校验（docs/DECISIONS.md DR-026）。
#
# ⚠️ 这段校验**逐条对齐** linkforge-core 的 ShortLinkBaseUrl.parse()。两处必须同规则，
#    否则会出现「脚本放行、启动失败」或「脚本拦住、应用其实能跑」这类难查的分歧。
#    Java 侧的 5 类拒绝：
#      1. null / 空串 / 全空白
#      2. 纯斜杠（"/"、"//"）——看起来有值，推不出 scheme 与 host
#      3. URI 语法非法（含内部空白等非法字符）
#      4. 缺 scheme（"localhost:8080"、"example.com"）
#      5. scheme 非 http/https，或有 scheme 但缺 host
#
# 归一化（去末尾斜杠）**不在脚本里做**：那是 ShortLinkBaseUrl 的职责，重复实现只会
# 制造第二份真相。脚本只负责拦下非法值，合法值原样注入。
function Get-BaseUrlError {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return '为空（缺失或全空白）'
    }

    $trimmed = $Value.Trim()

    # 纯斜杠：逐字符看是否全是 / 或 \
    if (-not ($trimmed.ToCharArray() | Where-Object { $_ -ne '/' -and $_ -ne '\' })) {
        return "不能是纯斜杠: `"$trimmed`""
    }

    # URI 语法：含任何空白字符都不是合法 URI（Java 侧 new URI 会抛 URISyntaxException）
    if ($trimmed -match '\s') {
        return "不是合法 URL（含空白字符）: `"$trimmed`""
    }

    # scheme 必须以字母开头，形如 http: // https:
    $schemeMatch = [regex]::Match($trimmed, '^(?<scheme>[A-Za-z][A-Za-z0-9+.\-]*):')
    if (-not $schemeMatch.Success) {
        return "必须带 scheme（如 http://localhost:8080）: `"$trimmed`""
    }

    $scheme = $schemeMatch.Groups['scheme'].Value.ToLowerInvariant()
    if ($scheme -ne 'http' -and $scheme -ne 'https') {
        return "scheme 只能是 http 或 https，实际为 `"$($schemeMatch.Groups['scheme'].Value)`""
    }

    # 有 scheme 必须有 :// 且 host 段非空（对应 Java 的 uri.getHost() 判空）
    $hostMatch = [regex]::Match($trimmed, '^[A-Za-z][A-Za-z0-9+.\-]*://(?<authority>[^/?#]+)')
    if (-not $hostMatch.Success -or [string]::IsNullOrWhiteSpace($hostMatch.Groups['authority'].Value)) {
        return "缺少 host: `"$trimmed`""
    }

    return $null
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
# SHORTLINK_BASE_URL 必填的依据见 docs/API.md §11 与 DR-014 / DR-026。
$required = @('SPRING_DATASOURCE_URL', 'MYSQL_USER', 'MYSQL_PASSWORD', 'SHORTLINK_BASE_URL')
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
Write-Host "  SHORTLINK_BASE_URL    = $(Show-Value 'SHORTLINK_BASE_URL')"
Write-Host "  共注入 $($injected.Count) 个变量"

if ($PrintEnv) {
    Write-Host '[run-local] -PrintEnv 已指定，仅打印变量名后退出：' -ForegroundColor Cyan
    $injected | Sort-Object | ForEach-Object { Write-Host "  $_" }
    exit 0
}

if ($missing.Count -gt 0) {
    Fail ".env 缺少必需变量: $($missing -join ', ')"
}

# SHORTLINK_BASE_URL 的格式校验（DR-026）。
# 为什么在这里也校验一遍，而不是只靠应用启动时抛异常：
#   - 应用抛的异常是一长串 Spring 堆栈，定位成本高；这里能直接点名变量与原因；
#   - 归一化（末尾斜杠）由 ShortLinkBaseUrl 负责，脚本不重复实现，只拦非法值。
if ($settings.Contains('SHORTLINK_BASE_URL')) {
    $baseUrlError = Get-BaseUrlError $settings['SHORTLINK_BASE_URL']
    if ($baseUrlError) {
        Fail "SHORTLINK_BASE_URL $baseUrlError（依据 docs/DECISIONS.md DR-026，合法取值形如 http://localhost:8080）"
    }
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
