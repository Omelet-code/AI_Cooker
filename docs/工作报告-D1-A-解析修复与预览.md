# D1-A 工作报告：解析修复与只读预览

日期：2026-10-07（Asia/Shanghai）。状态：D1-A新人数策略v2、非收费回归与只读提案已交付，等待主架构轻量复审。最新人数策略和验收见第9节；第8节R1是已被用户覆盖的历史契约。**没有应用开发库修复，也没有重建索引或部署。**

## 1. 目录、授权与保护

实际实现目录为 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker`，不是项目登记目录E:\Cooker。模型按最新派发回执为gpt-6.1-sol/high。HEAD始终为 `26c33ace4ad22e72350cba65cccb6f528c299917`，分支 `codex/kitchen-assistant-u3`。复用检出；没有新建工作树、会话或目标，没有向其他会话发消息，没有Git提交/合并。

已阅读5b09根协作规则/技术文档，并通过基线全文与差异重建核对0f47最新主文档；D1-A窄范围卡、D1审计/总修复卡、只读SQL和ADR-003/004/011为输入。应用diagnose流程，不初始化Issue系统。宿主默认沙箱不能完整访问5b09目录/Git/Docker，按正式require_escalated审批完成访问与限定写入；没有更改ACL或偷换目录。

以下为初次D1-A保护记录，R1另以第8节为准。`.data/d1-a/baseline.json`记录起始dirty；`source-baseline-hashes.json`记录433个已有源码/测试/资源。收尾核对仅4个允许文件变化，其余429个哈希一致，U3～T17所有范围外未提交文件保留。技术文档根文件、旧迁移、模型网关、其他业务模块、前端、IDE/运行配置未修改。诊断没有读取账户Key、Cookie或账户内容。

## 2. 修复结果与边界

| 问题 | 最终规则 |
| --- | --- |
| 小龙虾/香叶尾等号 | 分隔符不进入材料名；明确“两片/一个”等数量解析，rawLine原样保留 |
| 黑鳕鱼逗号与部位 | 保留“黑鳕鱼，带皮”“青葱，葱白”等属性；主句“2片，450g”多组量不选一个 |
| 中文量/范围 | 确定中文整数、小数、半单位和明确范围可结构化；口语省略位值、约量、公式量保持未知 |
| 重复单位范围 | 5g-10g、15g到30g按明确范围保存，不与逗号分隔的多组量混同 |
| 原料章节遗漏 | 计算无可靠列表时从必备原料恢复；工具、小标题/单位说明排除，可选章节只标可选材料 |
| 单位不一致 | kg/公斤/千克数量一次转为G/g，L/升一次转为ML/ml；mL大小写兼容；合法克/毫升标签保留以减少无端漂移 |
| 斤、计数、包装 | 斤保留CUSTOM/斤，不作地域换算；个/瓣/片/包/茶匙等不互换，不按密度变为克/毫升 |
| 人数（v2最新） | 明确人数按值；约人数按给定值；完整有效范围取下限；非法/冲突/份/尺寸/整批产量仍未知 |
| 配方变体 | 戚风表格与桂林十八酿组合均只留待审定诊断，不挑列或展平配方 |

材料器具排除词独立于旧厨具投影词典，旧T5筛选/排序未改变。主句明确数量不会因备注“约100g”变成猜测重量；备注数量留在原文，不相加、不作为单位换算依据。真正多组主句量保留未知。数值字段与标签一起归一化，原始数量/单位继续由rawLine和原Markdown追溯。

`ParsedIngredient`、`ParsedRecipe`构造器与字段形状保持兼容，没有新业务表、HTTP入口或启动触发器；解析诊断沿用warnings通道，当前规则版本为 `d1-a-parser-v2`，旧v1提案/日志保留。导入器未修改或调用，同版本SUCCESS仍幂等跳过，重跑旧导入不是应用修复的方法。材料计算仅兼容标签大小写，不更改原材料对象或旧序列化快照；旧G/kg错配仍拒绝按人数折算，不能再乘1000。

## 3. 全量只读预览

数据来自已运行cooker-postgres容器内的cooker库，固定SQL只读取公开系统原文/事实和公开引用词典。执行时同时使用PGOPTIONS默认只读、REPEATABLE READ READ ONLY事务、20秒语句超时与2秒锁超时，最后ROLLBACK。没有FOR UPDATE。显式JDBC读取接口额外拒绝自动提交、可写或不一致隔离级别的连接。

最初手写导出的摘要查询误用了不存在的kitchen_meal表，SQL报错终止，只读事务未提交；随后按实际meal_session表修正，并用最终固定SELECT资源重新导出完整快照。此环境错误不计入业务回归通过。

每份原文先验证SHA-256；全量372份匹配，解析失败0。比较同一解析器输出与旧事实，按rawLine及出现次序映射旧ID；保留旧sort_order，解析顺序另列，避免排除标题后把所有后续行重排。不存在的标准ID只列候选/待审定，不合并词典或标REVIEWED。

| 最终提案指标 | 数量 |
| --- | ---: |
| 公开系统菜谱覆盖 | 372/372 |
| 旧用料ID覆盖 | 3025/3025，重复0 |
| 原始物理行无法映射 | 0 |
| 未变化语义行 | 2153，oldFields与newFields完全相同 |
| 变化的旧行候选 | 872，含6条排除/移除待复核候选 |
| 新增漏料候选 | 53 |
| 新解析材料行 | 3072 |
| 涉及变化的菜谱候选（v2最新） | 232 |
| 旧未知数量或单位 | 559 |
| 新解析未知数量或单位 | 530，仍需逐项审定 |
| 旧缺量行恢复明确数量 | 286 |
| 旧确定量改为不确定 | 250，主要约量、公式量、多组量，不能冒充数量修复失败或全部人工错误 |
| 缺人数基准（v2最新） | 开发库快照旧310，新296；恢复可用基准16、非法/冲突等改为未知2 |
| 尾等号同名冲突 | 52组，仅列保留全部ID/待审定建议 |

这些是**未应用提案**，不是开发库改善后的统计。530不表示所有旧559条都可恢复，296不表示应全部填1。286/250来自原行数量字段比较，与最终未知并集不直接相加。新单位数量即使明确，包/茶匙等消费契约未支持单位仍可能保持采购待确认。

原9道零用料中7道生成候选：面包10、巴斯克7、龟苓膏4、酒酿3、鱼香肉丝15、煲仔饭8、芝麻烧饼6，共53行；戚风蛋糕和桂林十八酿仍是变体待审定。器具不计入材料；不从操作步骤抽量补料。

## 4. 产物与复现

Git忽略目录 `.data/d1-a/`：

- `manifest.jsonl`：逐行旧/新name、amountMin/Max、unitCode/Label、optional、sortOrder；source/recipe/document/line ID、物理行号、原文和rawLine哈希、规则版本、依据/诊断、标准ID候选、冲突与原chunk影响候选。全部applied=false。
- `summary.json`、`quality-summary.json`：解析覆盖、候选和未知分类。
- `database-input.jsonl`、`database-after.jsonl`：强制只读一致性公开快照；不含账户或完整私有资料。
- `baseline.json`、`source-baseline-hashes.json`、`source-protection-result.json`、`verification.json`：dirty/文件/数据保护和打包哈希。
- `red-regression.log`、`affected-regression-final.log`、`parser-preview-final.log`、`package.log`：真实执行证据。

显式工具：`com.cooker.recipe.diagnostic.RecipeParsePreviewTool`。用当前Java21编译类及现有Jackson2/spring-context依赖运行：

```powershell
# 工作目录必须为5b09根目录；classpath包含backend/target/classes与现有Jackson2、spring-context Jar。
& 'C:\Program Files\Java\jdk-21\bin\java.exe' -cp $classpath `
  com.cooker.recipe.diagnostic.RecipeParsePreviewTool `
  '.data\d1-a\database-input.jsonl' '.data\d1-a'
