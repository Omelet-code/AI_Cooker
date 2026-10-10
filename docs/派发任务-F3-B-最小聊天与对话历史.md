# F3-B：最小聊天与对话历史

日期：2026-10-02。状态：T14独立复审通过后，按用户“复审后端会话的工作，如果无问题就继续推进”冻结下一切片。仅本前端实现/非收费测试/文档，不自动扩张到部署、收费联调、后端T15、购物、提交合并。精确HTTP字段以s3-conversation-v1和s3-recommendation-v1为准，不新增后端接口。

## 1. 执行对象与目标【学习型】

- 原前端会话：`阶段二前端实现：登录收藏与做菜历史`，threadId `01a0f144-7c62-7a42-a1c0-86c64a3110c3`，host local。
- 沿用用户指定 `gpt-6-luna` / `high`，不创建新会话/工作树。
- 唯一实现目录：`C:\Users\Omelet\.codex\worktrees\0f47\Cooker`，保留全部dirty。后端实现仍在df67，不能复制后端到本树或更改会话cwd。
- 目标：登录用户在Web创建/选择对话，发送自然语言需求，展示T14固定推荐回答、卡片、条件及必要声明；查看已保存的对话消息和选中轮次结果，卡片跳入现有菜谱详情。
- 这是同步固定RAG的最小入口；不是工具循环、SSE、逐token输出或采购闭环。

开工先完整读取根AGENTS/技术文档与下方输入，检查dirty并输出六项边界：目标、允许目录、输入、输出、验收命令、明确不做。已获本次允许，不重复等待。

## 2. 输入与允许修改

必须完整阅读：

1. 本卡、`阶段3-T13会话与轮次契约.md`、`阶段3-T14固定推荐契约.md`、ADR-010。
2. `审查报告-T14-固定RAG推荐.md` 第10节，阶段2前后端接口契约的Cookie/CSRF/续期规则。
3. 现有router/AppLayout、shared/api/client.ts及types.ts、shared/auth/sessionCleanup.ts、identity Store、recipe详情路由与F3-A设置页面接线。

仅允许本树修改：

- `frontend/src/modules/conversation/**`：本模块API/类型、状态逻辑和组件。
- 新增 `frontend/src/views/ChatView.vue`，必要的本任务专属视图；`frontend/src/router/index.ts`、`frontend/src/layouts/AppLayout.vue`只追加聊天入口和相应待确认提示，不破坏已有导航/退出/做菜记录处理。
- `frontend/src/shared/api/types.ts`、`client.ts`只新增可选的每请求timeoutMs支持及已有AbortSignal接线的安全检查；默认10秒、现有CSRF/401单次续期/响应规则保持，不重写认证或全局Mock。
- 本任务所需的局部样式；如需共享样式，限定新增聊天命名空间，不改既有页面布局语义。
- 对应前端测试；`docs/工作报告-F3-B-最小聊天与对话历史.md`、可新增本任务教学/人工验收文档。

不改backend、根AGENTS/技术文档/ADR/冻结契约、身份/菜谱业务、package依赖、Vite代理/端口或运行环境。需要越界先报告，不静默新增组件框架/Markdown渲染器/Mock后端。

## 3. 页面和数据约定

新增受登录保护的 `/chat` 与 `/chat/:conversationId`，主导航增加“烹饪助手”，不修改现有首页重定向。可同一个ChatView展示会话列表和聊天区域，不必单独做复杂历史页面。

