# T14 固定 RAG 推荐实现详解

本文按本仓库实际实现解释一次菜谱推荐如何从 HTTP 请求走到 PostgreSQL 快照。适合熟悉 Spring Boot、Controller/Service/Mapper 与 MySQL 基础 CRUD 的开发者。阅读时可从 Controller 开始，沿 Service 接口逐层向下，再回到测试确认边界。

## 1. T14 在系统中的位置

```text
HTTP POST
  → RecommendationController
  → RecommendationServiceImpl
      → ConversationTurnService（短事务登记/启动）
      → ModelGateway（JSON 约束理解）
      → RecipeSearchService.search（T5 生产检索）
      → RecipeReadService.detail（真实候选详情）
      → ModelGateway（JSON 候选选择）
      → RecommendationTextPolicy（服务端固定文案）
      → ConversationRecommendationService.complete（短事务原子提交）
  → HTTP 快照响应
```

模块职责仍遵循项目边界：`agent` 编排；`conversation` 管会话、轮次、消息和快照；`retrieval` 只通过 T5 Service 执行搜索；`recipe` 只通过菜谱详情 Service 读取事实；`policy` 执行跨功能的饮食文本和硬约束策略。Agent 不直接访问其他模块的 Mapper 或 Entity。

主要源码入口：

- `backend/src/main/java/com/cooker/agent/controller/RecommendationController.java`
- `backend/src/main/java/com/cooker/agent/service/impl/RecommendationServiceImpl.java`
- `backend/src/main/java/com/cooker/agent/service/impl/ModelGatewayServiceImpl.java`
- `backend/src/main/java/com/cooker/conversation/service/impl/ConversationRecommendationServiceImpl.java`
- `backend/src/main/resources/db/migration/V9__create_conversation_recommendation.sql`

## 2. 从 Controller 看输入边界

`RecommendationController` 只负责接收 DTO、做参数校验、把路径 ID 和请求交给 Service，并设置无缓存响应。它不拼 SQL、不调用模型、不持有 `Entity`。

发送请求由 `SendRecommendationRequest` 表达，包含幂等 `requestId` 与本轮 `content`。路径中的 conversation ID 不能替代身份：Service 使用 `CurrentUserService` 获取登录用户，所有会话与快照查询都要再带用户边界。浏览器 HTTP 测试通过真实注册、登录与 CSRF 流程调用接口。

与 Spring Boot + MySQL CRUD 的对照：普通 Controller 常调用 `service.create(dto)` 后立即返回一条记录；这里 Controller 仍然薄，但 Service 会登记一个可恢复的对话轮次，并在同步执行期间经过多个受控步骤。HTTP 输入仍是普通 DTO，复杂度不应因此塞回 Controller。

## 3. 幂等键与轮次状态

同一会话中的 `(conversation_id, request_id)` 是幂等键。`ConversationTurnService.registerTurn` 在数据库中登记用户消息与轮次；重复键返回原轮次，不重复写第二条用户消息，也不重复调用模型。新轮次再切到 `RUNNING`，完成后成为 `SUCCEEDED` 或 `FAILED`。

这和 MySQL 常见的唯一索引幂等插入相似：

```sql
INSERT ...
ON CONFLICT (conversation_id, request_id) DO NOTHING;
```

关键不是 SQL 方言，而是把“先检查再插入”换成由数据库唯一约束仲裁竞争。两个并发 HTTP 请求带同一键时，只允许一个创建者执行外部工作；另一个读取已登记的轮次状态。

失败、澄清、无匹配与成功的语义不同：只有完成了可推荐结果或明确的无匹配结果，才更新有效约束快照；澄清或失败不能把半成品条件当作下一轮基线。轮次超时由 T13 的持久化 deadline 和恢复逻辑处理。

## 4. 两次 JSON 调用如何受限

T14 是固定两阶段 JSON 流程：

1. 约束理解模型将本轮自然语言映射为 `intent`、`query`、五项约束变更和澄清码。
2. 只有 `RECOMMEND` 才进行一次检索；候选不为空时，再调用一次候选选择模型。

两个阶段都经 `ModelGateway`，不是把供应商 SDK 暴露给业务 Service。`ModelChatRequest` 只带有界纯文本消息与 JSON 模式；网关按当前用户读取其凭证、创建供应商客户端并分类映射错误。调用次数分别有预算：理解最多一次，选择最多一次，不循环重试，不通过工具调用递归扩展步骤。

### 硬约束由 Java 校验

模型只提出结构化建议。`RecommendationConstraintPolicy` 校验 action、值范围和 evidence：

