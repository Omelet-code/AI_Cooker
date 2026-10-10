# F2-A R1 修复报告

- 日期：2026-09-30
- 工作树：C:\Users\Omelet\.codex\worktrees\0f47\Cooker
- 分支：codex/agent
- 基线 HEAD：e7470605fa7934059d32d8973d0101608402a766
- 契约：s2-api-v1
- 状态：修复完成，等待主架构复审；未提交。
- 范围：仅 F2-A 身份失效处理；未启动 F2-B，未修改 backend、E:\Cooker、共享契约或架构文档。

## 修改文件

- frontend/src/shared/api/client.ts
- frontend/src/shared/auth/sessionCleanup.ts（新增）
- frontend/src/app/authLifecycle.ts（新增）
- frontend/src/main.ts
- frontend/src/stores/identity.ts
- frontend/src/modules/identity/components/AuthForm.vue
- frontend/tests/auth-lifecycle.test.ts（新增）
- frontend/tests/identity-anonymous.test.ts（新增）
- docs/工作报告-F2-A-登录页面与认证联调.md
- docs/修复报告-F2-A.md（本报告）

## 修复原理

### 受控认证失效通知

共享请求层公开 onAuthenticationFailure 订阅口，仅发出最终失效原因，不导入 Store 或 Router。触发条件限定为：

- 续期端点以认证类 401 拒绝；
- REFRESH_CONFLICT 后唯一一次 /users/me 检查仍返回认证类 401；
- 成功续期/冲突恢复后，有限重放的受保护请求仍为认证类 401。

请求层以单次会话失效标记合并同一并发批次的通知；成功登录、成功续期或成功取得当前用户后复位。CSRF 403、权限 403、网络错误、超时和可恢复的 REFRESH_CONFLICT 不发出失效通知。401 续期与原请求重放、CSRF token 重取均保持各一次上限；refresh 及冲突后的 me 检查不会递归触发 refresh。

应用装配层订阅通知，且仅当 Pinia 身份当前仍为 authenticated 时清理 Store 和私有会话状态，再合并跳转到带“登录状态已失效”提示的登录页。匿名首屏触发的认证拒绝不会冒充此前存在的登录会话。

### 私有状态清理入口

新增共享 registerSessionCleanup / clearSessionScopedState 注册表。后续 recipe 等模块可注册清除用户私有缓存的回调，而不用让请求层依赖这些业务模块。认证最终失效时由应用装配统一调用；正常退出时由身份 Store 调用。单个清理回调异常不会阻止其他回调执行。

## 回归测试

npm run test：5 个测试文件、25 项通过。新增测试覆盖：

- 多个并发受保护请求共用一次被拒续期，终态通知只发一次；
- refresh 成功但重放后仍为认证 401 时发出终态通知；
- 应用装配只清理身份/私有状态一次，并只跳转登录一次；
- CSRF 403 经一次 token 重取恢复、权限 403、网络错误、超时均不误清身份；
- REFRESH_CONFLICT 后一次 me 成功恢复请求且不通知；me 认证 401 时通知且不循环；
- 初始匿名和正常退出后的匿名状态不显示“续期凭证无效”；
- 正常退出会运行已注册的私有状态清理回调。

最终验收命令：

| 命令 | 结果 |
|---|---|
| npm run type-check | 通过 |
| npm run test | 5 个文件，25 项通过 |
| npm run build | 通过 |
| git diff --check | 通过 |

## 真实浏览器证据与未验证项

主架构审查报告 docs/审查报告-T7与F2-A.md 记录了真实浏览器在 real 模式完成注册、记住我登录、手动刷新恢复、退出，以及 PostgreSQL 账号和续期撤销状态核验。本修复会话未重新执行会产生账号写入的浏览器流程，也未读取密码或 Cookie。内置 node_repl 浏览器调用因执行环境 helper_unknown_error: setup refresh had errors 退出，未产出新的浏览器截图。

仍未核验：关闭浏览器后重开恢复会话；访问 JWT 真实过期后的续期；生产 HTTPS/Secure Cookie。以上不影响本次源码回归测试结论，不得描述为通过。

## 工作树状态

修改均未提交；未运行 Git 暂存、提交、合并或切换。共享工作树里有后端与架构会话的并行未提交改动，本会话未修改它们。未更新共享文档或 s2-api-v1。修复完成后停止，等待主架构复审。
