# D2-A：部署准备工作报告

2026-10-09。按0f47冻结任务卡执行；实现目录为5b09，分支codex/kitchen-assistant-u3、HEAD26c33ace4ad22e72350cba65cccb6f528c299917。既有150条dirty按主架构已核对基线保留，本轮未执行Git写入/整合。前端0f47仅作为只读装配来源。

## 交付文件

全部为新增任务文件，共22份；未修改业务源码或旧迁移。

| 目录 | 文件 |
|---|---|
| `deploy/d2/` | `compose.yaml`、`.env.example`、`.dockerignore`、`images.lock.example.json`、`部署手册.md` |
| `deploy/d2/backend/` | `Dockerfile` |
| `deploy/d2/frontend/` | `Dockerfile` |
| `deploy/d2/postgres/` | `Dockerfile` |
| `deploy/d2/nginx/` | `default.conf.template` |
| `deploy/d2/config/` | `mock-catalog.yml` |
| `deploy/d2/scripts/` | `assemble.py`、`render.py`、`build.py`、`recipes.py` |
| `deploy/d2/tests/` | `FlywayPrepare.java`、`test_tools.py`、`verify_pg.py`、`verify_http.py`、`verify_web.py`、`verify_stack.py` |
| `docs/` | 本报告、`技术文档-D2-A-部署准备.md` |

隔离日志/发布装配/假数据位于 `.data/d2-a/`，不进入发布白名单。无秘密Mock配置来自已签收D1-R2配置，保留12个审定映射及虚构商品事实，不构成真实供应商证据。

## 关键实现

提供独立Compose和同域HTTPS入口，PG/后端没有宿主端口，模型/评测/导入关闭；必需秘密缺失拒绝，无change-me默认值。Web单独接入口网络，内部网络用于服务互通。卷、证书只读挂载、日志轮转明确。

发布脚本锁定5b09后端和0f47前端，固定文件与后缀白名单装配，拒错源、缺文件、非空输出及链接祖先；不读取真实.env、Key、账本、备份或缓存。必需运行角色规则资源在装配前检查。构建基础镜像限定官方批准仓库digest，扩展固定版本归档检查SHA256，构建有界超时。

菜谱工具只导出系统来源三层及关联安全元数据，11张表显式列，12绑定必须完整一致。目标当前FlywayV15且所有业务表为空才能恢复；外键不关闭，原ID保留，identity事务化重置，全文生成列重算，原始文本/null与精确numeric保留。已有1024维向量直接迁移，不收费重建。禁止将用户、凭证、业务历史或Flyway记录带入包。

## 已执行验证

| 验证 | 最终结果 | 证据 |
|---|---|---|
| 工具专项 | 14项通过，失败0、错误0、跳过0 | `.data/d2-a/tools-final.log` |
| 假秘密Compose实际渲染 | 通过；缺JWT拒绝 | `.data/d2-a/config-assembly-result.json`及最终QA |
| 实际双树装配 | 补齐后399份白名单文件；已有非空目录拒绝，无禁止路径 | `release-final/release-manifest.json`及最终QA |
| 后端源码独立打包 | 通过；测试未执行（maven.test.skip=true） | `.data/d2-a/backend-build-r2.log` |
| 前端独立安装/类型/构建 | 离线npm ci、type-check、build通过；未执行前端业务测试 | `frontend-install.log`、`frontend-type.log`、`frontend-build.log` |
| 官方输入来源 | 5个固定版本manifest digest查询通过；SCWS/zhparser官方HTTPS归档下载并计算摘要 | `.data/d2-a/images.lock.json`、`*-manifest.log` |
| 临时真实PG假数据 | 5组检查通过，三个库各实际执行15个迁移 | `.data/d2-a/pg-run-1/result.json` |
| 独立JAR隔离HTTP | health UP、CSRF200、Secure Cookie、V15空库通过；模型配置关闭 | `.data/d2-a/http-run-2/result.json` |
| Web Linux镜像 | 构建通过 | `.data/d2-a/image-frontend.log` |
| Web隔离HTTPS | 静态页200、Nginx语法、域名替换、代理变量与SSE模板通过 | `.data/d2-a/web-run-2/result.json` |
| 后端完整Linux镜像 | 最终装配包构建通过 | `.data/d2-a/image-backend-final.log` |
| 新PG完整Linux镜像 | 构建产物已核对；新镜像实际迁移/恢复5组通过 | `image-postgres.log`、`pg-new-image/result.json` |
| 三服务Compose隔离HTTPS | health UP、CSRF200、Secure Cookie、后端及PG无宿主端口通过 | `.data/d2-a/stack-run-1/result.json` |

