# D2-B 准备：公网 IP、公开注册及离线交付

本文件是后续获准部署时的操作模板。本轮没有连接服务器、安装 Docker/Certbot、创建 ACME 账户、申请证书、save/load 镜像、上传、导出恢复或上线。用户确认 Ubuntu22.04、2核/4GB、暂无域名、允许公开注册；实际架构、磁盘、公网IP、地域、防火墙仍需核验。默认模型关闭、百炼Key为空，采购为Mock；D2-B1允许按部署手册显式开启厨房LLM/query，评测/导入/document索引仍关闭。

## 一、服务器预检与 Docker 安装模板

以下只在后续批准服务器操作后运行：

```bash
cat /etc/os-release
uname -m
dpkg --print-architecture
free -h
df -h / /var/lib
df -i / /var/lib
ss -lnt
dpkg-query -W docker.io docker-compose docker-compose-v2 podman-docker containerd runc 2>/dev/null
```

确认系统确为jammy、CPU架构与本地Linux镜像一致。磁盘需容纳镜像解压、数据库/索引、日志、备份及升级时新旧两版；记录实际镜像大小/菜谱包大小后估算，预留至少一份完整回滚副本和增长空间，不承诺任意磁盘容量足够。发现现有容器或冲突包先评估，不自动删除。服务器仅运行镜像，不在4GB主机编译Maven/Node/扩展。

[Docker官方Ubuntu安装说明](https://docs.docker.com/engine/install/ubuntu/)支持Ubuntu22.04，采用官方apt仓库，按实际架构选择包。准备命令如下，仓库版本须在执行当天核对并固定：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
# 架构与系统代号来自前述实际预检，不猜测为amd64。
printf 'Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: jammy\nComponents: stable\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/docker.asc\n' "$(dpkg --print-architecture)" | sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null
sudo apt-get update
apt-cache madison docker-ce
# 将VERSION_STRING替换为已核对的精确版本，不原样执行占位符。
sudo apt-get install docker-ce=VERSION_STRING docker-ce-cli=VERSION_STRING containerd.io docker-buildx-plugin docker-compose-plugin
sudo docker version
sudo docker compose version
```

不要在本轮执行，不使用curl管道安装脚本。Compose功能和版本执行时记录；本机已验证的是Compose5.5.1，不能据此假定服务器版本。默认使用sudo管理Docker，不自动将普通账户加入具有宿主高权限的docker组。

公网入口只发布Web80/443，SSH限运维来源，PG/后端无宿主端口。云安全组及宿主Docker转发规则都必须核对，IPv4/IPv6分别检查。Docker发布端口可能绕过ufw，不能仅用ufw状态签收；使用Docker官方支持的防火墙链和云安全组，部署时从外部检查实际暴露端口。本轮不修改任何规则。[官方防火墙说明](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations)

## 二、本地镜像 save/load 路线

后端固定5b09、前端只读0f47，沿用装配/构建脚本。目标架构确认后在本地构建相同Linux平台；本轮不重复三镜像构建。仅修改部署配置时，可使用已经签收的Web镜像并挂载当前Nginx模板，避免旧镜像内部模板滞后。配置挂载和镜像版本必须一起冻结。

以下为后续操作模板，标签应换成本次真实获准发布标签：

```powershell
# 只读记录Linux OS/架构、精确本地image ID与Size。
docker image inspect cooker-d2-backend:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}'
docker image inspect cooker-d2-web:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}'
docker image inspect cooker-d2-postgres:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}'
# 获得镜像归档/传输授权后才执行；不把env、菜谱包或证书放入镜像归档。
docker image save --output cooker-linux-RELEASE.tar cooker-d2-backend:RELEASE cooker-d2-web:RELEASE cooker-d2-postgres:RELEASE
Get-FileHash -Algorithm SHA256 -LiteralPath cooker-linux-RELEASE.tar
```

将三个image ID、OS/架构、归档SHA256写入另存的发布清单。只给这个明确归档计算哈希，不做数据库/整仓哈希。服务器获准接收后验证传输完整性：

```bash
sha256sum --check cooker-linux-RELEASE.tar.sha256
sudo docker image load --input cooker-linux-RELEASE.tar
sudo docker image inspect cooker-d2-backend:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}}'
sudo docker image inspect cooker-d2-web:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}}'
sudo docker image inspect cooker-d2-postgres:RELEASE --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

