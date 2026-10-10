# UF1 默认人数设置页工作报告

## 启动核验与既有改动

- 实际目录：C:\Users\Omelet\.codex\worktrees\0f47\Cooker；分支：codex/agent；HEAD：4d7360d6f6b4a37e8826a2c5541bf745a7ed4a6e，与冻结卡基线一致。
- list_artifacts 未列出本会话附件；没有附加或修改工作树元数据。该路径来自用户指定的UF1卡。常规命令因 setup refresh had errors 无法启动；用户要求修复后，使用正规 require_escalated 审批通道继续，只在目标目录执行，无ACL改动。
- 开工时dirty包含主架构 AGENTS.md、技术文档.md、阶段计划与ADR索引；T12～T14/F3-B/F2既有前端和报告；ADR-009～011、review-probes、F2/F3及T13/T14/U1/U2任务/审查资料；身份、菜谱、设置、共享层、Stores、布局、页面与测试文件。router/index.ts 已有F3-B修改，AppLayout及多组前端文件原本未跟踪。均保留；只在允许的router/layout接线上追加UF1入口，其余既有改动未重置。
- 输入契约：kitchen-profile-v1、U1档案卡、厨房助手升级契约和ADR-011。U1后端尚未整合部署，因此不把当前8080或Mock描述为真实档案接口。

## 本次文件

- 新增 frontend/src/modules/identity/diningProfile/：api.ts、types.ts、validation.ts、useDiningProfile.ts。
- 新增 frontend/src/views/DiningProfileView.vue。
- 在既有 frontend/src/router/index.ts 添加受保护的 /settings/profile（profile-settings）；在既有 frontend/src/layouts/AppLayout.vue 加入“个人档案”入口，保留模型设置和原路由守卫。
- 新增 frontend/tests/dining-profile.test.ts、dining-profile-view.test.ts、dining-profile-router.test.ts。
- 新增本报告。未改共享请求/认证、Pinia身份Store、后端、依赖、根架构文档或部署配置。

## 实现

- API只请求共享 apiClient 的 /users/me/profile，登录态Cookie、CSRF、有限续期和默认 no-store 继续由共享层处理。读取无body；写入只发送 { defaultDiningCount: number | null }，不携带用户ID。
- 响应严格验证为唯一字段、null或1～2147483647整数；输入采用完整十进制正整数校验，不用 parseInt 截断小数。未设置保持null，不默认2。
- 表单草稿与服务端已保存值分开。仅服务端成功回值后更新已保存值；清空有独立按钮并明确写null。加载失败显示错误而不是“未设置”，写入失败保留草稿与原值。
- 网络超时、服务端错误或无效成功响应标为结果未知；不自动重发PUT。页面提供用户显式触发的GET核对入口。
- 账号变化、最终401/退出清理和页面卸载会取消本地等待、清空私有数据并丢弃晚到结果。状态只在页面内存，不进入Web Storage。
- 页面解释本餐人数优先、默认人数不从聊天推断，修改档案不改变既有餐单。所有用户和服务器文本以普通插值渲染。

## 验收

| 检查 | 结果 |
|---|---|
| UF1专项测试 | 3个测试文件、28项全部通过 |
| npm run test | 22个测试文件、142项全部通过 |
| npm run type-check | 退出码0 |
| npm run build | 退出码0，78个模块转换 |
| git diff --check | 退出码0；仅提示既有文件LF/CRLF转换 |
| 新增/本次文件行尾空白 | 全部通过 |

测试使用虚构数据和替身网络，无模型或Embedding请求、无8080档案测试写入。当前证明包括API路径/请求体/Cookie-CSRF、正整数/null边界、保存/修改/清空语义、非法输入不写入、未知写后手动GET、连点抑制、加载/保存错误、跨账号读写隔离、卸载晚响应、最终401清理、页面模板和登录路由/旧设置导航兼容。SSR模板测试不等于真实浏览器验收。

## 风险、人工验收与停止点

- U1接口只在后端工作树，未整合部署；本卡没有启动/重启服务、运行数据库迁移或写当前开发库。真实HTTP、PostgreSQL持久化及浏览器刷新/重登均待另行获准部署后验收。
- 用户浏览器人工清单：登录后打开个人档案；读取未设置与已保存值；保存新值、修改值、显式清空；刷新及退出重登确认回读；两个账号互相隔离；非法/空输入不发送；模型设置、菜谱页、原登录导航与受保护路由回归。
- 完成本卡后停止等待主架构轻量独立复审；不进入UF2，不发送其他会话消息，不部署/收费/迁移开发库/提交/合并/推送。
