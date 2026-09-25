$ErrorActionPreference = 'Continue'

Write-Host '检查 Cooker 本地开发环境...'

$commands = @(
    @{ Name = 'Java'; Command = 'java'; Arguments = @('-version') },
    @{ Name = 'Maven'; Command = 'mvn'; Arguments = @('-version') },
    @{ Name = 'Node.js'; Command = 'node'; Arguments = @('--version') },
    @{ Name = 'npm'; Command = 'npm'; Arguments = @('--version') },
    @{ Name = 'Docker'; Command = 'docker'; Arguments = @('--version') }
)

foreach ($item in $commands) {
    Write-Host "`n[$($item.Name)]"
    if (Get-Command $item.Command -ErrorAction SilentlyContinue) {
        & $item.Command @($item.Arguments)
    }
    else {
        Write-Warning "$($item.Name) 未安装或不在 PATH 中。"
    }
}

