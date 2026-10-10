# U5 R1～R3 轻量复审与 IDE 交接

日期：2026-10-05。用户请求修复完成后轻量复审并推进计划。结论：R1/R2/R3 实现与非收费回归签收，进入 IDE 启动及 U5 真人复测，真实供应商质量尚待用户反馈。

## 1. 独立复审结论

- 后端核对两个餐单/指导 Service、MealSafeProjection、MealSafeDiagnostics 和新增持续测试：先投影/隔离再计数，保留原事实及指纹；U3/U4 投影一致；危险已选依据失败保留选择；模型输出仍受原策略约束；日志不传原异常对象、message、正文或秘密。
- 前端核对创建代次与成功终态顺序，以及真实 Vue watch/内存路由/实际 activate 的持续测试。成功导航不再把已确认创建标为未知，真正超时和取消仍保持未知，账号切回及旧 finally 不污染新请求。
- 后端与起始 502 文件哈希独立比较：仅两个允许 Service 和两份既有专属测试发生变化，其他 498 文件一致，无删除。新增投影、诊断、测试及教学文件与交付清单相符。未改原事实服务、原 POM、T5、迁移或全局 safeText。
- 本次未发现阻断问题。正常链接转换仍是保守规则，不据此承诺所有复杂 Markdown 或自然语言均可接受；具体真人事故候选未保存，不追认某道菜为原始触发者。

## 2. 验证证据

- 前端独立执行 npm test：27 文件、185 项全部通过；type-check/build 通过。初次沙箱临时模块目录 ENOENT 导致加载失败，使用正常用户环境后通过，未修改业务代码规避。
- 后端独立 Java21 离线专项：MealSafeProjectionTest、MealSafeProjectionPostgresHttpTest、MealPlanningPostgresHttpTest、MealGuidancePostgresHttpTest，共36项全部执行通过；包含真实HTTP/Service与隔离PG、完整两套餐单→选择→五菜指导、危险候选隔离及日志不泄漏。
- 初次后端回归因 Docker Desktop 未运行而出现3个测试容器启动错误、2项单测通过；启动现有 Docker Desktop 后补跑36项通过。未把初次环境错误记录为业务断言失败。
- 查阅实现会话完整回归原日志：189项、188实际通过、1既有收费测试跳过；这是交付方全量证据，本次主架构采用36项风险匹配复跑，没有重复整个套件。
- 默认路径 `mvn -o -DskipTests package` 本次独立通过，Spring Boot repackage 与 `.jar.original` 重命名成功。交付方此前隔离打包证据保留，但默认路径受阻已不再重现；不能确定此前具体占用原因。
- 两工作树 git diff --check 通过，只有既有换行提示。未进行 Git 提交、整合或推送。未调用真实模型/Embedding。

## 3. IDE 交接的当前环境

- 最新后端源码：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker\backend`；main 为 `com.cooker.CookerApplication`，JDK21。E:\Cooker、0f47后端与df67不作为本次启动基线。
- 本轮检查时旧8080/5173服务均已退出，没有停止任何旧业务进程；现8080留给用户IDE启动。
- 已启动现有 Docker Desktop；cooker-postgres 健康，5433→5432，Flyway V12、372菜谱、餐单在途动作0。没有新增迁移、重导入或清库。
- Windows User 中 JWT稳定密钥、凭证加密主密钥、百炼Key、DB_USERNAME、DB_PASSWORD均存在；用户名/密码与现有Docker配置比对相符。只输出是否存在/是否相符，没有展示秘密。
- 已为本次交接启动0f47前端真实模式，PID13376，监听127.0.0.1:5173，使用IPv4优先访问既有8080代理。后端由用户在IDE运行；页面可访问不代表后端联调完成。
- IDE操作指南：`C:\Users\Omelet\.codex\worktrees\5b09\Cooker\docs\开发指南-U5-IDE启动与安全报错.md`。其中“旧Jar占用8080”“默认打包受阻”为此前交付时状态，当前以本报告为准。

## 4. 用户启动步骤与真人复测

1. 在IDE导入5b09的backend/pom.xml，项目与运行JDK均为21，main类和工作目录按上述配置。重启IDE以继承现有Windows用户变量，保留原JWT/加密主密钥。
2. 本地运行配置显式设置 `DB_URL=jdbc:postgresql://127.0.0.1:5433/cooker`、`SPRING_PROFILES_ACTIVE=local`、`COOKER_SECURE_COOKIE=false`。其余既有变量继承用户环境；不把秘密放共享配置。
3. Program arguments 使用下列真实试用配置。用户此前已授权真人调用费用，无需再次批准本次手动复测；没有自动批测或失败重试。

```text
--server.address=127.0.0.1 --server.port=8080 --cooker.agent.execution-enabled=true --cooker.recipe-import.enabled=false --spring.flyway.clean-disabled=true
```

4. 看到应用启动成功后打开 http://127.0.0.1:5173/kitchen，使用自己的账户与已保存的模型Key。新建会话应无“结果未知”误报。新建一餐输入：`3个人吃，帮我搭配4菜一汤，想吃清淡一点。`
5. 检查两套不同组合各4菜1汤、材料和3人依据；点击一套后检查完整5道菜指导、步骤依据与可选补充分区；刷新应回看同一结果。旧失败餐保留历史，不会自动重新生成。
6. 如失败，提供IDE控制台的“餐单诊断”行中的stage/code/recipeId/fieldPath/rule及页面现象；不要提交密钥或完整环境变量。先根据记录定位，再决定下一次尝试。

本次停留U5真人复测及内容质量确认。通过后进入U5质量/演示收尾；采购、SSE、微服务与MQ没有派发。
