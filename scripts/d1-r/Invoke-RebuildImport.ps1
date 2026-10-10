param(
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$DatasetPath,
    [Parameter(Mandatory=$true)][string]$SourceVersion,
    [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference='Stop'
if ($Target -notin @('jdbc:postgresql://localhost:5433/cooker_rebuild_d1r2_20261007','jdbc:postgresql://127.0.0.1:5433/cooker_rebuild_d1r2_20261007')) { throw '本轮仅允许指定最终新库' }
if ($SourceVersion -ne '1.6.0-a0b14bd') { throw '必须沿用原真实快照版本' }
if (-not (Test-Path -LiteralPath $DatasetPath -PathType Container)) { throw '只读数据源不存在' }
if (Test-Path -LiteralPath $LogPath) { throw '运行记录已存在，禁止覆盖旧证据' }
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jarPath=Join-Path $repoRoot 'backend/target/cooker-backend-0.0.1-SNAPSHOT.jar'
$javaPath='C:\Program Files\Java\jdk-21\bin\java.exe'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw '请先在本工作树离线构建最新Jar' }
# 只读取已有数据库密码，保存/恢复本进程变量；不写系统或User环境，不加载模型Key。
$savedUrl=$env:COOKER_REBUILD_JDBC_URL
$savedUser=$env:COOKER_REBUILD_USER
$savedPassword=$env:COOKER_REBUILD_PASSWORD
try {
    $databasePassword=$env:COOKER_REBUILD_PASSWORD
    if (-not $databasePassword) { $databasePassword=$env:DB_PASSWORD }
    if (-not $databasePassword) { $databasePassword=[Environment]::GetEnvironmentVariable('DB_PASSWORD','User') }
    if (-not $databasePassword) { throw '没有可用数据库凭证，请仅在当前进程注入' }
    $env:COOKER_REBUILD_JDBC_URL=$Target
    $env:COOKER_REBUILD_USER='root'
    $env:COOKER_REBUILD_PASSWORD=$databasePassword
    & $javaPath '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dloader.main=com.cooker.recipe.diagnostic.RecipeRebuildTool' -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher --target $Target --dataset-path $DatasetPath --source-version $SourceVersion *> $LogPath
    $runExit=$LASTEXITCODE
    if ($runExit -ne 0) { throw '迁移/导入失败，记录与新库现场已保留；不得删库重试或更换快照版本' }
    $result=Select-String -LiteralPath $LogPath -Pattern '^D1R_RESULT='
    if (-not $result) { throw '缺少真实导入结果，保留现场检查入口' }
    $result.Line
} finally {
    $env:COOKER_REBUILD_JDBC_URL=$savedUrl
    $env:COOKER_REBUILD_USER=$savedUser
    $env:COOKER_REBUILD_PASSWORD=$savedPassword
    $databasePassword=$null
}
