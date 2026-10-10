# D1-R 工作报告：新库重建与菜谱真实导入

日期：2026-10-07。实际工作目录：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker`。

## 1. 结果与授权范围

已完成旧库可恢复备份、同实例新库建立、V1～V15迁移，以及已签收 `d1-a-parser-v2` 通过真实 `RecipeImportService` 的原文/结构化导入。372个候选全部成功，失败0；同源重跑全部跳过，未产生重复原文或菜谱。此次完成的是新库数据重建，不代表菜谱语义已全部人工审核，也不代表网页或混合检索验收完成。

依据0f47《派发任务-D1-R-新库菜谱重建》及用户“账号和历史都是测试用，可不用考虑保留”的最新授权：不迁移测试账号、密钥或历史；不应用D1-B旧ID补丁。旧 `cooker` 保留，未删除、清空或升级V15。本轮未做旧库或文件哈希复审；既有导入Service正常保存的 `content_hash` 仍保留。

本轮只新增独立入口、对应测试、专属脚本和两份报告，没有修改已签收解析器、生产导入/检索/购物逻辑、主应用配置、系统/User环境变量、数据集、前端或Git历史，也没有向其他会话发消息。

## 2. 精确目标与备份

| 项目 | 实测值 |
| --- | --- |
| 容器 / 镜像 | `cooker-postgres` / `cooker-postgres:pg18-zhparser`，健康 |
| 地址 / 角色 | 主机5433→容器5432，`root` |
| 精确旧库 | `cooker`；建库前只读核对Flyway V14 |
| 精确新库 | `cooker_rebuild_20261007`；创建前再次确认不存在 |
| 创建时间 | 2026-10-07 21:29:48 +08:00 |
| 只读数据源 | `E:\数据集\dishes` |
| 扫描 | 373份Markdown，排除1份template，372个候选 |
| 真实sourceVersion | `1.6.0-a0b14bd`；未换版本绕过SUCCESS |

备份文件：

`C:\Users\Omelet\.codex\worktrees\5b09\Cooker\.data\backups\d1-r-20261007\cooker-before-d1-r-20261007-211741-4351b5d6.dump`

格式为 `pg_dump -Fc`，大小 **10,881,677字节**，完成时间2026-10-07 21:17:44 +08:00。容器源备份和本地复制文件回拷后的 `pg_restore --list` 均通过，目录258行。没有执行恢复，也没有声称已完成恢复演练。备份可能含原账户密文，位于被Git忽略的 `.data` 内，不提交分享。

证据：`.data/d1-r/backup.json`、`backup-readback.json`、`created.json`；备份目录另存 `.toc.txt`。原D1-A/B产物与旧备份全部保留。

### 恢复参考指令（本轮未执行）

后续另行批准恢复时，先确认独立恢复库 `cooker_restore_d1r_20261007` 不存在；存在则停止，不覆盖。下列指令将完整旧快照恢复到独立库，不使用 `--clean`，不会覆盖当前旧库或本轮新库：

```powershell
docker cp 'C:\Users\Omelet\.codex\worktrees\5b09\Cooker\.data\backups\d1-r-20261007\cooker-before-d1-r-20261007-211741-4351b5d6.dump' cooker-postgres:/tmp/cooker-d1r-restore-input.dump
docker exec cooker-postgres pg_restore --list /tmp/cooker-d1r-restore-input.dump
docker exec cooker-postgres createdb -U root cooker_restore_d1r_20261007
docker exec cooker-postgres pg_restore --exit-on-error --no-owner --no-acl -U root -d cooker_restore_d1r_20261007 /tmp/cooker-d1r-restore-input.dump
```

恢复成功后仍需核对迁移记录、关键行数和应用兼容性；本轮没有启动恢复库或切换程序。

## 3. 实际执行路径

1. 核对实例、角色、端口、旧库版本和新库未占用；只读统计数据源。
2. 备份准确旧库，验证本地副本目录可读。
3. 在Testcontainers自有临时PostgreSQL中验证独立入口的真实Service、事务代理、迁移、幂等、失败隔离及连接拒绝。
4. JDK21离线打包；备份通过后执行 `CREATE DATABASE cooker_rebuild_20261007 OWNER root`。
5. 一次性非Web入口只连接指定新库，迁移15项后导入；未启动Spring Boot全上下文，不装配认证、模型或索引Bean。
6. 查询新库真实落库结果，同一入口、同源版本重跑；只做必要统计、重复检查、样例和未知项导出。
7. 补充非法URL错误脱敏测试后，最终13项专项和离线打包通过。最终源码没有改变已完成导入的事务装配或生产解析规则，不再重复真实导入。

已执行导入命令（在5b09根目录）：

```powershell
.\scripts\d1-r\Invoke-RebuildImport.ps1 -Target 'jdbc:postgresql://localhost:5433/cooker_rebuild_20261007' -DatasetPath 'E:\数据集\dishes' -SourceVersion '1.6.0-a0b14bd' -LogPath '.data/d1-r/import-first.log'
# 第二次命令参数相同，仅运行记录使用import-repeat.log，禁止覆盖旧记录。
```

数据库密码从已有进程/User变量读取，仅传入子进程 `COOKER_REBUILD_PASSWORD`，未输出或持久化；脚本结束恢复原进程变量。主应用默认启动方式不变。

## 4. 导入与幂等实测

| 项目 | 首次 | 同源重跑 |
| --- | ---: | ---: |
| Flyway执行迁移 | 15 | 0 |
| 批次ID | 1 | 2 |
| 候选 / 成功 / 跳过 / 失败 | 372 / 372 / 0 / 0 | 372 / 0 / 372 / 0 |
| 警告条数 | 1994 | 0 |
| 失败路径 | 空 | 空 |
| 批次状态 | COMPLETED | COMPLETED |

首批业务导入21:29:52～21:30:14，重跑21:30:53～21:30:59，均为+08:00。重跑警告0表示SUCCESS跳过路径不重新累计警告，原文中保存的1994条警告仍在。

| 表/对象 | 首次提交后 | 重跑后 |
| --- | ---: | ---: |
| 来源 / 原文 / 菜谱 | 各372 | 各372 |
| 用料 `recipe_ingredient` | 3072 | 3072 |
| 食材 `ingredient` | 1044 | 1044 |
| 别名 | 75 | 75 |
| 步骤 | 3511 | 3511 |
| 厨具 / 菜谱厨具关联 | 25 / 130 | 25 / 130 |
| 导入批次 | 1 | 2 |
| 检索投影 `recipe_search_document` | 0 | 0 |

同一来源+版本的重复文档0、同一来源重复菜谱0；372文档全部SUCCESS；来源均为系统PUBLIC且无owner，用料未关联食材ID为0。食材1044条全部仍是 **UNREVIEWED**，不能把“有ID”等同于已人工标准化。

`app_user`、凭证、refresh token、profile、收藏、烹饪记录、会话/消息/推荐、餐单/动作/方案/指导、购物清单/商品匹配/购买组、D1-B治理批次/明细均为0。未迁移测试用户，不需要保留旧业务ID。

## 5. 真实样例与剩余边界

| 抽验 | 新库真实结果 |
| --- | --- |
| 小龙虾等号 | `小龙虾 = 2 斤`的raw_name为`小龙虾`；2 CUSTOM/斤，不猜地域换克 |
| 黑鳕鱼逗号描述 | raw_name为`黑鳕鱼，带皮`，不夹2片/450g和后续说明；raw_line完整，两组量冲突保持null |
| mL | 黑鳕鱼酱油`25mL`→25 ML；料酒5mL→5 ML |
| kg / L | 无骨鸡爪1kg→1000 G；葱油桂鱼5L→5000 ML；清蒸生蚝1升→1000 ML，没有二次乘1000 |
| 约人数 | 清蒸鳜鱼153、老式锅包肉177、芥末罗氏虾180、豆豉鲮鱼油麦菜192，servings均2 |
| 人数范围 | 卤菜“2-3人”→2；水煮鱼“3至5人”→3；仍保存范围依据和人工确认提示 |
| 漏料恢复 | 面包10、巴斯克7、龟苓膏4、酒酿3、鱼香肉丝15、煲仔饭8、芝麻烧饼6，共53行 |
| 标题/已识别工具 | `#`标题用料0，与已识别厨具同名用料0；戚风蛋糕厨具4条且材料0，不从多规格表格选列 |
| 原文保留 | 小龙虾、黑鳕鱼、戚风蛋糕3份数据库原文与源文件逐字符一致，未做哈希审计 |

