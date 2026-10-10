# F3-A 模型设置页工作报告

## 交付内容

- 新增模型凭证设置 API 适配器，复用共享 `apiClient` 的 Cookie、CSRF 和身份续期流程，对接 T8 的状态读取、保存和删除接口。
- 新增模型设置页及受保护路由 `/settings/model`，并在应用导航中加入入口。
- 新增页面私有的凭证状态管理：区分读取中、读取失败、已配置和未配置；Key 不预填、不持久化、不回显，切换账号或离开页面时清空。
- 输入校验拒绝全空白内容，并按 Unicode 码点限制最多 4096 字符；合法输入原样发送，不做 trim 或前缀校验。
- 写操作避免重复提交。网络错误或超时不自动重试、不自动回读，并提示用户手动读取状态；成功响应只说明凭证存在，不代表 Key 已验证有效。
- 删除需要用户确认。Mock 环境未实现接口时保持 404 错误，不显示成功状态或回退到真实接口；开发 Mock 状态沿用布局标记。

## 修改文件

- `frontend/src/modules/settings/api.ts`
- `frontend/src/modules/settings/types.ts`
- `frontend/src/modules/settings/useModelCredentialSettings.ts`
- `frontend/src/views/ModelSettingsView.vue`
- `frontend/src/router/index.ts`（新增受保护子路由；文件中另有先前阶段的并行改动）
- `frontend/src/layouts/AppLayout.vue`（仅增加模型设置导航入口；文件中另有先前阶段的并行改动）
- `frontend/tests/model-credential-settings.test.ts`
- `frontend/tests/model-settings-view.test.ts`

## 验收结果

在 `frontend/` 执行：

- `npm run test`：通过，14 个测试文件、72 个测试。
- `npm run type-check`：通过。
- `npm run build`：通过，Vite 生产构建成功，设置页以懒加载资源输出。
- `git diff --check`：退出码 0。Git 仅报告共享 worktree 中若干既有文件的 LF/CRLF 转换提示，没有空白错误。

测试覆盖 API 路径与请求体、共享 CSRF 请求头、Mock 404、状态加载失败、输入长度和原样提交、重复提交、删除确认、超时不自动重试、账号切换与迟到响应、销毁清理、错误内容不回显，以及设置页 SSR 展示。

## 未执行项与手工检查清单

按任务边界，本次未启动或操作浏览器，也未连接后端、数据库或真实模型服务，未使用真实 Key。后续手工联调可检查：

1. 登录后从导航打开“模型设置”，确认状态读取加载、成功和失败三种表现。
2. 用测试凭证保存和替换，确认输入框在成功后清空，状态接口的 `configured` 不被当作有效性验证。
3. 删除时取消确认和确认删除各一次，确认取消不发请求。
4. 在浏览器网络面板模拟保存请求超时，确认页面不自动重发写请求，并可由用户点击重新读取状态。
5. 退出登录或切换账号/离开页面后，确认输入内容不再显示。
6. 使用开发 Mock 模式打开页面，确认布局显示“开发 Mock”，未实现的凭证接口显示错误且不会误报保存成功。

## 学习要点

- 页面把 Key 留在组件作用域的 Vue `ref` 中，而不是 Pinia 或浏览器存储；通过组件作用域销毁和用户 ID 监听实现清理与隔离。
- Vue 模板会自动解包顶层绑定的 `ref`，但普通对象属性中的嵌套 `ref` 不会自动解包；页面因此将需要渲染的状态解构为顶层绑定。
- 网络超时表示“结果未知”，不能推断服务器没有处理。状态回读只能回答凭证是否存在，所以替换操作仍需明确告知用户其 Key 未经验证。
- `Array.from(text).length` 按 Unicode 码点计数，可以避免将代理对字符误算成两个字符；校验只检查，不改写原始输入。
