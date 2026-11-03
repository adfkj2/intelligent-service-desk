<#
.SYNOPSIS
    Local Docker launcher for ISD. Compatible with Windows PowerShell 5.1.
.EXAMPLE
    .\start.cmd
    .\demo.cmd -Port 18081
    .\start.cmd -NoBuild -NoBrowser
    .\start.cmd -Action Status
    .\start.cmd -Action Logs
    .\stop.cmd
#>
[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status', 'Logs')]
    [string]$Action = 'Start',
    [ValidateRange(0, 65535)]
    [int]$Port = 0,
    [switch]$Demo,
    [switch]$NoBrowser,
    [switch]$NoBuild,
    [ValidateRange(30, 1800)]
    [int]$WaitTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'docker-compose.stack.yml'
$dockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$savedEnvironment = @{}
foreach ($name in @('DOCKER_HOST', 'DOCKER_CONTEXT', 'ISD_BIND_ADDRESS', 'ISD_HTTP_PORT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$savedOutputEncoding = $OutputEncoding

function Invoke-DockerCommand {
    param([string[]]$DockerArguments, [switch]$Capture, [string]$InputText)
    $hasInput = $PSBoundParameters.ContainsKey('InputText')
    $savedPreference = $ErrorActionPreference
    try {
        # Docker writes normal build progress to stderr. Decide success by exit code,
        # rather than treating native stderr as a terminating PowerShell 5.1 error.
        $ErrorActionPreference = 'Continue'
        if ($Capture) {
            if ($hasInput) { $lines = $InputText | & $dockerCommand.Source @DockerArguments }
            else { $lines = & $dockerCommand.Source @DockerArguments }
        } else {
            if ($hasInput) { $InputText | & $dockerCommand.Source @DockerArguments }
            else { & $dockerCommand.Source @DockerArguments }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($code -ne 0) { throw "Docker command failed (exit $code). See the output above." }
    if ($Capture) { return ($lines -join [Environment]::NewLine).Trim() }
}

function Invoke-Compose {
    param([string[]]$ComposeArguments, [switch]$Capture, [string]$InputText)
    $arguments = @('compose', '--ansi', 'never', '--project-directory', $repoRoot, '--file', $composeFile) + $ComposeArguments
    $options = @{ DockerArguments = $arguments; Capture = $Capture }
    if ($PSBoundParameters.ContainsKey('InputText')) { $options.InputText = $InputText }
    Invoke-DockerCommand @options
}

function Test-DockerEndpoint {
    param([string]$Endpoint)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $dockerCommand.Source
    # Candidates are local named pipes, without spaces or quote characters.
    $process.StartInfo.Arguments = '--host=' + $Endpoint + ' info --format={{.ServerVersion}}'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void]$process.Start()
        if (-not $process.WaitForExit(3000)) {
            $process.Kill()
            $process.WaitForExit()
            return $false
        }
        return $process.ExitCode -eq 0
    } catch {
        return $false
    } finally {
        $process.Dispose()
    }
}

function Find-LocalDocker {
    $endpoints = @()
    if ($env:DOCKER_HOST -match '^npipe:[^\s"'']+$') { $endpoints += $env:DOCKER_HOST }
    $contextEndpoint = $null
    try {
        $contextEndpoint = Invoke-DockerCommand -DockerArguments @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}') -Capture
    } catch {
        # A stale context must not prevent trying Docker Desktop's local endpoints.
    }
    if ($contextEndpoint -match '^npipe:[^\s"'']+$') { $endpoints += $contextEndpoint }
    $endpoints += @('npipe:////./pipe/dockerDesktopLinuxEngine', 'npipe:////./pipe/docker_engine')
    foreach ($endpoint in ($endpoints | Select-Object -Unique)) {
        if (Test-DockerEndpoint -Endpoint $endpoint) { return $endpoint }
    }
    return $null
}

function Connect-LocalDocker {
    $endpoint = Find-LocalDocker
    if ($endpoint) { $env:DOCKER_HOST = $endpoint; return $true }
    if ($Action -ne 'Start') { return $false }
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) {
        throw 'Docker is not running. Start Docker Desktop in Linux containers mode, then try again.'
    }
    Write-Host 'Starting Docker Desktop. Waiting for its engine...' -ForegroundColor Cyan
    Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Seconds 2
        $endpoint = Find-LocalDocker
        if ($endpoint) { $env:DOCKER_HOST = $endpoint; return $true }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Docker did not become ready in 2 minutes. Open Docker Desktop, finish its setup, and try again.'
}

function Initialize-DemoUsers {
    $mysql = 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD exec mysql --default-character-set=utf8mb4 --batch --skip-column-names -uroot isd'
    $arguments = @('exec', '-T', 'mysql', 'sh', '-c', $mysql)
    $count = Invoke-Compose -ComposeArguments $arguments -InputText 'SELECT COUNT(*) FROM sys_user;' -Capture
    if ($count -notmatch '^\d+$') { throw 'Could not read the existing account count.' }
    if ([long]$count -gt 0) {
        Write-Host 'Accounts already exist. Demo initialization skipped; existing accounts are unchanged.' -ForegroundColor Yellow
        return
    }
    $sql = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'demo-users.sql') -Raw -Encoding UTF8
    $result = Invoke-Compose -ComposeArguments $arguments -InputText $sql -Capture
    if ($result -eq 'SKIPPED') {
        Write-Host 'An account was created during startup. Demo initialization skipped.' -ForegroundColor Yellow
        return
    }
    if ($result -ne 'CREATED') { throw 'Could not confirm demo account initialization.' }
    Write-Host 'Demo accounts created (local demo only):' -ForegroundColor Green
    Write-Host '  Employee:      demo_emp   / employee123'
    Write-Host '  Support:       demo_sup   / support123'
    Write-Host '  Administrator: demo_admin / admin12345'
}