- `KEEP`：保留上一轮有效值；`value` 和 `evidence` 必须为 `null`。
- `SET`：设置当前值；evidence 必须是当前用户消息中的逐字片段。
- `CLEAR`：清除当前值；也必须提供逐字证据。
- `availableToolNames = null` 表示用户没有交代厨具；空数组表示明确没有可用厨具。
- 除此之外的字段不会被“模型推断”静默改写。特别是未知烹饪时间不会变成任意默认值。

该设计类似后端先校验 DTO、再生成 `WHERE` 条件：模型负责把中文提议变成字段，Java 代码负责判定字段是否合法，检索 Service 负责真正执行过滤。不能把硬条件的最终解释权交给自然语言 Prompt。

## 5. 检索与候选来源

Service 构造 T5 的 `RecipeSearchCriteria` 后，调用 `RecipeSearchService.search`。T14 不复制 SQL，也不直接调用 Mapper。查询条件携带当前用户 ID，因此可见范围仍由检索模块执行。

最多取 5 个检索结果，再对每个真实 recipe ID 调 `RecipeReadService.detail`。详情缺失或不可见的结果被丢弃，不能拿搜索投影中的残余菜名伪造事实。存在明确时间上限时，没有估时或超过上限的候选被淘汰。

每个候选携带服务端计算的 `allowedReasonCodes`。第二次模型只能返回候选 ID 和这些理由码；Java 再检查 ID 是否属于当前候选、最多 3 个、无重复、理由非空且允许。输出名称、摘要、耗时和回答文本全部从数据库详情与固定模板生成。模型不能补充数据库里没有的菜或营养结论。

`NO_MATCH` 直接返回固定说明，不再调用选择模型、不放松时间或厨具条件。`CLARIFICATION` 返回固定追问，不把猜测的 query 或条件保存为有效约束。

## 6. 为什么模型调用不放进数据库事务

一次供应商 HTTP 请求可能耗时数秒甚至超时。如果从开始模型调用就持有 PostgreSQL 事务和行锁，慢服务会延长锁等待、占用连接池，还会让同一会话后续写请求堵塞。

本实现把生命周期拆成短事务：

1. 登记轮次、用户消息并提交。
2. 将轮次标为运行中并提交。
3. 在事务之外调用模型、检索和菜谱详情。
4. 再开一个短事务，锁父会话和轮次，核对状态/deadline，原子落最终结果。

最后一步锁序固定为“父会话 → 轮次”，以便和 T13 写入路径一致，避免两个路径用相反次序加锁造成死锁。写入任何项失败时由 Spring 事务回滚。

## 7. V9 快照表与 JSONB

`conversation_recommendation` 把当前有效约束 JSONB 与完整推荐结果 JSONB 按轮次持久化。关系字段仍保存 `turn_id`、`conversation_id`、`user_id` 和快照 `created_at`；父会话表另有 `updated_at`。JSONB 只承载版本化的快照结构，而不是把所有核心筛选字段都移进 JSON。

这与 MySQL 的普通行列有一点不同：PostgreSQL 可以对 JSONB 做类型检查、保留结构化快照，并在需要时提取字段查询。但版本号、主外键、所属用户等核心关系仍用普通关系列和约束表达。数据库保存模型的结果快照，不存 Prompt、凭证或模型自由文本作为可信产品事实。

表的外键组合限制快照必须属于相同用户和会话中的轮次。应用查询也加入当前用户条件，形成数据库完整性和 Service 授权的双重边界。

V9 迁移不重写 V8 对话数据。迁移集成测试先只应用到 V8、写入旧数据，再升级 V9 并核对旧行仍在、新约束可用。

## 8. 快照事务逐句看

`ConversationRecommendationServiceImpl.complete` 是事务边界。按代码顺序：

1. 解析会话和轮次 ID，验证答案长度、JSON 对象形状以及快照总字节数。
2. 获取父会话锁，并确认它归当前用户。
3. 获取轮次锁，确认归属、状态为 `RUNNING`，且数据库时间尚未超过 deadline。
4. 插入 `ASSISTANT` 消息。
5. 插入约束与推荐快照。
6. 仅当轮次状态更新成功时进入 `SUCCEEDED`。
7. 更新父会话触达时间。

这些 SQL 要么一起提交，要么一起回滚。HTTP 测试临时创建触发器拒绝快照写入，之后检查助手消息为 0、快照为 0、轮次为 `FAILED`。这比只验证一个 `@Transactional` 注解更能证明目标写入路径具备原子性。

## 9. 历史上下文和提示词大小

