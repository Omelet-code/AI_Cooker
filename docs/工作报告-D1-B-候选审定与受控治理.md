# D1-B：候选审定与受控治理工作报告

日期：2026-10-07。实现和证据仅位于 `C:\Users\Omelet\.codex\worktrees\5b09\Cooker`。

## 1. 交付结论

B1 已生成逐 ID 人工审定包；B2 已实现显式 dry-run/apply/rollback、Service事务、专属Mapper和唯一V15审计迁移。受影响非收费回归279项通过；最终审计映射调整后49项专项复验通过；离线package通过。失败、环境阻塞和最终日志分开保留。

开发库未连接、未迁移、未应用修复；真实逐ID审批为0。临时PG里的批准、修复和回滚仅为模拟验收，不构成实际用户审批或开发数据已修复。没有收费模型/Embedding、真实商品、前端改动、运行配置变更、Git写入或后续阶段实现。

## 2. B1 审定入口和对账

- [可读Markdown](../.data/d1-b/review/review.md)
- [转义HTML](../.data/d1-b/review/review.html)
- [机器可读JSON](../.data/d1-b/review/review.json)
- [全部DEFER审批模板](../.data/d1-b/review/approval-template.json)
- [汇总](../.data/d1-b/review/summary.json)

固定输入 SHA256：`571CD40658931616E1B47218EF3E230E17065E182A9B10726EA0D250B7842EF8`。
固定提案 SHA256：`AD2841C231559965FDA8E6424100600F655433511CBA96162A18711F35F61155`。
规则：`d1-a-parser-v2`。最终审定包 hash：`71589553321BAF82546855DC90D29FF924A9B4AF6119E51E39707A9AB32B4EB5`。

| 类别 | 待审项数 | 含义 |
| --- | ---: | --- |
| 名称数量单位 | 866 | 固定提案的旧材料字段变化 |
| 新增漏料 | 53 | 需单独确认，固定追加排序 |
| 移除误解析 | 6 | 仅明确非材料误解析，需单独确认 |
| 人数基准 | 18 | 16项恢复人数、2项转为未知 |
| 词典冲突 | 52 | 每组列旧ID/规范名与关联原行 |
| 不确定材料 | 127 | 未变化但量/单位未知的独立待复核项 |
| 缺人数待复核 | 294 | 不与人数变化候选重复计数 |
| 合计 | 1416 | 全部DEFER，APPROVE/REJECT均为0 |

固定公开输入含372道菜；232道涉及变化，872个旧材料行变化（含6移除）、53新增。这些数字是提案规模，不是已批准/已应用规模。530个材料候选带不确定标识，与变化/新增类别有重叠；人数未知总数296，其中2项在人数组内。4个“每份（约2人份）”真实例153/177/180/192均保留2人和依据；原库存已为2，不制造额外人数变化候选。

review、review-replay与打包入口生成的review-packaged中审定JSON逐字节一致。模板无请求UUID、目标库、审批人或允许动作；`template-sealed-not-approved.json`只用于离线入口拒绝验证，仍全部待审，绝不是可应用审批。

## 3. B2 实现和保护

独立`RecipeRepairTool`默认dry-run，显式apply/rollback需要准确JDBC目标与完整有效审批。治理凭证只从专用环境变量读取，不进入审批包或输出。入口不启动Spring Boot、不自动运行Flyway、导入、索引或模型；普通应用注册Service本身不会执行治理动作。

实现经`RecipeRepairService`接口→`RecipeRepairServiceImpl`→`RecipeRepairMapper`。固定源文件hash校验后用同D1-A预览器重算，审批只能选候选/身份动作，不能替换newFields；未知字段、小数ID/排序静默截断均拒绝。

写入事务先治理锁和批次锁，再固定顺序保护表锁/目标行锁；重验公开归属、原文与hash、source/document/line身份、旧人数/材料/raw_line/排序和词典前置条件。事实与批次/条目审计同时提交；异常全部回滚。同键同hash返回原收据、异内容拒绝；已回滚同键apply返回ROLLED_BACK，不重新写。

保留recipe/source/document主键及原文/content_hash/raw_line、旧关联ID；新增只追加，REMOVE只删除获批误解析行并可按原ID恢复。REMOVE条目保留original_line_id，执行后resulting_line_id为null；回执不伪造仍存在的关联。身份KEEP/REUSE/RENAME/CREATE都显式审批；冲突逐组逐行选择，不自动同名归并、不删旧标准ID、不伪造别名、不全表REVIEWED。新标准ID为UNREVIEWED。

