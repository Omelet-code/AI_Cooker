# Cooker 部署教程：Ubuntu 24.04 LTS，零基础公网 IP 版

更新日期：2026-10-10。适用：Ubuntu 24.04 LTS、4核/4GB、暂时没有域名，先用公网 IPv4 访问，允许公开注册。

用户已确认新的系统与规格。本版替代原Ubuntu22.04教程；不要混用旧版的jammy软件源。此次仅更新文档，不代表服务器已安装系统、Docker或部署项目。内存仍为4GB，先沿用已验收的保守容器资源限制，不因CPU增加自动扩大连接池或模型并发；实际负载测量后再调整。

本教程按“第一次接触 Linux 和 Docker”编写。你不需要先学完全部知识；一次只做一节，每节成功后再往下走。

## 0. 先读这五句话

1. 下面是操作说明，不代表服务器已经安装或部署。主架构本轮没有连接服务器、上传文件、迁移真实数据库或调用收费模型。
2. D2-B准备和厨房调用参数化调整D2-B1都已轻量验收（24项与9项独立专项通过）。**第4节必须从最新5b09配置装配，不能上传之前的旧配置包。** 旧版把开关固定为false、百炼Key固定为空。
3. 你已允许厨房LLM和query Embedding费用。不再要求累计费用系统作为个人演示前置，但公开用户会消耗你的百炼Key；入口限流不等于费用硬上限。
4. 数据只迁移系统菜谱原文、结构化数据、检索投影与已有向量；线上用户重新注册。不要复制整个本地数据库、账号、聊天或密钥。
5. 任何一步失败，**停止，不重复点击、不删除数据库、不执行`down -v`、不忽略证书错误**。把步骤编号和脱敏报错给我。不要发密码、API Key、私钥或完整环境文件。

这份教程的完整功能上线终点是：用户注册 → 填自己的DeepSeek Key → 小厨给两套餐单 → 选择一套 → 生成指导 → 确认材料 → Mock采购 → 刷新回看。Mock采购不是美团真实下单。

## 1. 先分清在哪台电脑操作

| 标记 | 你在哪里操作 | 如何打开 |
| --- | --- | --- |
| Windows | 自己的电脑 | 开始菜单搜索“PowerShell” |
| Linux | 云服务器 | 云厂商网页终端，或者PowerShell通过ssh登录 |
| 浏览器 | 自己的电脑 | Chrome/Edge等真实浏览器 |
| 云控制台 | 云厂商网站 | 服务器管理页面中的“安全组/防火墙” |

不要把Linux命令粘贴到尚未登录服务器的PowerShell中。`sudo`表示“以服务器管理员权限操作”，提示密码时输入的是服务器账号密码，不是数据库密码；输入时不显示星号是正常的。

本文中的`填写你的公网IP`、`填写服务器用户名`必须替换成真实值，不要连中文字一起执行。以下主流程只讲IPv4；如果你的服务器只有IPv6，先告诉我，不要照搬地址格式。

### 1.1 你需要准备的资料

- 服务器公网IPv4，在云厂商服务器详情页复制。
- SSH用户名，由厂商镜像决定，可能是ubuntu，也可能是root；不要猜。
- 服务器登录方式：密码或SSH私钥，保存在你自己电脑上，不发到聊天。
- 一个可收到证书通知的邮箱。
- 你自己的阿里百炼Embedding API Key，稍后直接填服务器文件。
- 最终用户各自的DeepSeek官方API Key，在网页填写，不写进服务器共享配置。

### 1.2 登录服务器

Windows PowerShell：

```powershell
ssh 填写服务器用户名@填写你的公网IP
```

如果厂商指定密钥登录：

```powershell
ssh -i "填写你电脑上SSH私钥的完整路径" 填写服务器用户名@填写你的公网IP
```

首次连接时，先核对厂商提供的服务器主机指纹；一致后才输入`yes`。不要为了省事关闭主机校验。登录后执行下面这条Linux命令：

```bash
whoami
```

成功标志：显示你的服务器用户名。后文统一保留`sudo`，即使你是root也可以按同一套步骤操作。退出服务器用`exit`；退出后同一个窗口又回到Windows。

## 2. 检查服务器，不安装东西

Linux服务器，逐条执行：

```bash
cat /etc/os-release
uname -m
dpkg --print-architecture
nproc
free -h
df -h / /var/lib
df -i / /var/lib
sudo ss -lntp
```

你要看到：

- 系统是Ubuntu，版本24.04，代号noble。
- 当前三个已核对的镜像是`linux/amd64`：服务器应该显示`x86_64`和`amd64`。如果显示`aarch64`/`arm64`，停下，需要重新准备对应架构镜像。
- 可用逻辑CPU数量通常应为4（`nproc`）；如果与购买规格不符，先核对云厂商配置及CPU限制。
- 内存接近4GB；Linux显示方式可能是GiB，不会恰好显示4.0。
- 磁盘有足够空闲空间和inode；不能只看服务器总容量。要容纳镜像、镜像tar、数据、备份和升级回滚。建议此次起步至少留20GiB空闲，再按实际文件大小核算；这不是经过实测的最低容量。
- 80/443未被已有网站占用。如果已被占用，停下，不要强行kill服务器上的服务。