查询最多取 6 个最近的完整成功轮次，也就是最多 12 条用户/助手消息，并按时间正序交给模型。历史只为理解意图提供上下文；有效硬条件从最近的成功快照读取，不从助手自然语言倒推。

在组装模型消息前还有消息条数和总字符数上限。达到限制时只从最旧处移除完整问答对，不裁切本轮输入，也不把任意长历史直接拼进 Prompt。凭证、userId、模型客户端和 Mapper 对象不出现在 Prompt DTO 中。

## 10. 超时与指标边界

执行器在每个外部阶段前后检查 90 秒软预算；持久化轮次还有 T13 数据库 deadline。软预算不是强制中断：如果供应商 HTTP 调用一直阻塞，Java 代码只能等该调用返回后再检测超预算。网关请求本身的连接/读取硬超时要由 T12 配置保证。

响应记录实际模型调用次数、检索调用次数及有供应商 Usage 时的 token 数。`queryEmbeddingRequests` 当前为 `null`，表示没有可验证的内部计数，不能误报为 0。不要把“查询向量服务替身被测试调用”说成真实线上百炼请求。

## 11. 验收命令

在 `backend` 目录执行：

```powershell
mvn -o -q test
mvn -o -q -DskipTests package
```

T14 HTTP 集成测试使用 Testcontainers PostgreSQL 与随机 HTTP 端口，走注册、登录、CSRF、Controller、真实生产检索 Service 和菜谱详情 Service；只替换 ModelGateway 与 query embedding。单元测试覆盖约束变更和执行关闭闸门。测试不调用真实收费 API。

## 12. 面试自检（含追问和参考答案）

### R1～R4 修复补充：理解、解析、复核和时间

第一轮传给真实 `ModelGateway` 的 system Prompt 现在包含完整的 `intent/query/changes/clarificationCodes` JSON 示例，以及五项变更的类型、范围、证据和 `null`/空数组语义。Prompt 是给模型看的格式指引，不是 Java 类型系统；即使 Prompt 完整，模型仍可能输出 `"20"`、`20.9` 或遗漏字段。因此必须用捕获 `ModelChatRequest` 的测试验证实际请求内容，并独立验证响应。

解析分两层：先在 `JsonNode` 上检查字段存在、原始 JSON 类型、重复/未知/尾随内容；然后转换 DTO 并执行 Bean Validation；最后由 `RecommendationConstraintPolicy` 判断 evidence 是否来自当前原文、KEEP/SET/CLEAR 是否合规。若先让 Jackson 把数字或字符串宽松转换成 Java 字段，Bean Validation 看见的已是转换后的值，无法知道原始类型错了。非法理解响应在检索之前失败，非法选择响应在持久化之前失败。

候选最初通过 `RecipeReadService.detail` 取得，仅供模型在最多五项中选择。模型选择可能耗时；期间私有菜谱权限、详情或硬条件可能改变。因此在第二次模型响应之后、成功提交之前，对最多三个选中 ID 再通过当前用户的详情 Service 读取，比较原候选详情，并重新核对时间和理由。读不到、不可见或事实变化就安全失败。这个检查缩短权限竞争窗口，但检查结束到数据库提交之间仍有时间差；若需要严格同时性，须在另一阶段设计跨模块锁或版本机制，不能声称本次已消除全部 TOCTOU。

`RecommendationClock` 默认取单调的 `System.nanoTime()`；测试可注入人工时钟，在 90 秒边界立即推进而不用等待。每次模型、检索、候选详情和最终复核前后，以及 NO_MATCH/CLARIFICATION/UNSUPPORTED 输出和短事务提交前，都检查软预算。软预算只能在阻塞调用返回后阻止下一步；T12 HTTP 超时约束单次请求，T13 数据库 120 秒 deadline 与状态条件更新约束最终写入。三者用途不同，均不能被另一个替代。

服务端固定回答仍会读取数据库菜名和摘要，这些字段也可能包含 URL、指令或诊疗承诺。`RecommendationTextPolicy` 对明显高风险字段采用保守替换，避免将其原样显示；它不是完整的自然语言安全分类器。正常名称与摘要保留，输出仍需遵守一般饮食参考声明。

### R4 残余补修：展示转换必须覆盖完整结果

读者可沿实际方法顺序追踪：`RecipeSearchService.search` 返回候选 ID → `RecipeReadService.detail` 读取详情 → 选择模型返回 ID/理由 → `revalidateSelections` 再核对当前用户权限与事实 → `RecommendationTextPolicy.safeRecommendation` 转换展示字段 → `answer` 从同一安全卡片生成固定中文回答 → `result` 同时交给 `ConversationRecommendationService.complete` 保存和 Controller 返回。候选交给选择模型前也先处理名称/摘要，避免源文本中的地址或指令进入第二轮 Prompt。GET 与同键终态重放只读已保存快照，不再调用模型或检索。

