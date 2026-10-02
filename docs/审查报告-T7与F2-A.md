# T7 与 F2-A 主架构审查报告

> 最新推进状态（2026-10-01）：9月30日修复复审结果保留；用户明确回复“继续吧”，解除暂停，恢复 T9/F2-B 派发。下一切片的执行和验收另行记录，不将本报告视为 T9/F2-B 已完成。

日期：2026-09-30；契约：s2-api-v1；工作树：`C:\Users\Omelet\.codex\worktrees\0f47\Cooker`；分支：`codex/agent`。

## 1. 结论与推进闸门

初审时两个任务已交付代码和事实报告，但**暂不签收**；下文保留初审发现和证据。用户授权修复后，R1～R3 已通过主架构独立复审，可作为 T9/F2-B 的实现依赖。尚缺的浏览器记住我证据仍保留，不能宣告阶段二完整签收；最新结果见第 6 节。

用户已授权推进至 T10 的后端 T7/T9/T10 和前端 F2-A/F2-B/F2-C 范围保持不变。依据 AGENTS 的修复授权边界，已向用户询问是否允许原会话先修复本报告问题，再复审并派发 T9/F2-B；截至本报告写入时尚未派发修复或下一项。主架构本次没有编写业务代码，没有提交或合并。

## 2. 已核验的证据

| 项目 | 本次审查结果 |
|---|---|
| 后端 JDK21 `mvn -q test` | 39 项，0 失败、0 错误、1 跳过；跳过的是既有收费 T6 评测 |
| PostgreSQL 集成测试 | 身份与既有检索 Testcontainers 测试均实际执行，独立库 V1～V4 迁移通过 |
| 前端类型检查 | `npm run type-check` 通过 |
| 前端单元测试 | 3 个文件、15 项通过 |
| 前端生产构建 | `npm run build` 通过 |
| 真实浏览器主流程 | 内置浏览器通过 Vite real 模式注册、勾选记住我登录、刷新后恢复用户、退出后刷新回登录页 |
| 账号持久化 | 专用测试账号存在于 PostgreSQL，密码为 Argon2id 哈希；不读取或输出完整哈希 |
| 续期持久化 | 登录后有记住我令牌行，绝对期限约 30 天；退出后该账号未撤销令牌数 0、撤销数 1 |
| 既有数据 | 菜谱仍为 372 行；Flyway 当前 V4 |
| 改动范围 | 既有检索测试只将迁移数量断言 3 改为 4，未修改检索逻辑；`git diff --check` 通过 |
| 学习文档 | T7 已包含真实目录、请求时序、SQL/事务、选型对照、9 道面试问答、6 道自检及 2 个练习 |

首次后端复跑受沙箱的 Docker 管道与 JUnit 临时目录权限影响：出现管道 AccessDenied、临时目录清理错误及数据库测试跳过。通过工具权限流程在沙箱外复跑后全部恢复，属于执行环境限制，不能写成业务代码故障，也不能以第一次跳过结果作为通过证据。

专用账号 `review_s2_58879486` 是本次新增测试数据，不是用户个人账号；测试密码仅临时用于本次流程，不写入报告。账号未删除、未修改已有账号或菜谱。前端由本次审查启动于 `http://127.0.0.1:5173`，后端为已有 `http://localhost:8080/api`。没有调用 LLM/Embedding。

## 3. 必须处理的发现

### R1：续期失败后，前端仍保留已认证身份（P1）

位置：`frontend/src/shared/api/client.ts` 的 `execute/refreshOnce`，`frontend/src/stores/identity.ts` 的 `initialize/clearUser`，`frontend/src/router/index.ts` 的守卫。

请求层在续期最终返回 `REFRESH_REJECTED`，或重放后的受保护请求仍返回认证 401 时，只向调用方抛异常。代码没有统一通知身份 Store 的入口；`clearUser` 仅定义，没有被该路径调用。Store 的 `initialize` 对 `authenticated` 提前返回，路由守卫继续相信旧状态。

审查使用现有 TypeScript 源码在内存编译运行，只将 fetch 替换为固定 HTTP 响应，不修改文件或后端数据：先置为已认证，`me` 返回 `ACCESS_TOKEN_EXPIRED`，`refresh` 返回 `REFRESH_REJECTED`。实际结果：

```text
errorCode = REFRESH_REJECTED
statusAfterFailure = authenticated
userStillPresent = true
```

这不表示绕过后端权限，但会让页面保留过期身份和后续个人列表，偏离“续期失败清状态、回登录页”的冻结契约。

修复验收要求：请求层提供受控的身份最终失效通知，避免导入业务 Store 产生依赖环；在应用装配处统一清身份并转登录页，后续 recipe 私有状态也须能清理。只对最终认证失败触发，不能把权限 403、CSRF 403、普通网络超时或可恢复 refresh 竞争误当注销。补测失效、重放后仍 401、并发通知和非认证错误；现有有限重放规则不得放宽。

### R2：非法 JSON／字段类型返回 500，而非 400（P2）

位置：`backend/src/main/java/com/cooker/common/ApiExceptionHandler.java`。

只为 Bean Validation 异常定义了 400 映射；请求体解析或类型转换错误落入 `@ExceptionHandler(Exception.class)`，统一成为 500。

真实 HTTP 复现：先获取 CSRF 并保留 Cookie，然后向 `/api/auth/login` 分别提交不完整 JSON `{` 和字符串类型的 `rememberMe`。两次均返回：

