# T17：Mock商品与成本快照工作报告

日期：2026-10-06；目录 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker`；起始HEAD `26c33ace4ad22e72350cba65cccb6f528c299917`，分支codex/kitchen-assistant-u3。输入为主架构0f47最新T17卡/契约及已签收T16；仅T17，保留全部U5/T16与既有dirty，不F3-D/T18。

## 1. 交付范围与状态

实现shopping-products-v1独立商品匹配资源：POST创建、GET指定、GET latest；明确标准ID绑定的Mock目录Provider、确定性选品、同商品合包、三类成本/未知/预算、PG独立快照与幂等回读。T16原API、需求/确认/预算/view_json及NOT_MATCHED保持原样；不添加价格列到旧清单。

默认空目录，不硬编码生产食材ID，不模糊猜名称；Provider=MOCK、realData=false，purchaseUrl=null。配置示例默认products=[]，没有凭证且不会自动加载。GET/latest/成功旧键重放零Provider；新UUID才显式查新价。当前完成状态以第4/5节最终验证为准，完成后停止等主架构轻量复审，不当作开发部署、真人页面或真实商品验收。

## 2. 设计、数据与费用语义

新请求严格只接UUID和六位cityCode字符串，路径清单ID由认证用户核验。复用shopping自己的事务建议锁，内容hash只listId+cityCode，唯一(user_id,request_id)兜底；旧键同内容优先回历史，异内容409。新匹配锁自己模块的源清单行，经原ShoppingService.get核验事实CURRENT，不按当前餐修订重算人数材料。无跨模块Mapper调用，也无conversation/recipe等生产改动。

Mock Provider一次批处理返回统一目录版本，按明确ingredientId、城市、available与支持单位筛选；候选用精确比例交叉乘比单价，再比较整包支出、productId。目录配置错误/负价/非法规格/超范围整体502，缺失合法规格/报价仍保留QUOTE_UNKNOWN。城市不支持、缺货、无绑定、维度不兼容分别有原因。足量与未知用量不查询Provider，CUSTOM/未知单位与未归一原料不猜匹配；完全无需查询时catalogVersion=NOT_QUERIED。

保留原itemKey/lineKeys与状态；确定同商品/城市/单位需求先合包，包含原optional分行，但不改T16汇总。明细支出仅独立行解释，总purchaseOutlay只累计采购组一次。BigDecimal中间80位精度，最终HALF_UP2位，包数CEILING；响应普通十进制字符串，数量/金额上界保存前拒绝，不能静默截断。

estimatedDemandCost仅有显式referenceUnitPrice/referenceUnit才算，成交Mock报价不冒充独立估价。完整消耗/支出未知则null，knownSubtotal保留已知消耗项；估价独立小计/未知项另列。全足量三金额0.00。预算用未具备部分消耗成本比较，全已知WITHIN/OVER，有未知UNKNOWN，未设NOT_SET；超预算保留并提示，不换菜/增预算。

V14独占新增shopping_product_match、shopping_product_match_item、shopping_product_purchase_group。BIGINT identity；头绑定(list_id,user_id)，明细与组绑定(match_id,list_id,user_id)；用户UUID唯一、明细顺序/itemKey唯一、组key唯一、latest倒序索引。核心量价/状态/归属为关系列，完整view与商品quote为JSONB；金额numeric(18,2)、数量numeric(24,8)、NaN拒绝与整数包装CHECK。删除原清单沿FK级联三表，未新增公开删除接口。V1～V13保留。

头/行/组同事务保存，最后再经原清单Service复核事实，异常无半份报价。回看/旧键再核验原菜谱可见性，失权404，仍可见但改变CHANGED保留原金额；目录涨价/新城市/新餐不改旧价。Provider现在本地Mock，未来真实网络不可直接搬进持锁事务；任意外部无协调事实写入的最后瞬间竞争也不被夸大为完全串行。

## 3. 文件清单

新增shopping生产14份：

1. controller/ProductMatchController.java
2. dto/ProductMatchRequest.java
3. config/MockCatalogProperties.java
4. provider/ProductProvider.java
5. provider/MockProductProvider.java
6. vo/ProductMatchView.java
7. entity/ProductMatchRow.java
8. service/ProductMatchService.java
9. service/ProductMatchCalculator.java
10. service/impl/ProductMatchServiceImpl.java
11. mapper/ProductMatchMapper.java
12. mapper/ProductQuoteJsonTypeHandler.java
13. resources/mapper/shopping/ProductMatchMapper.xml
14. resources/db/migration/V14__create_shopping_product_matches.sql

上述Java路径根为backend/src/main/java/com/cooker/shopping，资源为backend/src/main/resources。没有修改现有T16生产类。

新增四测试：agent/ProductMatchPostgresHttpTest、shopping/ProductMatchingTest、shopping/MockCatalogBindingTest、shopping/ProductMatchMigrationPostgresTest。HTTP/PG12项、计算/选品/严格输入8项、绑定1项、迁移1项。

十份旧测试仅最新V14迁移预期/条数/版本名称注释适配，业务断言保留：ConversationCompositeForeignKeyPostgresTest、ConversationHttpPostgresTest、ConversationMigrationPostgresTest、MealGuideMigrationPostgresTest、DiningProfileMigrationPostgresTest、LlmCredentialHttpPostgresTest、T10CookingHttpPostgresTest、T9RecipeHttpPostgresTest、RecipeSearchPostgresTest、ShoppingMigrationPostgresTest。明确target7/11/12/13的原历史准备段不升为最新；只调整其后full升级数量。

新增无密钥 `docs/examples/t17-mock-catalog.example.yml`、本文及《技术文档-T17-商品匹配与成本实现详解》。教学包含真实代码地图、成功请求流、Provider与配置、事务/SQL/幂等/快照、合包/BigDecimal、MySQL对照、官方资料、8问答/5自检/2练习及答案；不是用户学习验收证明。

## 4. 已执行验证

Java21离线，所有构建使用 `backend/target/t17-package-pom.xml`，输出和工作目录target/t17-build，依赖/仓库POM不改，不覆盖IDE classes/Jar。临时POM从已验证T16隔离POM复制，仅隔离路径与产物名调整。API配置只在构建子进程清空，未改Windows用户变量或应用开关。

第一批18项全部通过（HTTP10、纯计算8），随后新增配置绑定/迁移以及两项HTTP部分匹配/最终来源回滚；迁移测试首次编译因异常lambda泛型赋值失败，改为独立Throwable root遍历，仅新测试修正。最终T17+T16专项 **46项全部通过/0失败/0错误/0跳过**：T17 HTTP12、纯计算8、绑定1、迁移1；T16 HTTP12、计算7、请求4、迁移1。证据 `final-special.log`，真实PG实际执行，不将编译失败那批称为执行通过。

最终非收费全量 **249项，248通过、0失败、0错误、1项既有收费评估跳过**，`BUILD SUCCESS`；T17及T16专项没有跳过。全量日志末尾另有 Surefire 等待测试 JVM 退出30秒后清理进程的提示，不是测试失败；不据此声称测试进程无退出清理警告。

独立 `package` 成功，产物 `backend/target/t17-build/cooker-backend-t17-verification.jar`，54695396字节，SHA-256 `23790ac804fafabc65d55bf61a851a4a031b8135ff0049fd4035a1a75cca41a3`。Jar中366个应用类及资源文件与独立classes逐字节一致；其中V1～V14共14份SQL迁移及一个目录占位文件与源资源一致。产物仅用于验证，没有启动或替换开发服务。

关键实测：Mock标识/合包/超预算保存、原T16头row字节不变；latest无结果null；涨价后GET/latest/旧键Provider零调用、旧价不变、新UUID新价；足量与未知用量零查询；城市隔离/缺货/无价；严格参数、认证/CSRF/双用户；四并发1×201+3×200/单ID/Provider一次；无效目录502、明细触发器故障全回滚、原键重试；CHANGED旧成本保留/新匹配409、失权404不泄漏；当前餐修订不改旧需求；超数值范围400零残留；部分匹配保留已知小计/未知项；Provider返回后独立连接改变事实，提交前409、报价三表零行而T16仍在。

迁移真V13→V14只执行1，repeat0，旧T16row字节不变；跨用户头/明细/采购组反例精确23503并核对外键constraint，非法MATCHED、NaN及小数包数23514，删除源清单级联三表。绑定用Spring Boot Binder实测显式标准ID42仅属虚构数据。所有商品链路验证ModelGateway/RecipeSearchService/QueryEmbeddingService零交互；setup建餐仅替身，不冒充真实模型。

复现（cwd=backend，必须使用隔离POM）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:DASHSCOPE_API_KEY=''
$env:LLM_CREDENTIAL_MASTER_KEY=''
$env:COOKER_JWT_SECRET=''
mvn -o -f target/t17-package-pom.xml '-Dtest=ProductMatchingTest,MockCatalogBindingTest,ProductMatchMigrationPostgresTest,ProductMatchPostgresHttpTest,ShoppingRequestTest,ShoppingCalculationTest,ShoppingMigrationPostgresTest,ShoppingDemandPostgresHttpTest' test
mvn -o -f target/t17-package-pom.xml test
mvn -o -f target/t17-package-pom.xml -DskipTests package
```

