# Cooker 多 Agent 协作规则

## 1. 适用范围与语言

本文件适用于仓库根目录及所有子目录。更深层目录如果存在自己的 `AGENTS.md`，则以更深层文件中的补充规则为准。

- 所有对用户的回复使用简体中文。
- 代码注释、错误解释、提交说明和开发文档使用简体中文。
- 类名、方法名、数据库字段及标准技术术语使用英文。
- 开始任务前必须完整阅读本文件和根目录 `技术文档.md`。

## 2. 项目目标

Cooker 是一个家庭烹饪单 Agent。用户通过自然语言表达饮食需求，系统从 HowToCook 菜谱中进行混合检索，结合用户已有食材、烹饪时间、预算和厨具推荐菜谱，并生成缺货购物清单及具体商品购买入口。

MVP 截止日期：2026-10-05。

当前阶段：**阶段 0 已完成项目骨架；暂不进入阶段 1，业务功能尚未实现。**

## 3. 已冻结的产品约束

- 第一阶段菜谱来源仅为 HowToCook，并且只在项目初始化时导入一次，不做定时、实时或增量同步。
- 后续允许用户按照 HowToCook 模板上传私有自定义菜谱；自定义菜谱与系统菜谱统一保留原始 Markdown、结构化数据和向量化检索数据。
- 运行时采用单 Agent，不做多 Agent 编排。
- 健康相关内容只提供一般性饮食建议，不诊断、不治疗。
- 个性化条件包括已有食材、烹饪时间、预算和厨具。
- 用户需要注册、登录、历史对话、收藏和家庭食材库存。
- 用户点击“完成烹饪”后扣减库存，同一烹饪会话只能扣减一次。
- LLM 使用 DeepSeek Flash，API Key 由用户提供并加密保存。
- Embedding 使用阿里云百炼，平台 Key 由服务端配置。
- MVP 允许使用 Mock 商品服务，但接口必须能被真实平台实现替换。
- 不自动加入购物车或支付。
- 前端使用 Vue 3 与 TypeScript。
- MVP 使用模块化单体；Spring Cloud 与 RabbitMQ 后置。

## 4. 技术栈基线

### 后端

- Java 21
- Spring Boot 4.0.x
- Maven
- Spring MVC
- MyBatis-Plus 3.5.17
- PostgreSQL 18
- pgvector、pg_trgm
- Flyway
- Spring AI 2.0.x（在对应阶段引入）

### 前端

- Vue 3
- TypeScript
- Vite
- Vue Router
- Pinia
- Vitest

### 基础设施

- Docker Compose
- Nginx
- PostgreSQL + pgvector 镜像

未经主架构 Agent 同意，不得替换上述核心技术栈或新增同类框架。

## 5. 架构总则

### 5.1 模块化单体

后端是一个 Spring Boot 进程，但必须按业务能力隔离：

- `identity`：用户、认证、LLM 凭证。
- `conversation`：会话和消息历史。
- `recipe`：菜谱、食材、导入和解析。
- `retrieval`：结构化过滤、词法检索、向量检索和融合排序。
- `agent`：约束提取、工具调用和对话编排。
- `pantry`：家庭库存、单位换算和库存流水。
- `shopping`：缺货清单、商品匹配和成本计算。
- `policy`：健康边界、提示词规则和输出检查。

跨模块调用只能依赖对方的 Service 接口或明确的应用事件。禁止跨模块直接访问 Mapper、Entity 或 Mapper XML。

### 5.2 模块内分层

每个后端业务模块使用以下结构：

```text
<module>/
├─ controller/     # HTTP 参数接收、校验、响应转换
├─ service/        # 业务接口
│  └─ impl/        # 业务实现与事务边界
├─ mapper/         # MyBatis-Plus Mapper，承担 DAO 职责
├─ entity/         # 数据库实体
├─ dto/            # 请求和内部传输对象
└─ vo/             # 对外响应视图
```

强制约束：

