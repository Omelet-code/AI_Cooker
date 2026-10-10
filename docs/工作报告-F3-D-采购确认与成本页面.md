# F3-D 工作报告：采购确认与成本页面

日期：2026-10-06。工作树：`C:\Users\Omelet\.codex\worktrees\0f47\Cooker`。本轮只执行 F3-D；后端 T16/T17 目录只读核对。未部署、未调用模型/Embedding/真实商品服务、未写开发库、未执行 Git 写操作。

本次为原 F3-D 的 R1～R3 定点补修；独立审查探针基线 5/5 复现问题，补修后 5/5 通过。不重做 UF2，不以原 207 项全绿当作缺陷已关闭。

## 交付范围

- 新增 `frontend/src/modules/shopping/`：严格 DTO 解析、请求 API、独立 Pinia 状态、会话清理、成功导航 watch 与逐原料确认组件。
- 新增 `frontend/src/views/ShoppingView.vue`：服务器预览和逐行确认、采购清单创建、城市选择及显式 Mock 匹配、已知清单与报价快照回读。
- 路由只加登录保护的 `/shopping`、`/shopping/new`、`/shopping/:listId`；静态 `new` 路由先于动态清单路由。
- 厨房页只在餐单已选、事实当前、人数明确、方案可确定且无在途/未知操作时显示采购入口；不调用采购或指导 POST。
- 布局加入采购导航、原操作上下文核对入口、离页/退出风险提示和会话清理。
- 新增五个采购专属测试文件与测试夹具；增加本报告和技术学习说明。

## 实现要点

- 预览只取自 `/conversations/{conversationId}/meals/{mealId}/shopping-preview`，校验服务器回显的会话、餐单、版本、方案和 sourceFingerprint。所有原料（含可选）都需用户选“已有足量”或“需要购买”。
- 仅不确定或缺参考用量时才允许补数量；数量使用普通十进制字符串，单位选项按质量/体积维度约束。不使用 JS 浮点重算金额或材料用量。
- 创建清单发送冻结来源标识、sourceFingerprint、全部逐行确认和 UUID。201 新建与 200 同键复用都按服务器快照读取；成功先清 busy/待确认状态，再由 Vue watch 校验来源并导航。
- 超时、断网、停止本地等待、5xx 或无效 2xx 保留原路由、UUID、用户、上下文和冻结 payload；需在原上下文显式确认后同键重放。双击受 busy/原操作保护。账号变化清除私有状态，owner generation 阻止 A→B→A 旧响应污染；读写代次和发布前校验隔离同页重复读取与跨页迟到结果。
- 商品匹配必须由用户明确提交六位城市代码。报价回看只 GET 指定 match 或 latest；latest 的 null 表示尚未匹配。刷新、打开页面、重登均不触发匹配 POST。
- R1：清单与第二段报价结果先留在局部变量，仅在账号、页面上下文和最新读取代次均匹配时发布；catch/finally 同样隔离。旧写响应离开原上下文后保留未知键，不改新页 error/busy 或触发导航。
- R2：每个未知操作冻结原始路由。匹配报价回链保留原 `matchId`，布局与采购页共用同一返回路由构造器；建单来源 query 仅含 conversationId、mealId、expectedVersion、optionId。显式核对重用原路径、城市、UUID 和不可变 payload。
- R3：清单页提供清单固定链接和当前已存报价 `?matchId=` 固定链接。进行中的 POST 可停止本地等待；取消后保留未知原键，并提示服务器可能仍在处理。离页确认后停止本地等待并令旧写入代次失效。两个采购 POST 沿用共享默认短超时，不覆盖 `timeoutMs`。
- UI 明示 `MOCK`/`realData=false`；空目录的无匹配、缺货、报价未知均按后端事实呈现。null 金额显示“未知”，字符串 `0.00` 保留为零。独立估价、模拟消耗成本、整包支出及采购组各自展示；不将行级整包价格重新累加进总额。null 购买 URL 不生成链接。
- 当前没有全量清单历史 API，入口只允许打开用户已知清单编号/链接，不伪造历史列表。

## 验证

- 完整前端测试：`npm run test`，32 个测试文件、217 项通过；新增状态竞态、原报价返回/确认、固定链接和停止等待持续回归。
- `npm run type-check`：通过。
- `npm run build`：通过，Vite 转换 102 个模块并完成生产构建。
- 独立审查探针：`node node_modules/vitest/vitest.mjs run --config ../docs/review-probes/F3-D-review.config.ts`，5 项通过；探针文件保持未修改。
- 本次涉及的 11 个源码、测试与报告文件尾随空白检查：通过。`git status`/`git diff --check` 无法运行：Git 无法进入/识别此关联工作树（`Permission denied` / `not a work tree`）；未修改 ACL。
- 测试使用隔离 API 替身及真实 Vue Router/页面 watch 激活；不代表开发后端部署、开发库联调、真实商品目录或浏览器人工验收。

## 文件清单

- 新增：`frontend/src/modules/shopping/{api.ts,navigation.ts,state.ts,store.ts,types.ts,validation.ts}`
- 新增：`frontend/src/modules/shopping/components/ShoppingLines.vue`
- 新增：`frontend/src/views/ShoppingView.vue`
- 新增：`frontend/tests/{shopping-api.test.ts,shopping-fixtures.ts,shopping-kitchen-entry.test.ts,shopping-state.test.ts,shopping-validation.test.ts,shopping-view-router.test.ts}`
- 修改：`frontend/src/router/index.ts`、`frontend/src/views/KitchenAssistantView.vue`、`frontend/src/layouts/AppLayout.vue`
- 新增：`docs/工作报告-F3-D-采购确认与成本页面.md`、`docs/技术文档-F3-D-采购状态与金额展示.md`

## 未验项与复审边界

- 类型检查与生产构建通过；不代表运行中部署、真实 API/PG 或浏览器人工验收通过。
- Git 无法进入/识别 0f47 关联工作树，因此本次无法取得 Git 差异检查结果；只对任务文件做文本空白核验并报告限制。

- 尚未部署至开发环境；无真实浏览器人工点击、真实 5b09 HTTP 联调、真实商品供应商/付款验证。
- Mock 目录当前可能为空，因此实际页面可能诚实显示未匹配；本任务不改后端商品目录配置。
- 完成非收费自动化验收后停止，等待主架构轻量复审；不自动推进 T18。
