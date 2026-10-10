# U5 修复：安全投影、创建提示与 IDE 启动

日期：2026-10-05。用户明确允许修复前次两个问题，并要求后续通过 IDE 启动、在控制台查看服务端错误。仅原前后端会话执行，均沿用 `gpt-6.1-sol/medium`；主架构不写功能代码。依据 `排障记录-U5-首次真实餐单失败.md`。

## 1. 共同边界

- 开始前完整阅读各自工作树 AGENTS.md、技术文档.md，本卡、ADR-011、U3/U4 契约及此前修复记录。本卡的最新授权覆盖旧文档中本切片尚未授权的限制，不扩大其他任务。
- 保留现有 dirty，修改前记录本次相关文件基线。不要还原、提交、合并、推送、新建会话或切换工作树。
- 仅非收费自动化/协议替身与隔离 PG；不调用真实 DeepSeek/百炼，不修改现有开发库、自动更新失败餐、重启或停止服务。真人费用允许不等于实现会话可以批量调用。
- 先写能触发实际调用链问题的失败回归，再最小修复。完成后提供实际命令、测试数量、改动文件、限制与未验项；教学报告解释根因、实现层级及为何这样改，完成后停止等待轻量复审。
- 当前 Vite 可能对前端文件变更热更新；不要将其称为验收或主动重部署，提醒用户修复期间先停止真人提交。

## 2. 后端 R1：区分原始事实与安全投影

执行会话 `01a101fa-570c-7d50-9986-b45653973234`；目录 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker`，不是 0f47、df67 或 E:\Cooker。

现象：会话11的 CREATE 已完成一次模型理解及候选读取，随后 UNSAFE_CONTENT；第二次模型组餐尚未调用。本次具体候选/触发字段未保存，不能捏造为某道菜。已确定整个 MealRecipeFacts 被 safeTree 扫描，正常来源链接也能触发；U4 完整依据检查存在相同路径。

冻结原则：

1. 原始文档、结构化数据、事实指纹、原数量/单位/ID、权限与事实变更核验保持原始依据，不删除数据、不重导入、不重算历史指纹。安全投影只用于模型输入和新响应/快照展示，不能把投影哈希冒充原始事实哈希。
2. 允许将普通 Markdown 来源链接转为安全纯文本：保留有用的链接文字，移除链接目标及不必要的来源 URL；不抓取外链、不执行 HTML，不截断或删除烹饪关键步骤/用量以通过安全检查。保留真实 ID、步骤序号及证据覆盖。投影会改变显示文本但不改变原始事实；在报告中明确此与旧契约“保留原文”的边界，旧 T9/T14 接口不修改。
3. 注入指令、危险 HTML、诊疗断言等真正危险内容不能用“删关键词”伪造安全：候选阶段隔离该候选，其他合格候选仍可组餐；隔离后再计算数量/方案数，不足则正常澄清，禁止造菜或额外检索补池。GUIDE 对已选危险依据安全失败、保留选择，不偷偷换菜。模型输出的原有安全/类型/证据规则仍严格。
4. U3 模型候选摘要、新餐单材料/事实响应及序列化快照，U4 模型完整依据及服务器生成的依据/材料展示必须共用一致边界；POST、GET、历史重放均不能漏出被禁止的 URL/HTML/危险文本。不绕过最终输出校验。
5. 允许仅 agent 餐单/指导 Service、policy 专属投影辅助类与必要专属测试/教学文档；不改 T5、RecipeMealFactsService 的资格/指纹、导入、旧迁移、依赖、共享认证、模型网关预算和 HTTP 字段/类型。不另加业务迁移或全局放宽 safeText。

最小验收：真实 Service 链用模型/Embedding 替身覆盖带正常链接的四菜一汤两方案、点击选择后完整五菜指导、原步骤及材料证据不丢、原指纹/DB 不变；危险候选隔离与数量不足澄清；危险已选依据失败保留选择；新快照/GET/同键重放安全；原调用上限、零自动重试与权限回归。优先复用现有隔离 PG 测试。专项先红后绿，随后一次完整 Java21 离线回归及 package/diff 检查，遇环境阻塞如实报告。

## 3. 后端 R2：安全诊断日志与 IDE 启动说明

由同一后端会话执行，与 R1 一并交付。

- 餐单/指导失败日志至少包含 conversationId/mealId/actionId、动作类型/版本、执行阶段、安全错误码与异常类名；UNSAFE_CONTENT 增加可追溯 recipeId、字段路径和规则类别，全部受控元信息。不记录用户正文、菜谱正文、完整 Prompt、模型原响应、Key/密码、Cookie、Authorization 或供应商异常 message；不要直接将未净化 Throwable 交给 logger，也不把诊断信息暴露给其他用户。
- 正常候选隔离使用适当 INFO/WARN；动作失败 WARN/ERROR。不要开启 HTTP wire、Mapper DEBUG 或 Spring AI 的 Prompt 日志。补日志捕获测试，以恶意正文/模拟密钥验证控制台未泄漏；业务失败仍使用现有安全响应码。
- 在本工作树创建 `docs/开发指南-U5-IDE启动与安全报错.md`：说明导入 backend/pom.xml、JDK21、Maven 本地配置及 repository、main `com.cooker.CookerApplication`、工作目录 backend、profile local、8080/API context、Docker PG 5433 cooker、前端0f47真实模式及代理。
- 写明 Spring 程序参数：`--server.address=127.0.0.1 --server.port=8080 --cooker.agent.execution-enabled=false --cooker.recipe-import.enabled=false --spring.flyway.clean-disabled=true`，用户准备真实调用时显式改 execution-enabled=true。说明默认 application.yml 是 false，配置用户 Key 本身不会打开执行。
- 环境变量清单：DB_URL/DB_USERNAME/DB_PASSWORD、COOKER_JWT_SECRET、LLM_CREDENTIAL_MASTER_KEY、DASHSCOPE_API_KEY、COOKER_SECURE_COOKIE=false、SPRING_PROFILES_ACTIVE=local。DB_URL 使用 `jdbc:postgresql://127.0.0.1:5433/cooker`，其余秘密只用占位符；不写真实秘密到文档/.run/.env/Git，不展示现有值或生成新值。
- 稳定 JWT/加密主密钥沿用现有 Windows User 值；已运行的 IDE 不会自动取得新增环境变量，要完全退出再启动，或在本地非共享 Run Configuration 填入。Spring 默认不自动加载 .env。用户 DeepSeek Key 在网页账户模型设置，不转成全局 Key。
- 当前8080已被主架构 Jar 占用。文档要求切换时先确认精确 PID/命令行、无在途动作，再由用户或另获部署授权的主架构停止旧后端；本任务不 kill、不改成8081掩盖冲突、不清 PG 卷。前端可继续运行；IDE运行最新源码而非旧Jar。可给无密钥的运行配置示例，但不新增插件/启动器/共享秘密。
- 给一个控制台日志样例（虚构ID）、定位说明、常见失败与面试/自检内容。没有实际在 IDE 点击运行，必须标记未验证，不冒充已完成 IDE 联调。

