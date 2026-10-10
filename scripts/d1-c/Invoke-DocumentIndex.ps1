param(
    [Parameter(Mandatory=$true)][ValidateSet('preflight','execute','recover')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference='Stop'
if ($Target -notin @('jdbc:postgresql://localhost:5433/cooker_rebuild_d1r2_20261007','jdbc:postgresql://127.0.0.1:5433/cooker_rebuild_d1r2_20261007')) { throw '本次只允许准确最终库，拒绝旧库或其他地址' }
if (Test-Path -LiteralPath $LogPath) { throw '运行记录已存在，禁止覆盖旧证据' }
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ($repoRoot -ne 'C:\Users\Omelet\.codex\worktrees\5b09\Cooker') { throw '本卡只允许5b09实际实现目录' }
$jarPath=Join-Path $repoRoot 'backend/target/cooker-backend-0.0.1-SNAPSHOT.jar'
$javaPath='C:\Program Files\Java\jdk-21\bin\java.exe'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw '先完成非收费专项和最新离线Jar构建' }
$saved=@{}
foreach ($name in @('COOKER_D1C_JDBC_URL','COOKER_D1C_USER','COOKER_D1C_PASSWORD','COOKER_D1C_ENDPOINT','DASHSCOPE_API_KEY')) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
try {
    # 只读取已有平台Key和DB密码，注入本次Java子进程；不持久化变量或回显秘密。
    $databasePassword=$env:DB_PASSWORD
    if (-not $databasePassword) { $databasePassword=[Environment]::GetEnvironmentVariable('DB_PASSWORD','User') }
    $platformKey=$env:DASHSCOPE_API_KEY
    if (-not $platformKey) { $platformKey=[Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User') }
    if (-not $databasePassword -or -not $platformKey) { throw '已有数据库凭证或平台Key缺失，停止本次执行' }
    $endpoint=$env:COOKER_RETRIEVAL_DASHSCOPE_ENDPOINT
    if (-not $endpoint) { $endpoint=[Environment]::GetEnvironmentVariable('COOKER_RETRIEVAL_DASHSCOPE_ENDPOINT','User') }
    $env:COOKER_D1C_JDBC_URL=$Target; $env:COOKER_D1C_USER='root'; $env:COOKER_D1C_PASSWORD=$databasePassword
    $env:COOKER_D1C_ENDPOINT=$endpoint; $env:DASHSCOPE_API_KEY=$platformKey
    & $javaPath '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.net.http.retryPost=false' '-Dhttp.keepAlive=false' '-Dloader.main=com.cooker.retrieval.diagnostic.DocumentIndexTool' -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher --mode $Mode --target $Target *> $LogPath
    $runExit=$LASTEXITCODE
    $result=Select-String -LiteralPath $LogPath -Pattern '^D1C_RESULT='
    if ($result) { $result.Line }
    if ($runExit -ne 0) { throw '独立索引已停止，专属费用账本/记录/投影现场保留，不得重置或自动追加收费轮次' }
    if (-not $result) { throw '缺少真实运行结果，保留现场检查，不当作执行成功' }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    $databasePassword=$null; $platformKey=$null
}