rollback锁后校验after、标准身份引用/别名及保护摘要；后续用户修改、历史变化或新引用均拒绝。原文、收藏、做菜记录、餐单/指导/采购各层旧快照和检索投影受保护。全范围摘要和SHARE锁是保守实现：无关历史变化也会阻止回滚，事务期间会阻塞相关表写入；不把旧餐单的CHANGED状态通过改历史“修掉”。

V15只增加`recipe_repair_batch`、`recipe_repair_entry`与索引，不改旧迁移或业务表结构。临时PG真实验证V14→V15升级且非空旧事实/历史保持一致；本次未对开发库执行V15。

## 4. 实际测试证据

| 运行 | 实际结果 | 证据 |
| --- | --- | --- |
| 最初专项 | Docker引擎未启动；1个环境错误 | `b2-postgres-first.log` |
| 用户手动启动Docker后专项 | 48项，1个排序反例选样错误 | `b2-postgres-second.log` |
| 第一轮受影响回归 | 278项，10个旧迁移计数/版本前置失败，错误/跳过0 | `regression-first.log` |
| 同组最终回归 | 279项，失败/错误/跳过0 | `regression-final.log` |
| 最终审计回执专项复验 | 49项，失败/错误/跳过0；42项真实临时PG＋7项纯证据审批 | `specialist-delivery.log` |
| 最终离线package | BUILD SUCCESS；已验证测试不重复运行 | `package-delivery.log` |
| 打包入口 | review/seal成功；待审默认dry-run连接前拒绝（预期退出1） | `packaged-*.log` |

49项已包含在279项覆盖内，最后复验不是另外49项独立功能；不会累计成328项。279项回归后仅进一步明确REMOVE审计映射语义并采集临时收据，此调整由最终49项专项覆盖；其他回归代码未再改。

回归覆盖D1-A预览/解析、材料计算、导入Service、历史事实/HTTP收藏做菜记录、所有匹配迁移测试、餐单规划/指导/证据读取、检索Service与Mock采购/商品回归。模型和商品适配使用测试替身，不构成真人、真实模型或真实平台验收。测试Spring上下文使用临时PG和随机端口，不启动IDE/开发服务；Docker由用户手动启动。本会话没有启停Docker或开发程序。

临时PG专项覆盖固定证据/hash防篡改、默认待审与零事实批准拒绝、身份新建/复用/改名和冲突、约人数政策、增删排序、私有归属拒绝、来源/原文/结构化/词典漂移、审计后置异常、数据库唯一异常、同键幂等及并发、异内容拒绝、回滚重放、后续材料/人数/原文/新引用/别名/历史拒绝、旧ID与非空历史保护和真实升级。

[结构化验证汇总](../.data/d1-b/verification.json)记录每类测试实际计数；[临时执行收据](../.data/d1-b/temporary-receipts.json)含7类真实测试应用的recipeId/关联映射/新标准ID。收据的UUID和新ID属于各测试临时PG，不能拿去对开发库执行rollback，也不能当成D1-C实际开发数据影响清单。当前开发库实际影响recipeId为`[]`。

### 验收命令

