# D2-A：部署准备技术文档

2026-10-09。唯一实现工作树 5b09，后端来源 5b09、前端只读来源 0f47；未整合 Git。主树冻结卡优先于旧阶段状态。本轮授权限于部署文件、迁移工具及非收费隔离验证，不含真实菜谱导出或 D2-B 上线。

## 层级与职责

| 层级 | 文件 | 职责 |
|---|---|---|
| 运行拓扑 | `deploy/d2/compose.yaml` | PG/后端内部网络，Web独立入口，卷/证书/日志，模型关闭 |
| 构建 | 三个组件 Dockerfile、`.dockerignore`、`images.lock.example.json` | 多阶段构建与固定来源；限制 Docker 上下文 |
| HTTP入口 | `nginx/default.conf.template` | HTTPS、同域代理、静态路由、SSE不缓冲、不自动重试 |
| 配置 | `.env.example`、`config/mock-catalog.yml` | 无秘密参数例与已审定12项Mock映射 |
| 装配 | `scripts/assemble.py` | 锁定双树，白名单复制，拒错源/缺文件/非空输出/目录重定向 |
| 参数校验 | `scripts/render.py` | 显式env，缺值/域名/秘密形状/镜像摘要校验，静默Compose检查 |
| 镜像构建 | `scripts/build.py` | 官方仓库摘要、归档摘要、独立标签及900秒有界超时 |
| 数据迁移 | `scripts/recipes.py` | 11表系统菜谱白名单、版本/绑定校验与单事务恢复 |
| 验证 | `tests/test_tools.py`、`FlywayPrepare.java`、四个 `verify_*.py` | 假参数、PG假数据、独立JAR HTTP、Web自签HTTPS及三服务隔离冒烟 |

业务分层、Service接口、Prompt及前端业务源码均未变化；不新增业务 API、框架或数据表。部署模型保留 Spring Boot 模块化单体：Nginx → Spring Boot → PostgreSQL(vector/zhparser/pg_trgm)。

## 装配与构建思路

装配目录是可审查来源边界。后端仅 main Java、Mapper XML、SQL/TSV、固定应用配置、固定角色规则 properties 和 Maven配置；前端仅构建配置、业务源码与静态资源，排除测试文件和开发依赖。固定角色规则资源属于启动必需输入，缺失必须在装配时拒绝。不会整树复制或读取真实 `.env`。输出清单记录来源及每份文件路径，不用读取 Git 或对数据库做全量哈希。

Docker 上下文进一步默认排除所有内容，再允许必要构建输入。多阶段镜像使编译依赖与运行目录分开；后端运行非root JRE21，Web运行Nginx。镜像锁输入只能是批准仓库的 digest，扩展归档按固定版本、可信HTTPS获取后核验 SHA256。空锁示例有意拒绝构建，不能以 `latest` 或编造摘要替代。本轮两份归档摘要由官方HTTPS下载内容计算，没有声称独立签名验证或供应链完整签收。

配置校验读显式env文件，不搜索开发环境。env采用简单KEY=value、禁止重复键/插值/带引号值；输出只说明成功或失败，不打印秘密。Compose自身也要求必需秘密非空。模型、导入和评测固定关闭，DASHSCOPE_API_KEY为空，Mock配置独立挂载。配置关闭证据与真实收费调用证据分开。

网络上 `data` 和 `edge` 是内部网络。Web还接普通 `ingress` 网络，发布端口；后端另接 `egress`，PG不接公网网络。隔离实测发现只有internal网络时端口没有成功发布，已据此补齐入口网络。仅Web暴露宿主80/443，后端和PG没有ports字段。

## 白名单数据协议

协议外壳固定 schemaVersion1/migrationVersion15，11表及所有列明确列举。导出为只读repeatable-read快照，读取Flyway版本作校验，Flyway记录不进入数据包。只选择 SYSTEM_HOWTOCOOK/PUBLIC/无owner来源，再选择关联原始文档、菜谱、食材、步骤、厨具及搜索投影。必要批次保留ID与安全统计，诊断、错误正文及source_uri清空。source_key限定相对路径。12个已审定Mock食材ID/名称/REVIEWED状态完整保留，禁止按名称猜新ID。

原始 Markdown 与 raw_line 保持原值；数量null继续为null，numeric用文本通过JSON，避免浮点精度损失。向量保留已有值，限定1024维、text-embedding-v4/template1，READY必须有有限数值向量。JSON重复字段、额外表/列、错误绑定和私有投影均拒绝。

