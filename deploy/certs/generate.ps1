<#
.SYNOPSIS
    生成 Nginx 用的自签证书（仅本地演示，浏览器会告警）。

.DESCRIPTION
    优先用本机 openssl；没有就退回 Docker 里的 openssl（默认借用 maven 基础镜像，
    它基于 Debian，自带 /usr/bin/openssl，不需要额外拉镜像）。

.EXAMPLE
    .\deploy\certs\generate.ps1
    .\deploy\certs\generate.ps1 -Days 30
#>
param(
    [string]$CommonName = 'localhost',
    [int]$Days = 365,
    [string]$DockerImage = 'maven:3.9-eclipse-temurin-21'
)

$ErrorActionPreference = 'Stop'
$certDir = $PSScriptRoot
New-Item -ItemType Directory -Force -Path $certDir | Out-Null

$crt = Join-Path $certDir 'server.crt'
$key = Join-Path $certDir 'server.key'
if ((Test-Path $crt) -and (Test-Path $key)) {
    Write-Host "证书已存在，跳过：$crt" -ForegroundColor Yellow
    Write-Host '想重新生成就先删掉 server.crt / server.key'
    exit 0
}

# subjectAltName 必填：现代浏览器只认 SAN，不再看 CN
$opensslArgs = @(
    'req', '-x509', '-nodes', '-newkey', 'rsa:2048',
    '-keyout', 'server.key', '-out', 'server.crt',
    '-days', "$Days",
    '-subj', "/CN=$CommonName",
    '-addext', "subjectAltName=DNS:$CommonName,DNS:host.docker.internal,IP:127.0.0.1"
)

$localOpenssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($localOpenssl) {
    Write-Host "用本机 openssl 生成证书（CN=$CommonName, $Days 天）" -ForegroundColor Cyan
    Push-Location $certDir
    try {
        # openssl 把进度（那些 ++++ 的点）写到 stderr，PowerShell 会把原生命令的 stderr 当成错误记录，
        # 在 $ErrorActionPreference='Stop' 下直接中断脚本。这里并入标准输出丢弃，只看退出码。
        & $localOpenssl.Source @opensslArgs 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "openssl 退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "本机没有 openssl，改用容器（$DockerImage）生成" -ForegroundColor Cyan
    # 挂载目录后以容器内路径调用，证书文件落在宿主机 deploy/certs 下
    docker run --rm -v "${certDir}:/certs" -w /certs $DockerImage openssl @opensslArgs 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker 内 openssl 退出码 $LASTEXITCODE" }
}

Write-Host "完成：$crt / $key" -ForegroundColor Green
Write-Host '这两个文件已被 .gitignore 排除，不会进仓库'