在5b09仓库根目录，当前进程设置JDK21后执行；全部使用离线Maven且T6关闭：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
mvn -o '-Dcooker.t6.enabled=false' '-Dtest=RecipeRepairPostgresTest,RecipeRepairEvidenceTest,D1A*Test,HowToCookMarkdownParserTest,RecipeImport*Test,MealMaterialCalculatorTest,MealFactsPostgresTest,T9RecipeHttpPostgresTest,T10CookingHttpPostgresTest,RecipeSearchPostgresTest,Shopping*Test,Product*Test,*MigrationPostgresTest,MealPlanningPostgresHttpTest,MealGuideEvidencePostgresHttpTest,MealGuidancePostgresHttpTest,ShoppingDemandPostgresHttpTest' test
mvn -o '-Dcooker.t6.enabled=false' '-Dtest=RecipeRepairPostgresTest,RecipeRepairEvidenceTest' test
mvn -o '-Dcooker.t6.enabled=false' '-DskipTests' package
```

下列独立入口从仓库根目录运行；只生成待审包，不接数据库：

```powershell
& 'C:\Program Files\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' '-Dloader.main=com.cooker.recipe.diagnostic.RecipeRepairTool' -cp backend/target/cooker-backend-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher review .data/d1-a/database-input.jsonl .data/d1-a/manifest.jsonl .data/d1-b/review
```

治理格式：`[dry-run|apply|rollback] <固定输入> <固定提案> <完整有效审批> --target <准确JDBC主机:端口/库>`；省略模式是dry-run。专用环境变量为`COOKER_REPAIR_JDBC_URL`、`COOKER_REPAIR_USER`、`COOKER_REPAIR_PASSWORD`。真实开发库连接/审批/备份/应用本次未提供、未执行，必须另批；不要用普通运行配置启动应用来运行治理。

## 5. 准确修改清单

### 新增16个仓库文件

- `backend/src/main/java/com/cooker/recipe/diagnostic/RecipeRepairReview.java`
- `backend/src/main/java/com/cooker/recipe/diagnostic/RecipeRepairTool.java`
- `backend/src/main/java/com/cooker/recipe/dto/repair/RecipeRepairApproval.java`
- `backend/src/main/java/com/cooker/recipe/dto/repair/RecipeRepairEvidence.java`
- `backend/src/main/java/com/cooker/recipe/dto/repair/RecipeRepairResult.java`
- `backend/src/main/java/com/cooker/recipe/entity/RecipeRepairBatchEntity.java`
- `backend/src/main/java/com/cooker/recipe/mapper/RecipeRepairMapper.java`
- `backend/src/main/java/com/cooker/recipe/service/RecipeRepairService.java`
- `backend/src/main/java/com/cooker/recipe/service/impl/RecipeRepairServiceImpl.java`
- `backend/src/main/resources/db/migration/V15__create_recipe_repair_audit.sql`
- `backend/src/test/java/com/cooker/recipe/diagnostic/RecipeRepairEvidenceTest.java`
- `backend/src/test/java/com/cooker/recipe/diagnostic/RecipeRepairPostgresTest.java`
- `backend/src/test/resources/fixtures/d1-b/database-input.jsonl`
- `backend/src/test/resources/fixtures/d1-b/manifest.jsonl`
- `docs/工作报告-D1-B-候选审定与受控治理.md`
- `docs/技术文档-D1-B-审批事务与回滚.md`

`.data/d1-b/`另保存起始基线、审定包、失败/最终日志、结构化汇总、专项XML和临时回执；不覆盖`.data/d1-a/`。两个fixture逐字复制固定公开输入/提案，hash相同，不依赖开发库。

### 8个既有文件只适配实际失败的V15迁移前置检查

- `backend/src/test/java/com/cooker/conversation/ConversationMigrationPostgresTest.java`
- `backend/src/test/java/com/cooker/conversation/MealGuideMigrationPostgresTest.java`
- `backend/src/test/java/com/cooker/identity/DiningProfileMigrationPostgresTest.java`
- `backend/src/test/java/com/cooker/recipe/T9RecipeHttpPostgresTest.java`
- `backend/src/test/java/com/cooker/recipe/T10CookingHttpPostgresTest.java`
- `backend/src/test/java/com/cooker/retrieval/service/impl/RecipeSearchPostgresTest.java`
- `backend/src/test/java/com/cooker/shopping/ProductMatchMigrationPostgresTest.java`
- `backend/src/test/java/com/cooker/shopping/ShoppingMigrationPostgresTest.java`

失败记录为10个用例，涉及上述8文件。只将最新迁移计数/版本列表/max(version)/升级差值计入V15，并同步相应迁移说明；原业务断言、旧fixture及旧迁移不改。将这些适配逆向还原后，每个文件SHA256均等于本次起始基线，证明此前已有未提交改动和原业务正文逐字节保留。

## 6. 基线、风险和未完成项

起始HEAD为`26c33ace4ad22e72350cba65cccb6f528c299917`，分支`codex/kitchen-assistant-u3`，本次未提交/切分支/合并。起始637个文件保护基线中仅8个授权迁移测试变化，其余629文件保持原字节，缺失0；D1-A整个证据目录、解析/折算生产规则、旧报告、前端、根文档、POM及运行配置保持不变。差异空白检查和本次新增文件空白检查结果见保护汇总。

当前实现的全范围保护可能因无关新历史而拒绝rollback，SHARE锁和摘要扫描也有维护窗口成本；审批内容hash不验证真人身份。后续真实审批/备份/应用必须按新的用户授权处理；新事实可能让旧餐单CHANGED，投影影响和是否重建向量交D1-C另行判断。本次不重建、不改READY、不调用收费API、不进入D1-C/D1-D/T18。

两份中文文档均已交付，技术文档含真实代码层级、MySQL手工UPDATE对照、JSONB/唯一键说明、10个面试问答和自检。D1-B本次实现与隔离验证完成，停止等待主架构轻量复审；不主动跨会话发送消息。
