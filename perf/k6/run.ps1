<#
.SYNOPSIS
    用 Docker 跑 k6 压测脚本（Windows 友好，不要求本机装 k6）。

.EXAMPLE
    .\perf\k6\run.ps1 tickets
    .\perf\k6\run.ps1 ai
    .\perf\k6\run.ps1 tickets -Env @{ VUS = 20; DURATION = '30s' }
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('tickets', 'ai')]
    [string]$Script,

    # 传给 k6 的环境变量，例如 @{ VUS = 20; DURATION = '30s' }
    [hashtable]$Env = @{},

    # 默认走 host.docker.internal：k6 在容器里跑，应用在宿主机 8081
    [string]$BaseUrl = 'http://host.docker.internal:8081',

    # 国内直连 Docker Hub 不通时，用镜像加速地址
    [string]$Image = 'docker.m.daocloud.io/grafana/k6:0.54.0'
)

$scriptFile = Join-Path $PSScriptRoot "$Script.js"
if (-not (Test-Path $scriptFile)) {
    throw "脚本不存在：$scriptFile"
}

# 容器内路径固定为 /scripts/<file>：common.js 的相对 import 才能解析到
$dockerArgs = @(
    'run', '--rm', '-i',
    '--add-host=host.docker.internal:host-gateway',
    '-v', "$($PSScriptRoot):/scripts:ro",
    '-e', "BASE_URL=$BaseUrl"
)
foreach ($key in $Env.Keys) {
    $dockerArgs += @('-e', "$key=$($Env[$key])")
}
$dockerArgs += @($Image, 'run', "/scripts/$Script.js")

Write-Host "k6 -> $BaseUrl（$Script.js）" -ForegroundColor Cyan
& docker @dockerArgs
exit $LASTEXITCODE
