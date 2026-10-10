# UF2 工作报告：餐单选择与指导回看

日期：2026-10-04

## R1～R3 修复交付（最新，2026-10-05 收尾）

本次沿用 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`，分支 `codex/agent`、HEAD `572084b`。修复卡仅授权 R1～R3。初次 164 项测试遗漏了合法递增版本和同会话跨餐反例；本次将架构历史探针改写为常规测试中的正确行为断言，修复前 10 项反例失败，修复后通过。原 `docs/review-probes` 作为历史证据保留，不计入本次业务通过数。

- R1：REVISE 的动作版本按 `expectedVersion + 1` 检查，SELECT/GUIDE 仍按原版本检查。修订替身也改为递增版本；终态旧结果不覆盖已知更高版本，合法修订可展示新版本并关闭 pending。
- R2：增加独立餐单请求代次、餐单读取控制器和原目标记录；换餐、新餐或刷新会提示未确认操作并取消本地等待。迟到 SELECT/GET/GUIDE 不覆盖新餐，也不接续旧点击链。选择后 GET 必须精确匹配原版本/选项才能请求 GUIDE；SELECT 历史重放先 GET 当前餐单，避免旧快照清除已保存指导。
- R2：核对入口限定当前账号、原会话和原餐单；未知 CREATE 无餐单 ID 时需回到原新餐输入区。其他上下文只提供返回入口。未确认操作会阻止同目标另起新键，原键核对不自动触发 GUIDE。
- R3：`semanticSearchCalls` 改为 `number | null`，运行时允许未知 `null`，仍拒绝负数、字符串等无效值。合法 FAILED 能正常结束 pending，不把未知调用量改写成 0。

本次修改 10 个文件：

- `frontend/src/modules/conversation/kitchen/{state.ts,types.ts,validation.ts}`
- `frontend/src/views/KitchenAssistantView.vue`
- `frontend/tests/{kitchen-state.test.ts,kitchen-view.test.ts,kitchen-fixtures.ts}`
- 新增 `frontend/tests/kitchen-repair.test.ts`
- 本工作报告与 `docs/技术文档-UF2-餐单与指导状态.md`

最新验收在 0f47/frontend 执行：`npm run test` 为 **26 文件/177 项通过**；`npm run type-check`、`npm run build` 通过。UF2 四个专属测试文件定向运行 **35 项通过**，覆盖本次反例、四菜一汤两套完整事实、材料字符串及指导来源。`git diff --check` 通过（只有既有 LF/CRLF 提示）；本次涉及 10 个文件空白检查通过。前端/文档文件哈希与修复开始时基线比较：9 个既有文件改变、1 个专属测试新增、0 个删除，其他 187 个文件相同，变化均在白名单内，既有 dirty 保留。

仅使用隔离替身及页面渲染测试，未执行真人浏览器点击、真实后端 HTTP 联调、供应商请求或开发部署。当前为修复交付，等待主架构轻量复审；不据自动化通过宣告 UF2 整体签收或推进 U5。

范围：按 UF2 冻结任务卡完成前端页面接线；未改后端、共享请求/认证、UF1 档案、契约或根架构文档。未部署开发库、未调用模型/Embedding、未执行 Git 提交或合并。

## 交付内容

- 新增 `frontend/src/modules/conversation/kitchen/`：餐单类型、严格运行时校验、HTTP API、Pinia 状态及餐单卡片/指导面板。
- 新增 `frontend/src/views/KitchenAssistantView.vue`，提供会话列表、新建会话/本餐、自然语言需求、显式人数、修订、方案选择、指导生成与历史回看。
- 在既有路由中增加受保护的 `/kitchen` 与 `/kitchen/:conversationId`；旧 `/chat` 和 `ChatView` 路由保留。
- 布局增加“小厨餐单”入口、本模块未确认动作提示、浏览器离开提示和退出确认。
- 新增餐单 API、状态机、页面测试及共享测试夹具。
- 新增本报告与[UF2 技术学习说明](技术文档-UF2-餐单与指导状态.md)。

## 关键行为与设计

- 复用现有会话列表/详情/创建接口；餐单与指导使用独立 API，不伪造成聊天消息。人数默认值只读 UF1 档案；未填写人数时不把档案值伪装为本餐显式输入。
- “选择并生成指导”先以独立 UUID 和冻结请求体提交 SELECT。只有重新 GET 核验当前餐单仍为 `CURRENT`、`SELECTED`、版本与选项一致后，才提交使用另一 UUID 的 GUIDE。
- 历史加载、刷新、重新进入仅执行 GET。已有指导通过引用 ID 再 GET 完整快照；不会因页面打开自动生成。
- 对超时、断网、5xx、无法解析的成功响应保留请求编号和原请求内容，不自动重发 POST。已知动作 ID 最多轮询 30 次且不超过 120 秒。无动作 ID 的未知请求只有在用户确认后才允许用原编号核对/重放。
- GUIDE 失败保留已选方案；重新生成需要明确确认并创建新 UUID。创建会话结果未知时提示先查历史，不自动创建第二段会话。
- BIGINT 标识与材料小数保留字符串，未知值保留 `null`；运行时校验响应类型、枚举、会话/餐单归属和版本。`UNAVAILABLE` 不显示或沿用旧餐单、菜名、材料或指导缓存。
- 将原菜谱事实与服务端指导解释分开展示，引用定位使用站内锚点，不渲染 HTML 或模型外链；可选建议单独标记。
- 账号和路由代次隔离迟到响应；离开时中止本地等待并提示服务器可能仍在处理。请求状态只保存在模块内存，不进入 Web Storage。

## 文件清单

- `frontend/src/modules/conversation/kitchen/{api.ts,state.ts,store.ts,types.ts,validation.ts}`
- `frontend/src/modules/conversation/kitchen/components/{MealOptionCard.vue,MealGuidePanel.vue}`
- `frontend/src/views/KitchenAssistantView.vue`
- `frontend/src/router/index.ts`（UF2 增加两条厨房路由；文件中原有改动保持原样）
- `frontend/src/layouts/AppLayout.vue`（厨房导航、未确认状态与退出/离开提醒）
- `frontend/tests/{kitchen-api.test.ts,kitchen-state.test.ts,kitchen-view.test.ts,kitchen-fixtures.ts}`
- `docs/工作报告-UF2-餐单选择与指导回看.md`
- `docs/技术文档-UF2-餐单与指导状态.md`

工作树原有其他未提交改动未清理、未重置；没有把它们计入本次 UF2 文件清单。

## 初次交付验收记录（修复前历史）

在 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker\frontend` 执行：

