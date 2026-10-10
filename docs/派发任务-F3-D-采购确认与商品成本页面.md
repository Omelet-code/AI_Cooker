# F3-D：采购确认与商品成本页面

2026-10-06恢复执行授权：用户在未交付复审后明确“派发执行”。仍仅本卡F3-D、原前端gpt-6-luna/high、0f47树；UF2已完成，不重做或将其报告充作本任务交付。下方首次接收回执是历史，恢复发送结果另记。

日期：2026-10-06。T17轻量复审通过；用户已明确允许向原前端派发F3-D，模型gpt-6-luna/high。仅实现及非收费测试/教学，部署和真实联调另允许。发送回执另记。

## 1. 执行者与输入

原前端会话 `01a0f144-7c62-7a42-a1c0-86c64a3110c3`；工作树 `C:\Users\Omelet\.codex\worktrees\0f47\Cooker`，不新建/换树。完整阅读本树AGENTS.md、技术文档.md及深层规则、本卡、T16/T17冻结契约和复审回执、既有UF2状态约束。最新后端仅从 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker\backend` 的shopping Controller/DTO/VO及专项HTTP测试只读核对，不复制或修改后端。

输入是服务器所选套餐、原料预览及T16/T17接口；输出是食材逐行确认、保存采购需求、明确选城市/匹配商品、成本/采购组与只读快照回看。默认real，不静默回退前端Mock；测试替身不是实际后端集成证据。

## 2. 精确允许范围

- 新增 `frontend/src/modules/shopping/` 的类型/严格解析、API、状态/Store、专属组件与测试；新增 `frontend/src/views/ShoppingView.vue` 及视图/路由链测试。
- router/index.ts仅新增认证保护的 `/shopping`（入口）、`/shopping/new`（预览/确认）、`/shopping/:listId`（回看），命名避免动态路由吞掉new。
- KitchenAssistantView.vue仅新增已选套餐到采购确认的RouterLink及必要computed：携带conversationId、mealId、expectedVersion、optionId四个标识。仅SELECTED、CURRENT、有明确人数/所选方案且无在途/未知操作时可进入；无需先成功生成指导，不发送采购POST或GUIDE。
- AppLayout.vue仅新增采购导航、采购未确认操作的警告/回原上下文入口和退出/关闭确认；保留全部旧模块行为。复用registerSessionCleanup，不改身份/共享请求代码。
- 交付 `docs/工作报告-F3-D-采购确认与成本页面.md`、`docs/技术文档-F3-D-采购状态与金额展示.md`。

不改后端/迁移/依赖、shared请求认证、旧厨房状态/Store及业务行为、旧聊天/档案/菜谱、根架构文档和既有契约。范围不足先停报，不扩大。保留全部dirty，主架构同树文档不得覆盖。

## 3. 页面与真实接口

### 原料确认

`/shopping/new`从query读取四个来源标识，仅校验合法格式，不把query当权威事实；主动GET预览，读取服务器sourceFingerprint、完整lines、人数/预算。前端不能自行拼菜谱原料、从LLM正文抽料、继承自然语言已有材料或伪造源指纹。

逐原料显示菜名引用/原文、原单位/数值、可选标记、参考折算及未知原因。每行用户明确选HAVE_ENOUGH或NEED_BUY，不能默认用户足量已有；所有行含可选原料均需确认。可靠reference无需数量输入/覆写；needsConfirmation或无可靠参考才允许confirmedAmount与confirmedUnitCode，普通十进制字符串>0≤1e9、最多8位小数，不转换成JS Number再回传。可留两值null保存待确认，显示未知而不是0。

单位跟随后端实际规则：原G/KG或ML/L允许同维选项，其余有原单位仅同单位；原单位缺失仅允许明确G/KG/ML/L。不把COUNT/CUSTOM换质量，不新增unitLabel/食材映射字段。足量已有不声明原菜谱消耗量为0。

GET `/api/conversations/{conversationId}/meals/{mealId}/shopping-preview?expectedVersion=…&optionId=…`；POST `/api/shopping-lists`发送requestId及四个来源字段、sourceFingerprint和完整confirmations，每项lineKey/availability/confirmedAmount/confirmedUnitCode。首次201/重放200同样完成。确认成功先解除busy/登记已知结果，再发布新listId导航，不能重演UF2成功导航误报未知。成功后到 `/shopping/:listId`，不自动匹配商品。

### 清单与商品

GET `/api/shopping-lists/{listId}`只读原需求；GET `/api/shopping-lists/{listId}/product-matches/latest`读取最新已保存匹配，data=null是尚未匹配不是错误。清单DRAFT可保留未知量并匹配其它确定行；用户修改确认要从来源显式创建新清单，不能假定已有PATCH。来源CHANGED仅只读提示，禁新报价；失权404隐藏数据。

用户明确选六位cityCode字符串（可以提供少量固定城市选项或城市代码输入，并解释Mock支持情况），点“匹配模拟商品”才POST `/api/shopping-lists/{listId}/product-matches`，仅requestId/cityCode。不接受前端商品、单价、用量或URL。之后GET仅看快照，不自动再报价；换城市/刷新报价须明确新操作、新UUID，不自动更新历史。

匹配响应严格沿用实际ProductMatchView：schemaVersion/id/listId/sourceFingerprint/factsStatus/provider/realData/catalogVersion/cityCode/capturedAt/matchingStatus、lines、purchaseGroups、costs、message。状态及null值以T17实际VO/契约为准，不臆造字段。每行给匹配/缺货/无商品/用量待确认等中文原因；组展示原itemKeys、汇总量、规格、包数、支出。总支出仅展示服务器costs/purchaseGroups，不能再加明细独立行支出，不能用parseFloat自行计算权威成本。

金额分为独立来源预估、Mock报价折算的消耗成本、整包采购支出；null显示“未知/暂无估价”而非0，0.00必须显示0。显示knownSubtotal/unknownItemCount及独立预估小计/未知数、预算状态。OVER_BUDGET仍保留清单并提示，UNKNOWN不承诺符合预算。醒目MOCK/realData=false，purchaseUrl=null禁用购买，不能造美团链接或声称已支付真实成本。数据事实文本以Vue插值展示，不v-html。

### 回看范围

`/shopping/:listId?matchId=…`可通过GET指定报价回看旧价；无matchId读取latest。成功结果提供可复制/收藏的清单和当前报价链接，刷新/重登只读回服务器。`/shopping`提供从小厨进入的指引及已知清单ID的打开入口。当前后端没有“所有清单/全部报价列表”API，不伪造完整历史列表、私自新增接口或拿本地数组冒充PG历史。浏览器已知URL回看足以验此切片；全量历史索引后续单独设计。

## 4. 代次、幂等与未知结果

采购状态独立，复用共享request的认证/CSRF及默认短超时，不改全局配置。每个写操作保存原用户/来源或listId/城市、UUID及不可变payload；双击只发一次。同键重试必须原路径原payload，修改确认/城市不得沿用旧键。未知操作未核对时不发同一上下文的新POST。

超时/网络断开/取消等待/不可信响应保留未知，不宣称服务端停止/零写入；可取消本地等待。用户显式“核对原操作”才重放原POST，同键成功后读已知ID；GET不能按内容推测某个未知UUID成功。已知400/403/404/409等明确拒绝与未知网络状态区分，不能自动换UUID重发。未知HTTP失败若不能证明无提交，不擅当未保存。

用独立请求/上下文代次及owner隔离迟到结果，包括同账号A→B→A、同清单切报价、跨清单/来源切换及路由参数变化。迟到结果不能覆盖新页、触发导航、清掉新busy或自动匹配。未知上下文可在同一SPA会话保留供回原路径核对；账户退出清理私有状态，不向新账号重放。刷新/关闭前给未确认警告；本切片不承诺整页重载后恢复未保存UUID，不加入Token/Key或采购正文的浏览器持久存储。已成功拿到ID的页面刷新/重登必须正常读取。

## 5. 验收、交付与下一步

非收费Vitest含解析/API、逐行确认/数量单位、未知与0、空latest/部分匹配/超预算、Mock标识/购买禁用、组金额不重复、创建成功路由激活、双击/取消/显式原键核对、账户A→B→A及跨来源/清单/报价迟到响应、刷新链接只GET。路由相关回归通过实际Vue watch/Router激活链，不仅测脱离页面的工具函数；新增入口保留旧厨房和退出保护。

执行全量前端测试、type-check、build、diff及新增空白检查。报告改动清单、真实测试命令/数字、风险/未验项；不得用替身声称已完成后端PG/真人验收。教学文档讲类型/API/状态/组件地图、需求与报价生命周期、未知/幂等/代次、十进制与金额展示，给新技术学习点、面试问答/自检。

不收费、不启动/重启/部署、改环境变量或运行配置、不写开发库、不Git。完成后停等轻量复审。复审通过后另获开发库备份/V14升级、明确Mock目录标准ID配置与前后端启动允许，再由用户做真实页面验收；不可省略Mock来源说明。

## 派发回执

恢复回执（2026-10-06）：用户明确“派发执行”后已再次发送原前端，显式gpt-6-luna/high；即时快照active、turn `01a11163-4ad2-7801-874e-8a6f39e95962` inProgress/error=null。已明确本轮只F3-D、0f47执行、不重做UF2；仅接收/执行状态，待本轮实际代码/测试/两份报告和轻量复审。未派发其它任务，不部署/收费/开发库写入/Git，下方首次发送记录保留。

2026-10-06：已成功发送原前端会话，显式gpt-6-luna/high；即时快照active，turn `01a11151-d7a0-7863-9dc5-868119dc01fd` 为inProgress、error=null。仅F3-D实现与非收费测试/教学待交付、轻量复审，不代表完成/部署/真人验收。未向后端派发新任务，不收费/启停/开发库写入/Git/T18。
