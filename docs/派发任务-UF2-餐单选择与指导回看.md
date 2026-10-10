# UF2：餐单选择与指导回看

日期：2026-10-04。用户明确授权派发UF2；本卡冻结前端实现范围，不包含部署、真实模型/Embedding调用、U5实施或Git操作。U3/U4后端已通过独立非收费复审，但尚未部署开发库。

## 1. 执行者与基线

- 沿用原前端会话 `01a0f144-7c62-7a42-a1c0-86c64a3110c3`，模型 `gpt-6-luna`、思考强度 `high`。
- 所有命令显式工作目录 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`；不按历史元数据显示的E盘目录开发，不新建或切换工作树。
- 开始前完整阅读本树AGENTS.md、技术文档.md及深层规则；阅读本卡、`阶段3-U3餐单与选择契约.md`、`阶段3-U4依据生成契约.md`、`小厨基础人设.md`。契约相对本卡目录解析。
- 最新后端实际Controller/DTO/测试仅从 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker\backend` 只读核对；不得改该树或复制提交、部署后端。
- 记录自身基线和本次文件清单，保留既有dirty。主架构同树维护架构文档，不覆盖其变更。

## 2. 目标、输出与允许文件

完成自然语言提出本餐需求→两套真实餐单及材料→鼠标选定→依据生成详细指导→历史回看的页面接线。运行时是单Agent固定流程，不实现自由工具循环或新业务接口。

- 新增 `frontend/src/modules/conversation/kitchen/` 专属类型、API、状态、组件及测试；新增 `frontend/src/views/KitchenAssistantView.vue` 及专属视图测试。
- `frontend/src/router/index.ts` 仅添加受保护的 `/kitchen`、`/kitchen/:conversationId`；保留旧 `/chat` 和ChatView。
- `frontend/src/layouts/AppLayout.vue` 仅新增清晰导航及必要的本模块未完成动作警告/退出确认；不改变旧认证、做菜和聊天的行为。
- 输出 `docs/工作报告-UF2-餐单选择与指导回看.md`、`docs/技术文档-UF2-餐单与指导状态.md`。
- 不改后端、依赖、共享请求/认证、UF1档案实现、旧聊天API/状态、菜谱领域、根架构文档或契约。需要额外文件/行为时先报告停止相关越界部分。

## 3. HTTP及数据接线

复用已有conversationApi的列表/创建/详情；新餐单及指导使用新API模块，绝不把餐单伪造为旧messages。apiClient自动加 `/api`，调用路径不得重复前缀。

| 动作 | 路径（相对/api） | 输入/用途 |
| --- | --- | --- |
| 创建本餐 | POST /conversations/{id}/meals | requestId、content、可选diningCount |
| 餐单列表/详情 | GET /conversations/{id}/meals及/{mealId} | 服务器分页/当前版本 |
| 修订 | POST /conversations/{id}/meals/{mealId}/revisions | requestId、expectedVersion、content、可选diningCount |
| 选择 | POST /conversations/{id}/meals/{mealId}/selection | requestId、expectedVersion、optionId |
| 指导 | POST /conversations/{id}/meals/{mealId}/guides | requestId、expectedVersion、optionId |
| 动作/指导回读 | GET …/actions/{actionId}、…/guides/{guideId} | 状态及完整指导；零模型 |

以冻结契约和实际DTO为准，严格校验关键运行时类型/枚举/归属/版本，不只用TypeScript断言。所有BIGINT标识和数量小数保持字符串，未知保留null，不用浮点重新计算材料。

POST新终态201也可能是FAILED；200是复用、202是在途，不能凭HTTP成功就显示完成。400/404/409/503按服务端状态解释。MealActionView包含action和meal，guide仅引用，全文另GET GuideView。旧SELECT动作回放可能是较早快照，不用它覆盖较新餐单或清除已保存指导。

## 4. 页面与人数、材料、出处