输出后端 `docs/工作报告-U5-R1R2-安全投影与诊断.md` 和上述指南；不编辑主架构根文档。

## 4. 前端 R3：成功创建导航不误报未知

执行会话 `01a0f144-7c62-7a42-a1c0-86c64a3110c3`；实际目录 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`，不按历史元数据 E 盘执行。

- 已复现：create 成功设置 createdConversationId 时 createBusy 未清除；页面 watch 导航触发 activate→stopWaits(true)，错误设置 createUnknown。修复成功响应与等待终态的边界，不无条件清除真正未知请求。
- 允许仅 kitchen/state.ts、KitchenAssistantView.vue 必需接线、专属测试/工作与教学文档；不改共享认证/API 客户端、后端、旧聊天、UF1、依赖及根架构文件。
- 持续测试必须包含页面 watch/实际路由激活的成功创建路径、成功导航不提示未知且仅一次 POST、真实取消/超时仍未知、收到成功前手工离开、账号切换迟到响应隔离、双击保护。保留此前餐单版本/跨餐/同键核对全部回归。
- 完整 npm test/type-check/build/diff 检查；输出 `docs/工作报告-U5-R3-会话创建导航修复.md`，讲述异步生命周期与修复层级。非收费替身不称真人浏览器联调。

## 5. 后续闸门

交付后主架构一次风险匹配轻量复审，再协助用户切换 IDE 启动和真人测试。当前只派发本次修复，不推进采购/SSE/新阶段，不新建会话、不重部署、不自动收费请求，不宣称问题已关闭。

## 6. 派发回执

冻结卡已成功发送两个原会话，显式 `gpt-6.1-sol/medium`。即时快照确认后端 turn `01a107f1-11dd-7770-ac88-4d321984292d`、前端 turn `01a107f1-2d82-7921-bb66-04db8db8aa1d` 均 active/inProgress，error=null；后端已确认 R1/R2 及非收费边界。等待交付与轻量复审，非修复、IDE启动或真人质量验收完成。未停止/重启当前服务、请求供应商或操作 Git。