证据在忽略的target/t17-build：baseline.json、ide-baseline.json、compile.log、special.log、t16-t17-special.log（首次编译失败）、final-special.log、full-regression.log、package.log、surefire-reports与最终保护/包校验JSON。测试框架临时认证日志不复制到报告或其他会话。

## 5. 保护、风险与未验项

起始逐字节记录537个现有Git跟踪与未忽略文件，另315个IDE运行文件。最终527个基线文件哈希一致，只有允许适配的10份旧迁移测试变化，无基线文件缺失；新增21个文件（14份生产及资源、4份测试、2份文档和1份配置示例）。IDE的315个运行文件集合与字节哈希全部不变。`git diff --check`及本轮新增/修改文件逐行空白检查通过。HEAD仍为 `26c33ace4ad22e72350cba65cccb6f528c299917`，分支仍为 `codex/kitchen-assistant-u3`，保留原未提交成果与无关dirty。

没有真实商品/模型/Embedding/网络报价请求，没有写/迁移5433 cooker开发库、改运行配置/开关、启停服务、覆盖运行target/classes或Jar；没有stage/提交/合并/推送、换树或主动发消息其他会话。目录示例未加载、生产标准ID未绑定，默认诚实NO_PRODUCT。

未验：开发部署升级、实际页面/重登购物闭环、真人商品验收、真实商家/报价覆盖、生产吞吐与目录热更新。T17金额是Mock折算，不能声称真实采购支出准确。真实Provider网络事务/权限/费用需后续独立设计和授权。完成本切片后停止等主架构轻量复审，不自动F3-D/T18。
