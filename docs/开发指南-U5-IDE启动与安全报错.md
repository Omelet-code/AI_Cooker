# U5：IDE 启动与安全报错

日期：2026-10-05。源码目录：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker`。本指南依据当前源码与冻结卡；本次没有在 IDE 点击运行，没有切换现有服务，IDE 联调未验证。

## 1. 导入与运行配置

在 IntelliJ IDEA 导入 `backend/pom.xml`，项目 SDK、运行 JRE 和 Maven Runner 均选择 JDK 21。Maven 工作目录为 `backend`，项目 `.mvn/maven.config` 使用 `.mvn/settings.xml` 和 `backend/maven-repository`；IDE 导入应读取这些项目设置，依赖不应只去全局 `.m2` 查找。缓存齐备时可离线导入；不要用低版本 JDK 解释编译错误。

建立本地、非共享 Application Run Configuration：

| 项目 | 值 |
| --- | --- |
| Main class | `com.cooker.CookerApplication` |
| Working directory | `C:\Users\Omelet\.codex\worktrees\5b09\Cooker\backend` |
| JRE | Java 21 |
| Active profile | `local` |
| 服务 | `127.0.0.1:8080`，API context `/api` |

Program arguments：

```text
--server.address=127.0.0.1 --server.port=8080 --cooker.agent.execution-enabled=false --cooker.recipe-import.enabled=false --spring.flyway.clean-disabled=true
```

`application.yml` 的 `execution-enabled` 默认 `false`。在网页保存用户 DeepSeek Key 不会开启执行。用户准备进行明确允许的真实调用时，才显式将该参数改为 `true`；模型预算和真人调用边界仍沿用主架构安排。本任务没有打开执行或请求供应商。

## 2. 环境变量与秘密

以下是本地开发配置清单，不含真实秘密：

| 环境变量 | 本地值/来源 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/cooker` |
| `DB_USERNAME` | `<现有开发数据库用户名>` |
| `DB_PASSWORD` | `<现有开发数据库密码>` |
| `COOKER_JWT_SECRET` | `<沿用现有 Windows User 稳定值>` |
| `LLM_CREDENTIAL_MASTER_KEY` | `<沿用现有 Windows User 稳定值>` |
| `DASHSCOPE_API_KEY` | `<现有百炼 Key，真实语义检索前才配置>` |
| `COOKER_SECURE_COOKIE` | `false` |
| `SPRING_PROFILES_ACTIVE` | `local` |

秘密只放本机非共享运行配置或既有用户环境中，不写入文档、共享 `.run`、`.env` 或 Git。不要展示值，也不要每次启动生成新的 JWT 密钥/加密主密钥；主密钥变化可能导致已有用户模型凭证无法解密。DeepSeek Key 在网页账户模型设置中保存，不迁移成全局 Key。

已经打开的 IDE 不会自动取得随后新增的用户环境变量。完全退出 IDE 后重新启动，或在本地非共享 Run Configuration 填入已有值。Spring 默认不自动加载 `.env`；Compose 使用 `.env` 与 Java 进程继承环境是不同路径。

PG 5433 是冻结卡指定的现有 Docker 开发库；源码 `application.yml` 默认 5432，`compose.yaml` 的端口也可由环境覆盖，必须显式填写上述 DB_URL，不能把本机另一个 5432 实例当成这份数据。本次测试使用独立 Testcontainers 随机端口，不连接开发库、不执行清库/重新导入。

## 3. 现有 Jar 切换到 IDE

冻结卡说明 8080 当前由主架构 Jar 使用，本任务没有核验或停止它。切换时由用户或另获部署允许的主架构确认精确 PID、命令行、工作树与 Jar 来源，先确认无在途动作，再停止该旧后端。不要按进程名批量杀 Java，不使用 8081 掩盖冲突，不删除 PG 数据卷。源代码修改不会自动更新已经运行的 Jar；IDE 启动后才运行这份最新源码。此次默认目标 Jar 重命名受阻，已改用忽略目录完成隔离打包验证，不能直接将默认目标当作已成功重打包的可执行产物；IDE运行方式不依赖该产物。