可以把这一节的非秘密输出发给我确认。此时还不需要提供Key。

## 3. 安装Docker Engine和Compose

Linux服务器。先检查是否已有冲突软件：

```bash
dpkg-query -W docker.io docker-compose docker-compose-v2 podman-docker containerd runc 2>/dev/null
```

新服务器可能没有输出；这是正常情况。如果列出已安装软件或已有容器，先告诉我，不自动卸载。

### 3.1 安装基础工具和官方仓库

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl python3 openssl nano
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
```

仅在第2节确认Ubuntu24.04/noble后执行：

```bash
printf 'Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: noble\nComponents: stable\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/docker.asc\n' "$(dpkg --print-architecture)" | sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null
sudo apt-get update
apt-cache madison docker-ce
```

最后一条会显示可安装版本。复制**中间那列完整版本号**，例如形状是`5:某版本-1~ubuntu.24.04~noble`，不要使用这句话里的示例文字。

```bash
COOKER_DOCKER_VERSION='填写你刚复制的完整版本号'
sudo apt-get install -y docker-ce="$COOKER_DOCKER_VERSION" docker-ce-cli="$COOKER_DOCKER_VERSION" containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

成功标志：`docker version`有Client和Server两部分，`docker compose version`显示版本。记录版本，不在服务器安装Docker Desktop、JDK、Maven或Node。我们在Windows构建，Linux只运行。