- Controller 只能调用 Service，不能直接调用 Mapper。
- Service 必须定义项目自己的接口，实现类放在 `service.impl`。
- 不将 MyBatis-Plus `IService` 作为 Controller 或跨模块接口。
- Mapper 接口使用 `@Mapper`，简单 CRUD 可继承 `BaseMapper<Entity>`。
- 复杂 SQL、关联查询、pgvector 和全文检索优先放在 Mapper XML 或显式 Mapper 方法中。
- Entity 不得直接作为 Controller 请求或响应。
- DTO 用于输入和内部传输，VO 用于 HTTP 响应。
- 不引入 JPA、Spring Data Repository 或第二套 ORM。

### 5.3 `common` 使用边界

`common` 仅允许放置真正跨模块且无业务归属的内容，例如统一响应、基础异常、通用配置和无状态工具。

禁止把业务对象、业务 Service 或“暂时不知道放哪里”的代码放进 `common`。

## 6. 数据库规则

- 所有结构变更必须通过 Flyway，禁止依赖 ORM 自动建表。
- 表名和字段名使用 `snake_case`。
- 主键策略在首个业务迁移中统一，不允许模块自行选择不同风格。
- 金额和数量使用 `numeric`，禁止使用浮点类型表达金额。
- 原始 Markdown 使用 `text`；不稳定元数据和解析诊断可以使用 JSONB。
- HowToCook 是系统级一次性种子数据；可为初始化失败提供幂等重试，但不得实现上游自动同步。
- HowToCook 与用户自定义菜谱复用同一套模板解析和索引管线，来源类型与所有者必须可区分。
- 用户自定义菜谱必须同时保留原始文档、当前结构化事实和可重建的向量检索投影。
- 核心可查询字段必须使用关系列，不能全部塞进 JSONB。
- 向量必须记录模型、维度、内容哈希和生成时间。
- 更换 Embedding 模型时不得混用旧向量，应采用版本化或重建策略。
- 所有用户数据查询必须带用户边界，防止越权访问。
- 用户自定义菜谱默认私有；检索时只能返回系统菜谱和当前用户拥有的自定义菜谱。
- 库存变化必须写入流水；完成烹饪操作必须幂等。

## 7. Agent 与模型规则

- Agent 只能推荐数据库中存在的菜谱。
- LLM 负责理解、选择工具和解释，不负责事实存储和关键事务。
- 不允许将库存扣减、凭证删除等关键写操作暴露为无需确认的 LLM 工具。
- 工具输入必须使用结构化 DTO 并进行 Bean Validation。
- 所有工具调用设置次数上限、超时和错误映射。
- 对话上下文必须限制长度，不得无限拼接历史消息。
- 健康相关输出不得包含诊断、治疗承诺或药物建议。
- DeepSeek Key 不得写入配置文件、日志、异常、链路标签或前端响应。

## 8. 安全规则

- 用户密码使用强密码哈希，不得可逆加密。
- 用户 LLM Key 使用带随机 IV 的认证加密方案；主密钥仅从环境变量或 Secret 注入。
- 数据库只保存密文、IV、算法版本和必要元数据。
- 前端只能看到 Key 的脱敏状态。
- `.env`、密钥、Token、数据库密码不得提交。
- 外部输入、Markdown 和模型输出均视为不可信数据。
- 日志默认不记录完整请求体、模型 Prompt、工具结果和用户凭证。

## 9. 前端结构规则

前端按业务模块组织：

```text
src/
├─ modules/
│  ├─ identity/
│  ├─ conversation/
│  ├─ recipe/
│  ├─ pantry/
│  ├─ shopping/
│  └─ settings/
├─ shared/
├─ router/
├─ stores/
└─ views/
```

- 模块私有组件、API、类型和 Store 放在自己的模块内。
- `shared` 只放无业务归属且被多个模块复用的内容。
- API 类型应与后端契约保持一致，禁止在页面中随意拼接未定义字段。
- 必须处理加载、空数据、错误、超时和权限失效状态。
- 不在前端存储或展示完整 DeepSeek Key。

## 10. 测试与验收

每个业务任务至少包含与风险匹配的测试：