```

工具没有Spring组件注册，没有启动应用、数据库迁移、导入或索引能力。SQL资源和JDBC方法供显式只读捕获复用；主程序只重解析已捕获快照。影响chunk列表是保守候选，未重算内容hash/向量；不能作为D1-C费用数或READY证据。

## 5. 测试、打包与保护证据

| 执行阶段 | 实际用例 | 结果 |
| --- | ---: | --- |
| 修复前真实格式失败回归 | 10 | 10失败、0错误、0跳过，确实先红 |
| 受影响recipe/材料/采购/检索回归 | 96 | 96通过、0失败/错误/跳过，含真实隔离PG与HTTP |
| 组合变体与最后收口后的专项 | 41 | 41通过、0失败/错误/跳过；与96有重叠，不能相加 |
| 离线package | 不执行测试 | 通过；不是部署或供应商验收 |

以上表为初次D1-A历史证据；R1历史为35项专项与133项受影响回归，详见第8节；新策略最新190项全部通过，详见第9节。前面的28/29/94等仍保留为迭代证据。收费T6未选中，不把“未运行”计为1条实际跳过。没有运行整个仓库全部测试，也没有以旧249项历史证据冒充本次回归。

所有测试写库均使用临时Testcontainers，专属预览类不启动应用；真实断言可写连接拒绝、PG只读写入错误25006、私有原文不出快照、hash漂移拒绝、重复预览一致和未变行不漂移。新增fixture来自数据库公开原文，而非简化Mock冒充真实菜谱。

```powershell
# 在5b09/backend，JAVA_HOME指向JDK21，沿用该目录.mvn/maven.config。
mvn -o '-Dcooker.t6.enabled=false' '-Dtest=D1A*Test,HowToCookMarkdownParserTest,RecipeImport*Test,MealMaterialCalculatorTest,MealFactsPostgresTest,T9RecipeHttpPostgresTest,T10CookingHttpPostgresTest,RecipeSearchPostgresTest,Shopping*Test,Product*Test' test
mvn -o '-Dcooker.t6.enabled=false' '-Dtest=D1A*Test,HowToCookMarkdownParserTest,RecipeImport*Test,MealMaterialCalculatorTest' test
mvn -o '-Dcooker.t6.enabled=false' '-DskipTests' package
```

初次D1-A Jar SHA-256：`45413A440795D975FC075A75B67BA9819BBDF0C93CAA40DA2310D6DDD83F4907`。初次D1-A读取的开发库原文、用料、词典、餐单、方案、指导、购物清单、商品报价、检索投影九组摘要前后一致。R1复用同一公开快照，没有再次连接开发库；该历史一致性证据不表示R1实时重查开发库。保护摘要不包含账户原文，不冒充整库并发写入审计。差异空白检查通过。

## 6. 初次D1-A累计修改清单与代码地图（R1清单见第8节）

已有4个允许文件：

1. `backend/src/main/java/com/cooker/recipe/parser/HowToCookMarkdownParser.java`：材料扫描、明确中文数、范围/未知、人数字段、章节/变体与一致单位。
2. `backend/src/main/java/com/cooker/recipe/service/impl/MealMaterialCalculator.java`：单位标签大小写兼容，原对象保持不变。
3. `backend/src/test/java/com/cooker/recipe/parser/HowToCookMarkdownParserTest.java`：旧范围人数断言调整为真实未知，不降低业务安全断言。
4. `backend/src/test/java/com/cooker/recipe/service/impl/MealMaterialCalculatorTest.java`：mL、基准量和旧错配持续回归。

新增9个源码/测试/资源文件：recipe/diagnostic/RecipeParsePreviewTool.java；diagnostic/recipe/d1-a-readonly.sql；recipe/parser/D1AParsingRegressionTest.java；recipe/diagnostic/D1AReadOnlyPreviewPostgresTest.java；fixtures/d1-a下lobster、cod、pork、chiffon、variants五份公开原文。完整前缀分别为backend/src/main/java、backend/src/main/resources、backend/src/test/java、backend/src/test/resources。

新增两份交付文档即本报告和 `技术文档-D1-A-解析规则与数据治理.md`。没有更新根架构文档或ADR：未改变模块边界、核心技术栈、外部服务、业务schema或HTTP契约；本次规则说明集中在授权的专属教学文档，主架构再轻量复审/回执。

## 7. 未验边界与下一步闸门

D1-B数据库应用、D1-C索引hash重建/Embedding费用、D1-D页面/确认语义、T18、开发部署/启停、Git整合均未实施。没有真实LLM、Embedding或商品平台请求。商品Mock覆盖仍是既有明确ID目录，不因名称修复自动增加覆盖。

候选不等于自动可应用：多组量、范围、配方变体、并列原料名、斤地域、未知人数和同名ID仍需审定。现有数据库中原解析缺陷仍在；旧餐单、指导、报价没有覆写。将来应用事实变化可能触发旧餐单的事实冲突保护，不能通过篡改历史消除。产物保留原ID和规则依据，但不是已经具备回滚/幂等应用事务的治理工具。

本会话完成后停止，等待主架构按本次差异、风险匹配测试与上述保护证据轻量复审，不自行推进后续任务。

## 8. R1历史：人数约量与范围最小修复（已被新策略覆盖）

以下完整保留旧R1实现与验收记录，原null人数策略已被用户最新策略覆盖，不能作为当前拒收依据或新策略验收。旧交付文件另外保存在v2-before。

本次输入为主架构《派发修复-D1-A-R1-人数约量与范围》冻结卡、审查回执及只读独立探针。只改人数模式/parseServings、人数回归和本来两份报告；材料数量、词典、DTO、预览器、其他模块均不变。原`.data/d1-a/manifest.jsonl`等提案、验证和两份报告复制到`r1-before/`；原数据库输入、旧失败日志和初次基线保留。

旧实现约量判断只看前三个字符并要求约词直接邻接数字，无法覆盖“约 2 人份”“大概 3人”和后缀“3人左右”；区间模式缺少“到”，会取末端人数。R1完整识别人数区间（含重复人单位、到/至/或及常用横线），仅在紧邻人数的前后缀识别约量，保留明确2人/两人/十二人及相同确定人数。数字左边界避免从小数或“只/三人”比率中截取人数，不同确定基准仍为未知。材料量和耗时的约词不会污染独立明确人数。

| 真实recipeId | 原文依据 | R1前预览 | R1后预览 |
| --- | --- | ---: | --- |
| 153 / 清蒸鳜鱼 | 每份（约 2 人份）： | 2 | null / UNKNOWN_REQUIRES_REVIEW |
| 177 / 老式锅包肉 | 每份（约 2 人份）： | 2 | null / UNKNOWN_REQUIRES_REVIEW |
| 180 / 芥末罗氏虾 | 每份（约 2 人份）： | 2 | null / UNKNOWN_REQUIRES_REVIEW |
| 192 / 豆豉鲮鱼油麦菜 | 每份（约 2 人份）： | 2 | null / UNKNOWN_REQUIRES_REVIEW |
| 264 / 日式肥牛丼饭 | 约为二人食用分量 | 2 | null / UNKNOWN_REQUIRES_REVIEW |
| 350 / 葱煎豆腐 | 动态份数公式中的1.5只/三人、2根/三人 | 3 | null / UNKNOWN_REQUIRES_REVIEW |

实际6道变更，而非预设只有四道。缺人数319→325；与开发库原事实比较，明确恢复17→15、原确定值改为未知26→30。变化菜谱总数仍237，因为这六道已属于初次材料变化候选。材料逐行JSON记录前后完全相同：旧用料ID3025唯一覆盖，2153个UNCHANGED字段相等，物理行映射缺失0；其他材料指标不变。两次R1全量372道预览的manifest字节哈希一致，所有记录applied=false。

| 本轮执行 | 实际执行 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| 旧实现人数先红回归 | 35 | 14 | 0 | 0 |
| 修复后人数专项 | 35 | 0 | 0 | 0 |
| 非收费受影响回归（含隔离PG、HTTP） | 133 | 0 | 0 | 0 |

35项专项已包含在133项中，不能相加。真实红灯日志`r1-red-regression.log`保留；绿灯、受影响、打包日志分别为`r1-green-regression.log`、`r1-affected-regression.log`、`r1-package.log`。测试写入只发生在临时Testcontainers；未选收费T6，没有真实模型/Embedding/商品请求。离线package单独通过，其中skipTests是明确未执行测试，不计入133项跳过数。

复现命令：在5b09/backend设置当前进程JDK21后，人数专项用`mvn -o '-Dcooker.t6.enabled=false' '-Dtest=D1AParsingRegressionTest#r1*' test`；全量受影响与package沿用第5节第一和第三条命令。预览沿用第4节原程序/原输入，两次输出目录为`.data/d1-a`及其`r1-repeat`子目录。规则版本仍为d1-a-parser-v1，避免改变材料诊断/预览器契约；人数R1实现通过本节、日志及新manifest哈希追溯。

- 原快照SHA-256：`571CD40658931616E1B47218EF3E230E17065E182A9B10726EA0D250B7842EF8`，与R1起始记录一致。
- R1前manifest：`3F78ACC34BC00E49CEE8468C303917B1A544FF64BFD3ACD5ED2C19B29002969A`。
- R1后manifest：`B1CBBFE7BB002FBDBBC81038B3A60EC6C99CEAC2ED2044F6F0A8E43457E2C898`。
- R1 Jar：`66B63415EC4AD6BD4B66F92CF40F31443D02F11EAEDD092783A1C8D16BA031D7`。
- 验证产物：`r1-baseline.json`、`r1-preview-verification.json`、`r1-source-protection-result.json`、`r1-verification.json`；当前summary/quality-summary为R1最新，原版在r1-before。

本轮修改4个文件：HowToCookMarkdownParser.java、D1AParsingRegressionTest.java及本报告/技术文档两份。R1保护基线444个文件中4个允许变化，范围外440个保持原哈希；原dirty路径保留，HEAD/分支不变。未向其他会话发消息，未应用开发库、重导入、重建索引、部署、启停或Git整合；完成本轮交付后停等复审。

## 9. 最新：约人数取值、人数范围取下限（d1-a-parser-v2）

用户明确覆盖旧R1验收。已完整读取0f47《阶段D1-人数折算策略》，核对原D1-A/R1卡开头及主文档的覆盖说明。实际实现仍在5b09；没有编辑0f47、根主文档或前端。原R1报告、源码、manifest、summary与成功/失败日志复制保留在`v2-before/`，原`r1-before/`及原公开输入不覆盖。

| 表达 | 参考人数基准 | 预览servingsBasis |
| --- | ---: | --- |
| 2人、两人 | 2 | EXPLICIT_PEOPLE |
| 约2人、每份（约 2 人份）、2人左右 | 2 | APPROXIMATE_PEOPLE_AS_BASELINE |
| 2到3人、2-3人、二至三人 | 2 | RANGE_MIN_AS_BASELINE |
| 未提供、非正/非整数、倒置/冲突、份/产量/尺寸 | null | UNKNOWN_REQUIRES_REVIEW |

人数区间完整匹配两个端点，均通过正整数与顺序校验后才采用下限；不交换倒置值、不忽略小数/负数上限、不截取末端。相互冲突按最终人数基准拒绝，相同基准可保留；范围与约量同时出现时范围标签优先，警告完整保留原依据。解析warnings带版本和“人数依据=”标记；只有所有基准校验通过才发布采用提示。预览器只读取该解析器标记区分依据，没有另做人数解析或更改ID映射。

Markdown行首“- 2 人 1 顿”中的短横线是项目符号，仍为明确2人；“-2人”是非法负数。真实217号半成品意面与列表/序号正例均已核对，不能因负号保护丢失合法人数。材料约量、范围、单位、斤及多组量等规则完全不变，MealMaterialCalculator未改。原DTO、schema、HTTP不变。

### 9.1 全量同源预览

- 372道菜谱、解析失败0；相对R1有29道人数值与依据变化，全部是原未知基准恢复：24道人数范围取下限、5道约人数取值。
- 最新人数依据：明确47、约人数5、范围下限24、未知296，合计372。缺人数R1的325→v2的296；与开发库旧快照310比较，恢复16、转未知2。
- 真实153/177/180/192全部为2，标签全部APPROXIMATE_PEOPLE_AS_BASELINE；264“约为二人”也是2/约人数依据。350动态份数材料比率仍未知；217列表人数仍2/明确依据。
- 提案变化菜谱总数237→232：5道原仅因拒绝范围/约量而变化的菜谱，在新策略下与旧事实相同；不是遗漏菜谱。
- 原3025用料ID唯一覆盖；3072解析材料、2153未变行、872旧行变化候选、53新增和6移除候选不变；未知数量/单位仍530。
- 材料逐行比较只排除ruleVersion和诊断中的版本前缀，其他JSON字段/诊断语义完全一致；全局版本升级导致材料记录元信息更新，不冒充manifest字节与v1相同。两次v2 manifest字节哈希一致，所有applied=false。

逐项29个ID/原R1基准/新基准/依据见`v2-preview-verification.json`。原输入SHA-256不变：`571CD40658931616E1B47218EF3E230E17065E182A9B10726EA0D250B7842EF8`。最新manifest：`AD2841C231559965FDA8E6424100600F655433511CBA96162A18711F35F61155`。当前summary/quality-summary已真实重生成，未手改输入或只改报告数字。

### 9.2 本轮真实验收

| 执行 | 执行数 | 失败 | 错误 | 跳过 | 证据性质 |
| --- | ---: | ---: | ---: | ---: | --- |
| 人数策略先红 | 86 | 34 | 0 | 0 | 新预期在旧R1上执行，保留初次fixture预期同步记录 |
| 首次解析/预览专项 | 123 | 2 | 0 | 0 | 两条fixture人数预期未同步，已按实际原文改正 |
| 受影响初步回归 | 188 | 0 | 0 | 0 | 列表正例补充前，保留为中间证据 |
| 最终受影响及隔离PG/HTTP | 190 | 0 | 0 | 0 | 当前v2最终验收，含新增两条列表正例 |

首轮range-amount期望曾误写2，核对真实原文3-4人后更正为3；pork原文1-2人旧null期望更正为1。这两处均只同步人数断言，不改fixture或材料断言。先红及首次失败日志没有删除；中间188和最终190有重叠，不能相加。旧R1的133项通过只作为历史。

最终190中含73个人数参数化正反例和19个预览依据参数化例，均已实际执行。无人数/非法数/倒置/冲突/份产量尺寸反例、有效上下限、明确/空格约量/后缀约量及四类预览依据持续覆盖。PG/HTTP均在临时Testcontainers写入；没有真实模型、Embedding、商品服务调用，没有浏览器/部署验收。

最终命令仍为第5节完整受影响选择；新策略日志为`v2-affected-regression-final.log`。先红、首次专项、188中间分别为`v2-red-regression.log`、`v2-green-regression.log`、`v2-affected-regression-initial188.log`；原R1日志保持。离线package单独通过，记录`v2-package.log`；skipTests不计为190项测试跳过。

最新Jar SHA-256：`6F41DD7A40F18CA5E967ED5A47DE1609170B8AABFAFDD357FB20102AD1DFBD42`。保护证据：`v2-baseline.json`、`v2-preview-verification.json`、`v2-source-protection-result.json`、`v2-verification.json`，两份生产文件相对R1差异另存`v2-parser-diff.txt`与`v2-preview-metadata-diff.txt`。原verification与R1验证文件保留不覆盖。

### 9.3 准确变更清单及停点

本轮7个源码/文档文件：

1. `backend/src/main/java/com/cooker/recipe/parser/HowToCookMarkdownParser.java`：仅人数模式/parseServings与规则版本v2。
2. `backend/src/main/java/com/cooker/recipe/diagnostic/RecipeParsePreviewTool.java`：仅servingsBasis依据标签。
3. `backend/src/test/java/com/cooker/recipe/parser/D1AParsingRegressionTest.java`：人数新策略/边界及旧人数预期。
4. `backend/src/test/java/com/cooker/recipe/parser/HowToCookMarkdownParserTest.java`：真实3-4人下限及范围警告。
5. `backend/src/test/java/com/cooker/recipe/diagnostic/D1AReadOnlyPreviewPostgresTest.java`：19项依据标签/未变行/ID/重复提案回归。
6. 本工作报告。
7. `docs/技术文档-D1-A-解析规则与数据治理.md`。

此外仅`.data/d1-a/`本地提案/日志/保护证据及backend/target构建输出。起始444个受保护文件中7个允许变化，范围外437个哈希一致；原dirty保留，HEAD/分支不变。没有改DTO/schema/HTTP/材料计算/字典/ID映射/旧迁移或0f47文件。

基准供参考折算，不宣传精确食量、足量保证或自动采购量。原菜谱基准不由用户本餐人数补齐；例如2到3人采用2，目标3人参考比例3/2仍需人工确认。本轮未改下游计算或交互。原开发库仍未应用，九组历史一致性证据未在本轮实时重查。无开发库应用、向量收费/索引重建、启停部署、Git/ACL/跨会话消息或D1-B/C/D/T18。完成后停止，等待轻量复审。