必须与本地清单相同，再把生产env中的三个IMAGE填成 `sha256:<实际本地image ID的64位>`；或者有受控仓库时填写真实 `repository@sha256:<RepoDigest>`。两个概念不同，load后不假定存在RepoDigest，不编造仓库摘要，也不强制开镜像仓库。禁止仅用漂移tag运行。镜像包、无秘密部署配置、另批系统菜谱包、秘密env和证书分别管理/传输；不传本地用户/历史/凭证/账本。镜像仅从可信来源和已核对发布包生成。

## 三、入口与参数

沿用环境变量DOMAIN，但其含义改为入口主机：规范公网IPv4、规范公网IPv6或未来域名。IP必须为全局单播，拒绝私网、回环、链路本地、组播、端口、scheme及模板注入。

| 参数 | 示例形状 |
|---|---|
| DOMAIN | 实际公网IPv4，或不带方括号的规范IPv6，或域名 |
| PUBLIC_ORIGIN | IPv4为`https://实际IP`；IPv6为`https://[实际IPv6]`；域名为`https://域名` |
| ACME_WEBROOT | `/srv/cooker/acme`，HTTP-01挑战文件根 |
| TLS_DIRECTORY | `/srv/cooker/tls`，工具管理的证书bundle根 |

DOMAIN和PUBLIC_ORIGIN必须一致，绝不使用客户端Host构造重定向。假地址仅用于测试，例中1.1.1.1/2606:4700:4700::1111并不是用户服务器。真实env按 `.env.example` 在安全目录创建，JWT及32字节Base64主密钥没有默认值。不要提交/打印秘密。

```bash
python3 deploy/scripts/render.py --compose deploy/compose.yaml --env-file /etc/cooker/production.env
python3 deploy/scripts/render.py --compose deploy/compose.bootstrap.yaml --env-file /etc/cooker/production.env
```

只做静默渲染校验。正式env必须设置实际限流参数或使用默认演示值；参数不是性能成绩。Cookie保持Secure与CSRF，无HTTP登录替代入口。

## 四、IP证书：版本、引导与首次发布