- Service 业务规则：单元测试。
- Mapper 和 PostgreSQL 特性：Testcontainers 集成测试。
- 外部模型和商品平台：Mock Server 或适配器测试。
- 核心 HTTP 接口：接口集成测试。
- 前端工具和 Store：Vitest。
- 最终业务闭环：浏览器端冒烟测试。

不得让常规自动化测试依赖真实收费 API。

## 11. 多 Agent 角色

### 主架构 Agent

- 维护 `技术文档.md`、ADR 和模块边界。
- 决定跨模块接口、数据归属和技术版本。
- 拆分任务、审查实现并执行最终集成。

### 数据 Agent

- 负责 `recipe` 的表结构、HowToCook 导入、食材标准化和 Flyway。
- 不修改 Agent 编排或用户认证。

### 检索 Agent

- 负责 `retrieval`、Embedding、pgvector、pg_trgm 和检索评测。
- 不直接修改菜谱导入规则，需求通过 Service 接口协商。

### 身份安全 Agent

- 负责 `identity`、认证、授权和 LLM Key 加密。
- 不读取或记录用户明文 Key。

### 库存采购 Agent

- 负责 `pantry`、`shopping`、库存流水、成本计算和商品适配器。
- 不修改用户认证和菜谱检索算法。

### Agent Runtime Agent

- 负责 `agent`、`conversation`、Spring AI、工具调用和流式输出。
- 不绕过 Service 直接操作其他模块 Mapper。

### 前端 Agent

- 负责 `frontend`，严格依照 API 契约开发。
- 后端未完成时使用固定 Mock，不自行改变接口字段。

### 测试部署 Agent

- 负责测试基础设施、Docker、Nginx、CI 和演示脚本。
- 不通过降低断言或关闭安全配置来让测试通过。

## 12. 多会话任务规则

每个会话只领取一个边界清晰的任务。开始前输出并确认：

- 任务目标；
- 允许修改的目录；
- 输入和依赖；
- 输出；
- 验收命令；
- 明确不做的内容。

并行任务不得修改同一核心文件。如果确实需要修改共享契约，先由主架构 Agent更新契约，再通知其他会话同步。

子 Agent 完成任务时必须报告：

1. 修改文件列表；
2. 关键实现和设计选择；
3. 已执行的测试及结果；
4. 未完成项；
5. 风险和后续建议；
6. 是否更新文档。

## 13. 当前阶段限制

阶段 0 只允许：

- 建立目录和构建配置；
- 编写、校正架构文档和 ADR；
- 准备 PostgreSQL、Nginx 与 Docker Compose 骨架；
- 保留无业务功能的启动入口和前端占位页；
- 执行项目骨架所需的构建、格式和环境检查。

阶段 0 禁止：

- 建立业务表或实现任何业务 Mapper、Entity、Service、Controller；
- 导入或解析 HowToCook；
- 调用 DeepSeek 或百炼；
- 实现检索、库存、购物、对话、认证或前端业务功能；
- 引入 Spring AI、Spring Cloud、Nacos、RabbitMQ、Redis、Elasticsearch 或 Kubernetes；
- 派发或执行 `docs/阶段1-菜谱数据与混合检索架构.md` 中的 T1～T6。

阶段 1 文档当前仅作为未来架构草案保留。只有用户明确批准进入阶段 1 后，主架构会话才能复核并冻结契约、派发实现任务。

## 14. 常用命令

```powershell
# 环境检查
.\scripts\check-prerequisites.ps1

# PostgreSQL
docker compose up -d postgres

# 后端
Set-Location backend
mvn test
mvn spring-boot:run

# 前端
Set-Location frontend
npm install
npm run type-check
npm run test
npm run build
```

## 15. 文档同步

以下变化必须同步更新 `技术文档.md`，必要时新增 ADR：

- 模块职责或依赖方向改变；
- 技术栈、版本或外部服务改变；
- 数据归属和关键表改变；
- API 契约改变；
- 阶段状态和验收标准改变；
- 出现新的安全或合规约束。

不要在没有更新文档的情况下悄悄改变架构。