PG专项具体验证：保留批次/来源/文档/菜谱/食材/步骤/厨具/投影ID和外键，原始Markdown和UNKNOWN/null未推断，numeric长小数精确；vector_dims=1024且生成tsv与当前配置一致，序列下个值正确；源假用户/私有菜谱不导出，目标所有非白名单私有表为空；非空目标拒绝且保留已有数据；坏FK恢复整体回滚，序列也回滚。首轮隔离PG使用已核对本地镜像固定ID5626ad…；第二轮使用本次构建新PG镜像ID f13f4147…，同样5组检查全部通过，是新镜像的实际扩展/迁移/恢复证据。

未以业务自动化通过冒充真实可用。源码打包跳过测试是构建步骤，专项14项和隔离验证单独记录，没有运行全量业务回归。模型使用空平台Key和禁执行配置，未请求DeepSeek/Embedding，未读取真实Key/.env。

## 首轮失败与修正

- 工具首轮8项中7通过、1失败：增加源Flyway版本只读校验后，旧断言把“读取版本”和“导出迁移记录”混同。改为明确断言Flyway不在数据包表/导出聚合中，保留禁止迁移用户数据断言；最终专项14项全通过。
- 首轮HTTP启动失败1次：白名单缺少固定 `meal-role-v1.properties`，已补入并设为必需文件；重新装配、打包、启动通过。失败日志保留在http-run-1。
- 首轮Web专项错误1次：internal网络未发布宿主入口端口。新增独立ingress网络后复验通过。临时组件已清理。
- Web构建有两条InvalidDefaultArgInFrom提示：基础镜像ARG有意没有可用默认值，实际构建由已核对摘要显式提供；不是成功镜像缺失基础镜像。
- 两个镜像下载曾长时间停滞；精确停止docker客户端14852、41500后，核查发现buildx子进程仍继续。新PG镜像实际完成并经新镜像PG专项验证；原命令包装退出码1属于客户端中断，不将其当作未生成产物。旧后端装配包对应buildx子进程23336随后精确结束，最终包后端镜像重新构建退出码0。构建工具据此完善为900秒超时并精确结束本工具创建的进程树；没有停止Docker引擎/业务容器。

## 清理与保留

新建组件都带 `cooker.d2a.verification=<本轮名称>` 标签；删除前复核标签，仅清理新组件及其匿名卷/独立网络，Java使用本轮Popen句柄停止。PG专项容器 `cooker-d2a-verify-1e869f7e4b`，HTTP失败/成功容器 `cooker-d2a-http-0dd3c67eb4` / `cooker-d2a-http-757a0a2c0c`，Web成功容器 `cooker-d2a-web-3b471f8786` 均已清理；首轮Web临时组件也由finally清理。所用随机端口未占8080/5173/5433。第二轮新PG容器 `cooker-d2a-verify-74618f0ea1` 与三服务项目 `d2a-stack-ce2a537ef5`（容器/网络/独立卷）也已清理。三个独立镜像与构建下载缓存作为证据保留，未执行全局Docker缓存清理。

未连接或导出宿主业务库，未启停当前业务后端/前端/业务PG，未上传/购买/安装/部署服务器，未更改ACL/持久环境，未收费调用或跨会话派发。

## 剩余边界与建议

已交付部署准备代码、手册、技术文档与隔离证据；三个Linux镜像和独立Compose三服务假秘密HTTPS冒烟均有实际证据。尚未完成真实菜谱迁移、生产仓库镜像发布/RepoDigest、真实证书/域名、浏览器业务闭环、真实SSE流或生产可用性验收。测试Compose用已核对本轮本地image ID覆盖运行镜像，不伪造生产仓库RepoDigest；发布模板仍要求实际生产digest。

服务器Linux版本/支持状态、域名与证书续期、访问模式、开放注册、多人费用保护和备份策略尚待主架构确认。真实系统菜谱导出和D2-B实际部署分别另批；本轮停止等待主轻量复审。

[部署手册](../deploy/d2/部署手册.md) · [技术文档](技术文档-D2-A-部署准备.md)