官方已提供IPv4/IPv6证书，IP证书使用shortlived，寿命160小时；本轮只确认资料支持，未签发。采用Certbot **至少5.4** 的webroot模式，5.3有IP参数但不足以支持本流程的webroot。使用certonly，不依赖IP证书的nginx自动安装插件。[IP证书寿命](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability) · [Certbot IP/webroot官方说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot) · [5.4发布记录](https://github.com/certbot/certbot/blob/master/certbot/CHANGELOG.md)

Ubuntu安装路线采用Certbot官方snap说明，先确认snapd和已有Certbot来源，避免混装。以下安装只在后续授权后运行：

```bash
snap version
sudo snap install --classic certbot
/snap/bin/certbot --version
/snap/bin/certbot certonly --help all
```

若未安装snapd，其安装也属于后续服务器授权，不在本轮处理。确认版本>=5.4，实际help有 `--ip-address`、`--required-profile`、webroot。官方安装方法：[Certbot snap](https://certbot.eff.org/instructions?ws=other&os=snap)。本流程直接用`/snap/bin/certbot`避免旧apt版本抢占PATH。

1. 在批准服务器上创建独立ACME根、空TLS根及root独占env/config目录，准备已核对镜像。更新无秘密 `cert-flow.example.json` 为 `/etc/cooker/cert-flow.json`；project、release、envFile、host、certName、lineage、tlsDirectory与真实部署一致。envFile权限600，配置仅root可改。
2. 仅启动`compose.bootstrap.yaml`的challenge服务。只发布HTTP80，提供`/.well-known/acme-challenge/`，其余503，完全不需要证书。没有HTTP注册或临时自签公网登录。先用非秘密测试token验证公网80及云规则，随后删除测试token。
3. 先作staging签发演练，再用独立正式cert-name签发。staging账户/申请也属于实际ACME操作，必须另获允许；staging证书不可信，不能作为生产证书发布。

```bash
# 在后续批准的服务器上填写真实PUBLIC_IP、ACME_EMAIL；本轮不执行。
sudo /snap/bin/certbot certonly --staging --non-interactive --agree-tos --email "$ACME_EMAIL" \
  --required-profile shortlived --webroot --webroot-path /srv/cooker/acme \
  --ip-address "$PUBLIC_IP" --cert-name cooker-ip-staging
# staging验证完成且正式签发另获允许后：
sudo /snap/bin/certbot certonly --non-interactive --agree-tos --email "$ACME_EMAIL" \
  --required-profile shortlived --webroot --webroot-path /srv/cooker/acme \
  --ip-address "$PUBLIC_IP" --cert-name cooker-public
```

HTTP-01需要公网80可达；IP不能依赖DNS-01来证明地址所有权。[官方HTTP-01说明](https://letsencrypt.org/docs/challenge-types/) `--required-profile`选择失败即失败，不退回其他寿命。未来域名切换时改DOMAIN/PUBLIC_ORIGIN、用`-d 真实域名`取代IP参数并签发匹配DNS SAN，重新校验和发布；保持原证书回滚副本，不关闭验证。

4. 正式证书检查并首次原子发布：

```bash
sudo python3 /srv/cooker/release/deploy/scripts/certificates.py check --config /etc/cooker/cert-flow.json
sudo python3 /srv/cooker/release/deploy/scripts/certificates.py publish --initial --config /etc/cooker/cert-flow.json
```

检查使用OpenSSL：至少剩余1小时、私钥公钥匹配、系统信任链及IP/DNS SAN匹配，私钥不输出。发布根必须为空或已被本工具相同项目管理；生成`bundles/<修订>/`，私钥600，原子交换`current`相对链接。Nginx只读挂载整个TLS根，读取current下的证书，避免live/archive链接跨挂载或原子替换单文件无法更新。不要把自签/staging证书加入生产信任库。

5. 停止并移除仅本次bootstrap项目的challenge容器/网络（不删除TLS/菜谱/PG卷），释放80，然后启动正式Compose。启动前再静默渲染。正式HTTP80继续只允许挑战路径，其余重定向到固定HTTPS源。HTTPS使用实际CA验证访问，禁止curl -k或浏览器忽略告警；核对注册、登录、CSRF与Secure Cookie。实际数据迁移和业务上线仍分别另批，本轮未执行这些命令。

## 五、续期、reload、告警与过期拒绝

Certbot保留lineage与renewal配置，不能仅传送两个PEM后丢弃ACME账户/续期状态。webroot保持80可达。每次成功续期的deploy-hook运行`certificates.py publish`：验证新证书 → 新bundle → 原子切换 → `nginx -t` → `nginx -s reload`。语法/reload失败恢复原链接并记录受限告警；旧有效证书继续服务，不自动重启、不终止已接纳长请求。CLI使用同一配置文件旁的flock串行化publish和guard。[Certbot deploy-hook](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)

提供`systemd/`模板：renew每6小时检查、guard每小时检查、OnFailure本地journal告警；安装前同步路径与cert-name，执行 `systemd-analyze verify`，再批准安装启用。若使用snap自带续期调度，需正确配置同一deploy-hook并只保留那一套；若选本模板，部署时先查明并停用对应重复调度。不要未经核对同时启用两套。

```bash
# 后续授权安装调度后，检查状态并演练；本轮不安装/启用/发ACME请求。
systemctl list-timers --all '*certbot*' '*cooker-cert*'
systemctl status cooker-cert-renew.timer cooker-cert-guard.timer
journalctl -u cooker-cert-renew.service -u cooker-cert-guard.service
sudo /snap/bin/certbot renew --cert-name cooker-public --dry-run
```

dry-run仍会访问ACME，本轮没有运行；正常dry-run不运行deploy-hook，若将来演练hook需显式`--run-deploy-hooks`并核对正式lineage、服务权限和实际新连接证书。期望实际新连接看到新证书序列号/有效期，不能只以reload退出码当实际换证成功。

guard对剩余24小时预警，保留仍有效Web；失信、SAN错误、私钥不匹配或过期时，即使本地告警写入失败也停止**指定项目Web**，不停止后端/PG、不删卷；客户端正常TLS验证也会拒绝过期证书。此紧急拒绝与普通限流保留长请求不同。告警写入受限JSONL及journal，必须在上线前接入真实运维通知/监控并演练，当前未接入外部通知。

过期恢复：先修复ACME/挑战或主机时间，正式续期成功，Web已停止时使用`publish --initial`离线发布，校验后获准重新启动Web并严格检查新连接。证书bundle保留回滚，清理策略另批；不要在失败时自动恢复自签、忽略验证、回退HTTP或擅自改变厨房调用配置。

## 六、公开注册保护与4GB资源起点

| 类别 | 默认演示值 | 范围 |
|---|---|---|
| 注册 | 3次/分钟、burst2、并发1 | 同一真实连接IP的POST register |
| 登录 | 10次/分钟、burst5、并发2 | 同一真实连接IP的POST login |
| 收费相关HTTP | 6次/分钟、burst2、单IP并发1、全局并发2 | 旧turns、meals创建、revisions和guides的POST |

`$uri`为规范化URI，URL编码/重复斜线不能生成新桶；客户端X-Forwarded-For不作身份依据，转发时覆盖为真实连接地址。不部署real_ip_header/trust网段。若未来接CDN/负载均衡，必须另审可信代理链，不能直接信任任意客户端头。429为ENTRY_RATE_LIMITED及Retry-After；已有长请求继续，GET历史、CSRF、auth refresh及零模型selection不被这些桶限制。公网NAT共用IP会共享额度，需部署后观察调整。这不是抗攻击测量、用户额度或收费硬上限。[Nginx速率](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html) · [连接限制](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)

Nginx仅计HTTP活跃请求，不能覆盖已返回202的后台作业、重复供应商调用或进程重启后的累计费用。项目Embedding累计配额与长期费用保护尚未实现。用户2026-10-10自愿承担费用并允许本次演示开启厨房LLM/query，覆盖此前禁用前置；默认仍false/空Key，显式开启按部署手册D2-B1节执行。index/import/evaluation仍固定关闭，公开注册与供应商调用分别验收，不把入口限流当硬费用上限。

| 组件 | Compose硬上限 | CPU上限 | 起点配置 |
|---|---|---|---|
| 后端 | 1536MiB | 1.0 | Xms256m/Xmx768m、Metaspace192m、Direct128m、OOM退出；Hikari8/idle2/等待5秒 |
| PG | 1024MiB | 0.75 | shared_buffers128MB、work_mem4MB、maintenance64MB、max_connections40 |
| Web | 128MiB | 0.25 | 7个1MiB限流共享区、日志轮转、连接和请求等待有界 |

总容器内存2688MiB，4GiB宿主约留1408MiB；Java堆以外还有线程栈/native/JIT，PG work_mem可按并发算子成倍增长。不是实际RSS/p95测量；首次冷启动、Flyway、索引查询、并发注册、OOM/restart和数据库连接等待都待实测。不在本轮改swap、内核或现有库；若OOM持续，先停止新增流量并诊断，不自动扩大或恢复收费。

## 上线前自检

核对CPU/磁盘/版本与镜像清单；实际80/443/SSH及IPv6暴露；正式IP/DNS SAN和可信链；自动续期/有效期预警/失败通知/过期拒绝；同域注册登录/CSRF/安全Cookie；真实配置429及长请求；系统菜谱白名单恢复与备份回滚；模型及项目Key默认关闭；启用时核对D2-B1显式配置和用户账户凭证。所有服务器、CA和真实用户验收须后续授权；隔离自签证书＋假后端通过不替代公开上线签收。