这里区分“菜谱事实”和“可展示文本”：数据库中的 ID、估时及经核验的理由有业务可信度，名称和摘要却可能来自外部 Markdown 或用户上传。存储在关系表中并不会让一段 URL、命令或诊疗宣传自动安全。输出转换保留 ID、时间和理由，遇到本阶段明确识别的危险名称使用固定“菜谱”，危险摘要省略；普通名称/摘要和原本的 `null` 摘要不变。这个规则不改源菜谱、搜索排序或数据库事实。

HTML 转义解决的是浏览器把特殊字符当标签执行的问题；本次内容安全问题是文本即使被转义成普通文字，仍会向用户显示不应推荐的链接或治疗承诺。两者不能互相替代。对整个 `result.recipes` 先转换，再生成 answer 和快照，才能避免“回答安全、推荐卡片危险”的不一致；只在 GET 临时过滤会留下危险历史 JSON，只修前端则其他客户端仍可见。

可用 Spring Boot 常见分层理解：Controller 不自己修补推荐卡片；agent Service 在形成结果前调用 policy，conversation Service 在短事务中保存已经确定的结果对象。快照用于重放，因此它应保存当时的安全展示结果，而不是每次回读重新解释源 Markdown。这也让回读与首次响应一致，且不会因重放再次产生模型/Embedding 成本。

**面试追问：**为什么不在 Controller 返回前只过滤 `answer`？参考回答：完整结果还有 `recipes` 数组，并会持久化；只改一个出口会让 POST、GET 和其他客户端看到不同内容。为什么候选 Prompt 也要处理？参考回答：数据库字段仍是不可信文本，作为模型上下文会扩大注入面。为什么不改变菜谱表里的原名？参考回答：原始事实与展示策略职责不同，修改源数据会破坏导入和检索契约。若旧快照已经保存危险字段怎么办？参考回答：本次只保障新写入快照；历史快照治理需要单独设计和授权，不能假称本次自动修复。

**自检与练习：**检查 `safeRecommendation` 是否保留 `recipeId`、分钟和理由；比较正常摘要、`null` 摘要和危险摘要的输出；在测试中分别断言 POST 卡片、PG 结果 JSON、助手正文、GET、终态重放，并核对后两者没有新模型/检索调用。尝试只在 `answer` 过滤一次，观察完整结果回归为何失败；再尝试只在 GET 过滤，解释数据库快照为什么仍有问题。这些是代码与测试练习，不表示用户已经掌握。

**知识清单与自检：**能否解释 JSON 原始类型与 DTO 类型的差别、Bean Validation 与业务校验的分工、`nanoTime` 为什么适合测耗时、事务外读详情为什么仍有竞争窗口，以及为何一个失败轮次不能留下助手成功消息？对应证据分别在 `RecommendationPostgresHttpTest`、`RecommendationSoftBudgetTest`、`RecommendationConstraintPolicyTest` 与快照回滚用例中。测试以模型和嵌入替身运行，不证明真实供应商响应格式。

**面试追问：**“已经写了严格 Prompt，为什么还拒绝数字预算？”参考回答：Prompt 不能强制模型遵守类型；原始 JSON 中预算必须是十进制字符串，先验类型检查才能阻止 Jackson 转换掩盖错误。“最终复核成功，为什么仍可能撤权？”参考回答：复核与提交并非同一个跨模块锁定事务，仍存在短暂 TOCTOU 窗口；本次以安全失败和缩窗为目标，不宣称线性化可见性。“软预算到了会取消正在执行的 HTTP 吗？”参考回答：不会；它只在调用前后检查，阻塞调用的上限由网关超时控制。

### 1. 为什么不要在外部模型调用时持有数据库事务？

**参考答案：**外部调用耗时不可控，持锁会扩大锁等待和连接占用。先短事务登记，外部调用在事务外，最后短事务提交结果。

**追问：**若模型返回后轮次已过期怎么办？

**参考答案：**最终事务重新锁行并比较状态与数据库 deadline；已过期则拒绝完成，不写成功结果。

### 2. 幂等键应该由谁保证？

**参考答案：**业务层确定键的范围，数据库唯一约束负责并发仲裁；不能只在 Java 中先查再插。

**追问：**重复请求的响应应重新调用模型吗？

**参考答案：**不应。返回已登记轮次/快照；验证模型调用次数在重复请求前后不变。

