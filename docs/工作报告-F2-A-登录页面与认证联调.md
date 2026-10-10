# F2-A 工作报告：登录页面与认证联调

- 日期：2026-09-30
- 分支：`codex/agent`
- 开始时基线：`e7470605fa7934059d32d8973d0101608402a766`
- 提交状态：未提交；本会话未执行 Git 暂存、提交、切换或合并。
- 契约：`s2-api-v1`
- API 模式：`real` 默认；只有显式设置 `VITE_API_MODE=mock` 才启用开发 Mock。
- 真实联调状态：**主架构已核验 real 模式普通认证主流程；本次 R1 修复待复审。关闭重开与访问 JWT 过期续期仍未验证。**

## 交付内容

在 `frontend/` 内新增或修改：

- `package.json`：将 test 命令改为真实执行 `vitest run`，移除空测试时成功退出选项。没有增加依赖。
- `.env.example`：说明 real 是默认模式及显式 Mock 开关。
- `src/modules/identity/`：登录/注册表单、契约类型、接口封装及用户名/密码校验。
- `src/shared/api/`：原生 fetch 请求层和统一响应/错误类型。
- `src/stores/identity.ts`：用户资料与认证加载状态。
- `src/router/index.ts`：登录、注册、受保护的 `/recipes` 路由及安全的站内跳转。
- `src/layouts/AppLayout.vue`、`src/views/RecipesView.vue`：登录后的用户名、退出操作和“菜谱功能待接入”说明，不显示虚构菜谱。
- `src/views/LoginView.vue`、`src/views/RegisterView.vue`、`src/App.vue`、`src/main.ts`、`src/env.d.ts`、`src/styles/main.css`、`vite.config.ts`：页面入口、初始加载反馈、基础样式、Vitest 配置；保留原有 Vite `/api` 代理。
- `tests/`：表单校验、请求层及身份 Store 测试。
- 本报告：`docs/工作报告-F2-A-登录页面与认证联调.md`。

## 实现要点

- 请求仅使用同源相对地址 `/api` 和原生 `fetch`，使用 `credentials: 'include'`、10 秒默认超时及 `cache: 'no-store'`。Cookie 由浏览器管理；代码不读写 `localStorage` 或 `sessionStorage`，也不接触 JWT/续期令牌。
- CSRF 初始化值保存在请求客户端闭包内存。写请求提交 `X-XSRF-TOKEN`；收到明确的 403 `CSRF_INVALID` 时重新取一次 token 并重试一次。
- 401 仅对 `AUTH_REQUIRED`、`ACCESS_TOKEN_EXPIRED` 尝试续期；登录失败不续期。所有并发受保护请求共享一个 refresh Promise；refresh 本身关闭续期入口。原请求在续期后最多重放一次。403 `ACCESS_DENIED` 不会触发续期。
- 启动按“取 CSRF → 请求 `/users/me` → 请求层按需续期”的顺序恢复身份。登录后刷新 CSRF；退出后无论请求结果如何都清理 Pinia 用户状态并尝试刷新 CSRF。
- Mock 只实现认证演示所需内存态，登录/注册页和登录后布局均显示“开发 Mock”。Mock 不会在 real 请求失败时自动启用，也不伪造菜谱。
- 对照 Spring Boot + MySQL 的分层理解：`identityApi` 是前端调用层，Pinia 只保存页面当前身份状态；它不是账号事实来源。真实账号由服务端数据库维护，浏览器通过 HttpOnly Cookie 证明会话。续期合并 Promise 解决同一页面多个请求同时发现访问令牌过期的问题。

## 验收证据

