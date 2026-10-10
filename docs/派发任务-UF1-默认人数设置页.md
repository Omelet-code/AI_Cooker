# UF1：默认人数设置页

日期：2026-10-03。用户明确“启动”，允许原前端会话执行本切片，模型gpt-6-luna/high。与后端U2并行，仅页面/非收费测试/交付，不授权部署、后端整合或后续UF2。

## 执行与范围

原会话「阶段二前端实现：登录收藏与做菜历史」，threadId `01a0f144-7c62-7a42-a1c0-86c64a3110c3`，实际开发继续 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`。会话metadata显示E:\Cooker是历史，不能在E盘覆盖开发；先用明确workdir核验普通执行、HEAD及dirty。保留所有既有前端/主架构文档改动，不新建/附加工作树、不改ACL或平台元数据，若环境阻塞停下报告。

允许新增 `frontend/src/modules/identity/diningProfile/` 专属API/类型/校验/状态/组件、`frontend/src/views/DiningProfileView.vue`、对应frontend/tests及UF1交付文档。仅为入口可修改router/index.ts和layouts/AppLayout.vue，不改其认证守卫/退出或未知聊天逻辑。不改共享请求/认证Store、其他业务、依赖、后端、部署文件或根架构文档；需要额外接线先报告。U2只写5b09后端，与本任务不冲突。

## 已冻结API与页面

契约 `kitchen-profile-v1`，依据U1任务卡和已签收源码：GET/PUT `/api/users/me/profile`，在共享apiClient中使用 `/users/me/profile`；成功HTTP200、通用ApiEnvelope、data仅 `{defaultDiningCount:number|null}`。PUT必需该字段，清空显式null，不带userId。正32位整数1～2147483647，不自行设新的产品人数上限；此技术范围不等于Agent支持任意人数。

新增受登录保护路由 `/settings/profile`，名称 `profile-settings`，导航“个人档案”，保持原模型设置入口。页面展示服务端当前值/未设置、编辑人数、显式“保存”和“清空默认人数”，说明“本餐指定人数优先；默认人数不从聊天推断，修改不影响已有餐单”。现有用户null不默认为2，不以空表单暗中写入默认值。

表单先严格核验整数文本及范围，再发送JSON number；不使用parseInt截断小数，不提交字符串、空对象或undefined。清空动作发送null并成功后才更新。复用既有Cookie/CSRF/登录续期，不接触JWT或完整模型Key。

处理首次加载/未设置/保存中/成功/错误/网络超时/未登录；加载失败不展示成“未设置”，保存失败或结果未知不假装成功。禁用并发提交；未知写结果提示重新GET核对，不自动重发PUT或自动覆盖后续设置。用户之后显式重试设置仍是替换值，不新增事件幂等键。

API响应校验正整数/null，不接受非法值；表单编辑值与已保存值分开。账号切换、最终401、离开路由/卸载时清理取消等待、使旧响应失效，不串账号；数据不存localStorage/sessionStorage。real模式调用真实API，失败不静默回退Mock；测试用专属注入API/替身，不扩展全局Mock或旧请求策略。不使用v-html。

## 验收和交付

开始完整读自身/主架构AGENTS.md、技术文档.md、本卡、U1精确契约和厨房助手升级契约/ADR-011；旧“前端未授权”历史由本次具体允许覆盖。启动报告简短说明cwd/HEAD/dirty、范围和依赖。

- API/状态及实际页面测试：null显示、读取、保存/修改/显式清空、非法输入不发送、加载/保存失败、未知写后GET核对、忙时连点、卸载/路由/换账号晚响应隔离、旧登录/导航兼容。测试实际页面接线，不仅孤立工具函数。
- 执行 `npm run test`、`npm run type-check`、`npm run build`、diff/新增空白检查，输出汇总。无真实LLM/Embedding或采购请求，不发送测试写入当前开发库，不自行启动/重启服务。
- 交付 `docs/工作报告-UF1-默认人数设置页.md`，包含文件/实际测试/风险，以及真实浏览器清单：读取、保存、修改、清空、刷新/重登、账号隔离与旧页面回归；简短解释表单值/服务端值/未来本餐快照的区别。
- **U1在5b09，当前运行后端尚未整合部署V10。** 自动化与Mock通过不能称真实联调签收；部署/整合另获允许后才让用户按清单验收，不把现有8080当已具备此接口。

完成停下等主架构轻量复审，不领取UF2、不修范围外缺陷、不主动向其他会话发消息、不部署/改开发库/提交合并推送。

## 派发回执

已成功发送原前端threadId `01a0f144-7c62-7a42-a1c0-86c64a3110c3`，显式gpt-6-luna/high；快照确认active、turn `01a10247-e4f4-7800-b2e0-99c137014795` inProgress、error=null，原会话已开始0f47环境与任务卡核验。未新建会话/工作树；开始执行不是环境验证全部完成或UF1交付签收。
