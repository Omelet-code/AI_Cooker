# ADR-002：使用 MyBatis-Plus，并保留显式 Service 实现层

- 状态：已接受
- 日期：2026-09-25

## 背景

项目希望保持熟悉的 `Controller/Service/DAO` 分层，并需要直接编写 PostgreSQL 混合检索 SQL。

## 决策

- 使用 MyBatis-Plus 3.5.17。
- 每个业务模块内部使用 `controller/service/service/impl/mapper/entity/dto/vo`。
- `Mapper` 承担传统 DAO 职责。
- Service 使用项目自定义接口及 `impl` 实现，不直接把 MyBatis-Plus `IService` 暴露给 Controller。
- 简单单表 CRUD 使用 `BaseMapper`；复杂查询和混合检索使用 Mapper XML 或显式 SQL。

## 理由

- 保留清晰的业务层边界。
- 便于学习 PostgreSQL 特性和审查复杂 SQL。
- 避免将持久化框架 API 扩散到 Controller 和跨模块接口。

## 影响

- 不引入 JPA Repository。
- Controller 不得直接注入 Mapper。
- 跨模块不得访问其他模块的 Mapper、Entity 或 XML。

