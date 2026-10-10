# T18-B1：默认客户端HTTP守卫接缝

2026-10-09，实际目录`C:\Users\Omelet\.codex\worktrees\5b09\Cooker`。仅完成已批准接缝及定向非收费验证；下一停等主架构轻量复审，再集中确认两餐真人操作、启停、必要写入与收费。没有新中间任务或跨会话消息。

## 结果与范围

两个实际默认生产客户端均已通过Spring注入共用SyncModelTransport。普通模式保持原SimpleClientHttpRequestFactory、旧构造器、模型/鉴权/JSON/thinking、2048/8192和30/60秒档位。显式受控模式采用JDK21同步HTTP，必须启动时关闭POST及连接重试；禁重定向，query/LLM共用进程级批准账本，无上下文、停止或超额在发送前拒绝。没有新管理页、API、依赖或迁移。

先持久METHOD_RESERVED再持久HTTP_RESERVED，写后force(true)；错误/未知不退额度，串行批次锁覆盖实际调用及响应读取/解码，追加网页线程无法越界。审批配置只由服务端启动读取，没有HTTP激活/换批入口；旧账本CREATE_NEW拒绝重开，崩溃后只读核对，不恢复额度。批准标识是操作记录，不是密码、数字签名或替代用户授权。

受控模式实际默认query及默认DeepSeek工厂、本机替身接收数已验证；Service/临时PG实际接线也通过。不是仅测试手工构造无关SDK客户端。HTTP_RESERVED是保守发送尝试，不证明供应商接收或收费；失败usage未知留null，不报免费或恰好一次计费。日志不保存Key/Prompt/请求正文。

基线HEAD=`26c33ace4ad22e72350cba65cccb6f528c299917`、分支`codex/kitchen-assistant-u3`、top=5b09。普通Git只读权限失败后经正规只读核对成功，未改ACL/Git状态。已读自身规则/技术文档、0f47 B1冻结卡、B0回执和真人候选，主树只读。既有dirty/U4档位保留；未修改资格SQL、排名、RRF、词法降级策略、Prompt、身份、密钥加密、迁移、采购规则或17＋1＋2目标口径。

## 修改文件

修改2份生产文件：

- [QueryEmbeddingServiceImpl.java](../backend/src/main/java/com/cooker/retrieval/service/impl/QueryEmbeddingServiceImpl.java)：保留单参数构造器，增加Spring提供者注入，实际embed纳入方法/HTTP保护。
- [DefaultDeepSeekChatClientFactory.java](../backend/src/main/java/com/cooker/agent/config/DefaultDeepSeekChatClientFactory.java)：保留无参和原同包测试构造器，新增注入；普通返回原模型，受控包装实际call且禁止stream。正式供应商URL不新增配置，测试地址仍仅同包构造器使用。

新增6份生产辅助（专用评测传输包）：

- [SyncModelTransport.java](../backend/src/main/java/com/cooker/evaluation/transport/SyncModelTransport.java)：窄同步提供者接口。
- [DefaultSyncModelTransport.java](../backend/src/main/java/com/cooker/evaluation/transport/DefaultSyncModelTransport.java)：普通/受控模式、启动参数验证、方法上下文与全局停止。
- [EvaluationBatch.java](../backend/src/main/java/com/cooker/evaluation/transport/EvaluationBatch.java)：独立持久账本、参数化双通道额度和有限状态。
- [BudgetRequestFactory.java](../backend/src/main/java/com/cooker/evaluation/transport/BudgetRequestFactory.java)：HTTP前票据核对、协议限制、有界响应读取及失败停止。
- [EvaluationHttpProperties.java](../backend/src/main/java/com/cooker/evaluation/transport/EvaluationHttpProperties.java)：默认关闭的服务端配置。
- [EvaluationTransportConfiguration.java](../backend/src/main/java/com/cooker/evaluation/transport/EvaluationTransportConfiguration.java)：启动时读取有界批准文件并绑定唯一账本。

新增2份专项：[DefaultClientGuardTest.java](../backend/src/test/java/com/cooker/agent/config/DefaultClientGuardTest.java)、[DefaultQueryPostgresGuardTest.java](../backend/src/test/java/com/cooker/t18/DefaultQueryPostgresGuardTest.java)。新增本报告及[技术文档](技术文档-T18-B1-依赖注入与发送前保护.md)，合计12份本轮文件。B0脚本/资源不改；根架构文档由主架构维护，本轮只更新两专题报告。