- 会话列表可分页，创建可传合法标题或省略使用后端默认“新对话”；防止创建连点重复。新建操作网络结果未知不能自动再次创建，不声称幂等（创建接口没有requestId）。
- 接口：POST/GET `/conversations`；GET `/{id}`、`/{id}/messages`、`/{id}/turns/{turnId}`；POST `/{id}/turns`；GET `/{id}/turns/{turnId}/recommendation`。都经现有请求层加 `/api`，不得发布或使用旧messages/stream/events发送草案。
- ID保持字符串，不能parseInt/Number后拼路由；请求不带userId/Key/地址/模型名。Page为items/page/size/total；会话排序updatedAt DESC，消息分页sequence ASC且第一页最早，不能把第一页面假称最新消息。可读total后定位最后一页，提供明确前后页按钮，不自动加载无限历史。
- USER和ASSISTANT按sequence展示，失败轮次可能只有USER，不伪造助手答复。消息和answer作为普通文本渲染，不使用v-html或自动链接。历史按PG回读，刷新/重进页面不重发原POST。
- 历史每条message已有turnId；按选中轮次读取状态/结构化推荐结果，result=null是合法。为避免无限N+1，默认只读当前选中的轮次，其他轮次用户主动点选；不为全量历史逐一预取推荐。
- RECOMMENDED卡片最多3道，以已校验name/summary/estimatedMinutes/reasonCodes展示；null不填0。RouterLink只用本地recipe-detail路由与合法recipeId，不采纳模型/菜谱自由URL。
- NO_MATCH、CLARIFICATION、UNSUPPORTED按服务器answer和outcome显示；明确预算未验证、已有食材不是家庭库存、偏好不保证、仅一般饮食建议。理由不能夸大“命中”为清淡认证/过敏安全，不能声称预算满足。
- FAILED以turn.error显示安全中文提示；HTTP201/200并不保证业务成功，必须检查turn.status。CREDENTIAL_NOT_CONFIGURED可引导既有模型设置页，503执行关闭提示后端未启用，不跳Mock、不自动保存Key或启用后端。
- 指标如展示，分网关尝试/检索调用与供应商计费；usage/queryEmbeddingRequests=null为“未知”，不能填0、伪称实际费用/实时延迟评测。无需制作复杂指标仪表盘。

## 4. 发送、未知结果和生命周期

每次用户明确发送时生成一个UUID和不可变原始content，与当前用户/会话绑定；正文非空白、最多4000 Unicode码点，前端用码点计数，实际传输保留原文。发送中禁用同会话连点；编辑下一条草稿不能改变本次固定payload。只在用户明确发起另一条消息时生成新UUID。

同步T14可能超过通用10秒：只为发送turn请求设置 `timeoutMs=125000`，普通GET及原业务默认仍10秒。这个值是浏览器等待策略，不保证后端硬125秒终止；前端abort/离开不代表模型或事务被取消。使用现有apiClient共享身份/CSRF能力，不新建独立认证客户端。

网络错误、超时、中断、无法解析响应或安全500可能已在服务器登记/执行：保持本次requestId+原文为“结果未知”，不自动重发POST、不生成新键重试。若已知turnId，可通过GET核对；已知PENDING/RUNNING/202可有界GET轮询（最多30次且墙钟预算120秒，达到任一限制停止），取消路由/账号变化后的轮询。GET只读，不启动恢复或模型。达到上限仍未终态时显示“尚未确认”，不伪装FAILED/SUCCEEDED。

未知且没有turnId时，可提供用户明确点击的“核对/重试本次发送”，仍发原requestId+原文，不自动循环。点击前解释：服务器若已接收，会回读同一轮；若之前未接收，将开始执行并可能产生模型费用。既有FAILED重放不会重新执行，需要用户看清原因后明确发起新轮，不自动换键。

CONVERSATION_BUSY/IDEMPOTENCY_CONFLICT等409不自动改键，给中文说明；不要通过点击刷新/切换历史触发POST。现有CSRF/401请求层的有限重放继续使用同一payload；不额外增加供应商/业务重试。

会话/用户变化时递增请求代次并取消未完成客户端等待，所有响应/定时器校验当前用户、会话、代次，旧响应不能回填新账号。等待CSRF/续期期间已取消的旧发送也不能随后发出；shared层处理已abort的signal，不把取消说成服务器终止。