真实未知分类：

- 人数未知296道：未明确人数293、基准冲突3；尺寸、份数、整批产量不猜人数。已采用约人数依据5条、范围下限依据24条，仍是参考折算基准。
- 用量/单位未知530行：数量或单位未知459、多组量冲突71。斤地域未明确另9条警告，保留CUSTOM/斤；它们不全属于数量未知。
- 零材料3道：戚风蛋糕（多规格表）、桂林十八酿（多配方组合）、虎皮肘子（表格），均保留原文并待复核，不构造单一配方。
- 警告1994条由新建待复核食材1044、v2解析警告876、缺估算时间74组成；不是1994次导入失败，也不是1994个独立缺陷。

**发现但本轮未修改的规则覆盖缺口**：蒸花卷用料381/382仍包含`圆碟子，直径 28cm`、`蒸架，直径 20cm`，对应食材194/195；太阳蛋用料299仍叫`油的用量为`；牛油火锅底料1883仍叫`每份原料可制作`。电饭煲蒸米饭用料2383/2384仍包含长说明，其中2384把说明中的“一”解析为1 COUNT，不能当可信采购量。关键词抽验另含正常的“蒸锅用水”等候选，不把所有含厨具词的行一概判错。

这些结果说明已签收v2并未覆盖所有自然语言格式。新库真实导入完成，**“所有厨具或说明均已从食材剔除”这一更强结论不成立**。任务卡禁止修改生产规则，因此只记录精确行和原文，未继续清洗或假称全量质量签收。新规则治理另需明确任务边界。

证据与完整清单（均在5b09）：