- 展示会话及本餐历史、明确的“开始新的一餐”和自然语言输入；本餐不自动沿用上一餐约束。CLARIFICATION显示服务器问题并走revision，旧版本按钮失效。
- 只读复用UF1档案API展示默认人数和设置入口。本餐显式输入优先；未输入则不带或传null让服务器采用档案，不能把档案值伪装EXPLICIT。档案加载失败不当作未设置，不猜人数；既有餐人数及来源以快照为准。
- OPTIONS_READY展示两套餐单，菜/汤分组、材料原量与参考折算、未确认/未知量、合并清单及服务器提示；仅一套或无匹配不凑数。预算未核实、已有材料不等于库存等提示保留。
- GUIDANCE展示overview、准备、逐菜解释、烹饪顺序、注意事项、独立“模型补充：可选建议”；同时可查看原菜谱材料/步骤，说明事实与模型解释的区别。
- evidence的recipeId/stepNos/ingredientIds关联当前实际事实；ingredientIds是recipeIngredientId。用站内菜谱详情和来源锚点，不渲染模型外链或v-html。
- CHANGED可标明历史事实已变化供回看，禁止据旧版本新选择/生成，引导修订；UNAVAILABLE严格隐藏菜名/材料/内容，不保留旧缓存泄露。

## 5. 点击链、未知结果与费用边界

- “选这套并生成指导”是一次明确用户动作：先提交零模型SELECT，确认当前归属/版本/选择且成功后，才发独立GUIDE；两步使用不同UUID和各自不可变payload。双击只启动一条链。
- SELECT响应未知、失败、非CURRENT或点击链已过期时，不自动GUIDE。同方案200复用后GET当前餐单核对：已有guide则GET回看，不能多发生成POST；重新进入页面的GET结果不能启动生成。
- 页面打开、刷新、重登、历史回看只GET。GUIDE失败保持选中方案，旧失败键只读回原失败；用户明确确认“重新生成，可能产生费用”后才用新键重试，不自动更换UUID。
- 网络失败、超时、取消、5xx或无法可靠解析的2xx按结果未知处理，保留原requestId/payload/账号与会话/餐/版本/选项上下文；不自动重新POST。已知actionId只GET有界轮询，最多30次且总计120秒。
- 未取得actionId的未知动作提供明确人工确认后的原键原payload核对入口；这可能继续执行，不宣称免费。创建会话没有同等幂等协议，结果未知不自动重建会话。
- 创建/修订/指导POST等待可用125秒显式timeout；GET和选择保留默认短超时。取消只取消前端等待，不能声称服务端已停止或免计费。保留共享请求既有明确认证/CSRF拒绝处理，不新增未知网络重试。
- 账号、路由、会话、本餐变化取消等待并用请求代次隔离迟到结果；pending时提示未确认状态和核对方式。退出清理本模块私有内存，不能跨账号显示。不要把对话/Key/Token/请求payload存入localStorage或sessionStorage。

## 6. 验收与教学汇报

测试使用专属注入替身，不扩大全局Mock或在real模式失败时静默回退；不声称替身就是真实联调。至少覆盖：

- 四菜一汤两套、单套/澄清/无匹配、材料未知与参考量、默认/显式人数及档案失败、修订版本。
- 实际视图按钮SELECT→GUIDE两POST及不同UUID；双击、已有指导复用零新增GUIDE；失败或未知SELECT不继续生成。
- GUIDE失败保留选择、显式新键重试；201失败/202轮询、失效响应/网络未知原键保留、轮询上限；刷新/历史仅GET。
- 迟到响应、换账号/会话/新餐、取消；CHANGED/UNAVAILABLE回看隐藏；文本安全、来源锚点、大整数ID。
- 旧聊天、UF1、登录/收藏/做菜记录回归不被破坏。

执行完整 `npm run test`、`npm run type-check`、`npm run build`、tracked diff与本次新增文件空白检查，报告实际计数/命令、修改文件、未验项。工作报告给出待部署后的浏览器人工清单。

教学文档简要解释Vue组件/API/状态层、SELECT与GUIDE分离原因、幂等与未知结果、服务端快照与前端状态、材料事实/引用/可选补充；列新技术学习点、面试问答与自检清单。每一阶段如遇阻塞先汇报，不自行拓展；完成后停止等待主架构轻量复审。

## 7. 真人试用闸门

UF2复审通过后即可进入U5的初步真人试用准备，不必等采购/MQ/Cloud。需另获本地部署允许，备份后升级开发PG至V12、运行5b09后端和本树新前端，先非收费真实联调。真实生成还需另获调用预算与可验证的服务器配额/限定窗口；不因本卡默认开启模型或做收费请求。初步试用与后续质量统计分开记录。

## 8. 派发回执

冻结卡已发送原前端会话，显式模型gpt-6-luna/high；一次即时快照确认active、turn `01a10741-d27f-73c2-8f00-10ba3139f0d6` inProgress、error=null。当前等待实现交付和主架构轻量复审；发送和开始执行不等于实现或验收完成。未派发后端/U5，未收费/部署/Git操作。