### 3. 为什么 unknown 与空列表必须区分？

**参考答案：**`null` 是用户未提供信息，空列表可能是明确“没有”；把两者都转成空会改变过滤语义。

**追问：**厨具未知时如何检索？

**参考答案：**保留 `null`，不应用“必须匹配用户厨具”的限制；明确无厨具时则保留空数组并按契约处理。

### 4. 模型输出为什么不能直接作为 SQL 条件？

**参考答案：**模型输出是不可信输入，可能类型错误、越权、编造事实或忽略硬条件。DTO 解析后还必须由 Java 验证 evidence、范围和归属，再调用检索 Service。

**追问：**evidence 为什么要求来自当前消息？

**参考答案：**确保本轮显式改变条件，避免模型从历史或常识推测后悄悄覆盖有效值。

### 5. 检索结果为什么还要查详情？

**参考答案：**搜索投影可能过期，也不是事实展示的权威来源。详情 Service 会重新应用菜谱可见性，并提供服务端事实。

**追问：**详情查不到时怎么办？

**参考答案：**丢弃候选；不能使用投影残值伪装成当前菜谱。

### 6. 推荐快照放 JSONB 的优点和代价是什么？

**参考答案：**适合保存版本化响应结构，读取回放时无需重跑模型；代价是字段级查询与迁移管理不如普通列直接。因此 user、conversation、turn、版本等关键关系继续使用关系列。

**追问：**JSONB 能否代替所有关系表？

**参考答案：**不能。外键、唯一键和核心过滤列应保持明确关系模型。

### 7. 如何证明成功状态和助手消息是原子的？

**参考答案：**检查实现中都处于同一个事务，并通过数据库故障注入让中间写入失败，随后确认所有前序写均不存在。

**追问：**仅检查 `@Transactional` 够吗？

**参考答案：**不够，还需验证代理边界、事务管理器以及失败路径实际经过该 Service 方法。

### 8. 为什么服务端渲染最终回答？

**参考答案：**模型只选择真实候选和有限理由码；名称、描述与限制性话术从数据库和固定模板生成，减少编造和不受控文本。

**追问：**推荐排序仍由谁决定？

**参考答案：**T5 决定召回排序，选择模型只能从有限候选中选择；Java 验证选择，不允许模型创造候选事实。

### 9. 90 秒软预算与 HTTP 超时有什么区别？

**参考答案：**软预算只能在一次同步调用返回前后检测，不能中断阻塞调用；连接和读取硬超时由 HTTP 客户端配置负责，持久 deadline 则由数据库保证最终写入边界。

**追问：**响应中的 `queryEmbeddingRequests = null` 表示什么？

**参考答案：**当前没有可信计数，不等于 0，也不应推测真实供应商费用。

### 10. 应如何测试私有菜谱的用户边界？

**参考答案：**用两个不同用户建立数据，通过真实 HTTP 身份和 Service 搜索/详情读取，断言另一个用户的私有内容既不会进入候选也不能读取快照。

**追问：**是否只靠 Controller 鉴权即可？

**参考答案：**不够。Mapper 查询应带所有者条件，组合外键也应限制数据关系；跨层校验更可靠。

## 13. 自测题与答案

1. `availableToolNames = null` 与 `[]` 各是什么语义？
   - **答案：**未知/未说明；用户明确没有可用厨具。
2. 如果候选选择 JSON 返回一个不在候选集中的 ID，应该怎样处理？
   - **答案：**拒绝整个模型结果，轮次进入失败状态，不写助手成功消息和推荐快照。
3. 为什么有效约束不能从助手上一轮回答中解析？
   - **答案：**自然语言不可作为可信状态源；读取结构化成功快照。
4. 最终短事务锁顺序是什么？
   - **答案：**父会话、再轮次，然后写消息、快照、成功状态和更新时间。
5. 查询向量调用的观测值目前为什么是 `null`？
   - **答案：**T5 内部还没有可用于本响应的可信请求计数；不能用 0 假装测量完成。

## 14. 练习

1. 给候选选择校验增加一个测试：允许理由集合为空或模型重复返回理由时，轮次失败且不产生成功快照。沿用现有 `RecommendationPostgresHttpTest` 和候选 JSON 测试替身。
2. 为 `RecommendationTextPolicy` 增加一个固定文案测试：预算存在时声明价格尚未核实；无预算时不得生成预算数字。将模板约束留在服务端，不把自由回答交给模型。

完成练习后，应能说清楚每个不变量由哪层负责，并指出对应测试，而不只是复述类名。
