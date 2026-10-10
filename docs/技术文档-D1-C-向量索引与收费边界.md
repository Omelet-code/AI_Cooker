# D1-C：向量索引与收费边界

日期：2026-10-08。实际实现目录5b09。唯一目标为 `localhost:5433/root/cooker_rebuild_d1r2_20261007`；实际收费次数、usage及落库结果以[本轮工作报告](工作报告-D1-C-检索投影与真实向量重建.md)为准。

本轮已完成1856块真实document向量：首轮375次HTTP、唯一恢复4次，累计379次；恢复仅19个NETWORK失败块，原成功1837块未重发。最终全部READY、覆盖372道菜，独立只读和零出站复用核验通过。可观测成功usage178940 Token，4次失败尝试usage及实付金额未知。43项专项通过；额外非收费全量回归仍有3项旧V14断言失败，不宣称全量通过或检索质量签收。

## 1. 原文、事实、检索投影各保存什么

HowToCook完整Markdown保存在recipe_source_document；recipe、用料、步骤及厨具表保存确定或明确未知的结构化事实。RecipeIndexProjectionService回查这些事实，RecipeSearchDocumentBuilder按冻结模板生成四种chunk；百炼把chunk转成1024维document向量，RecipeSearchDocumentMapper把文本、版本、权限和向量写进recipe_search_document。

```mermaid
flowchart LR
  A[完整原文] --> B[已导入的结构化事实]
  B --> C[公共投影Service]
  C --> D[原Builder：四类chunk]
  D --> E[出站前持久记账]
  E --> F[百炼document向量]
  F --> G[pgvector及自动tsvector]
```

D1-C只生成可重建的检索冗余，不再解析、导入或审定。不会通过LLM补简介、材料数量、原人数或条件配方。544行量/单位未知、296道人数未知、牛油条件配方及964个未审食材仍是源事实边界；把这些文本向量化不等于人工审核。

## 2. 代码分层和最小装配

| 层级/组件 | 本轮职责 |
| --- | --- |
| recipe/diagnostic/RecipeProjectionContext | 仅装配recipe内部五个既有Mapper与真实投影Service；不改事实查询含义 |
| retrieval/diagnostic/DocumentIndexTool | 精确目标与配置校验、显式模式、前后核验及独立报告；无Boot/Web/Flyway/LLM |
| retrieval/diagnostic/IndexPlan | 消费真实Service与原Builder，冻结预期块、来源关联、业务内容标识、C/B及输入上界 |
| retrieval/diagnostic/BoundedIndexLedger | 固定运行目录、互斥、追加持久计数、一次恢复、失败停止和安全usage |
| retrieval/service/impl/IndexRunControl | 独立任务的显式保护钩子；普通装配默认NONE，query侧不接入 |
| RecipeIndexServiceImpl | 仍按原版本判断待生成块；停止异常穿透逐菜catch，先全批校验再写，写失败保留已成功行 |
| DashScopeEmbeddingModel | 仍使用Spring AI EmbeddingModel和百炼原生HTTP；document安全响应校验、有限失败分类与usage观察 |
| scripts/d1-c/Invoke-DocumentIndex.ps1 | 仅注入本次子进程既有凭证，保存新日志，结束恢复进程变量 |

跨模块只通过recipe公共Service及DTO消费事实；装配帮助类留在recipe内部。没有跨模块重写事实SQL、第二套ORM、VectorStore、ChatClient或新切块器。现有未提交基线保留，根架构状态由主架构复审后维护。

## 3. 为什么C和B不是同一个除法

预检固定模板1、每步骤块4个完整步骤、长度配置4000字符、每批10、text-embedding-v4/1024/document/dense。Builder不按字符截断单个长步骤，也不为减少费用更换模板。

真实预检：C=1856；overview372、ingredients369、steps1022、tools93。369道菜各需1次请求，3道各需2次；B=369+3×2=375。现有索引按菜谱独立分批，因此不是ceil(1856/10)=186。两道菜各4块，也需要2次请求，不能在费用记录里合称1次。

首轮每块最多发送一次，唯一恢复轮次只取仍失败/缺失的块，每块累计最多两次。每轮HTTP上界B、累计2B=750；达到任一块/HTTP上界在出站前拒绝，不靠结束后补计数。

## 4. 输入长度预检与官方边界

2026-10-08核对[阿里同步Embedding官方资料](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/)：v4单批10条、单条8192 Token，支持1024维和dense，document/query用途分开。沿既有原生endpoint，不擅自改地域或兼容接口。