注册现有sessionCleanup，退出/身份最终失效清理会话消息、选中结果、草稿和待确认payload；账号切换同样隔离。未确认发送离开页面前给明确提醒，放弃只放弃本地等待/重试信息，后端可能仍在执行；与现有做菜待确认提示共存。不得持久化Key/JWT、完整聊天或待发送正文到Web Storage；跨刷新恢复从后端历史/轮次读取，不实现额外浏览器长记忆。

## 5. 测试、产出与闸门

必须至少覆盖实际页面共用的状态逻辑和API接线，不只测试未被视图使用的孤立辅助函数：

1. 会话/消息的正常、加载、空、错误、稳定分页与选轮历史回读；路由保护、卡片跳现有详情。
2. 四种outcome及FAILED/result=null、503未启用、Key未配置、合法null和必要notice；201失败不能当成功。
3. 一次发送固定UUID/原文、连点保护；超时/网络/非法响应进入未知；显式核对保持原键/内容，未知不自动POST；失败新轮只能用户发起。
4. 202在途有界GET、终态停止、额度停止；刷新/重选会话/历史GET不重发；未知重试提示收费风险。
5. route/账号切换、退出/最终401后的旧响应与定时器不回填；在CSRF或续期等待时取消不发旧POST。
6. shared请求超时override只影响本次发送，旧业务默认10秒，CSRF/续期重放和取消行为原回归不退化；虚构fetch计数区分GET/POST。
7. 普通文本/恶意HTML不以v-html执行，未知自由URL不变购买入口；现有登录/收藏/做菜/设置测试保持。

测试使用虚构fetch/模块替身，无真实DeepSeek/百炼请求，不读取用户Key。runtime默认real，失败不退Mock；本次无需新增开发Mock服务。若现有显式mock模式不支持聊天，显示清楚开发限制，不将测试fixture当真实后端。

在本树frontend执行：

```powershell
npm run test
npm run type-check
npm run build
git diff --check
```

交付代码、测试、工作报告及待人工清单，按六项汇报机制报告文件、设计、实际测试、未完成、风险和文档。解释同步POST与GET状态、业务失败与HTTP失败、幂等和“结果未知”、request代次及超时选择；给简洁面试追问/自检，不宣称用户已掌握。

后端T12～T14只在df67完成实现，本轮没有整合/部署，不能假设开发8080已经有新接口。前端可以完成真实API接线与非收费测试，但要如实把浏览器真实后端/PG/真实模型联调列待验，不自行启动替身服务冒充真实。后续主架构先复审，再单独取得部署和限定调用数的收费联调允许，提供用户浏览器清单。

明确不做：不改/复制后端、不启动部署/迁移/重启开发服务、不调用真实模型或Embedding、不提交合并推送；不做T15工具循环、SSE/F3-C、采购/F3-D、库存或自定义上传，不新增依赖/页面框架、不改已有登录取舍。不以F3-B实现完成宣称阶段三/全产品完成。完成后停止等主架构复审，不领取下一任务。

## 6. 最小业务的含义

F3-B接线完成后，具备“用户配置模型Key → 新建对话 → 表达需求 → 约束提取/混合检索/受限选择 → 推荐回答与菜谱卡片 → 查看详情 → 保存并回看历史”的最小推荐交互。经授权部署、真实模型及浏览器验收后，才称为真实可用的最小烹饪Agent。已有收藏/做菜记录可从详情继续使用，不新增业务接口。

缺货清单、具体商品和预算核实仍是后续3B；SSE/Tool Calling是后续增强，不是完成此最小闭环的必要前提。清淡等是语义偏好，已有食材/预算是未核验声明，不夸大个性化程度。

## 7. 派发状态

已成功发送原前端threadId，显式 `gpt-6-luna/high`，工具无错误；紧接快照确认active，turn `01a0fd1c-6254-7910-b8c1-fc0de9a2d3b7` inProgress、error=null。未新建会话/工作树，原后端不领新任务。当前等待前端交付和独立复审，不代表实现完成、开发后端部署或真实模型/浏览器签收。