恢复要求当前Flyway创建的V15空目标，锁住全部业务表并检查为空，不能仅检查菜谱表。按外键顺序写入，保持数据库约束。全文列tsv是生成列，恢复content后由当前cooker_zh配置重算。所有identity序列用ALTER SEQUENCE RESTART到最大ID+1；不同于setval，该操作能随整个恢复事务一起回滚，避免FK失败留下序列副作用。失败立即断开psql并回滚，数据库错误正文可能含输入记录，因此不输出原始stderr。

工具只通过显式容器内psql执行，不隐式连接宿主PG；确认库名是防误操作提示，不能替代实际操作授权。数据文件限制256MiB；超大包/将来Flyway版本变化需要重新审查协议。本轮仅假数据导出恢复。

## 命令用途及证据层次

完整使用模板见 [部署手册](../deploy/d2/部署手册.md)。`assemble.py`只装配，`render.py`只渲染检查，`build.py`只构建，`recipes.py`分别负责只读导出、离线校验和目标恢复。它们不自动部署或开启模型。

`test_tools.py`用临时目录/假秘密覆盖拒绝边界；`verify_pg.py`新建精确标记容器、随机宿主端口，当前迁移实际建立三个临时库再做假数据恢复及失败回滚；`verify_http.py`用独立装配JAR/假秘密启动新进程与PG，验证health、CSRF、Secure Cookie和V15空库；`verify_web.py`用本轮Web镜像/一小时自签证书验证HTTPS静态页和Nginx模板。`verify_stack.py`以本轮三个镜像的固定本地image ID覆盖运行镜像，独立Compose项目只发布随机Web端口，验证HTTPS代理到后端及PG连接、CSRF和Cookie。所有HTTP/HTTPS验证均不构成真实证书、浏览器业务闭环、真实SSE流或生产签收。所有组件通过精确标记/进程句柄清理，不匹配开发容器或开发PID。

## 与传统 Spring Boot 部署比较

| 方式 | 运行形态 | 需要管理的边界 |
|---|---|---|
| JAR + systemd + 主机PG/Nginx | 运维维护主机JRE及组件版本 | systemd环境秘密、用户权限、备份、主机软件兼容 |
| 本任务Compose | 固定镜像、独立网络/卷/证书挂载 | 镜像来源/摘要、卷持久化、证书续期、应用迁移兼容 |

容器不替代备份、认证、CSRF、费用治理或数据迁移设计；它主要明确可重复运行输入和服务边界。单机方案不需要Spring Cloud、RabbitMQ或Kubernetes。

## 学习清单与面试问答

- 能区分来源白名单、Docker上下文、构建成功、HTTP启动与生产签收；编译成功仍可能缺运行资源。
- 能说明同域HTTPS对Secure Cookie/CSRF的作用；内部网络与入口网络各有什么职责。
- 能解释Flyway负责结构、白名单迁移负责数据，不能覆盖目标Flyway记录。
- 能说明生成全文列为什么重算，向量为什么直接迁移而不收费重建。
- 能解释ID/FK/identity序列为何必须一起验证，以及setval和ALTER SEQUENCE的回滚区别。
- 能说明null/UNKNOWN为何保持，numeric经过JSON为何使用文本。

**问：镜像固定digest后就一定可生产了吗？** 答：还需要平台架构、可信来源、安全维护、完整构建、配置、证书和部署验证；digest只是输入固定。

**问：为什么不直接pg_dump整个开发库？** 答：本次只允许系统菜谱迁移，整库会包含用户、凭证和业务历史，也不能处理目标当前迁移记录的边界。

**问：为什么恢复失败还要查序列？** 答：恢复若使用非事务序列更新，数据回滚后序列仍可能改变；本工具使用事务化DDL并在假外键错误中实测回滚。

**问：为何不能把停止账本当作上线费用保护？** 答：它是限定运行批次的控制机制，未实现开放注册、多人配额、预算、访问策略和生产监控。这些是另行授权的业务与运维决策。

## 验收自检

核对实际双树、文件清单、缺值拒绝、非空输出保护、必需角色资源、源码构建、镜像构建分别有证据；检查临时PG的15迁移、原始/结构化/向量一致、私有表空、序列与FK失败回滚；核对HTTPS模板/入口网络和模型关闭；确认临时组件已清理。未验证项及历史失败见 [工作报告](工作报告-D2-A-部署准备.md)，不能将隔离假数据结果记为生产签收。
