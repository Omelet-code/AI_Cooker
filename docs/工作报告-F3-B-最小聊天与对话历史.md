# F3-B 最小聊天与对话历史工作报告

## 任务边界

- 目标：受保护的聊天与对话历史入口、同步发送、选轮快照和本地菜谱详情跳转。
- 允许目录：conversation 模块、ChatView、路由/布局最小追加、共享请求的单请求超时与取消安全检查、专属测试与文档。
- 输入：s3-conversation-v1、s3-recommendation-v1、ADR-010、T14审查第10节及现有Cookie/CSRF/身份清理。
- 输出：实现、非收费测试、工作报告、教学文档及待人工验收清单。
- 验收：npm run test、npm run type-check、npm run build、git diff --check。
- 不做：后端、根架构/冻结契约、依赖/代理/端口、真实模型/数据库/turn POST联调、部署、购物/SSE、提交合并推送和后续任务。

## 开工 dirty 清单

下列为恢复执行时的既有状态，全部保留；未跟踪目录以Git原始清单展示。

```text
 M AGENTS.md
 M docs/阶段3-架构与任务计划.md
 M frontend/package.json
 M frontend/src/App.vue
 M frontend/src/env.d.ts
 M frontend/src/main.ts
 M frontend/src/router/index.ts
 M frontend/src/styles/main.css
 M frontend/vite.config.ts
 M 技术文档.md
?? docs/adr/ADR-009-会话轮次短事务与幂等边界.md
?? docs/adr/ADR-010-固定RAG推荐与结果快照.md
?? docs/review-probes/
?? docs/修复报告-F2-A.md
?? docs/修复报告-F2-B.md
?? docs/修复报告-T7.md
?? docs/审查报告-T12与F3-A.md
?? docs/审查报告-T13-会话与轮次持久化.md
?? docs/审查报告-T14-固定RAG推荐.md
?? docs/工作报告-F2-A-登录页面与认证联调.md
?? docs/工作报告-F2-B-菜谱页面与收藏联调.md
?? docs/工作报告-F2-C-R1收尾修复.md
?? docs/工作报告-F2-C-做菜历史与评分联调.md
?? docs/工作报告-F3-A-模型设置页.md
?? docs/派发任务-F3-B-最小聊天与对话历史.md
?? docs/派发任务-T13-会话与轮次持久化.md
?? docs/派发任务-T14-固定RAG推荐.md
?? docs/派发修复-T12-R1-文本截断.md
?? docs/派发修复-T13-R1与R2.md
?? docs/派发修复-T13-R2-复合外键测试.md
?? docs/派发修复-T14-R1至R4.md
?? docs/派发修复-T14-R4-统一安全推荐结果.md
?? docs/阶段3-T13会话与轮次契约.md
?? docs/阶段3-T13目标与接口草案.md
?? docs/阶段3-T14固定推荐契约.md
?? frontend/.env.example
?? frontend/src/app/
?? frontend/src/layouts/
?? frontend/src/modules/identity/api.ts
?? frontend/src/modules/identity/components/
?? frontend/src/modules/identity/types.ts
?? frontend/src/modules/identity/validation.ts
?? frontend/src/modules/recipe/api.ts
?? frontend/src/modules/recipe/components/
?? frontend/src/modules/recipe/format.ts
?? frontend/src/modules/recipe/types.ts
?? frontend/src/modules/recipe/useCookingCompletion.ts
?? frontend/src/modules/settings/api.ts
?? frontend/src/modules/settings/types.ts
?? frontend/src/modules/settings/useModelCredentialSettings.ts
?? frontend/src/shared/api/
?? frontend/src/shared/auth/
?? frontend/src/stores/cooking.ts
?? frontend/src/stores/identity.ts
?? frontend/src/stores/recipe.ts
?? frontend/src/views/CookedRecipesView.vue
?? frontend/src/views/FavoritesView.vue
?? frontend/src/views/LoginView.vue
?? frontend/src/views/ModelSettingsView.vue
?? frontend/src/views/RecipeDetailView.vue
?? frontend/src/views/RecipesView.vue
?? frontend/src/views/RegisterView.vue
?? frontend/tests/
```

## 1. 修改文件

新增业务文件：

- `frontend/src/modules/conversation/types.ts`
- `frontend/src/modules/conversation/validation.ts`
- `frontend/src/modules/conversation/api.ts`
- `frontend/src/modules/conversation/presentation.ts`
- `frontend/src/modules/conversation/useChatState.ts`
- `frontend/src/modules/conversation/store.ts`
- `frontend/src/modules/conversation/components/RecommendationPanel.vue`
- `frontend/src/views/ChatView.vue`

最小接线修改：

