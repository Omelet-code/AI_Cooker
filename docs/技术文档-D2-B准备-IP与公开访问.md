# D2-B 准备技术文档：公网 IP 与公开访问

日期：2026-10-09。本文件记录部署准备设计，不代表实际上线。操作模板见 `deploy/d2/IP部署与续期.md`；执行结果见同目录工作报告。业务src、迁移与前端源码未修改。

## 一、配置层级与模块边界

| 层级 | 职责 | 约束 |
|---|---|---|
| Spring Boot已有配置 | Secure Cookie、模型开关、数据库连接池、JVM参数 | 只使用现有配置及Boot4.0.8属性，不新增业务额度机制 |
| Compose | 独立项目/网络/卷、容器资源、入口端口、只读配置挂载 | 仅Web80/443；PG和后端不发布宿主端口 |
| Nginx | TLS、HTTP-01、同域代理、真实地址覆盖、入口429 | 不替代CSRF、身份授权、后台并发或费用账本 |
| 证书工具/systemd | 可信证书验证、原子发布、reload、预警及失效保护 | 仅指定标记一致项目；模板未安装启用 |
| 运维外部配置 | IP/域名、秘密、证书、批准镜像、云安全组 | 不随源码或白名单包携带真实秘密 |

保持模块化单体。采购依旧显式Mock，没有引入Redis、验证码、第二套身份系统、Cloud/MQ或业务计费模块。数据库恢复工具与原白名单语义保持不变。

## 二、IP HTTPS与自动续期

DOMAIN接受规范公网IPv4/IPv6或域名；PUBLIC_ORIGIN为对应HTTPS源，IPv6带方括号。参数校验拒绝地址注入、私网/回环、IPv6作用域、混入scheme/port及不一致源。固定源重定向避免使用不可信Host；测试域名不意味着真实DNS配置完成。