本轮文本总字符222781，最长1076字符。预检采用UTF-8字节数加32封装余量，最大2289，作为字节级BPE的保守Token上界，不是精确供应商Token计数。依据是官方说明v4属于Qwen3-Embedding系列，公开[Qwen3-Embedding tokenizer配置](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/raw/main/tokenizer_config.json)使用Qwen2Tokenizer，其[官方实现](https://github.com/huggingface/transformers/blob/main/src/transformers/models/qwen2/tokenization_qwen2.py)使用字节级BPE；合并不会增加字节片段数。该上界属于基于公开实现的推断，实际供应商usage独立记录；不把字符数直接当Token。

超界或无法保守排除风险会在预检停止，不能截断步骤、换模型或额外收费试探。计划只保存块标识、长度、来源权限和原Builder的业务contentHash，不持久化全文或增加原文/全库哈希审计。

## 5. 发送前记账，超时不是免费

固定目录 `.data/d1-c/d1c-20261008` 内plan.json不可盲目覆盖；attempts.jsonl按事件顺序追加。每个SEND记录模式、recipeId/chunkKey和次数，写入并force同步后才能POST。读取账本时复用原计数；换模式不清零。FileLock拒绝同时启动第二个索引进程。

如果发送超时或连接断开，该SEND仍消耗一个尝试。调用返回失败才追加FAILURE；返回合法向量才追加SUCCESS和安全数值usage。如果进程在发送后崩溃，留下未闭合SEND，费用结果未知，后续不能把它当“供应商没有收到”而重新跑首轮。

正常完成追加END；execute只能开始一次，recover仅在首轮完成后开始一次，成功块不再发送。计划/事实/权限漂移、损坏账本或全局停止均保留现场，禁止覆盖文件重新获取额度。没有SDK自动重试、后台无限重试或循环补齐。

## 6. 全局错误与瞬时失败

| 情况 | 行为 |
| --- | --- |
| 401/403 | AUTH全局停止，当前已发送批可FAILED，后续仍缺失 |
| 无效模型/参数、错误维度/格式/非有限向量 | PARAMETER/RESPONSE全局停止 |
| quota/balance/arrear/allocation等错误、无法确认的429 | QUOTA全局停止，不当普通短期限流 |
| 明确请求瞬时限流、网络、5xx | 有界失败隔离；连续3批失败停止，不能遍历浪费 |
| 重定向 | 禁止跟随携带Key，REDIRECT停止 |
| 块/HTTP预算用尽、事实配置漂移 | 发送前停止，无新尝试 |
| 已返回向量但写库失败 | DATABASE_WRITE停止，保留部分READY，不清空或默默重新收费生成 |

停止异常有明确sent标志：发送前拒绝不能伪标为供应商失败；已经发送的当前批失败才记录FAILED。既有catch(RuntimeException)不再吞掉全局停止，未处理菜谱不能全库伪FAILED或READY。

读取响应的SocketTimeout可能被RestClientException包住，并不总是ResourceAccessException。适配器只检查有限层级的异常原因类型，不回显原因消息，按NETWORK计账；本轮专项曾发现并修正这一分类。

## 7. HTTP和凭证保护

独立进程使用SimpleClientHttpRequestFactory，连接超时10秒、读取30秒；请求顺序发送，发送间隔至少1100ms。启动时显式设置sun.net.http.retryPost=false与http.keepAlive=false，禁止HttpURLConnection默认POST重试。JDK21本地源码对应retryPostProp分支及本机断连/超时替身均已核对。

只允许现有阿里官方HTTPS原生路径；拒绝URL里的用户信息、query、fragment及其他域名。每个连接instanceFollowRedirects=false，3xx有专属停止分类；本机测试确认第二目的地址收到0请求。

Key仅从既有DASHSCOPE_API_KEY进程/User变量读取，只注入本次Java子进程。DB密码处理相同，不写系统/User变量、文件、命令行或日志，不读取账户LLM密钥。测试显式清空真实平台Key且只使用test-only，真实调用的报告仅记录Key存在性。

## 8. READY、失败恢复和SQL核验

READY要求当期内容标识、模板、模型、维度及原文/权限关联一致；仅“行已存在”不能跳过。返回向量先检查条数、document索引唯一、1024维、数字且有限，再逐行通过原Mapper upsertReady。投影tsv由PG生成列自动维护。

独立固定快照不清理其他历史块：已有异常记录先停报，因此本轮保护关闭过期块删除。数据库后置写失败可留下第一行READY、其余缺失；测试证明不会把已成功行改FAILED。账本即使记录供应商成功，也不等于数据库已READY。

独立核对预期、实际、READY/FAILED/PENDING/缺失和每菜覆盖；检查向量非空、vector_dims=1024、有限值、indexed_at、版本、来源和权限；以中文全文查询证明tsv实际可用。源事实数量及12审定ID前后另核对，不追加全库哈希审计。

完成后仅以禁止出站的EmbeddingModel替身运行原索引Service，确认现有当期READY全部跳过、调用0。替身一旦被调用就抛停止异常，不能生成假向量或补写生产READY；不再用真实百炼跑全库幂等验证。

## 9. document与query、RRF有什么区别

本轮document向量是菜谱文本的持久检索投影。query向量是在用户显式检索时把需求转成同模型、同维度的查询向量，它没有被本轮收费授权覆盖。没有DeepSeek、query向量或旧T6收费批测。

T5先做结构化资格和权限过滤，再分别召回词法与向量候选；每个通道按recipeId保留最佳chunk，最后用1/(60+rank)做RRF。向量距离与ts_rank的原始分值不能直接相加。生成1856个向量不证明Hit@3/Hit@5、p95或真实网页已经验收，也不会自动切换服务连接。

## 10. usage与账单

适配器仅提取usage.total_tokens的非负整数；缺失标未知，不把缺失补0。记录首轮/恢复HTTP和文本数、每块发送上限、成功/失败响应、可观测Token及未知usage尝试。超时或失败可能仍收费，缺供应商usage不能据此断言免费。

Token是模型计量，不是实付账单；套餐、免费额度、地域价格或折扣都会影响支付。工作报告给出可观测值，不能把估算金额冒充账单结算。

## 11. PostgreSQL与MySQL对照

| 本项目能力 | MySQL对应考虑 |
| --- | --- |
| numeric与BigDecimal | DECIMAL同样应避免浮点金额，不改变事实null |
| ON CONFLICT投影唯一键 | 需改写重复键路径，同时保留版本及成功跳过规则 |
| pgvector/vector_dims/余弦距离 | 不能直接复用PG迁移/SQL；需重新选择并验收向量能力 |
| zhparser、tsvector及GIN | MySQL全文分词/索引机制不同，需要重新验证中文行为 |
| 外键和来源权限 | 引擎变化也不能取消owner/visibility及当前原文关联 |
| Java文件互斥和持久费用账本 | 属于外部调用治理，不能靠数据库写幂等替代 |

本轮没有更换数据库、索引算法或技术栈。

## 12. 面试追问和学习自检

1. 为什么索引行幂等不代表费用幂等？——超时/写失败可能已经调用成功，必须在发送前持久记账并保留未知现场。
2. 为什么B不是ceil(C/10)？——既有Service逐菜分批，不能把不同菜谱当一个批次估算。
3. 为什么401要穿透逐菜catch？——它是全局配置错误，继续遍历只会扩大无效尝试。
4. 为什么429不能统一重试？——有些是额度耗尽，必须根据有限错误分类，未知保守停止。
5. FileLock能否阻止其他工作树未经许可的脚本？——只能保护本卡固定入口/目录；授权仍限定此入口，不能声称全平台费用硬配额。
6. 账本SUCCESS是否等于READY？——不等；响应成功后写库仍可能失败。
7. 为什么不能用假向量补齐生产READY？——它会伪造模型、内容与检索质量证据；替身仅允许阻断出站并验证跳过。
8. 全文列非空和向量1024是否证明检索质量？——只证明落库可用性，质量与网页闭环需分别验收。

自检：能追踪一次SEND到SUCCESS/FAILURE和数据库状态吗；能指出query与document授权不同吗；能解释每块两次与每轮B同时成立吗；能区分保守Token上界、供应商usage和账单吗；能说明未知材料仍未完成语义审定吗。

练习一：两道菜分别4块、12块，每批10。求C、B和2B；答案16、3、6。若首批失败而后两批成功，恢复只发送原4块，累计HTTP4，不再发送已成功12块。

练习二：供应商成功返回10条向量，第二行SQL失败。画出账本与投影状态；解释为什么不能清空第一行READY、伪造其余READY或再次收费生成。对照本轮临时PG后置触发器测试。

交付后停等主架构轻量复审；本轮不包含切库、启停前后端、真实query/LLM/商品、T18或Git写操作。