## 定向执行与证据

| 验证 | 通过 | 失败 | 错误 | 跳过 | 证据 |
| --- | ---: | ---: | ---: | ---: | --- |
| 首轮默认客户端＋原协议/超时 | 24 | 0 | 0 | 0 | [client-tests.log](../.data/t18-b1/client-tests.log)，新增9、原ModelGateway10、原MealGuide5；15:28:46结束 |
| 最终新增默认客户端＋实际PG | 12 | 0 | 0 | 0 | [final-tests.log](../.data/t18-b1/final-tests.log)，客户端11、PG1；15:33:50结束 |
| 其中流式/截断停止定向复验 | 2（不累计） | 0 | 0 | 0 | [refinement-tests.log](../.data/t18-b1/refinement-tests.log)，15:37:01结束 |
| 同一响应用例补充非法/尾随JSON后复验 | 1（不累计） | 0 | 0 | 0 | [json-final.log](../.data/t18-b1/json-final.log)，15:41:39结束 |

当前独立Java用例总计27项：原协议15＋新增客户端11＋PG1；不叠加重跑。compile/testCompile与专项构建成功，[首次编译记录](../.data/t18-b1/compile.log)保留。全部模型Key虚构，HTTP仅127.0.0.1替身；PG是Testcontainers新库/随机端口，迁移V1—V15，未连接宿主库。两报告链接、两个限定生产文件diff空白及新增文件空白检查通过；不把已有未提交U4等差异算成本轮修改。

实际专项覆盖普通构造器兼容、QUERY单文本/1024/query模式、DeepSeek固定协议与两输出档位、缺上下文/票据、方法与HTTP分别耗尽、跨通道停止、并发上限、401/403/429/500/503/307带Location、断开响应/超时实际只接收一次、document/stream拒绝、账本写失败、启动属性漂移、独立批次及旧账本拒绝。LLM截断/无效JSON不记成功。

PG证明实际RecipeSearchService→QueryEmbeddingServiceImpl→HTTP守卫→替身→vector/lexical/RRF；空资格0调用，401后原词法降级结果保留，账本停止且下一调用拒绝。不会把词法降级算HYBRID成绩。

本次未执行：B0已签收Python22/Java13不重做；历史全量、哈希、真实API、真人浏览器、真实TLS/供应商返回质量及账单均未测，正式观测0。不为凑p95补样本。**按用户先真人后打包部署的顺序，本轮未执行package、启动/重启或部署。**

验收运行于显式JDK21/Maven离线，不持久改环境；测试子JVM使用：

```powershell
# backend目录，沿用已核实Java21 Maven launcher；下面为Maven参数
-o '-Dcooker.t6.enabled=false' '-DargLine=-Djdk.httpclient.enableAllMethodRetry=false -Djdk.httpclient.disableRetryConnect=true' '-Dtest=DefaultClientGuardTest,DefaultQueryPostgresGuardTest,ModelGatewayProtocolTest,MealGuideGatewayProtocolTest' test
```

实际先24项、后12项、再受影响定向复验，没有重跑历史全量。测试内属性漂移只在子JVM临时设置并finally恢复；未改User/System环境、真实Key或运行程序。

## 直接衔接真人测试

技术文档给出可直接用于现有IDE启动的VM选项、批准JSON格式和账本使用方式。尚需主架构集中一次确认：最终库/账号/在途与Key安全配置只读核对、精确前后端启停、用户页面两餐操作和必要写入、参数化6 LLM/4 query的方法及HTTP额度、失败停止与只读回看。18题检索批保持独立后置，不共享账本。

获批后可编译最新源代码，通过现有CookerApplication/IDE启动受控进程，无需先package；不能使用B0旧Jar宣称加载本轮接缝。本轮没有创建任何真实批准文件/账本或操作项目程序。支持普通模式但真人收费窗口必须明确受控；退出受控模式需要关闭窗口后由主架构按批准回退，不静默降级到普通付费。

剩余实际限制：受控请求串行且响应缓冲（请求≤1MB、响应≤4MB），属于评测保护，不作为普通性能成绩；受控实现限定本轮验证的JDK21和两项启动参数，漂移停止。批准文件不是自动身份审批；幂等业务结果不等于供应商恰好一次收费。真人两餐不是完整检索质量验收，不报p95。启动与测试数据写入仍另批，完成停等主轻量复审。