如果报仓库连接失败、版本不存在或权限错误，停下，不使用来历不明的“一键安装脚本”。本步骤依据[Docker官方Ubuntu安装说明](https://docs.docker.com/engine/install/ubuntu/)，Ubuntu24.04在其支持列表内。

## 4. 在Windows准备镜像和部署文件

**D2-B1最小配置已复审通过。** 本节是你后续自行操作的命令，本轮主架构尚未执行归档/导出/上传。

### 4.1 为什么不能直接复制E盘项目

最新后端实际在5b09工作树，前端在0f47工作树。已有装配工具会从这两个明确来源复制白名单，不带真实env、Key、缓存、数据库备份。不要直接压缩整个`E:\Cooker`或整棵工作树。

Windows PowerShell：

```powershell
python "C:\Users\Omelet\.codex\worktrees\5b09\Cooker\deploy\d2\scripts\assemble.py" --backend "C:\Users\Omelet\.codex\worktrees\5b09\Cooker" --frontend "C:\Users\Omelet\.codex\worktrees\0f47\Cooker" --output "E:\CookerRelease\d2-20261010\release"
```

成功标志：“发布白名单装配完成”，且出现`E:\CookerRelease\d2-20261010\release\deploy`。输出目录必须不存在或为空；如果提示非空，停下，不删除旧发布包，换一个新版本目录并同步后面所有路径。

如果`python`不可用，可把开头替换为：

```powershell
& "C:\Users\Omelet\AppData\Local\Programs\Python\Python313\python.exe"
```

这是命令前缀，不是单独完成装配；后面仍要接脚本路径和参数。

### 4.2 核对三个已验收镜像

先启动自己电脑的Docker Desktop，然后在Windows PowerShell执行：

```powershell
docker image inspect cooker-d2a-backend:final-20261009 cooker-d2a-frontend:verify-20261009 cooker-d2a-postgres:verify-20261009 --format '{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}'
```

2026-10-10主只读核对结果如下；若你得到不同ID或镜像不存在，停下核对，不随意替换镜像：

| 组件/标签 | 本地image ID | 平台 |
| --- | --- | --- |
| 后端：cooker-d2a-backend:final-20261009 | sha256:0265afe097fc48fe432d8b18b86021dbb4f6fc47ff73e85080f1b32cb3b0375b | linux/amd64 |
| 前端：cooker-d2a-frontend:verify-20261009 | sha256:506b5c5fe6d2d0417d9c892402b82248b9e10232020aec1cae18a9bab3653bfd | linux/amd64 |
| PostgreSQL：cooker-d2a-postgres:verify-20261009 | sha256:f13f4147c6f96bc09f64860c500f7831023be0da6e8635e7494671ea89a63c6f | linux/amd64 |

本次D2-B/B1只改外部部署配置，不改业务源码，因此可以复用这些镜像，并挂载最新Nginx模板。后续如果修改业务源码，就必须重新构建并冻结新的ID，不能继续照抄此表。

`image ID`标识本地镜像，`RepoDigest`标识仓库镜像；本方案不用你额外开镜像仓库，直接save/load并按image ID运行。

### 4.3 导出镜像和压缩部署文件

Windows PowerShell，确保目标文件尚不存在；不要覆盖旧发布归档：

```powershell
docker image save --output "E:\CookerRelease\d2-20261010\cooker-images.tar" cooker-d2a-backend:final-20261009 cooker-d2a-frontend:verify-20261009 cooker-d2a-postgres:verify-20261009
tar.exe -czf "E:\CookerRelease\d2-20261010\cooker-config.tar.gz" -C "E:\CookerRelease\d2-20261010\release" deploy release-manifest.json
Get-FileHash -Algorithm SHA256 -LiteralPath "E:\CookerRelease\d2-20261010\cooker-images.tar"
Get-FileHash -Algorithm SHA256 -LiteralPath "E:\CookerRelease\d2-20261010\cooker-config.tar.gz"
```

镜像归档可能较大，等命令结束再继续。记下两个SHA256，稍后服务器验证传输是否完整；不对数据库做哈希复审。**镜像包不包含菜谱数据库，也不包含密钥。** 配置包有无秘密的`.env.example`，不是你真正的`production.env`。

## 5. 准备系统菜谱迁移包

这是实际本地数据导出步骤，需在确认源库后执行；本轮没有替你导出。只读导出源数据，但会在Windows创建一个JSON文件。

此前源为容器`cooker-postgres`、数据库`cooker_rebuild_d1r2_20261007`、角色`root`。这是既有基线，不代替执行时核验：

```powershell
docker ps --filter name=cooker-postgres --format '{{.Names}} {{.Status}} {{.Ports}}'
docker exec cooker-postgres psql -X -U root -d cooker_rebuild_d1r2_20261007 -tAc "SELECT count(*) FROM public.recipe;"
```

如果容器、库或角色与实际情况不符，停下确认，不改为5432的另一个本地库。核对后：

```powershell
python "E:\CookerRelease\d2-20261010\release\deploy\scripts\recipes.py" export --container cooker-postgres --database cooker_rebuild_d1r2_20261007 --role root --confirm-database cooker_rebuild_d1r2_20261007 --file "E:\CookerRelease\d2-20261010\system-recipes.json"
```

成功标志：“菜谱白名单工具完成；未调用模型，未迁移用户表”。文件已存在时工具会拒绝，不删除旧文件重试。

该包包含系统菜谱三层数据和已有1024维向量，不需要再次付费生成document向量。用户账户、收藏、历史、餐单、指导、采购和API Key不在包内。不得用Navicat整库导出代替。

Windows只核对包行数，不打印菜谱正文：

```powershell
$cookerRecipePackage = Get-Content -LiteralPath "E:\CookerRelease\d2-20261010\system-recipes.json" -Raw | ConvertFrom-Json
$cookerRecipePackage.tables.recipe.Count
$cookerRecipePackage.tables.recipe_source_document.Count
$cookerRecipePackage.tables.recipe_search_document.Count
```

记下这三个数字，恢复后比对。最近基线为372菜谱/1856检索块，但以本次实际导出为准，不凭旧数字判断。

## 6. 上传到Linux服务器

### 6.1 Linux先创建接收目录

Linux服务器：

```bash
sudo install -d -m 0700 -o "$(id -un)" -g "$(id -gn)" /var/tmp/cooker-upload-20261010
```

本目录只用于本次发布文件。如果此前已放过另一批文件，不覆盖，先确认版本。

### 6.2 Windows上传三个文件

另开一个未SSH登录的Windows PowerShell窗口：

```powershell
scp "E:\CookerRelease\d2-20261010\cooker-images.tar" 填写服务器用户名@填写你的公网IP:/var/tmp/cooker-upload-20261010/
scp "E:\CookerRelease\d2-20261010\cooker-config.tar.gz" 填写服务器用户名@填写你的公网IP:/var/tmp/cooker-upload-20261010/
scp "E:\CookerRelease\d2-20261010\system-recipes.json" 填写服务器用户名@填写你的公网IP:/var/tmp/cooker-upload-20261010/
```

密钥登录时给scp加`-i "你的私钥完整路径"`，方法与ssh一样。等待百分比达到100%再继续。

### 6.3 Linux验证并装入

```bash
sha256sum /var/tmp/cooker-upload-20261010/cooker-images.tar
sha256sum /var/tmp/cooker-upload-20261010/cooker-config.tar.gz
sudo docker image load --input /var/tmp/cooker-upload-20261010/cooker-images.tar
sudo docker image inspect cooker-d2a-backend:final-20261009 cooker-d2a-frontend:verify-20261009 cooker-d2a-postgres:verify-20261009 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

前两个值应与第4节Windows一致，三个ID应与表格一致。不同就停下，不启动。

建立本次全新发布目录；**若`/srv/cooker/release`已经存在，不执行解压覆盖**：

```bash
sudo install -d -m 0755 /srv/cooker
sudo test ! -e /srv/cooker/release
```

最后一条没有输出且成功退出才继续；想确认退出码，紧接着输入`echo $?`，应为0。非0表示路径已存在，停下。

```bash
sudo install -d -m 0755 /srv/cooker/release
sudo tar -xzf /var/tmp/cooker-upload-20261010/cooker-config.tar.gz -C /srv/cooker/release
sudo test -f /srv/cooker/release/deploy/compose.yaml
sudo install -d -m 0700 /srv/cooker/data
sudo install -m 0600 /var/tmp/cooker-upload-20261010/system-recipes.json /srv/cooker/data/system-recipes.json
```

成功标志：`/srv/cooker/release/deploy`存在。后续所有Compose命令用这个目录，不用源代码树里的`deploy/d2`路径。

## 7. 创建环境配置：不要把密钥写进镜像

Linux服务器：

```bash
sudo install -d -m 0700 /etc/cooker
sudo install -d -m 0755 /srv/cooker/acme
sudo install -d -m 0755 /srv/cooker/tls
```

`/etc/cooker/production.env`保存密码和运行参数；TLS目录先保持空，不放`.gitkeep`或随便复制证书进去。

### 7.1 自动生成三种不同的秘密

下面脚本只生成本次新环境文件，不打印密码/密钥，已有文件会拒绝覆盖。先把代码中的`填写你的公网IPv4`替换为实际IP，然后整体复制到Linux终端：

```bash
sudo python3 - <<'PY'
from pathlib import Path
import base64, ipaddress, os, secrets

host = '填写你的公网IPv4'
ip = ipaddress.ip_address(host)
if ip.version != 4 or not ip.is_global or ip.is_multicast:
    raise SystemExit('请填写服务器的真实公网IPv4，不是内网地址')
dest = Path('/etc/cooker/production.env')
values = {
    'RELEASE_PROJECT': 'cooker-public',
    'POSTGRES_DB': 'cooker',
    'POSTGRES_USER': 'cooker',
    'POSTGRES_PASSWORD': secrets.token_hex(32),
    'COOKER_JWT_SECRET': secrets.token_hex(32),
    'LLM_CREDENTIAL_MASTER_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
    'DOMAIN': host,
    'PUBLIC_ORIGIN': 'https://' + host,
    'ACME_WEBROOT': '/srv/cooker/acme',
    'TLS_DIRECTORY': '/srv/cooker/tls',
    'POSTGRES_IMAGE': 'sha256:f13f4147c6f96bc09f64860c500f7831023be0da6e8635e7494671ea89a63c6f',
    'BACKEND_IMAGE': 'sha256:0265afe097fc48fe432d8b18b86021dbb4f6fc47ff73e85080f1b32cb3b0375b',
    'WEB_IMAGE': 'sha256:506b5c5fe6d2d0417d9c892402b82248b9e10232020aec1cae18a9bab3653bfd',
    'COOKER_AGENT_EXECUTION_ENABLED': 'false',
    'DASHSCOPE_API_KEY': '',
    'REGISTER_RATE': '3r/m', 'LOGIN_RATE': '10r/m', 'MODEL_RATE': '6r/m',
    'REGISTER_BURST': '2', 'LOGIN_BURST': '5', 'MODEL_BURST': '2',
    'REGISTER_CONCURRENCY': '1', 'LOGIN_CONCURRENCY': '2',
    'MODEL_IP_CONCURRENCY': '1', 'MODEL_GLOBAL_CONCURRENCY': '2',
}
fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as out:
    out.write(''.join(k + '=' + v + '\n' for k, v in values.items()))
print('环境文件已创建，秘密未打印；请安全备份')
PY
```

不需要把上面的随机秘密手工抄到聊天；程序会直接写入服务器文件。若提示文件存在，说明已生成过，**不要删文件重新生成**。丢失加密主密钥后，数据库里保存的用户Key无法解密；JWT密钥也不要随意更换。

### 7.2 每项是什么

| 项目 | 意义 | 你需要做什么 |
| --- | --- | --- |
| POSTGRES_PASSWORD | 应用连接PG的密码 | 脚本自动生成，保留不改 |
| COOKER_JWT_SECRET | 签发/验证用户登录Token | 自动生成，与数据库密码不是一回事 |
| LLM_CREDENTIAL_MASTER_KEY | 加密用户DeepSeek Key的32字节主密钥 | 自动生成，必须安全备份 |
| DOMAIN | 本次入口IP，参数名沿用DOMAIN | 填真实公网IP，不加https、不加端口 |
| PUBLIC_ORIGIN | 浏览器访问的完整HTTPS源 | `https://你的IP`，与DOMAIN一致 |
| 三个IMAGE | 使用哪三个已导入镜像 | 用上表ID，不写latest |
| DASHSCOPE_API_KEY | 你承担的query Embedding调用Key | 第13节用编辑器填入，不写镜像 |
| COOKER_AGENT_EXECUTION_ENABLED | 是否允许实际厨房调用 | 初始化false，完成基础验收后设true |

env格式是`KEY=value`：等号两边不要加空格，不加引号、不出现重复键、不写`export`。加密主密钥结尾可能有`=`，不要删除。

不要另外在shell中export同名部署变量；Compose的shell值可能优先于env文件。本文服务器启动统一使用不带`-E`的sudo和显式env文件，保留干净运行环境。render的安全环境只作用于它自己的验证子进程，不能替代后续启动进程的环境约束。

查看权限而不打印秘密：

```bash
sudo stat -c '%a %U %n' /etc/cooker/production.env
sudo python3 /srv/cooker/release/deploy/scripts/render.py --compose /srv/cooker/release/deploy/compose.yaml --env-file /etc/cooker/production.env
sudo python3 /srv/cooker/release/deploy/scripts/render.py --compose /srv/cooker/release/deploy/compose.bootstrap.yaml --env-file /etc/cooker/production.env
```

期望：权限600、拥有者root；两次“部署参数和Compose渲染检查通过；未启动服务”。不要执行不带`--quiet`的Compose config并把完整输出截图，它可能包含秘密。

### 7.3 如果需要编辑，使用nano

```bash
sudo nano /etc/cooker/production.env
```

方向键移动，直接修改文字；保存按`Ctrl+O`，按Enter确认文件名；退出按`Ctrl+X`。这是Linux文件，不能在Windows记事本改一个同名文件就期待容器收到配置。

先安全备份该文件和主密钥至你控制的加密离线位置。不要放公共网盘、Git仓库或Web可访问目录，也不要上传给我。

## 8. 云安全组和首次HTTP挑战服务

云控制台新建或核对入站规则：

| 端口 | 谁可以访问 | 用途 |
| --- | --- | --- |
| TCP22 | 仅你的管理公网IP或厂商指定管理入口 | SSH管理 |
| TCP80 | 公网 | 证书HTTP-01验证与HTTPS跳转 |
| TCP443 | 公网 | 正式网页HTTPS |
| 8080、5432、5433 | 不对公网开放 | 后端/数据库只在内部网络 |

保留已有SSH通道，不盲目修改默认策略把自己锁在外面。Docker发布端口可能绕过ufw，不能只看`ufw status`就认为安全；同时检查云安全组和实际映射。[Docker防火墙说明](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations)

Linux启动**只提供证书验证文件**的引导服务：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.bootstrap.yaml up -d
```

成功：challenge容器运行；此时没有PG/后端公开业务。浏览器访问`http://你的IP`出现503是**预期行为**，不是坏了。

创建一个没有秘密的测试文件：

```bash
sudo install -d -m 0755 /srv/cooker/acme/.well-known/acme-challenge
printf '%s' 'cooker-http01-ok' | sudo tee /srv/cooker/acme/.well-known/acme-challenge/cooker-probe-20261010 >/dev/null
```

从Windows浏览器打开：`http://你的IP/.well-known/acme-challenge/cooker-probe-20261010`，应显示`cooker-http01-ok`。必须从服务器外检查，服务器上curl localhost成功不代表公网可达。成功后只删除这个自己创建的测试文件：

```bash
sudo rm -- /srv/cooker/acme/.well-known/acme-challenge/cooker-probe-20261010
```

这不删除数据库或证书，只移除可随时重建的公开测试token。

## 9. 申请公网IP的HTTPS证书

IP证书目前支持，但需要Certbot5.4或以上的webroot功能，证书约六天有效，必须设置自动续期。[Let’s Encrypt官方操作说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)

### 9.1 安装并检查Certbot

Linux：

```bash
snap version
```

如果snap不存在，先检查是否已有其他来源Certbot；新机器没有时安装：

```bash
sudo apt-get install -y snapd
sudo systemctl enable --now snapd.socket
```

然后：

```bash
sudo snap install --classic certbot
/snap/bin/certbot --version
/snap/bin/certbot certonly --help all
```

确认版本至少5.4，help包含`--ip-address`、`--required-profile`。旧apt Certbot不一定支持；不要混用多个版本，也不要用nginx自动安装插件替代webroot。

### 9.2 先测试签发，再正式签发

Linux，在同一个终端设置两个**非秘密**变量：

```bash
COOKER_PUBLIC_IP='填写你的公网IP'
COOKER_ACME_EMAIL='填写你的邮箱'
```

先执行测试签发（会访问ACME测试服务）：

```bash
sudo /snap/bin/certbot certonly --staging --non-interactive --agree-tos --email "$COOKER_ACME_EMAIL" --required-profile shortlived --webroot --webroot-path /srv/cooker/acme --ip-address "$COOKER_PUBLIC_IP" --cert-name cooker-ip-staging
```

成功后才执行正式签发：

```bash
sudo /snap/bin/certbot certonly --non-interactive --agree-tos --email "$COOKER_ACME_EMAIL" --required-profile shortlived --webroot --webroot-path /srv/cooker/acme --ip-address "$COOKER_PUBLIC_IP" --cert-name cooker-public
```

正常会报告保存证书路径，正式证书位于`/etc/letsencrypt/live/cooker-public/`。若失败，不反复请求CA，不使用测试证书上线。检查80公网访问、IP正确性、系统时间、Certbot版本；请求频率也受CA限制。

`--agree-tos`代表接受CA服务条款，执行前应阅读其提示/条款。这里的API是证书服务，不是收费LLM调用。

### 9.3 填写证书工具配置

```bash
sudo install -m 0600 /srv/cooker/release/deploy/config/cert-flow.example.json /etc/cooker/cert-flow.json
sudo nano /etc/cooker/cert-flow.json
```

把host换成真实公网IP，其他值与本教程一致：

```json
{
  "project": "cooker-public",
  "release": "/srv/cooker/release",
  "envFile": "/etc/cooker/production.env",
  "host": "填写你的公网IP",
  "certName": "cooker-public",
  "lineage": "/etc/letsencrypt/live/cooker-public",
  "tlsDirectory": "/srv/cooker/tls",
  "alertFile": "/var/log/cooker-cert-alert.jsonl"
}
```

JSON必须保留英文双引号、逗号和括号，没有注释。保存后：

```bash
sudo python3 /srv/cooker/release/deploy/scripts/certificates.py check --config /etc/cooker/cert-flow.json
sudo python3 /srv/cooker/release/deploy/scripts/certificates.py publish --initial --config /etc/cooker/cert-flow.json
```

两次都应显示“证书流程步骤通过”。工具会核对可信链、IP SAN、有效期和私钥匹配，再生成`/srv/cooker/tls/current`。**不要手工把证书随便复制到current或关闭验证。** 第一轮发布时正式web还没启动，这是正常情况。

## 10. 初始化服务器数据库，但暂时不开放网页

Linux。以下操作会创建本次独立生产库/持久化卷，不影响你Windows的数据库。

先只启动PG和后端，让Flyway建立表：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml up -d postgres backend
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml ps
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml logs --tail 100 backend
```

冷启动需等一会儿；PG应healthy，后端日志应完成启动/Flyway迁移，没有反复崩溃。日志可能包含路径等敏感信息，发给我前先脱敏。不要公开完整docker inspect输出，里面可能有密码/Key。

取本次PG容器ID（同一Linux终端执行）：

```bash
COOKER_PG_CONTAINER=$(sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml ps -q postgres)
test -n "$COOKER_PG_CONTAINER"
sudo docker exec "$COOKER_PG_CONTAINER" psql -X -U cooker -d cooker -tAc "SELECT count(*), max(version::int) FROM public.flyway_schema_history WHERE success;"
```

应该显示`15|15`。如果数据库名/角色没有按第7节用cooker，需同步修改命令，不要猜密码。

然后**停止后端**，保证恢复时没有用户注册/后台写入：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml stop backend
```

PG继续运行；正式web还没有启动，bootstrap仍只提供挑战文件。接下来恢复：

```bash
sudo python3 /srv/cooker/release/deploy/scripts/recipes.py check --container "$COOKER_PG_CONTAINER" --database cooker --role cooker --confirm-database cooker --file /srv/cooker/data/system-recipes.json
sudo python3 /srv/cooker/release/deploy/scripts/recipes.py restore --container "$COOKER_PG_CONTAINER" --database cooker --role cooker --confirm-database cooker --file /srv/cooker/data/system-recipes.json
```

重要区别：`check`检查JSON包结构，不证明目标库为空；`restore`才在事务中检查空目标/外键等。如果已有业务数据，恢复会拒绝。**不能清库绕过。restore只对本次空库执行一次，不是每次启动都要执行。** 失败时工具会事务回滚，停止排查，不反复建库。

恢复后只查系统统计：

```bash
sudo docker exec "$COOKER_PG_CONTAINER" psql -X -U cooker -d cooker -tAc "SELECT 'recipe',count(*) FROM public.recipe UNION ALL SELECT 'raw',count(*) FROM public.recipe_source_document UNION ALL SELECT 'search',count(*) FROM public.recipe_search_document UNION ALL SELECT 'ready',count(*) FROM public.recipe_search_document WHERE embedding_status='READY';"
```

recipe/raw/search应与第5节本次包统计一致，READY应与迁移包的有效向量行一致。数据恢复不等于检索质量评测；本轮不重算向量，也不做数据库哈希审查。

## 11. 开启正式HTTPS页面，先验基础功能

Linux。先移除**本次bootstrap项目**，释放80；此命令不带`-v`，不针对正式PG项目：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.bootstrap.yaml down
sudo python3 /srv/cooker/release/deploy/scripts/render.py --compose /srv/cooker/release/deploy/compose.yaml --env-file /etc/cooker/production.env
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml up -d
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml ps
```

浏览器：明确输入`https://你的公网IP`，不要用http、不忽略浏览器证书告警。Linux也可验证：

```bash
curl --fail --show-error "https://填写你的公网IP/api/actuator/health"
```

应该返回包含`"status":"UP"`的健康信息。不可使用`curl -k`让错误假装通过。

人工确认：

- 浏览器无证书错误，首页正常。
- 新用户注册、用户名密码登录、退出、记住我行为正常。
- 能浏览系统菜谱及原文用料，收藏/取消收藏、做菜历史真实保存。
- 刷新和另一个账号隔离正常。
- 公网不直接访问后端8080或数据库5432/5433。

此时厨房模型仍关闭，不能把“暂不能生成”当Key故障。基础通过后再第13节开启。

## 12. 配置自动续期：IP证书不能只申请一次

证书寿命约六天。这里选**本项目systemd模板**，六小时检查一次续期，每小时检查有效期；不同时启用两套重复续期。

Linux先查看已有调度：

```bash
systemctl list-timers --all '*certbot*' '*cooker-cert*'
```

如果存在snap自带` snap.certbot.renew.timer`，确认它确实是本次Certbot续期timer、没有服务其他证书后，只停用这一项：

```bash
sudo systemctl disable --now snap.certbot.renew.timer
```

名字不存在时不要猜其他unit；发实际timer名称给我，避免停用别人的证书服务。

安装明确的五份模板，不复制未知unit：

```bash
sudo install -m 0644 /srv/cooker/release/deploy/systemd/cooker-cert-renew.service /etc/systemd/system/cooker-cert-renew.service
sudo install -m 0644 /srv/cooker/release/deploy/systemd/cooker-cert-renew.timer /etc/systemd/system/cooker-cert-renew.timer
sudo install -m 0644 /srv/cooker/release/deploy/systemd/cooker-cert-guard.service /etc/systemd/system/cooker-cert-guard.service
sudo install -m 0644 /srv/cooker/release/deploy/systemd/cooker-cert-guard.timer /etc/systemd/system/cooker-cert-guard.timer
sudo install -m 0644 '/srv/cooker/release/deploy/systemd/cooker-cert-alert@.service' '/etc/systemd/system/cooker-cert-alert@.service'
sudo systemd-analyze verify /etc/systemd/system/cooker-cert-renew.service /etc/systemd/system/cooker-cert-renew.timer /etc/systemd/system/cooker-cert-guard.service /etc/systemd/system/cooker-cert-guard.timer '/etc/systemd/system/cooker-cert-alert@.service'
```

若有错误先修正，不跳过。通过后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cooker-cert-renew.timer cooker-cert-guard.timer
systemctl list-timers --all '*cooker-cert*'
sudo /snap/bin/certbot renew --cert-name cooker-public --dry-run --deploy-hook '/usr/bin/python3 /srv/cooker/release/deploy/scripts/certificates.py publish --config /etc/cooker/cert-flow.json' --run-deploy-hooks
```

dry-run仍访问CA测试服务，不是完全离线；`--run-deploy-hooks`用于验证成功后发布当前正式证书的hook，不把测试证书上线。它会对web做受控reload，不是重启整个系统。[Certbot续期说明](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)

再检查：

```bash
sudo python3 /srv/cooker/release/deploy/scripts/certificates.py guard --config /etc/cooker/cert-flow.json
sudo openssl x509 -in /srv/cooker/tls/current/fullchain.pem -noout -dates
sudo journalctl -u cooker-cert-renew.service -u cooker-cert-guard.service --since today --no-pager
```

timer应有下一次时间，guard通过。测试时还需核对新TLS连接的实际证书，不只看reload成功。当前告警只在本地日志/journal，不会自动发邮件；你的邮箱填进Certbot不等于项目已接入运维通知。稳定公开展示前应接实际通知/监控，避免续期失败无人发现。

过期/失信时guard会停止指定web，后端/PG和数据卷保留。此时不要改HTTP登录或自签证书绕过；修复签发后离线重新publish，再确认启动web。

## 13. 开启厨房模型与填写API Key

前置：D2-B1配置复审通过、基础HTTPS/注册/菜谱已经通过、用户允许模型费用（本次已允许）。

### 13.1 填你的百炼Key

Linux：

```bash
sudo nano /etc/cooker/production.env
```

只改以下两项，保留其他秘密：

```text
COOKER_AGENT_EXECUTION_ENABLED=true
DASHSCOPE_API_KEY=填你的真实百炼APIKey
```

上面是**文件内容，不是终端命令**。不要复制真实Key到聊天，不用`export Key=...`把Key留进命令历史，也不打印env。你的账户需有text-embedding-v4调用权限，部署沿用1024维；不得换另一个Embedding模型去查询旧向量。

保存后校验：

```bash
sudo python3 /srv/cooker/release/deploy/scripts/render.py --compose /srv/cooker/release/deploy/compose.yaml --env-file /etc/cooker/production.env
```

通过后才重建**后端容器**以应用运行环境。首次上线尚无用户生成时可以直接执行；已有公开用户时先停止新请求、确认没有在途生成，不直接中断他们：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml up -d --no-deps --force-recreate backend
```

这不重建镜像、不删除PG卷。**`docker compose restart`不会更新容器环境变量**，所以不能只restart后就认定Key已生效。后端重建短暂期间API可能502，等健康恢复再测试。

### 13.2 填用户自己的DeepSeek Key

浏览器登录你的线上新账号 → “模型设置” → 选择官方DeepSeek/deepseek-flash → 填你的DeepSeek官方Key → 保存。

这个Key不是JWT密钥，也不是服务器加密主密钥。它会以加密形式保存到用户账户；服务器环境不设置全站共享DeepSeek Key。别人公开注册后，也需自行提供自己的Key。

### 13.3 做第一餐真实测试

浏览器提交：`3个人吃，随机推荐4菜一汤，想吃清淡一点。`

检查两套各4菜1汤、菜谱不重复、人数正确；鼠标选择一套，再请求指导；确认逐菜材料/步骤、整餐备菜、模型补充分区、末尾菜谱参考链接；继续材料确认、城市选择、Mock商品与成本、刷新回看。

这是实际收费LLM和query调用。当前你已接受费用，仍不盲目重复失败请求；先核对原操作状态，避免重复生成。public用户的query也消耗你的百炼Key，项目没有新增长期累计金额硬上限；可以在供应商后台查看用量，启用其实际支持的提醒/限制。

如果出现429，先等`Retry-After`，不要连续点击。它是入口限流，不一定是Key故障。若出现凭证、超时、模型输出校验错误，记录请求编号/状态与脱敏日志给我，不重新注册/换Key/反复retry当通用解决方案。

## 14. 你以后如何看日志、启停和紧急关闭模型

Linux，以下固定路径都指本次生产项目。

查看服务：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml ps
```

看后端最近日志：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml logs --tail 100 backend
```

持续看日志加`-f`；按`Ctrl+C`仅结束观看，不会关闭服务。不要对外公布日志/完整inspect/渲染配置。

紧急先阻止新公网访问：

```bash
sudo docker compose --project-directory /srv/cooker/release/deploy --env-file /etc/cooker/production.env -f /srv/cooker/release/deploy/compose.yaml stop web
```

这不会自动取消已经进入后端的模型任务；等待/核对在途，不能把断开网页当取消收费。再用nano把`COOKER_AGENT_EXECUTION_ENABLED=false`、`DASHSCOPE_API_KEY=`，校验后按第13节重建后端容器。确认禁用生效，再按计划启动web。费用风险紧急时可在供应商侧禁用相关Key，但这是另一项实际管理操作，应注意用户在途请求。

普通维护暂时停服务用明确`stop`，恢复用`start`或`up -d`。不要用`down -v`、`docker system prune --volumes`或手工删除`/var/lib/docker`来“重新启动”。PG数据在卷里，不在镜像里；卷删了，换镜像不会救回来。

资源观察：

```bash
free -h
df -h /
sudo docker stats --no-stream
```

后端硬上限1536MiB、PG1024MiB、web128MiB，共2688MiB，是4GB机器的起点，不是性能保证。出现OOM/restart/连接等待时先控制新流量排查，不直接扩大到挤满整个宿主。

## 15. 备份与回滚，不要等数据丢了才想起

至少备份：生产数据库、`production.env`中的加密主密钥/运行秘密、Certbot账户和续期配置、当前发布文件/镜像清单。用户注册后数据库备份含用户及加密凭证，必须限制访问；不能当作“无秘密菜谱包”公开传输。

Linux示例为本次新建、权限受限的**完整生产备份**；只在确认备份范围后执行，文件名已有时不覆盖：

```bash
sudo install -d -m 0700 /srv/cooker/backups
sudo sh -c 'set -eC; umask 077; cd /srv/cooker/release/deploy; cid=$(docker compose --env-file /etc/cooker/production.env -f compose.yaml ps -q postgres); test -n "$cid"; docker exec "$cid" pg_dump -U cooker -d cooker -Fc > /srv/cooker/backups/cooker-20261010.dump'
```

命令成功、文件非空只证明完成一次导出，不等于恢复演练通过；失败可能留下不完整文件，保留排查，不覆盖当成功。应在独立库/卷演练恢复，不覆盖运行库。备份还需安全保存到服务器外的加密位置；同一磁盘损坏会同时丢库和备份。

后续升级先冻结新镜像ID/部署配置、备份、停止新流量，再切版本。涉及数据库结构或已有真实用户时，回滚不能简单清库：用批准备份恢复到独立新卷，确认后切换，旧卷保留。首次部署不用执行任何“回滚清库”命令。

## 16. 常见问题：看到了什么，先做什么

| 现象 | 首先检查 | 不要做什么 |
| --- | --- | --- |
| ssh连接超时 | 公网IP、22安全组、管理来源/SSH端口 | 公开所有端口或分享私钥 |
| Docker版本没有Server | docker服务状态和sudo权限 | 在Linux安装Docker Desktop |
| render参数检查失败 | IP/HTTPS源一致、必需项、镜像ID、Key开关、文件格式 | 打印整份env发聊天 |
| 80被占用 | 是否bootstrap仍在、已有网站 | kill所有nginx/删除旧站 |
| HTTP首页503 | bootstrap阶段这是正常的 | 开HTTP登录来绕过 |
| certbot签发失败 | 外网HTTP-01、IP、时间、版本 | 无止境重试或使用staging上线 |
| 浏览器证书告警 | IP SAN、正式可信链、有效期 | 点“仍然继续”当验收 |
| 网站502 | backend是否启动/健康、日志、短暂重建期 | 把数据库密码改来改去 |
| 能看菜谱但不能生成 | D2-B1、开关true、百炼Key、用户DeepSeekKey | 只restart容器或重复换Key |
| 数据恢复拒绝 | 目标是否空、V15、包一致、恢复是否已做过 | 删除用户/清库绕保护 |
| 提示429 | 等待、入口请求频率/并发 | 连续点击或绕过安全入口 |
| 重新打开后Key失效 | 主密钥是否丢失/改变、用户Key状态 | 重新生成加密主密钥 |
| 六天后网站不可用 | 续期timer、hook、guard、证书日期 | 回退HTTP或删PG卷 |

## 17. 完成清单与当前实际状态

每项打勾要有你实际看到的结果，不把本地假后端测试当服务器成功：

- [x] D2-B1已复审通过（仅配置/非收费证据）。
- [ ] 最新配置包装配完成。
- [ ] 服务器Ubuntu24.04/noble/amd64/4核4GB资源核验，Docker/Compose实际安装。
- [ ] 三个镜像传输校验、加载ID核对；PG/后端不暴露公网端口。
- [ ] production.env权限600且秘密安全备份。
- [ ] HTTP-01外网可达，正式IP证书签发/可信链与SAN通过。
- [ ] 新库V15迁移、系统三层与已有向量恢复、私有业务数据不迁移。
- [ ] HTTPS注册/登录/记住我/收藏/历史及换账号隔离人工通过。
- [ ] 证书续期、hook、guard调度及真实通知/恢复演练通过。
- [ ] 厨房启用配置与用户Key生效，一餐真实LLM/query测试通过。
- [ ] 材料确认/Mock采购/刷新回看通过，费用承担与供应商用量可见。
- [ ] 备份和独立恢复/回滚演练、磁盘/内存观察完成。

本教程交付时：D2-A/B准备及D2-B1启用配置均已通过限定轻量验收；三个本地镜像的ID/amd64/大小已只读核验，**没有实际save/load、导出、上传、服务器部署或新收费调用**。T18的检索指标仍待独立执行，不能将上线成功当召回率/p95成绩。
