# T14 固定 RAG 推荐派发卡

独立审查回执（2026-10-02）：原任务已完成、会话空闲。主架构全量80项/0失败/0错误/1既有收费跳过与打包通过，但R1～R4待修，T14暂不签收，见 `审查报告-T14-固定RAG推荐.md`。本次只检验，没有派发修复或新任务；专项修复需用户另行具体允许。下方active/inProgress仅为原派发时快照。

日期：2026-10-02。用户要求“检验并推进”；T13已独立签收，本次仅交原后端执行T14。契约为 `s3-recommendation-v1`，冻结不是实现/部署/收费证据。前端F3-B暂不派发，T15/购物/真实调用另行授权。

派发回执：本卡与精确契约输入已成功发送原后端threadId，显式gpt-6-luna/high；快照确认active、turn `01a0fc73-238a-7c12-bcac-59f6b07f97a9` inProgress且无错误。仅证明启动，不代表实现/签收；未创建新会话、未派发前端或其他切片。

## 1. 任务与会话

- 目标：固定两次JSON模型理解/选择连接生产检索与详情，新增同步发送轮次及只读推荐结果，保存可重放的校验后快照。
- 原会话：`阶段二后端实现：T7至T10`，threadId `01a0fb5b-6274-7a43-9fbf-8c923c194a5a`，host local，`gpt-6-luna` / `high`。
- 唯一实现目录 `C:\Users\Omelet\.codex\worktrees\df67\Cooker`，沿用当前分支；保留T12/T13/T5 dirty，不重置、覆盖、提交、迁移或整合工作树。
- 最新架构输入在 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`，仅精确路径只读，不附加或写入该工作树。

## 2. 开始前输入

完整阅读根AGENTS.md、技术文档.md、本卡、`docs/阶段3-T14固定推荐契约.md`、`docs/adr/ADR-010-固定RAG推荐与结果快照.md`、ADR-008/009、T13契约/审查第8节、阶段三计划及后端教学要求。检查df67现有实现/未提交修改；契约冲突或范围外阻塞先报告，不自行替换框架。

T12真实接口ModelGateway、T13状态机Service、T5 RecipeSearchService.search、RecipeReadService.detail为依赖。query Embedding替身只在测试中注入，生产不能用Mock答案或绕过T5入口。

## 3. 允许修改目录与文件

仅在df67：

- `backend/src/main/java/com/cooker/agent/`：Controller/Service/DTO/VO、编排及专属开关配置；保留T12供应商协议/地址/模型/凭证安全基线，确需修正范围外网关行为先报告。
- `backend/src/main/java/com/cooker/policy/`：无持久层的校验/约束合并/固定文本输出。
- `backend/src/main/java/com/cooker/conversation/`、`backend/src/main/resources/mapper/conversation/`：仅有界历史、最新有效约束、推荐快照读写及原子完成的新Service能力；不削弱T13幂等、锁序、120秒deadline或旧HTTP字段。
- 新 `backend/src/main/resources/db/migration/V9__create_conversation_recommendation.sql`；不改V1～V8。
- `backend/src/main/resources/application.yml`：仅新增默认false的 `cooker.agent.execution-enabled`，不得读取/修改本机环境密钥或启动服务。
- 对应agent/policy/conversation专属测试与测试资源。
- 旧测试仅限因迁移总数V8→V9必要的兼容：identity/LlmCredentialHttpPostgresTest、identity/LlmCredentialMigrationPostgresTest、recipe/T9RecipeHttpPostgresTest、recipe/T10CookingHttpPostgresTest、retrieval/service/impl/RecipeSearchPostgresTest。保留原断言强度、逐文件说明，不能以本卡授权其他模块业务修改。conversation专属迁移数量/升级目标同样需精确区分空库与V7/V8升级跑次。
- `docs/工作报告-T14-固定RAG推荐.md`、`docs/技术文档-T14-固定RAG推荐实现详解.md`。

不改pom依赖/全局技术栈、identity/recipe/retrieval生产代码/SQL、前端、根架构文档/共享契约、部署脚本、任何数据集或开发库。边界内的新类仍遵循controller/service/impl/mapper/entity/dto/vo；agent不能跨模块访问Mapper/Entity。

## 4. 核心产出与验收

精确HTTP/JSON/错误/状态/快照规范以T14契约为准，不自行回退旧POST messages/202草案。本次同步POST的新执行201、终态重放200、在途回读202；新GET recommendation仅读PG，旧GET turn不变。开关默认关闭，测试显式开启并用虚构模型/向量替身。

落实KEEP/SET/CLEAR及当前原文证据、null厨具与[]不同、时间未知不满足时间上限、上一有效快照只同用户会话、失败/澄清不覆盖；预算/已有食材只记录意图，不能虚构硬过滤或库存。最多2次网关/1次检索/5候选/3推荐，模型只能选本轮候选ID与可证实理由码，answer由服务器确定性生成。

编排不包事务；登记/开始/原子助手+快照+成功分开短事务；结果回读不能重算或重新收费。90秒为阶段边界软预算，不冒充硬网络取消；保留T12/T5现有单次超时和T13DB期限。模型、用户、菜谱文本/异常/Key不入日志；usage未知为null，网关方法次数不冒充实际计费请求数。

至少完整覆盖契约第7节8组风险矩阵，其中一组HTTP端到端使用真实生产检索Service/真实详情/隔离PG，仅模型与QueryEmbedding为替身。保留T13复合外键精确错误与对照测试，不能删断言/改预期状态来过关。零真实DeepSeek/百炼请求，不使用真实用户Key。

Java21，df67/backend：

```powershell
mvn -o -q test
mvn -o -q -DskipTests package
```

另执行git diff --check与新增文件空白检查；报告总数/失败/错误/跳过、各新类实际执行情况。正规权限不足可申请审批，不强改ACL。T5契约SHA256保持 `4830C16B62539E720E0A6119A32B0E7E717BF239862B41104D83B112B35DD70B`。

## 5. 学习与汇报

中文注释重点解释跨模块Service、约束三态、当前原文证据的能力边界、模型ID校验、JSONB投影与数据库事实、短事务原子提交/失败回滚、重放与费用、有限历史和软预算。教学文档遵循每T标准，真实代码地图/请求流、技术版本、至少2方案取舍、8面试题、5自检/2练习；新知识清单对照Spring Boot/MySQL。用实际测试证明，不虚构质量/时延/规模或保证用户已经学会。

开工先复述六要素：目标、目录、输入、输出、验收、不做项；本次已授权，不重复索要实现许可。交付按既定六项汇报机制，明确未部署、前端/收费验收未做；等待主架构独立复审，不自行签收、不发送其他会话消息或领取下一任务。

## 6. 明确不做

不派发/实现F3-B/T15、工具循环、SSE、异步线程/调度、购物/库存、自定义菜谱、Cloud/MQ/新Agent框架；不读取真实Key、不收费请求、不迁移开发数据库、不部署/重启、不提交合并推送、不整合0f47/E:\Cooker。出现范围外问题停止并报告，不因“推进”持续启动整阶段。
