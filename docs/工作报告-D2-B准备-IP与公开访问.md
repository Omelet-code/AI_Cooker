# D2-B 准备工作报告：公网 IP 与公开访问

日期：2026-10-09。结论：本轮配置、工具、手册和非收费定向验证已完成，交付主架构轻量复审；实际服务器部署、可信证书签发和公开注册验收未执行。模型执行及百炼 Key 保持关闭。

## 一、授权与实际基线

权威输入为主树 `docs/派发任务-D2-B准备-公网IP与公开注册保护.md`、D2 阶段计划及 D2-A 轻量验收回执。唯一实现目录 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker`，分支 `codex/kitchen-assistant-u3`，HEAD `26c33ace4ad22e72350cba65cccb6f528c299917`。开工 dirty 共153条，全部保留；没有提交、切换、合并、重置或清理。默认 Git 查询曾提示不在工作树内，显式指定 `--work-tree` 的只读查询成功；未修改 ACL 或 Git 配置。

前端 `0f47` 仅作为既有装配只读来源。用户确认 Ubuntu 22.04、2核/4GB、未安装 Docker、无域名、先用公网IP、允许公开注册；CPU架构、磁盘、IP、实际防火墙和服务器状态尚未核验。登记目录 `E:\Cooker` 不替代实际实现树。

仅修改 `deploy/d2/**` 和本任务两份报告；未修改后端业务源码、数据库迁移、前端业务源码或系统菜谱白名单迁移语义。

## 二、修改文件清单

| 类型 | 文件 | 结果 |
|---|---|---|
| 修改 | `deploy/d2/compose.yaml` | 4GB资源起点、现有Hikari属性、限流参数、挑战目录及当前证书只读挂载 |
| 修改 | `deploy/d2/.env.example` | 入口IP/域名与HTTPS源、HTTP-01目录、限流值、镜像ID说明 |
| 修改 | `deploy/d2/nginx/default.conf.template` | IP/域名HTTPS、HTTP-01、入口429、并发、真实地址覆盖、固定重定向源 |
| 修改 | `deploy/d2/scripts/render.py` | 公网地址/源/限流/必需秘密验证，区分本地image ID与RepoDigest |
| 修改 | `deploy/d2/scripts/assemble.py` | 新手册、首次引导、证书工具与systemd模板纳入固定白名单 |
| 修改 | `deploy/d2/tests/test_tools.py` | 原14项断言保留，补充新必需参数fixture |
| 修改 | `deploy/d2/部署手册.md` | 同步本轮入口、离线镜像与当前证书路径，保留原迁移保护 |
| 新增 | `deploy/d2/compose.bootstrap.yaml`、`deploy/d2/nginx/bootstrap.conf.template` | 无证书首次引导，仅80端口HTTP-01，其余503 |
| 新增 | `deploy/d2/scripts/certificates.py`、`deploy/d2/config/cert-flow.example.json` | 证书检查、受控原子发布、reload失败回滚、有效期守护及受限告警 |
| 新增 | `deploy/d2/systemd/cooker-cert-renew.service`、`cooker-cert-renew.timer` | 指定证书六小时续期检查模板 |
| 新增 | `deploy/d2/systemd/cooker-cert-guard.service`、`cooker-cert-guard.timer`、`cooker-cert-alert@.service` | 每小时守护、本地告警模板 |
| 新增 | `deploy/d2/tests/test_d2b.py`、`deploy/d2/tests/verify_d2b_proxy.py` | 10项新增工具专项、隔离Nginx实测脚本 |
| 新增 | `deploy/d2/IP部署与续期.md` | Ubuntu安装、CPU/磁盘预检、镜像save/load、IP证书和公开访问操作模板 |
| 新增 | 本报告、`docs/技术文档-D2-B准备-IP与公开访问.md` | 结果与证据边界、设计理由、缺口和学习自检 |

本机 `.data/d2-b/` 只保存假秘密、测试证书、隔离日志与白名单装配结果，不纳入源码发布清单或提交。

## 三、实现与设计选择

DOMAIN保留参数名，接受规范公网IPv4/IPv6或域名；PUBLIC_ORIGIN必须与其精确匹配，IPv6源使用方括号。拒绝私网、回环、作用域后缀、协议/端口混入、非法IP与模板注入。Secure Cookie、CSRF和权限代码保持原实现；PG和后端无宿主端口。仅批准的Nginx参数经过envsubst，Nginx自身变量保留。

实际POST路由核对后，注册、登录和旧推荐turns、餐单创建、修订、指导生成分别配置速率/并发，返回429与Retry-After。只读历史、GET CSRF、refresh和零模型selection不计入这些桶。客户端伪造X-Forwarded-For不参与计数，代理将其覆盖为实际连接地址。已经开始的长请求不会因拒绝新请求或安全reload被取消。

证书流程校验有效期、公钥与私钥、可信链及IP/DNS SAN，再发布受控bundle并原子切换current；运行Web先nginx -t再reload，失败恢复旧链接并告警。24小时内到期只预警；证书失效后守护只停止标记一致的Web，即使本地告警写入失败也尝试停止，不动PG/后端或卷。定时检查存在调度间隔：不是在到期瞬间停止；严格验证的客户端会拒绝过期证书。告警仅落受限文件/journal，外部通知渠道需部署时接入。

Ubuntu和CA兼容性已核对官方资料；首次无证书单独开放HTTP-01，staging与正式证书分离，自签证书仅测试。镜像离线传输模板核对OS/CPU架构、image ID和归档SHA256，不把本地image ID伪称仓库RepoDigest。镜像、配置、菜谱数据、秘密和证书分别管理。

后端1536MiB、PG1024MiB、Web128MiB，总2688MiB；按4GiB计算约留1408MiB给宿主。Hikari最大8、最小idle2、等待5秒已核对Boot4.0.8元数据属性。JVM heap768m、Metaspace192m、Direct128m只是起点，线程/native/JIT及PG并发算子仍可能OOM，未做目标机器RSS/p95/压力测试。

## 四、验证与结果

| 验证 | 通过 | 失败 | 错误 | 跳过 | 证据性质 |
|---|---:|---:|---:|---:|---|
| unittest工具专项 | 24 | 0 | 0 | 0 | 14项既有＋10项新增；证书命令/发布/守护为Mock |
| 隔离Nginx专项 | 11组 | 0 | 0 | 0 | 真实Docker/Nginx/本机TLS，假后端、假身份、测试证书 |
| Compose静态渲染 | 2 | 0 | 0 | 0 | 正常包与无证书引导包，假秘密，本地ID形状；不启动完整业务 |
| 白名单装配及输出检查 | 1组 | 0 | 0 | 0 | 409文件，新续期文件齐全，无真实env/缓存/数据导出 |

执行命令：

```powershell
python -m unittest discover -s deploy/d2/tests -p 'test_*.py' -v
python deploy/d2/tests/verify_d2b_proxy.py --output .data/d2-b/proxy-run-2
python deploy/d2/scripts/render.py --compose deploy/d2/compose.yaml --env-file .data/d2-b/fake.env
python deploy/d2/scripts/render.py --compose deploy/d2/compose.bootstrap.yaml --env-file .data/d2-b/fake.env
python deploy/d2/scripts/assemble.py --backend C:/Users/Omelet/.codex/worktrees/5b09/Cooker --frontend C:/Users/Omelet/.codex/worktrees/0f47/Cooker --output .data/d2-b/delivery
```

代理证据 `.data/d2-b/proxy-run-2/result.json`：IPv4 SAN成功/不匹配拒绝、注册登录旧turns限流、当前餐单修订指导及规范化URI429、只读/CSRF/refresh/selection放行、伪造转发地址覆盖、HTTP-01与固定HTTPS源、单IP并发与长请求reload、全局并发2拒绝第三请求、IPv6 SAN与方括号源、未来域名DNS SAN、无证书引导仅挑战开放。Python客户端显式信任测试证书并验证SAN，未关闭验证、未改宿主信任库；TCP仅连127.0.0.1随机端口，示例公网IP未被访问。

复用D2-A核对过的Web镜像ID `sha256:506b5c5fe6d2d0417d9c892402b82248b9e10232020aec1cae18a9bab3653bfd`。隔离项目 `d2b-proxy-d75f37b601` 在finally核对标签后清理；最后按 `cooker.d2b.verification` 标签查询容器和网络均无残留。没有重建三个完整镜像、PG或重复全量业务测试。首轮8组代理证据保留，最终11组使用新目录，没有覆盖旧结果。

未执行项不计作测试框架skip：真实Ubuntu/systemd/OpenSSL发布与过期停服务、ACME账户/可信IP签发续期、Docker/Certbot安装、归档save/load及上传、真实公网注册/CSRF/权限/浏览器、服务器负载/OOM与持久化恢复、真实系统菜谱迁移、收费模型及真实供应商。D2-A原隔离PG与业务HTTP证据保留，不能用本轮假后端替代生产身份验收。

## 五、未完成项、风险与后续候选

没有实现长期生产费用额度系统。现有单次attempt和T18有限批次账本无法保证长期多人项目Embedding累计费用，Nginx活跃HTTP计数也不能限制已返回202的后台作业。模型、Key、导入/索引/评测保持关闭；最小后续候选见技术报告，需独立授权。

NAT共享额度、绕过公开入口的后台任务、可信代理链变化、小时级守护间隔、外部告警未接、4GB内存不足、短证书频繁续期、磁盘增长与回滚空间均须真实部署阶段验证。没有把公开注册准备视为抗攻击或上线签收。

本轮收费调用0、真实秘密读取0、服务器连接0、真实菜谱导出0；未安装宿主软件、申请证书、上传、执行实际镜像save/load、启停既有业务或联系其他会话。两报告与部署手册已同步；主根架构文档不在本轮允许修改范围，交由主架构复审整合。交付后停止，等待主轻量复审，不自动进入部署。
