# PF2 工作报告：厨房指导与材料详情

日期：2026-10-07

## 完成内容

- 未选餐单时仍展示服务器返回的候选方案；卡片只展示菜名、搭配评价和选定操作，长材料列表改为“查看材料清单”入口。
- 服务端确认 `SELECTED` 后，正文展示指导；不再并排重复显示两套方案。右栏只显示由 `selectedOptionId` 查出的所选方案菜名、评价及材料详情入口。指导读取失败时摘要仍保留，重生成仍是原有显式按钮。
- 指导按菜谱组织。每菜分开展示保存的原材料/步骤/厨具/补充事实、小厨讲解、该菜可选模型补充和服务器给出的本餐参考量；整餐备菜独立保留。
- 烹饪顺序与注意事项继续作为整餐公共内容折叠展示，不根据文字猜测归属。跨菜或未指定单菜的模型补充保留在公共补充区，不当作事实或材料。
- 引用移到指导末尾，按经过解析的 `evidence.recipeId` 去重后链接菜谱详情。没有删除或改写 `stepNos`、`ingredientIds` 校验；可选补充的 `recipeIds` 只用于适用范围，不参与事实引用集合。
- 新增登录保护的材料详情路由，使用既有 `getMeal`。读取前校验 URL 标识、当前账号代次、会话/餐单归属、版本、事实状态及方案归属；`CHANGED` 明示历史快照，版本不匹配或 `UNAVAILABLE` 不替换成当前版。
- 材料页完整展示合并参考、待确认项、逐菜原始材料和参考折算。返回工作台携带原 `conversationId`、`mealId`、`version`、`optionId`；工作台只读 GET 恢复餐单，不触发 SELECT、GUIDE 或采购写入。
- 既有采购入口的计算条件和购物 API/状态均未改。

## 本次改动文件

- `frontend/src/router/index.ts`
- `frontend/src/views/KitchenAssistantView.vue`
- 新增 `frontend/src/views/KitchenMaterialsView.vue`
- `frontend/src/modules/conversation/kitchen/components/MealGuidePanel.vue`
- `frontend/src/modules/conversation/kitchen/components/MealOptionCard.vue`
- 新增 `frontend/src/modules/conversation/kitchen/components/guidePresentation.ts`
- 新增 `frontend/src/modules/conversation/kitchen/materialsState.ts`
- `frontend/tests/kitchen-view.test.ts`
- 新增 `frontend/tests/guide-presentation.test.ts`
- 新增 `frontend/tests/kitchen-materials-state.test.ts`
- 新增 `frontend/tests/kitchen-materials-view.test.ts`
- `frontend/tests/shopping-kitchen-entry.test.ts`（仅给既有简化路由夹具补上应用中的聊天/材料路由；采购断言未变）
- 本报告及 `docs/技术文档-PF2-厨房指导与材料详情.md`

## 验收记录

- PF1/PF2 本次专项：`npm run test -- tests/pf1-navigation-profile.test.ts tests/kitchen-view.test.ts tests/kitchen-materials-view.test.ts tests/kitchen-materials-state.test.ts tests/guide-presentation.test.ts`：5 个文件、22 项通过。
- 全量：`npm run test`：36 个文件、233 项通过。
- `npm run type-check`：通过。
- `npm run build`：通过，Vite 转换 109 个模块并完成生产构建。
- 对本次改动文件执行 PowerShell 行尾空白扫描；Git 差异检查结果见整卡交付说明。

以上均为本地自动化和构建证据，不是浏览器人工验收或已部署真实 API 联调。没有写开发数据库，也没有真实模型/Embedding 请求。
