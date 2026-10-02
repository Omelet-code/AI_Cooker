# ADR-008：DeepSeek官方接入与受控Agent编排

- 日期：2026-10-02，Asia/Shanghai。
- 状态：**用户已确认官方平台、deepseek-flash及3A→3B顺序；其余为主架构推荐，待确认和具体实现授权**。

2026-10-02首批授权更新：用户明确允许派发T12/F3-A。T12内的手工原生适配、非思考、固定官方地址、无自动重试及协议/安全Mock测试按冻结首批任务卡执行；这是首批技术基线，尚非实现或真实API验收证据。对话、RAG编排、工具执行/SSE、购物及收费联调仍待各自具体授权，不从本ADR扩大实现范围。

## 背景

用户按账号提供模型Key，T8已提供受控解密接口；项目采用Spring Boot4与Spring AI2.0.1，当前只有Embedding依赖。阶段三需要自然语言理解、真实菜谱引用、受控工具与前端对话，不建设无限自主规划或多Agent。

## 官方资料核验

核验日期2026-10-02：DeepSeek官方主页明确使用 `https://api.deepseek.com`、`/chat/completions` 和 `deepseek-flash`。能力表列出JSON与工具调用；首次调用示例支持stream参数。用户的官方API Key不会由项目生成，也不能用LLM_CREDENTIAL_MASTER_KEY替代。依据：[首次调用](https://api-docs.deepseek.com/zh-cn/)、[模型能力](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)。

Chat Completions说明：默认thinking开启，可显式disabled；JSON模式还需提示产出JSON并检查截断；思考模式不能使用required或指定工具选择。流式协议为SSE，末尾有完成标志。常规工具由客户端实际执行；strict属于Beta。MVP建议非思考、普通稳定接口、Java校验，不依赖Beta strict。依据：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)。中文部分子页面多次获取超时，以上采用可访问的同站英文规范，不把重定向首页当作专题证据。

## 候选方案

| 方案 | 学习成本与优势 | 风险/维护成本 |
|---|---|---|
| A：Spring AI原生DeepSeek模块，手工配置 | 延续现有模型抽象；专有参数和工具消息有对应类型 | 需要核验锁定版本、隔离Key、覆盖默认重试及日志行为 |
| B：Spring AI OpenAI兼容适配 | 通用协议，未来更换平台方便 | DeepSeek专有参数与流式差异需额外验证，不能把兼容理解为完全一致 |
| C：RestClient直接HTTP | 请求可读，符合已有百炼适配方式 | 自行维护消息/工具/流式序列化及错误处理，重复框架能力 |

## 推荐决策与理由

推荐A：后续授权时引入与现有版本一致的 `spring-ai-deepseek:2.0.1`，不自动引入使用全局Key的starter。以项目 `CookingModelGateway`/等价接口屏蔽供应商，底层手工构造本轮DeepSeek客户端，使用当前用户解密材料。本轮结束不保留用户客户端/明文Key缓存，不修改共享单例的Key。JSONB/SQL仍归现有Mapper，检索继续调用已签收Service。

Spring官方资料给出手工配置；本次同时核对v2.0.1的options、model和API源码，确认存在字符串模型ID、thinking选项、客户端Key配置、call/stream和重试模板入口。这是设计可行性证据，不是本项目已经引入、编译通过或真实调用成功。具体兼容门槛属于T12。依据：[Spring DeepSeek参考](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html)、[v2.0.1 options](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatOptions.java)、[v2.0.1 API](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/api/DeepSeekApi.java)。

编排推荐分阶段：先“结构化理解→Service检索→候选约束解释”固定流水线，再加入白名单工具的有界循环。主架构选择低层ChatModel以便审查预算和状态，不直接采用无限循环/自动Memory/RAG Advisor；暂不新增LangChain4j、LangGraph、向量Store或另一套Agent框架。

## 安全、费用及实现闸门

- 模型名字符串显式配置为deepseek-flash，不使用框架默认旧名；固定官方Base URL，不允许前端提供任意目的地址携带Key，防止泄露/SSRF。
- 显式关闭thinking，不混用相冲突的reasoning_effort。开启思考作为后续评测选项，另行定义工具消息与成本策略。
- 网关默认不自动重试模型请求，单次调用失败映射为安全错误。网络断线不能证明供应商未计费；幂等保证本地重复请求不重复执行，不承诺跨进程/网络的收费恰好一次。
- options/Prompt/客户端对象不得toString或打请求体日志。核对的v2.0.1模型源码在空响应/空choices分支存在包含Prompt的WARN；T12必须覆盖这些分支并关闭该模型日志中的内容输出，异常处理也不能回显供应商原始正文。关闭内容日志不等于关闭所有安全审计，保留状态码/轮次ID/耗时等最小信息。依据：[v2.0.1 ChatModel](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatModel.java)。
- 所有工具校验类型、范围、用户身份、资源可见性和次数。模型只能请求动作，不能执行Mapper、Shell、任意HTTP或更改认证/库存/购物关键写入。
- 3A/3B真实收费联调分别取得具体调用范围和费用风险批准；本次只读文档没有使用任何模型Key或收费请求。

## 影响及复审条件

新增Spring AI Chat依赖仅在T12具体授权后实施；无法在Java21/Boot4/锁定版本下通过Mock Server协议测试时停下报告，不私自升级全项目或改选框架。后续需求包括更换模型、思考工具链、私有部署或真实商品平台时再复审。