try {
    if (-not $dockerCommand) { throw 'Install Docker Desktop first: https://www.docker.com/products/docker-desktop/' }
    if (-not (Test-Path -LiteralPath $composeFile)) { throw "Compose file not found: $composeFile" }
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    # This entry point always uses a local engine; restore any remote context setting on exit.
    [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', $null, 'Process')
    # Local shortcuts bind only to this computer. Direct Compose usage keeps its existing default.
    if (-not $env:ISD_BIND_ADDRESS) { $env:ISD_BIND_ADDRESS = '127.0.0.1' }
    if ($Port -gt 0) { $env:ISD_HTTP_PORT = "$Port" }
    if (-not (Connect-LocalDocker)) {
        if ($Action -eq 'Logs') { throw 'Docker is not running; no live logs are available.' }
        Write-Host 'Docker is not running. ISD services are stopped.'
        exit 0
    }
    $containerOs = Invoke-DockerCommand -DockerArguments @('info', '--format', '{{.OSType}}') -Capture
    if ($containerOs -ne 'linux') { throw 'Switch Docker Desktop to Linux containers mode, then try again.' }
    [void](Invoke-DockerCommand -DockerArguments @('compose', 'version', '--short') -Capture)
    $configuration = Invoke-Compose -ComposeArguments @('config', '--format', 'json') -Capture | ConvertFrom-Json
    $publishedPort = @($configuration.services.app.ports | Where-Object { $_.target -eq 8081 })[0].published
    $validatedPort = 0
    if (-not [int]::TryParse([string]$publishedPort, [ref]$validatedPort) -or $validatedPort -lt 1 -or $validatedPort -gt 65535) {
        throw 'ISD_HTTP_PORT must be a port number between 1 and 65535.'
    }
    $url = "http://localhost:${publishedPort}/"

    switch ($Action) {
        'Stop' {
            Invoke-Compose -ComposeArguments @('down')
            Write-Host 'ISD stopped. Database and Redis data volumes are preserved.' -ForegroundColor Green
        }
        'Status' {
            Invoke-Compose -ComposeArguments @('ps', '--all')
            $appId = Invoke-Compose -ComposeArguments @('ps', '--quiet', 'app') -Capture
            if ($appId) {
                $mapping = Invoke-Compose -ComposeArguments @('port', 'app', '8081') -Capture
                if ($mapping -match ':(\d+)\s*$') { $url = "http://localhost:$($Matches[1])/" }
            }
            Write-Host "Application URL: $url"
        }
        'Logs' { Invoke-Compose -ComposeArguments @('logs', '--follow', '--tail', '100', 'app') }
        'Start' {
            $help = Invoke-Compose -ComposeArguments @('up', '--help') -Capture
            if ($help -notmatch '--wait-timeout') { throw 'Update Docker Desktop: this launcher requires Compose with --wait-timeout support.' }
            Write-Host 'Starting ISD, MySQL and Redis. The first build may take several minutes.' -ForegroundColor Cyan
            $arguments = @('up', '--detach', '--wait', '--wait-timeout', "$WaitTimeoutSeconds")
            if ($NoBuild) { $arguments += '--no-build' } else { $arguments += '--build' }
            Invoke-Compose -ComposeArguments $arguments
            $ready = $false
            for ($attempt = 0; $attempt -lt 15; $attempt++) {
                try {
                    $ping = Invoke-WebRequest -Uri ($url + 'api/ping') -UseBasicParsing -TimeoutSec 3
                    $body = $ping.Content | ConvertFrom-Json
                    if ($body.code -eq 0 -and $body.data.service -eq 'isd') { $ready = $true; break }
                } catch { }
                Start-Sleep -Seconds 1
            }
            if (-not $ready) { throw 'Services started but the page is not reachable. Run start.cmd -Action Logs.' }
            if ($Demo) { Initialize-DemoUsers }
            Write-Host "Ready: $url" -ForegroundColor Green
            Write-Host 'Services run in the background. Use stop.cmd to stop them and keep your data.'
            if (-not $NoBrowser) { Start-Process -FilePath $url | Out-Null }
        }
    }
} catch {
    Write-Host ("ISD: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    $OutputEncoding = $savedOutputEncoding
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
