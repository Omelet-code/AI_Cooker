# F2-B 菜谱页面与收藏联调工作报告

日期：2026-10-01
工作树：`C:\Users\Omelet\.codex\worktrees\0f47\Cooker`

## 实现与修复

完成 s2-api-v1 菜谱列表/搜索分页、详情、收藏列表；复用 F2-A fetch、CSRF、续期和私有会话清理。R1/R2 按 `docs/修复任务-F2-B.md` 完成修复，细节和原因见 `docs/修复报告-F2-B.md`。

- `frontend/src/modules/recipe/format.ts`：数值化数量优先；缺少数值时显示原始食材行，只有原文也缺失时提示未知。
- `frontend/src/views/RecipeDetailView.vue`：直接使用格式化函数，并保留“（可选）”标识；只用安全文本插值。
- `frontend/src/stores/recipe.ts`：收藏成功后更新缓存并重读已加载/在途的列表、收藏、详情查询；新请求序号阻止旧响应覆盖；收藏末页超界时回退到有效页，错误和 loading 状态收敛。
- `frontend/tests/recipe-format.test.ts`、`recipe-detail-view.test.ts`、`recipe-store.test.ts`：覆盖格式化、真实详情组件 SSR、收藏读写交错、分页、错误与会话竞态。

## 自动化验收

- `npm run type-check`：通过。
- `npm run test`：9 个文件、40 项测试通过。
- `npm run build`：通过。
- `git diff --check`：通过；仅有 Git 的 LF/CRLF 行尾转换提示，没有空白错误。

## 真实接口联调历史证据

此前双账号真实 HTTP 联调通过：372 条菜谱中第一页读取和详情成功，添加/重复添加收藏成功，刷新认证后仍能读取；第二账号收藏列表为 0，取消后第一账号收藏列表为空。使用的两个专用测试账号已退出，未更改既有用户收藏或菜谱。

## 浏览器验收状态

Windows 浏览器自动化 helper 初始化异常，真实页面点击流程仍未验证。用户将亲自完成 real 模式浏览器验收；建议检查详情原始用量/可选标识、收藏/取消与页面切换、末页删除后的页码和总数、刷新持久化、双账号隔离。HTTP 或组件单测均不替代此项。本报告不代表 F2-B 最终签收。

