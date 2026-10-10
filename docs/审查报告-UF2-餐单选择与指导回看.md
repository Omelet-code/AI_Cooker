# UF2轻量复审回执

## 最新修复复审与签收（2026-10-05）

用户要求复审修复并推进。原前端gpt-6.1-sol/medium已交付且idle；主架构独立完整前端测试26文件/177项全部通过，type-check/build通过，tracked diff和10个本次修复文件空白检查无错误。核对实际状态机/解析器/视图、正确行为持续测试及教学补充，未发现本次修复残余阻断。

- R1关闭：REVISE按expectedVersion+1接受，7→8展示及pending结束、旧结果不覆盖已知更高版本持续回归通过；SELECT/GUIDE版本不递增。
- R2关闭：独立餐单代次/读取控制器和原目标记录，换餐/新餐取消等待且旧链失效；迟到SELECT/餐单GET/指导GET/GUIDE、不同会话/餐核对、新版本原选项、未知同目标新键均有正确行为回归。原键核对不自动生成指导，生成前精确核原版本和选择。
- R3关闭：semanticSearchCalls保留number|null，合法FAILED结束pending且不伪造0，负数/字符串拒绝。

UF2实现及非收费自动化验收签收；35项UF2专属测试包含本次修复和四菜一汤两套事实/指导解析与页面渲染，已在177项全量运行内执行，不重复累加。主架构未重跑历史缺陷探针，复用其历史复现证据；旧探针留文档区，不在常规测试集。未把渲染测试称为实际浏览器点击/真实HTTP/供应商质量验证。

下一进入U5联调准备，见 `阶段3-U5联调与初步真人试用计划.md`。当前仍未部署后端V10～V12/未真人试用，部署、备份迁移与真实调用窗口分别待允许。本轮只复审和推进计划，不新派实现任务、改业务代码、收费、部署或Git操作。下方暂不签收/R1～R3开放为修复前历史。

日期：2026-10-04。结论：暂不签收，先修复R1～R3，不能推进U5部署/真人试用。原前端已交付且idle；本轮不修改业务代码、不派发修复或下一任务、不收费/部署/Git操作。

## 独立证据

在0f47/frontend独立执行完整npm test：25文件/164项全部通过；type-check、build通过。tracked diff及14个本次新增交付文件空白检查无错误（既有LF/CRLF提示不算错误）。核对新模块、路由/布局、后端实际版本与指标契约、工作/教学报告；保留无关dirty。

另以实际createKitchenState及响应解析器、隔离替身做4个非收费复现探针，4项均复现缺陷表现；不把这种“断言缺陷存在”的通过计入业务验收。探针留存 `review-probes/UF2-contract-review.test.ts`，从常规测试目录移出。没有真实浏览器/后端HTTP/模型请求。

## 阻断项

### R1 / P2：合法修订的新版本被误判为未知

`frontend/src/modules/conversation/kitchen/state.ts:352` 将所有expectedVersion请求的action.version直接与旧版本比较。但后端register接受REVISE时版本加一（5b09的MealPersistenceServiceImpl），契约也明确登记时递增。合法7→8会保留第7版界面和未知pending；原状态测试替身却返回第7版掩盖问题。

复现：给实际状态提交expectedVersion=7，替身返回契约合法的REVISE动作/餐单version=8；当前activeMeal仍7，pending.status=unknown且提示版本不符。修复需区分CREATE、REVISE、SELECT、GUIDE，保留合法历史重放与当前版本保护；持续回归用真实递增替身并断言新版本被接收。

### R2 / P1：同会话跨餐缺少点击链隔离

`state.ts:108` current只绑定账号/会话和公共代次；`showMeal`同会话换餐不更新点击链代次，视图历史按钮在操作中仍能调用。SELECT迟到后applyAction覆盖当前餐，并继续原餐GUIDE。

复现：对餐30发SELECT暂不返回→showMeal(31)并确认界面为31→返回餐30的成功选择；实际generateGuide调用1次，activeMeal又变30。仅是替身方法调用，不宣称已实际收费。

同一缺口还体现在未知操作核对：餐/会话1的CREATE超时→activate会话2→确认retryPending，仍会POST会话1；UI全局pending列表提供该按钮，方法只有账号守卫。需要显式回原会话/本餐且确认上下文后核对，不能跨当前上下文写入。新餐/换餐及历史读取须有明确提示、取消等待和独立请求代次；当前链失效不自动生成，旧结果仅留原上下文待GET核对。未知动作尚未核对时禁止无提示地另起同目标的新键链。

持续回归至少覆盖迟到SELECT/GET/GUIDE、同会话换餐/新餐、不同会话未知核对、当前版本变化；旧请求不覆盖新餐、不追加GUIDE、不跨上下文POST。

### R3 / P2：合法的未知检索指标null被拒绝

`kitchen/validation.ts:196`、types.ts将semanticSearchCalls限定number；冻结契约允许未知null，后端MealPlanningServiceImpl在语义候选调用前置null，中途失败不能伪造调用次数。合法FAILED结果因此变INVALID_RESPONSE，显示未知且难以结束核对。

复现：实际parseMealAction接收合法失败动作，仅semanticSearchCalls/queryEmbeddingRequests设null，抛ApiError。需要保持number|null，正常失败结果按FAILED处理；错误字符串/负数仍拒绝，未知不显示为0。

## 已通过部分与推进边界

路径、独立模块、旧/chat保留、SELECT/GUIDE分键、已有指导GET复用、历史只读、来源锚点/可选补充分区、默认人数展示及UNAVAILABLE基本隐藏已接入；不能用其覆盖上述缺陷。教学文档已交付，不等于用户已经掌握。

已准备最小修复卡 `派发修复-UF2-R1至R3.md`，待用户明确允许原前端修复。复审通过后再进入U5准备：另批开发PG备份/V12迁移和新后端/前端部署，先非收费真实联调，再另批可验证调用窗口与预算。当前无需在旧服务上人工测试新功能。
