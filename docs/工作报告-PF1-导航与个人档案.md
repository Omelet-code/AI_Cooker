# PF1 工作报告：导航与个人档案

日期：2026-10-07

## 完成内容

- 顶栏收敛为“浏览菜谱、烹饪助手、采购清单、个人档案、模型设置”五项；“烹饪助手”默认进入 `/kitchen`，模型设置仍为独立入口。
- 新增个人档案布局及“用餐设置、我的收藏、做菜历史”子导航。三个既有页面直接复用，各自的API、Pinia状态和写操作未改。
- 收藏和做菜历史改为个人档案子路由，保留原路由名 `favorites`、`cooked-recipes`，并以路由别名兼容 `/favorites`、`/cooked-recipes`。别名解析保留 query/hash；保护根路由的登录守卫继续生效。
- 厨房工作台提供“旧版推荐记录与操作核对”链接至 `/chat`。旧 `/chat`、`/chat/:conversationId` 路由和聊天状态/API保持原样，未迁移消息或自动生成内容。

## 本次改动文件

- `frontend/src/router/index.ts`
- `frontend/src/layouts/AppLayout.vue`
- 新增 `frontend/src/layouts/ProfileLayout.vue`
- `frontend/src/views/KitchenAssistantView.vue`（仅增加旧版入口及其局部样式）
- 新增 `frontend/tests/pf1-navigation-profile.test.ts`
- `frontend/tests/kitchen-view.test.ts`
- 本报告及 `docs/技术文档-PF1-导航与个人档案.md`

## 验收记录

- `npm run test -- tests/pf1-navigation-profile.test.ts tests/kitchen-view.test.ts`：2 个文件、9 项通过。
- 覆盖子路由名称、旧收藏/历史链接的 query/hash、聊天深链、登录守卫、个人档案子导航、单一助手入口及旧聊天核对入口。
- 此处是组件服务端渲染和路由自动化验证，不是浏览器人工验收。

PF1 已完成，已继续执行本卡 PF2。全量测试、类型检查、生产构建和差异检查在整卡收尾时执行；本报告不引用之前 UF2 的测试结果作为本次证据。