```text
HTTP 500
code = INTERNAL_ERROR
message = 服务暂不可用
```

期望为 `HTTP 400 / VALIDATION_ERROR`，不能回传堆栈、原始请求体或凭证。需要区分框架参数错误与真实服务内部故障，并补 HTTP 回归测试，尤其是非法 JSON、错误类型和缺失必需请求体。

### R3：JwtService 未使用已冻结的接口／impl 分层（P2）

位置：`backend/src/main/java/com/cooker/identity/service/JwtService.java`。

当前 `service` 下放置带 `@Service` 的具体实现类，并在构造器里配置签发器和验证器。AGENTS 要求项目自己的 Service 接口放 `service`，实现放 `service.impl`。`AuthService`、`CurrentUserService` 已遵守，JWT 能力也应统一，不宜在引入后续模块前保留例外。

修复验收要求：保留稳定 `issue/verify` 接口、将实际实现移至 impl、保持依赖注入及现有 JWT 测试；同步 T7 代码地图和阅读路径。不替换认证框架，不改变已执行的 V4。

## 4. 非阻断项与未完成验证

- 初次匿名访问与退出后刷新，页面显示“续期凭证无效，请重新登录”；正常匿名状态不应表现成错误，可在 F2-A 修复时区分预期匿名与真正故障。
- T7 报告关于“验证签发时间”的表述应收紧：当前使用的 `JwtTimestampValidator` 校验已有 exp/nbf，不提供 iat 的额外业务校验；已查看本地 7.0.7 依赖字节码，默认允许缺失 exp。正常签发器总是生成 exp，这不意味着任何人可以伪造合法签名。后续应明确访问 JWT 必需 claim 的校验策略及测试，或准确记录现有边界，不能宣称已经覆盖未测试的条件。
- 后端已有并发续期 Service 测试、旧 Cookie HTTP 重放测试，但不是两个真实浏览器标签页同时返回 Set-Cookie 的验证。前端对 `REFRESH_CONFLICT` 的一次 me 检查也缺少对应回归用例。
- 本次刷新恢复使用仍有效的访问 Cookie；尚未验证真实浏览器关闭重开，以及访问 JWT 到期后的浏览器续期。不能仅凭持久 Cookie 和 30 天数据库期限宣告“记住我关闭重开”完成。
- 生产稳定 JWT Secret、HTTPS/Secure Cookie、限速和设备管理仍按已记录边界处理；不通过本次审查扩大为新的实现任务。

## 5. 复审和后续切片

授权更新：用户已于本次审查后明确回复“修复”，原后端与前端会话按 `docs/修复任务-T7与F2-A.md` 处理 R1～R3 及对应测试、文档；上文“授权待确认”保留为初审时事实。现已完成独立复审，结果见第 6 节。

获得针对修复的明确授权后，两个原会话仅修改各自 T7/F2-A 允许目录与对应测试、报告。后端更新独立教学文档；主架构复跑上述命令，核对 R1～R3，补齐真实浏览器认证证据后再签收。

下一对切片仍为：

- T9：真实菜谱列表/详情、可见性、收藏幂等和分页，新 V5；不接 T5 收费混合检索，不改导入解析和已执行迁移；独立技术文档及工作报告必需。
- F2-B：`/recipes`、`/recipes/:id`、`/favorites` 的 real 页面；复用经过复审的请求层，处理空值、加载/错误/分页和用户切换清理；真实收藏联调后交付。

不启动 T8、T10/F2-C、T11 或后续阶段；T10/F2-C 在下一对切片审查完成后依据原有授权领取。

## 6. 修复复审结果（2026-09-30）

- R1 通过：请求层只发送受控认证失效事件，应用装配清理身份及注册的私有状态，并跳转登录页；正常退出也调用私有状态清理入口。主架构用实际 client、identity API、Pinia Store 和装配源码在内存编译运行，仅替换 fetch 响应。相同复现现在得到 `REFRESH_REJECTED`、`status=anonymous`、`userPresent=false`、`redirects=1`、`route=login`；这是源码路径复测，不是实际过期 Cookie 的浏览器证据。
- R2 通过：主架构对部署后的 8080 服务独立发送非法 JSON、错误 rememberMe 类型和缺少 body，三者均为 HTTP400 / VALIDATION_ERROR。内部异常脱敏500有独立回归测试。
- R3 通过：JwtService 是项目接口，JwtServiceImpl 位于 service.impl；调用方按接口注入，教学文档准确记录 exp/nbf/iat 验证边界。
- 后端独立 JDK21 `mvn -q test`：42 项、0 失败、0 错误、1 跳过；身份及检索 Testcontainers 实际执行。跳过项仍为既有收费 T6 评测。
- 前端独立 type-check、25 项 Vitest、build 通过；覆盖最终失效、并发、有限重放、匿名状态、403/网络/超时负例和 refresh 冲突的一次 me 恢复。`git diff --check` 通过。
- 修复报告和 T7 教学文档已更新；既有 V1～V4 不变，部署报告记录菜谱372行。没有收费调用、提交或合并。

结论：本次指定修复闭环完成，T7/F2-A 的源码与已测主流程可供下一切片依赖；允许按原授权领取 T9/F2-B。浏览器关闭重开、访问 JWT 真正过期后的续期、双标签 Cookie 竞争和生产 HTTPS 仍未核验；不删减最终验收项，也不宣称记住我全部验证或阶段二完成。任务冻结见 `docs/派发任务-T9与F2-B.md`。