官方公布IP证书支持IPv4/IPv6，采用约160小时的shortlived证书；Certbot 5.4起webroot支持IP，首次流程选择HTTP-01/certonly而不依赖nginx安装插件。依据：[IP证书官方公告](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)、[Certbot IP兼容说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)、[Certbot changelog](https://github.com/certbot/certbot/blob/master/certbot/CHANGELOG.md)。真实服务器签发可行性仍待核验。

首次引导单独Compose项目、仅80端口，无TLS/PG/后端。HTTP-01挑战读取专用webroot，其他路由503，不能先让用户通过HTTP注册。staging与正式证书使用不同名称；正式可信链校验成功、发布证书并释放本项目引导端口后，才允许启动业务HTTPS。HTTP-01需80端口公网可达：[挑战官方说明](https://letsencrypt.org/docs/challenge-types/)。

证书工具调用OpenSSL核对checkend、证书/私钥公钥、系统CA信任及sslserver用途，IP用verify_ip，域名用verify_hostname。生产CLI不提供跳过验证选项。发布新bundle后原子切换相对current链接，Nginx配置检查通过才reload，失败恢复旧链接；只读挂载整个TLS根目录让新bundle可见。配置锁串行化同一配置的发布与守护，配置/目录需受限管理。

六小时续期检查；成功续期才deploy hook。采用snap原生timer或本包timer中的一套，避免双重调度。每小时守护：24小时内到期只预警；无效/过期停止指定Web，不停PG/后端，不删卷。检查存在小时级窗口；客户端严格验证本身会拒绝过期证书。离线初始发布仅允许Web未创建或已停止，便于过期恢复；不自动开HTTP或替换自签证书。模板仅提供本地journal/JSONL告警，必须另接实际运维通知。参考：[Certbot续期与hook](https://eff-certbot.readthedocs.io/en/stable/using.html)、[snap安装说明](https://certbot.eff.org/instructions?ws=other&os=snap)。

## 三、真实路由与入口保护

以下路由来自当前Controller/Service核对，包含 `/api` 上下文路径：

| 方法和路由 | 保护桶 | 默认值 |
|---|---|---|
| POST `/api/auth/register` | 每连接IP注册速率/并发 | 3r/m、burst2、并发1 |
| POST `/api/auth/login` | 每连接IP登录速率/并发 | 10r/m、burst5、并发2 |
| POST `/api/conversations/{id}/turns` | 每IP模型入口速率/并发及全局HTTP并发 | 6r/m、burst2、单IP1、全局2 |
| POST `/api/conversations/{id}/meals` | 同上 | 同上 |
| POST `/api/conversations/{id}/meals/{mealId}/revisions` | 同上 | 同上 |
| POST `/api/conversations/{id}/meals/{mealId}/guides` | 同上 | 同上 |
| GET历史、菜谱、CSRF；POST refresh与selection | 不加入上述桶 | 原后端身份/业务规则仍适用 |

没有虚构凭证verify或用户recipe/search接口。使用方法＋规范化 `$uri` 映射，非匹配key为空，不计入桶；多条模型入口共享IP桶，不能通过换餐单ID重置。URL编码、重复斜线和可选尾斜线有定向验证。客户端XFF不可信，使用 `$binary_remote_addr`，转发XFF/X-Real-IP覆盖为 `$remote_addr`；当前不配置real_ip_header。未来接可信代理需另审地址链。

速率采用Nginx limit_req及burst/nodelay；并发采用limit_conn，二者明确429及ENTRY_RATE_LIMITED、Retry-After20。限流参数只能接受受控整数与r/m或r/s，不支持off或注入；配置校验必须先于启动。NAT同IP共享额度。limit_conn统计当前HTTP请求，不等于TCP连接数或后台工作数；已有长请求继续完成。参考：[limit_req](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)、[limit_conn](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)。

代理连接等待5秒、读写等待95秒、关闭buffering及upstream重试；读等待属于相邻读操作的空闲超时，不是端到端业务总时限。限流不关闭Secure Cookie、CSRF或授权校验；这些生产业务行为仍需真实HTTPS后端/浏览器验收。

## 四、费用保护缺口与独立候选任务

当前餐单Service存在单次attempt上限2，指导生成attempt上限1；现有输入限制和T18批次ledger仍保留。批次账本约束特定显式运行，不能作为永久多人服务累计项目配额。Nginx限制活跃HTTP，202返回后后台作业不再受其并发计数；供应商失败未知usage、后台重复调用、进程重启也不能由入口桶结算。

因此COOKER_AGENT_EXECUTION_ENABLED=false、DASHSCOPE_API_KEY空，普通应用不启索引/导入/评测。公开注册准备与收费能力开启分开；本轮没有新增后端计费逻辑或放开收费。

最小后续候选仅供主架构另批：在模型/Embedding调用前事务化预留额度，按项目/用户、用途和请求幂等键持久化；持久记录预留/已发出/成功/失败usage未知，未知不得自动释放或重试；模型与query/document分别记账，重启不清零；限制后台任务并发而不只活跃HTTP；确保所有调用路径都经过保护并有停止开关与审计。

可讨论的非实施试验起点：单次餐单LLM最多2、指导1、query embedding最多2、document embedding0；项目query每日请求数上限100、后台模型作业全局1。数值必须独立评审和批准，不代表代码已有这些保护，不代表货币费用上限。金额上限需根据实际供应商计价/usage单独冻结，失败未知用量要保守处理；本轮允许收费请求数仍为0。不能先填Key再补配额。

## 五、4GB资源、安装与镜像交付

后端1536MiB/1CPU、PG1024MiB/0.75CPU、Web128MiB/0.25CPU；合计2688MiB，按4GiB约留1408MiB。JVM Xms256m/Xmx768m、Metaspace192m、Direct128m、OOM退出；Hikari最大8、idle2、等待5000ms已从当前Boot4.0.8元数据核对。PG启动参数shared_buffers128MB、work_mem4MB、maintenance_work_mem64MB、max_connections40，仅新部署配置，不修改现有数据库。

这些是预算起点，不是性能结论。Java还需线程栈/native/JIT，PG work_mem可能按查询算子和连接倍增；CPU上限会影响冷启动和p95。目标实测包括真实可用内存/架构/磁盘、冷启动/Flyway、并发注册、查询RSS、连接等待、OOM/restart与磁盘增长。若实际内存按十进制4GB更少，应按机器数据重算预留；本轮不改swap/内核。

Ubuntu22.04官方Docker Engine/Compose apt安装及版本固定模板采用官方仓库，冲突包先评估，不自动卸载已有业务软件。发布端口可能绕过ufw，需同时核对云安全组与实际Docker规则；只有Web80/443对外。依据：[Docker Ubuntu官方安装与防火墙说明](https://docs.docker.com/engine/install/ubuntu/)。

本地构建Linux镜像，服务器仅运行。save/load模板记录三个运行镜像image ID、OS/CPU架构、大小与归档SHA256，load后再次核对ID；本地ID不是RepoDigest，不强迫开仓库，基础镜像仍固定官方仓库摘要。包外秘密/证书和另批菜谱数据单独传输。磁盘估算同时包括镜像、归档、数据库、增长、备份与旧版本回滚，不能只按镜像大小购盘。模板未实际安装、save/load、上传或连接服务器。

## 六、传统Spring Boot对比、学习与面试自检

相较直接运行jar，本方案仍是一个Spring Boot业务进程，但用Compose明确数据库/网络/资源，用Nginx统一TLS/静态前端/入口保护，并以迁移和镜像清单固定可重建版本。避免把公网TLS、IP来源和证书生命周期塞入Controller；关键身份、CSRF与事务仍由业务后端负责。新增复杂度是网络、镜像架构、短证书续期、磁盘/内存预算及回滚，必须通过真实部署演练补证据。

待学习与自检：

- 为什么IP证书需要IP SAN而域名证书用DNS SAN？浏览器严格信任与测试显式信任有何差别？
- 首次没有证书如何完成HTTP-01，为什么不能先开放HTTP注册？160小时证书如何发现续期失败？
- 为什么只读挂载TLS根目录加current原子链接，reload与restart对长连接有何差别？失败回滚如何界定？
- 为什么不能信任客户端XFF？NAT共用额度和可信代理配置变化如何影响计数？
- 为什么活跃HTTP并发2不是后台作业并发2，也不是收费硬上限？202和未知usage如何计入持久额度？
- 为什么Xmx不是整个Java进程内存，PG work_mem也不是全实例固定内存？如何在4GB预算中定位OOM？
- 本地image ID、RepoDigest、归档SHA256分别证明什么？Linux架构不匹配及Docker绕过ufw如何核验？
- 哪些证据属于工具/Mock、隔离Nginx、真实业务HTTPS、真实ACME、服务器压测、生产签收？

## 七、验收边界

本轮24项工具、11组隔离Nginx、两份Compose假秘密渲染、409文件白名单装配通过，失败/错误/测试框架跳过均0。证书OpenSSL发布/守护仅Mock，Nginx TLS实测为严格验证的测试自签证书和假后端。真实服务器、Ubuntu/systemd运行、ACME与正式可信链、公开身份/CSRF浏览器、目标资源、费用配额、真实菜谱迁移均未验证。D2-A历史证据保留，不重复全量构建/PG/业务测试；本轮无收费调用，无真实秘密读取。

两报告与部署手册已同步，根架构文档整合交由主架构。本轮交付后等待轻量复审，不自动进入安装、部署、收费或下一项业务实现。