在指定工作树 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker\frontend` 执行：

| 命令 | 结果 |
|---|---|
| `npm run type-check` | 通过 |
| `npm run test` | 3 个测试文件、15 项通过 |
| `npm run build` | 通过；Vite 完成生产构建 |
| `git diff --check` | 通过 |

Vitest 覆盖了表单字段边界、CSRF 初始化、403 `CSRF_INVALID` 有限重试、403 与 401 分离、并发 refresh 合并、refresh 不递归、原请求只重放一次、公开登录不触发续期、记住我字段、密码不进入 Pinia 状态以及退出后的身份清理。

`npm ci --no-audit --no-fund` 因工作树没有 `node_modules` 而运行，仅按现有 `package-lock.json` 安装 104 个已声明依赖，没有新增依赖或变更 lockfile。

## 真实联调与浏览器状态

- 已启动本地 Vite 开发服务器：`http://127.0.0.1:5173`。只读 HTTP 探测 `/login` 返回 200。
- 后端只读探测 `http://localhost:8080/api/auth/csrf` 在 3 秒超时；委派指定的 `docs/运行状态-阶段2后端.md` 和 T7 工作报告当时均不存在，无法确认服务是否就绪。
- 浏览器自动化两次因 `helper_unknown_error: setup refresh had errors` 退出。未输入用户名/密码、未注册演示用户、未执行任何认证写请求；未取得浏览器截图或真实网络/Cookie/数据库证据。
- 因此本报告不宣称真实注册、登录、续期、退出、“记住我”或 PostgreSQL 持久化通过。后续需启动 T7 服务，使用专用演示用户在 real 模式按页面执行，并记录服务端/数据库可核验证据。

复现前端页面：

1. 在上述工作树进入 `frontend`，运行 `npm ci`。
2. 不设置 `VITE_API_MODE`（默认 real）；启动 `npm run dev -- --host 127.0.0.1`。
3. 打开 `http://127.0.0.1:5173/login`。无后端时预期展示中文连接错误，不能自动变成 Mock。
4. 仅做组件演示时，显式设置 `VITE_API_MODE=mock` 后重启 Vite；页面会显示“开发 Mock”。此演示不构成真实联调证据。

## 未完成项与风险

- 待 T7 后端就绪后完成 real 模式注册、登录、刷新、退出与“记住我”真实浏览器联调，核对服务端 Cookie 属性、CSRF 轮换及续期持久性。
- UI 截图检查受本机浏览器自动化 helper 错误阻断；当前有 HTTP 200、自动化测试和生产构建证据，缺少人工可视页面确认。
- 当前 Mock 仅为明确标注的开发辅助，不验证服务端用户名唯一、Cookie 生命周期或跨标签页刷新竞争；这些须以 T7 证据为准。
- 本次未修改阶段共享架构文档/契约，也未修改后端。工作树内同时存在其他会话的后端及架构未提交变更，本任务未触碰它们。

## 技术选择说明与自检

不增加 Axios 或 UI 组件库：当前接口规模下集中封装原生 fetch 已足以统一凭证、CSRF、超时和有限重试。Pinia 仅持有当前页面状态；刷新后必须重新请求后端。ID 保持字符串以免 Java `BIGINT` 超过 JavaScript 安全整数范围。用户退出后清空当前用户；未来加入的列表/评分/未完成操作状态也应由对应模块在账号切换时清理。

建议自检：

1. 为什么 HttpOnly Cookie 降低 JavaScript 读取令牌的风险，但仍需要 CSRF token？
2. 为什么“记住我”应延长可轮换的续期凭证，而不是访问 JWT？
3. 两个页面请求同时遇到 401 时，Promise 合并如何避免续期竞争？
4. Pinia 里的用户名为什么不能证明服务端数据已持久化？
5. 怎样通过刷新页面和重新登录验证后端数据库仍保存账号状态？
## 2026-09-30 R1 修复补记

本补记覆盖本报告原有“真实联调待验”状态：主架构审查报告随后记录了 real 模式下真实注册、记住我登录、刷新恢复、退出及 PostgreSQL 账号/续期记录核验通过。本修复会话没有重跑这些有写入的浏览器流程；依据 docs/审查报告-T7与F2-A.md 记录证据，不把它们描述为本会话的新实测。浏览器关闭重开和访问 JWT 到期后的续期仍未验证。

R1 修复将最终认证失效事件从 shared/api 受控发出，由应用装配订阅；认证 Store 清理、私有状态清理和路由跳转均留在应用层。新增通用 session cleanup 注册口，后续用户私有状态模块可注册清理回调，正常退出和认证最终失效都会运行。匿名 Store 初始化把预期的认证拒绝作为匿名状态，不再显示“续期凭证无效”。

本轮最终验证：npm run type-check 通过；npm run test 为 5 个文件、25 项通过；npm run build 通过；git diff --check 通过。详细修复范围及未验证项见 docs/修复报告-F2-A.md。

本会话未输入凭证、访问或记录 Cookie。尝试调用内置 node_repl 浏览器能力时，工具因 helper_unknown_error: setup refresh had errors 退出，因此没有新增浏览器截图；审查报告中既有真实流程证据与本会话的源码回归测试分别记录。
