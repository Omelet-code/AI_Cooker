# D1-D1F 工作报告：原文用料展示

日期：2026-10-08

## 结果

已完成前端原文用料展示收尾。对没有服务端结构化原量的材料，页面完整显示 `rawLine`；缺失时回退 `rawName`/页面名称，均为空时显示“原文未提供”。这些行不再重复呈现“原量未知/参考量未知”的数字列，而显示“按原文准备；采购总量待确认”。页面不从原文中抽取 `1 ml`、`1 小块` 或括号中的 `15g`。

服务端给出明确原量时仍显示该数值和范围。参考量未知时独立显示未知及服务端原因，例如原量为 300 克但人数未知，不会因此隐藏 300 克。

## 实现与范围

- 在 `frontend/src/modules/recipe/format.ts` 增加纯展示函数：选择原文回退文本，以及仅凭服务端数值字段判断结构化原量是否存在。
- 更新菜谱详情、逐菜指导、材料详情和采购确认行。原文通过 Vue 文本插值显示，不使用 `v-html`。
- 材料合并区不将无法提供整餐数量依据的未知汇总重复展示；逐菜原始材料仍保留条目与来源。
- 采购的“已有足量/需要购买”、数量/单位输入及其 `choice`、`amount`、`unit` 事件处理未改；确认量、成本、API、状态、校验、身份权限和历史读取均未改。
- 已知原量但参考量未知的条目仍展示原量和服务端原因。若服务端已给出数量，但其语义属于未识别条件项，前端不会自行判定或改写，只按现有字段展示并保留原因。

## 修改文件

- `frontend/src/modules/recipe/format.ts`
- `frontend/src/views/RecipeDetailView.vue`
- `frontend/src/modules/conversation/kitchen/components/MealGuidePanel.vue`
- `frontend/src/views/KitchenMaterialsView.vue`
- `frontend/src/modules/shopping/components/ShoppingLines.vue`
- `frontend/tests/recipe-format.test.ts`
- `frontend/tests/recipe-detail-view.test.ts`
- `frontend/tests/kitchen-materials-view.test.ts`（只读路由回归保持原断言）
- `frontend/tests/d1-d1f-ingredient-display.test.ts`（新增）
- 本报告与 `docs/技术文档-D1-D1F-原文用料与数量确认.md`

## 验收

在 `frontend` 目录执行：

- `npm run test -- tests/recipe-format.test.ts tests/recipe-detail-view.test.ts tests/kitchen-materials-view.test.ts tests/d1-d1f-ingredient-display.test.ts`：4 个文件、14 项通过。
- `npm run test`：37 个文件、239 项通过。
- `npm run type-check`：通过。
- `npm run build`：通过，Vite 转换 109 个模块。
- 本轮 11 个范围文件的尾随空白检查：通过。

新增专项覆盖两条原始样例、`15g` 不抽取、300 克原量保留、名称回退、空原文、安全文本插值、采购确认输入保留以及材料/指导/单菜详情的未知数量降噪。采购金额和成本代码未改，既有采购状态用例纳入全量通过结果。

## 边界

本轮没有调用模型/Embedding、读写数据库、启停服务、切库或做浏览器真人验收。按任务要求未执行 Git 操作，因此未运行 Git diff/status；现有工作树其余内容未主动清理或覆盖。当前交付停在轻量复审，不代表切库或上线。