- `npm run type-check`：通过。
- `npm run test`：25 个测试文件、164 项测试全部通过。
- `npm run build`：通过，Vite 生产构建完成。
- UF2 定向测试 `npm run test -- tests/kitchen-api.test.ts tests/kitchen-state.test.ts tests/kitchen-view.test.ts`：3 个文件、22 项通过。

针对测试使用隔离替身，没有把替身结果宣称为真实后端联调。UF2 未执行浏览器真实交互、开发 PostgreSQL 部署或真实生成请求。

## 待部署后的浏览器人工核对

1. 用真实登录账号打开 `/kitchen`，验证会话列表、新建会话和导航；确认 `/chat` 原聊天入口仍可用。
2. 提交本餐需求，确认默认人数提示来自档案，显式人数会随本餐请求提交；检查澄清、单套/双套、材料未知及来源信息。
3. 选择方案，确认先完成 SELECT 再产生 GUIDE；刷新或重新打开历史时只读取已保存指导。
4. 核对指导中的原菜谱事实、证据锚点、烹饪顺序和独立可选建议；验证 `CHANGED` 可回看但不能基于旧事实继续生成，`UNAVAILABLE` 不显示旧数据。
5. 在请求处理中切换路由/账号或离开页面，核对警告、原编号提示及无自动重复 POST；确认退出清理当前账号的本地厨房状态。

当前停止在 UF2 前端交付，等待主架构轻量复审；部署、U5 与真实模型调用仍不在本任务授权内。