- `.data/d1-r/quality-samples.txt`：分类、实际样例。
- `.data/d1-r/verification-repeat.txt`：重跑后数量、空表、人数/单位、恢复和Mock检查。
- `.data/d1-r/raw-and-boundaries.txt`、`raw-sample-check.json`：原文及剩余格式。
- [全部530条未知用料](../.data/d1-r/unknown-materials.csv)、[全部296道未知人数](../.data/d1-r/unknown-servings.csv)。

## 6. 完整食材ID表与Mock重绑定清单

[新库1044条标准食材ID与规范名完整表](../.data/d1-r/ingredient-map.csv)由新库只读 `COPY ... TO STDOUT` 导出，含 `id,canonical_name,normalization_status`，CSV1044条记录（含表头1045行）。该表仅属于本轮新库，不能套用旧库数值ID。

只读检查了0f47运行配置 `.data/runtime/3b-20261006/mock-catalog.yml` 的12个显式绑定。下表“候选”仅指在新库中找到商品文字所对应名称的行，供人工核对；没有自动匹配商品、改配置或将候选升级为REVIEWED。

| 现有Mock商品 | 旧配置ID | 同数值ID在新库的名称 | 新库名称行供人工核对 | 结论 |
| --- | ---: | --- | --- | --- |
| mock-shrimp-194 | 194 | 圆碟子，直径 28cm | 鲜虾138；虾57另行区分 | 必须重绑定并确认商品食材 |
| mock-butter-195 | 195 | 蒸架，直径 20cm | 黄油105 | 必须重绑定 |
| mock-soy-10 | 10 | 生抽 | 生抽10 | 名称未漂移，仍核对新库绑定 |
| mock-oyster-11 | 11 | 蚝油 | 蚝油11 | 名称未漂移，仍核对新库绑定 |
| mock-darksoy-12 | 12 | 老抽 | 老抽12 | 名称未漂移，仍核对新库绑定 |
| mock-oil-70 | 70 | 千张 一张 | 食用油63 | 必须重绑定 |
| mock-salt-59 | 59 | 冰糖 | 盐35 | 必须重绑定 |
| mock-sugar-60 | 60 | 植物油 | 糖56 | 必须重绑定 |
| mock-celery-578 | 578 | 酸笋 | 芹菜456 | 必须重绑定 |
| mock-sesame-645 | 645 | 鲮鱼罐头 1 罐 | 香油512 | 必须重绑定 |
| mock-tomato-830 | 830 | 冷冻去头去皮基围虾 | 西红柿643 | 必须重绑定 |
| mock-egg-4 | 4 | 鸡蛋 | 鸡蛋4 | 名称未漂移，仍核对新库绑定 |

8个旧数值ID语义漂移、4个名称恰好一致，12项都需要在未来切库时按新库语义确认。旧商品ID中的数字不自动等于新ingredientId，价格/包装仍属MOCK；未实施采购验收。

## 7. 测试、改动与未验边界

最终执行：

```powershell
# 在backend，JAVA_HOME指向JDK21；常规测试不使用收费API。
mvn -o '-Dcooker.t6.enabled=false' '-Dtest=RecipeRebuildToolPostgresTest,RecipeImportServiceImplTest,RecipeImportFileServiceImplTest' test
mvn -o '-Dcooker.t6.enabled=false' '-DskipTests' package
```

13项通过，失败/错误/跳过均0：独立入口9项（2项真实临时PG事务/幂等，7项拒绝目标与错误脱敏）、既有导入Service各2项。最终离线打包通过；没有重跑历史大套件。测试中的TRUNCATE/触发器仅在自有临时数据库，绝不针对旧库或本轮新库。

修改文件列表（全部新增）：

1. `backend/src/main/java/com/cooker/recipe/diagnostic/RecipeRebuildTool.java`
2. `backend/src/test/java/com/cooker/recipe/diagnostic/RecipeRebuildToolPostgresTest.java`
3. `scripts/d1-r/Invoke-RebuildImport.ps1`
4. `docs/工作报告-D1-R-新库重建与菜谱导入.md`
5. `docs/技术文档-D1-R-迁移导入与三层数据.md`

本轮保留原dirty、D1-A/B工具及证据，不提交或合并。正常应用不会扫描注册独立入口的事务配置；单文件Service有真实Spring事务代理，批次允许成功文件提交并隔离失败文件。

**网页状态**：未切换运行数据源；0f47原3B启动配置仍指向 `5433/cooker`。本轮只读探测8080健康和5173网页均未能连通，没有启动、停止或重启它们。不能声称当前网页已连接新库或用网页判定此次导入成败。

**索引状态**：新库vector、zhparser、pg_trgm扩展可用，但检索投影/向量表0行；没有复制旧向量、调用Embedding或伪造READY。既有索引入口会调用Embedding，缺少可直接落库的零Embedding专用入口，所以本轮后置索引，不改收费流程。混合检索未就绪。

未完成或未验：完整人工食材审定、剩余解析格式治理、恢复演练、程序切库、重新注册/模型Key配置、Mock绑定更新、向量重建、真实浏览器闭环、真实商品/模型验收。源码及事实证据已交付，停止等待轻量复审，不自动推进这些后续任务。
