# F2-C 做菜历史与评分联调工作报告

日期：2026-10-01
工作树：C:\Users\Omelet\.codex\worktrees\0f47\Cooker
范围：仅前端 F2-C；未修改 E:\Cooker、后端、共享认证实现或 API 契约。

## 完成内容

### 做菜完成与幂等操作

- 菜谱详情加入“完成烹饪”确认弹窗，可选择不评分或 1～5 分；用户取消时不发请求。
- recipeApi.completeCooking 调用 POST /api/cooking-records，请求体固定为 {recipeId, idempotencyKey, rating}；历史读取使用 GET /api/users/me/cooked-recipes?page&size。
- Pinia cooking Store 只在确认时生成 UUID。等待期间阻止重复提交；网络超时/未知结果和可重试错误保留相同菜谱、评分与幂等键；409 IDEMPOTENCY_CONFLICT 不自动换键或重试。
- 成功后清除待处理操作，并从服务端重新读取历史；不在客户端伪造做菜次数，也不扣库存、不自动收藏。
- 页面导航期间 Store 保留待处理操作；全局提示提供返回入口，浏览器刷新/关闭前提示，退出登录前就待处理操作向用户确认。用户确认退出或切换账号时清理账号私有状态，旧账号的迟到响应不能恢复状态。
- “结束本次操作”是用户明确放弃重试的动作。若服务器可能已接收该请求，之后应以服务端历史为准。

### 做菜历史

- 新增 /cooked-recipes 页面及导航入口。
- 使用服务端返回的做菜次数、最近时间、熟悉度和最近非空评分；latestRating 为 null 时显示“未评分”，不显示成 0 分。
- 支持分页，以及首次加载、空状态、错误和重试状态；时间格式化失败时显示原始值。
- 详情页原有收藏操作、原始食材行与“可选”标识保持可用。

## 修改文件

- frontend/src/modules/recipe/api.ts
- frontend/src/modules/recipe/types.ts
- frontend/src/stores/cooking.ts
- frontend/src/views/RecipeDetailView.vue
- frontend/src/views/CookedRecipesView.vue
- frontend/src/layouts/AppLayout.vue
- frontend/src/router/index.ts
- frontend/src/styles/main.css
- frontend/tests/cooking-store.test.ts
- frontend/tests/cooked-recipes-view.test.ts

## 验收结果

在 frontend 目录执行：

- npm run type-check：通过。
- npm run test：11 个测试文件、55 项测试通过；包含 F2-B 收藏和食材格式化回归。
- npm run build：通过，Vite 生产包生成成功。
- git diff --check：通过；另检查本报告和新增前端文件，没有行尾空格。

Store 测试覆盖可选评分 null/1～5、防重复提交、超时后相同 payload/UUID 重试、成功后新 UUID、幂等冲突、一般失败重试、历史分页、null 评分、用户切换和迟到响应。历史页面 SSR 测试确认服务端次数/熟悉度显示及 null 评分文本。

## 真实联调与未完成项

- 当前 worktree 已有 T10 控制器和 V6 迁移源码，但运行状态文档仍记载本地 Java PID 41468 部署的是 T7/T9 且 Flyway 到 V5。该进程启动时间早于 T10 控制器/V6 文件时间。
- 对 GET /api/users/me/cooked-recipes?page=1&size=20 的匿名只读请求返回 HTTP 401 AUTH_REQUIRED。此结果没有验证 T10 控制器是否已部署，因此不作为真实接口通过证据。
- 本任务没有重启共享后端、迁移或写入数据库，也没有创建账号或做菜记录。未完成登录态下的 POST/GET 真实联调。
- 浏览器自动化工具在本会话不可用；未声称浏览器验收通过。需待 T10 后端实际部署后，由人工浏览器或经授权的专用账号继续验收：首次记录、同键重放不增加次数、新 UUID 新增记录、null/显式评分、刷新/重新登录持久化及双账号隔离。

## 学习清单

- **客户端状态机**：一次操作经历 submitting、unknown、failed、conflict 或成功清除；rating 与 recipeId 在提交后保持不变。
- **UUID 与数据库约束**：UUID 让客户端重试引用同一操作；真正的并发唯一性和跨请求防重由服务端唯一约束/事务保证，按钮禁用只改善交互。
- **超时含义**：客户端超时只说明没有及时收到结果，不能推断服务端回滚，所以未知结果时不能换键。
- **服务端事实与 Pinia 缓存**：页面展示次数、最近时间、熟悉度及最新非空评分都来自后端；Store 只缓存读取结果，不自行累加。
- **账号边界**：会话清理清除私有状态并递增 generation，响应还会核对身份和请求序号，避免旧账号响应写回。

## 自检

1. 用户取消确认时会生成 UUID 或发请求吗？**不会；只有 Store 的显式完成动作创建操作。**
2. 超时后能改评分并沿用原幂等键吗？**不能；保留的操作数据作为重试 payload。**
3. 重试时客户端会主动生成另一个 UUID 吗？**不会；冲突也不会自动换键。**
4. 刚完成后客户端会本地把做菜次数加一吗？**不会；成功后从服务端回读历史。**
5. 用户换账号后旧请求还可以写入当前 Store 吗？**不可以；generation、当前身份与操作键都会校验。**

## 后续练习

1. 在不改服务端事实来源的前提下，为历史列表增加基于已加载数据的无障碍统计摘要。
2. 为真实接口增加一项专用账号集成验收，记录请求状态码与前后历史计数，但不输出 Cookie、CSRF Token 或凭证。