- `frontend/src/router/index.ts`：追加两条受保护聊天路由；首页重定向保持原规则。
- `frontend/src/layouts/AppLayout.vue`：追加聊天入口/待确认条、退出和刷新提醒，与原做菜处理共存。
- `frontend/src/shared/api/types.ts`：增加可选的单请求timeoutMs。
- `frontend/src/shared/api/client.ts`：传递单请求超时，补充已有signal的取消检查与共用CSRF/续期等待者退出。

新增测试：`chat-fixtures.ts`、`conversation-api.test.ts`、`chat-state.test.ts`、`chat-shared-client.test.ts`、`chat-view.test.ts`、`chat-auth-cleanup.test.ts`（均在frontend/tests）。新增文档为本报告、`技术文档-F3-B-同步聊天与状态隔离.md`、`人工验收-F3-B-聊天与对话历史.md`。根文档、后端、身份/菜谱业务、依赖与代理均未由本任务修改。

## 2. 实现与选择

- 同一ChatView展示分页会话与消息、同步发送、选轮快照和既有菜谱详情入口；消息第一页是最早，默认取得total后定位最后页，手工按钮仍可查看更早/更晚。
- conversation API只使用T13/T14冻结路径和字段；ID保持正BIGINT十进制字符串，运行时校验返回JSON、所属会话/轮次/requestId及可空字段，非法结果进入未知而非成功。
- 实际视图使用模块Pinia store与共用useChatState。一次明确发送固定UUID/原文，提交中抑制重复；未知保存原payload，无turnId须确认费用提示才重放，有turnId只读GET。
- 已知在途自动读取最多30次且120秒截止，达到任一上限停止为尚未确认；终态立即停止。人工回读是单次GET，不重启自动循环。刷新/历史读取不POST，GET不恢复过期轮次。
- 按用户/会话/请求代次隔离；切换路由取消客户端等待并保留原会话待确认入口，用户变化或sessionCleanup清除私有消息/草稿/快照/待确认。未保存到Web Storage。
- 默认请求10秒保持，仅turn POST125000ms；共用CSRF/续期不因单个取消者中断，但取消者不会随后发出旧POST。原有限认证重放保持相同payload。
- 四outcome、FAILED和合法result=null分别显示。名称、摘要、正文、answer普通文本渲染，详情仅用合法本地route；null不填0，不声称预算满足、库存足量或健康安全。Mock不支持聊天时显示限制，失败不回退Mock。

## 3. 实际验收结果

在指定worktree的frontend执行：

| 命令 | 实际结果 |
|---|---|
| `npm run test` | 19个测试文件、114项测试全部通过，包含旧GET忽略取消的回归 |
| `npm run type-check` | 退出码0，通过 |
| `npm run build` | 退出码0，通过，72模块完成转换，ChatView懒加载资源输出 |
| `git diff --check` | 退出码0，通过；仅提示既有文件的LF/CRLF转换 |

测试覆盖实际共用状态和API、原键/原文及连点、4000码点、未知/500/中断/非法响应、显式核对、FAILED/503/冲突/凭证缺失、新轮须明确发起、有界GET/终态/墙钟取消、选轮与分页竞态、用户/路由变化、最终401共用清理、共享超时与CSRF/续期原payload有限重放、SSR普通文本/null/声明和真实路由登录守卫。

所有新增请求使用虚构fetch或模块替身。120秒和125秒测试用虚拟计时器，不作长时间真实等待；未使用真实Key/模型或运行服务的turn POST。SSR证明模板展示与转义，状态测试证明页面共用逻辑；不能冒充浏览器交互/PG/供应商验收。页面局部导航确认在SSR中未执行，待人工清单单列。

## 4. 未完成项

本卡范围内实现完成；真实浏览器、后端/PostgreSQL和真实DeepSeek/百炼联调未执行。冻结输入明确后端实现仍仅df67，尚未整合部署，因此本次不假设8080已具备新接口，未启动替身服务冒充真实。

## 5. 风险与后续

- 前端abort或等待超时不等于服务器执行被取消；原键核对可能开始此前未到服务器的付费执行。
- 刷新/退出会丢失本地原payload；跨刷新从后端历史回读，不提供浏览器长记忆。
- 自动GET停止后可能仍在途；GET不会执行过期恢复，不能自行改为FAILED。
- 普通文本转义防止页面执行HTML，不替代服务端语义/权限校验；快照中的详情访问仍由既有后端可见性规则决定。
- 代码/非收费测试通过不等于真实供应商质量、费用、时延或阶段三/采购签收。下一步由主架构复审，再另行取得部署和限定真实调用授权。

## 6. 文档与停止点

已新增工作报告、教学说明和13项待人工清单，解释同步POST与GET、2xx与业务状态、unknown/FAILED、幂等、代次/取消、超时、分页/N+1和事实边界，提供8道追问、自检与2个练习。未更新根架构或冻结契约，因为实现沿用本次已冻结边界。

完成本卡后停止，等待主架构独立复审；未创建/派发任务，也未提交、合并、推送、部署或启动后续切片。

