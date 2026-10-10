# D1-C：检索投影与真实向量重建

日期：2026-10-08。实际实现目录：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker`。依据0f47《派发任务-D1-C-检索投影与真实向量重建》和《阶段D1-R2与D1-C-顺序及收费授权》执行，沿用已经获得的本批document费用授权。

## 1. 结论与停点

**本批真实document向量生成完成：372道菜的1856块全部READY，FAILED/PENDING/缺失均为0；独立只读SQL及同库禁止出站复用核验通过。** 首轮375次HTTP，4批NETWORK失败留下19块；唯一恢复4次HTTP成功补齐这19块，1837个成功块未重发。累计379次HTTP、1875次文本发送，每块最多2次，符合750次HTTP及一次恢复授权边界。

43项最终专项和最新package通过；额外完整非收费回归仍有3项旧V14迁移断言失败，不能称全量通过。可观测成功响应usage为178940 Token，4次失败尝试usage未知，实付账单未取得。事实计数和12审定ID不变、两旧库保留。交付后停等主架构轻量复审；不再追加收费轮次。

## 2. 唯一目标与冻结事实

唯一写库为既有`localhost:5433/root/cooker_rebuild_d1r2_20261007`；只写`recipe_search_document`。独立入口连接前校验精确URL，连接后校验数据库和角色；拒绝旧库、其他端口、附加URL参数和其他工作树入口。

预检事实：菜谱372、原文372、用料3102、食材976、REVIEWED12、用户0、导入批次2，V1～V15成功。每道菜仍关联`1.6.0-a0b14bd`当前原文、解析成功、SYSTEM_HOWTOCOOK/PUBLIC/owner=null。12审定ID为4、10、11、12、35、56、63、105、138、432、486、614。

没有再导入、修改解析器或结构化事实、追加审定、复制旧向量、建库或迁移。旧`cooker`和首版`cooker_rebuild_20261007`保留，当前服务连接不切换，未启停8080/5173。

544行量/单位未知、296道人数未知、964个UNREVIEWED食材及3道零材料组合菜保留。牛油用料1893仍为条件未知量；1896虽然原文明示15 G，但条件未选定、人数未知，不能解释成可靠的已选采购需求。两行不在12个Mock绑定范围内。索引成功不代表全库语义质量通过。

## 3. 零费用预检

真实公共投影Service和原Builder共同生成计划；没有用替身估算真实块数。

| 项目 | 实测 |
| --- | --- |
| 模型/用途/维度/输出 | text-embedding-v4 / document / 1024 / dense |
| 模板/步骤块/字符配置/批量 | 1 / 4个完整步骤 / 4000 / 10 |
| 预期块C | 1856 |
| overview / ingredients / steps / tools | 372 / 369 / 1022 / 93 |
| 首轮HTTP上界B | 375，逐菜Σceil(c_i/10) |
| 累计授权HTTP上界2B | 750 |
| 每菜请求分布 | 369道各1次，3道各2次 |
| 文本总字符 / 最长字符 | 222781 / 1076 |
| 最大保守Token上界 | 2289，UTF-8字节数+32；非精确计量 |
| 初始投影 | 0，缺失1856 |
| 预检真实HTTP尝试 | 0 |

最长输入低于v4单条8192 Token限制。保守上界的公开实现依据及推断边界见[技术文档](技术文档-D1-C-向量索引与收费边界.md)，没有把字符数当精确Token，没有截断步骤或换模型。

首次预检于08:45:29～08:45:35完成，入口测量6.022秒；最新Jar的正式执行前预检于09:03:57～09:04:04完成，测量7.321秒，计划一致，事实前后相同，0请求。以上为北京时间；测量从计划构建后开始，不含Java启动及初始事实读取。

## 4. 最小实现与费用保护

复用`RecipeIndexProjectionService → RecipeSearchDocumentBuilder → RecipeIndexService → DashScopeEmbeddingModel → RecipeSearchDocumentMapper`。recipe内部帮助类仅装配既有事实Mapper和Service，retrieval入口不跨模块重写事实SQL。不启动Boot/Web、Flyway、导入、DeepSeek或query调用。

固定账本目录`.data/d1-c/d1c-20261008`：`plan.json`冻结快照与配置；`attempts.jsonl`追加BEGIN/SEND/SUCCESS/FAILURE/STOP/END，SEND落盘并force后才出站；`run.lock`互斥。跨进程复用计数，禁止替换目录重置额度。每块首轮一次、累计最多两次；首轮一次、恢复至多一次且仅失败/缺失，READY及供应商已成功块不得再次发送。

401/403、参数/模型/维度/响应错误、配额、未知429或重定向立即全局停止，停止异常穿透逐菜catch；明确瞬时限流、网络、5xx可隔离，但连续3批错误停止。结果未知SEND、账本损坏、漂移或数据库写失败保留现场，不自动补齐。返回向量全批检查后逐行写库，后置SQL失败不会把先前READY覆盖成FAILED。

独立HTTP连接10秒、读取30秒，顺序发送、至少1100ms间隔；关闭JDK POST隐式重试、keep-alive和重定向。本机断连/超时/302测试证明单次尝试不暗中重发、第二地址0请求。仅从现有进程/User环境读取凭证并注入本次子进程，不修改持久环境，不输出或保存Key、数据库密码或供应商完整请求/响应。

适配器仅增加document侧有限数值校验、计数、失败类别和安全`usage.total_tokens`观察；query请求行为、Builder、T5筛选/RRF、HTTP业务契约和迁移不变。

## 5. 实际测试与构建

均使用JDK21、离线Maven；常规测试进程清空真实平台Key，`cooker.t6.enabled=false`。PG测试使用随机端口临时容器和专属测试数据库，没有写宿主最终库或开发库。

| 检查 | 实际结果 |
| --- | --- |
| 初轮专项 | 40项，1失败：响应读取超时被RestClientException包装后误分类CLIENT；已修正为检查有限cause类型的NETWORK |
| 修正后专项 | 40项全通过 |
| 扩充403/参数/配额场景后的最终专项 | 43项，失败0、错误0、跳过0 |
| 额外完整非收费回归（按要求排除3个哈希专项） | 391项，387通过、3失败、1跳过；不能称全量通过 |
| 最新离线package | BUILD SUCCESS；打包步骤跳过测试，测试通过证据来自前述专项 |
| 启动脚本语法 | PowerShell AST通过 |

最终专项由账本12、HTTP12、真实临时PG6、原索引4、原适配器1、原Builder1、原检索Service6、原检索PG1组成，共43项；PG涵盖401/403/400/配额429首批停止、瞬时失败一次恢复及SQL后置失败保留现场。

完整非收费回归的3失败均为已有迁移断言仍限定V14，而当前签收基线为V15：`ConversationCompositeForeignKeyPostgresTest:92`、`ConversationHttpPostgresTest:62`、`LlmCredentialHttpPostgresTest:69`。本次不修改范围外认证/会话测试，不降低断言或回退迁移。该回归中的索引/适配器/检索权限与PG测试通过；1跳过来自关闭收费T6的正常边界。已记录残留供主架构复审。

最终专项命令：

```powershell
mvn -o '-Dcooker.t6.enabled=false' '-DargLine=-Dsun.net.http.retryPost=false -Dhttp.keepAlive=false' '-Dtest=BoundedIndexLedgerTest,D1CDocumentHttpTest,D1CIndexPostgresTest,RecipeIndexServiceImplTest,DashScopeEmbeddingModelTest,RecipeSearchDocumentBuilderTest,RecipeSearchServiceImplTest,RecipeSearchPostgresTest' test
mvn -o '-Dcooker.t6.enabled=false' '-DskipTests' package
```

证据：[最终专项](../.data/d1-c/focused-release.log)、[额外全量失败记录](../.data/d1-c/nonpaid-regression.log)、[最新打包](../.data/d1-c/package-final.log)、[正式执行前预检](../.data/d1-c/preflight-final.log)。不同轮次的重跑不相加冒充不同测试数。

## 6. 真实运行与最终只读核验

| 项目 | 首轮execute | 唯一recover | 累计或最终 |
| --- | --- | --- | --- |
| HTTP尝试 | 375 | 4 | 379≤750 |
| 发送文本数 | 1856 | 19 | 1875，唯一块1856 |
| 本轮成功写入块 / 跳过块 / 失败块 | 1837 / 0 / 19 | 19 / 1837 / 0 | READY1856，FAILED0 |
| 成功响应 / 失败响应 | 371 / 4 | 4 / 0 | 375 / 4 |
| 可观测成功usage.total_tokens | 176992 | 1948 | 178940 |
| usage未知尝试 | 4 | 0 | 4，不据此断言免费 |
| 单块发送最多次数 | 1 | 累计2 | 1837块一次、19块两次 |
| 开始～结束（北京时间） | 09:05:24.798～09:13:24.982 | 09:14:08.235～09:14:39.559 | 2026-10-08 |
| 入口记录耗时 | 480.183秒 | 31.324秒 | 两轮合计511.507秒 |

耗时从计划构建后开始，包含索引/核验，未包含Java启动和初始事实读取；两轮之间还有检查与启动间隔，不把两轮耗时之和当连续墙钟时间。Token是观测计量，不是金额；未查询账单，4次NETWORK失败可能已被供应商计费，其Token及金额仍未知。

4批失败均为NETWORK，分别是recipeId131（6块）、304（6块）、313（4块）、348（3块），未连续3批失败、未触发全局STOP，首轮正常END。恢复仅这19块，成功1837块跳过；首轮及恢复BEGIN/END各一次，无未闭合SEND、无停止事件。账本已用完首轮和唯一恢复许可，禁止删除账本、换目录或再执行recover。

入口计划逐块核对与独立只读SQL相互印证：

| 核验 | 最终结果 |
| --- | --- |
| 预期 / 实有 / READY | 1856 / 1856 / 1856 |
| FAILED / PENDING / 缺失 / 多余 / 元数据不符 | 全部0 |
| READY覆盖 / 未覆盖菜谱 | 372 / 0 |
| 非空、1024维、有限值、indexed_at异常 | 0 |
| 模型、维度、模板版本异常 | 0 |
| 当前原文关联、来源和权限错误 | 0 |
| 非空tsvector | 1856 |
| 中文鸡蛋全文匹配 | 261块；仅证明词法列可用，不是检索质量评测 |
| 来源事实前后 | 372菜 / 372原文 / 3102用料 / 976食材 / 12审定 / 0用户 / 2批次 |
| 12审定ID及规范名 | 与D1-R2保持一致 |
| 旧cooker、首版新库、最终库 | 均存在 |

独立SQL于09:15:03在显式READ ONLY事务执行，正常COMMIT，退出0；检查三库存在、迁移、源事实、向量、权限、全文列及12审定ID，不增加原文/全库哈希审计。来源版本仍为`1.6.0-a0b14bd`，源事实和两条条件配方未改变。

09:15:18～09:15:44执行同库禁止出站EmbeddingModel替身核验：原索引Service报告372菜/1856块，indexed0、skipped1856、failed0，累计HTTP计数仍379，真实新增请求0。替身不能生成向量，一旦被调用就停止；本次没有补写假向量。

证据：[首轮真实运行](../.data/d1-c/execute-first.log)、[唯一恢复](../.data/d1-c/recover-once.log)、[费用账本](../.data/d1-c/d1c-20261008/attempts.jsonl)、[独立费用汇总](../.data/d1-c/ledger-summary-final.json)、[独立只读SQL](../.data/d1-c/independent-readonly.sql)、[SQL输出](../.data/d1-c/independent-readonly.log)、[零出站复用](../.data/d1-c/preflight-reuse.log)。运行目录内每次UUID结果JSON保留，未覆盖首轮、失败或预检证据。

## 7. 本轮修改文件

共15份代码/脚本/交付文档，另有专属`.data/d1-c`运行证据。既有dirty已只读核对并保留，不暂存、提交或整合其他任务成果。

| 文件（仓库相对路径） | 类型与职责 |
| --- | --- |
| backend/src/main/java/com/cooker/retrieval/service/impl/EmbeddingProviderException.java | 修改：有限安全错误类别 |
| backend/src/main/java/com/cooker/retrieval/service/impl/DashScopeEmbeddingModel.java | 修改：document记账、响应校验、脱敏分类、usage |
| backend/src/main/java/com/cooker/retrieval/service/impl/RecipeIndexServiceImpl.java | 修改：受控钩子、停止穿透、先校验后写、SQL失败保留现场 |
| backend/src/main/java/com/cooker/retrieval/service/impl/IndexRunControl.java | 新增：显式保护接口，默认NONE |
| backend/src/main/java/com/cooker/retrieval/service/impl/IndexRunStoppedException.java | 新增：发送前后停止边界 |
| backend/src/main/java/com/cooker/recipe/diagnostic/RecipeProjectionContext.java | 新增：recipe内部既有事实Service装配 |
| backend/src/main/java/com/cooker/retrieval/diagnostic/IndexPlan.java | 新增：冻结零费用计划与输入上界 |
| backend/src/main/java/com/cooker/retrieval/diagnostic/BoundedIndexLedger.java | 新增：互斥、持久计数、有界恢复及全局停止 |
| backend/src/main/java/com/cooker/retrieval/diagnostic/DocumentIndexTool.java | 新增：限定数据库、独立入口与安全报告 |
| backend/src/test/java/com/cooker/retrieval/diagnostic/BoundedIndexLedgerTest.java | 新增：12项费用边界测试 |
| backend/src/test/java/com/cooker/retrieval/diagnostic/D1CDocumentHttpTest.java | 新增：12项替身HTTP/网络测试 |
| backend/src/test/java/com/cooker/retrieval/diagnostic/D1CIndexPostgresTest.java | 新增：6项真实临时PG与替身HTTP测试 |
| scripts/d1-c/Invoke-DocumentIndex.ps1 | 新增：独立受控子进程启动脚本 |
| docs/工作报告-D1-C-检索投影与真实向量重建.md | 新增：本报告 |
| docs/技术文档-D1-C-向量索引与收费边界.md | 新增：分层、PG/MySQL对照、计费保护与面试自检 |

## 8. 残留与后续边界

本轮只能证明限定快照的真实document向量生成与落库，不证明新版检索Hit@3/Hit@5、p95、全库人工语义审定或网页/真实模型业务闭环。没有DeepSeek、真实query Embedding/T6、真实商品、切库/服务启停、账号历史修改、前端、T18或Git写操作。没有向其他会话发送新任务或完成消息。

3项旧迁移断言残留与源数据未知项已如实列出。根架构阶段状态由主架构轻量复审后维护；本轮保留15份限定交付，不自行升级其他阶段签收。