前端可继续使用 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker\frontend`。其源码默认 API 模式 `real`，可用 `VITE_API_MODE=real` 明确配置；Vite 5173 的 `/api` 代理目标为 `http://localhost:8080`。前端不直接访问 PG。若重新启动前端，应遵循主架构的部署安排；本指南不代表已经启动或完成真人浏览器验收。

## 4. 安全日志定位

以下 ID 全为虚构示例：

```text
WARN 餐单诊断 event=CANDIDATE_ISOLATED conversationId=901 mealId=902 actionId=903 type=CREATE version=1 stage=PROJECT_CANDIDATE code=UNSAFE_CONTENT exceptionClass=Unsafe recipeId=904 fieldPath=facts.steps[0].content rule=INJECTION
WARN 餐单诊断 event=ACTION_FAILED conversationId=901 mealId=902 actionId=905 type=GUIDE version=1 stage=PROJECT_GUIDE code=UNSAFE_CONTENT exceptionClass=Unsafe recipeId=904 fieldPath=facts[0].steps[0].content rule=HTML
```

用 conversationId/mealId/actionId 对齐页面动作与版本，先看 stage 和 code，再看 recipeId、fieldPath、rule。候选隔离不是整动作失败：继续使用剩余候选，数量不足则正常澄清；已选危险依据的 GUIDE 会安全失败，保留原选择。`INJECTION/MEDICAL/HTML/CONTROL/LINK_SCHEME/TEXT_BOUNDARY` 是受控规则分类。模型输出仍由原策略严格检查；该边界没有可靠的菜谱/具体字段定位时，记录 `recipeId=null fieldPath=model.output rule=OUTPUT_BOUNDARY`，不能猜测责任菜谱。

服务只记录受控元信息和异常类名，不记录异常 message、cause、栈、用户/菜谱正文、Prompt、原始模型响应、Key、Cookie 或 Authorization。不要开启 HTTP wire、Mapper DEBUG 或 Spring AI Prompt 日志。已登记执行失败使用 WARN，登记前认证/参数/状态拒绝仍沿用现有固定 HTTP 错误响应，不因此新增业务字段。

| 症状 | 检查方法 |
| --- | --- |
| `Address already in use` | 核对 8080 的确切旧进程，再按切换步骤处理 |
| 数据库连接失败 | 显式 DB_URL、5433 端口、既有容器健康与账号配置；不删卷 |
| 无法解析依赖/类名 | JDK21、backend Maven 导入、项目仓库与缓存 |
| `AGENT_EXECUTION_DISABLED` | 默认保护开关仍关闭；仅用户准备真实调用时显式启用 |
| `CREDENTIAL_NOT_CONFIGURED` / 凭证解密失败 | 网页账户 Key、稳定主密钥、IDE 的环境继承；不输出真实值 |
| `UNSAFE_CONTENT` | 阶段与规则定位；普通来源链接采用显示投影，危险内容不能删除关键词洗白 |
| `MEAL_FACTS_CHANGED` / 版本冲突 | 原事实/可见性或请求版本变动，保持既有重放与版本规则 |
| `RESOURCE_LIMIT` | 输入或软执行预算超过原上限，不裁剪依据、不自动重试 |

## 5. 面试与自检

1. 原事实与显示投影为何分开？前者负责身份、资格、变更指纹；后者只消除普通来源链接的展示风险。对投影重新哈希不能证明原依据未变。
2. 候选隔离为何先于计数？模型只能从安全的实际候选子集中选择，数量不足不能靠模型造菜或额外检索。
3. GUIDE 为何失败而不换菜？用户已点击选定套餐，替换会违背选择及证据契约。
4. 为什么只记录类名和元信息？供应商异常和 cause 都可能携带 Prompt/秘密；安全定位不需要把它们打印出来。
5. 如何区分源码、构建产物、运行进程和数据库？IDE/Jar 决定运行代码，PG 卷决定持久数据，修改源码不会更新旧进程。
6. 本次哪些尚未验证？IDE 点击启动、8080 切换、真人页面与真实供应商质量；非收费隔离回归不能冒充这些证据。