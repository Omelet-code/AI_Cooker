# ADR-005：JWT“记住我”与烹饪记录边界

- 状态：已接受（2026-09-30 已授权 T7/T9/T10；T8 仍待后续授权）
- 日期：2026-09-29

## 背景

用户确认采用“用户名 + 密码 + JWT”登录，并需要“记住我”，关闭浏览器后仍可登录。用户将“历史菜谱”定义为真正做过的菜谱、次数、熟悉度和主动给出的喜爱评分；聊天历史是另一项功能。由于自由输入库存与标准食材、单位难以可靠匹配，MVP 暂缓持久库存与自动扣减。

## 候选方案

### 登录续期

1. 长有效期 JWT：最少组件，但令牌泄露窗口长，主动失效困难。
2. 短有效期 JWT + 服务端可撤销的随机续期令牌：多一个存储与轮换流程，兼顾“记住我”和短访问凭证。
3. 服务端 Session：实现直观，但不符合本阶段用户明确选择的 JWT。

### 已做菜谱

1. 仅保存每道菜的累计次数：无法核验时间线与请求重试，难以解释统计来源。
2. 每次完成烹饪记录一个事件，按事件汇总次数：可审计且可做幂等。

## 决策

- 采用方案 2：访问令牌为短有效期签名 JWT；续期令牌为高熵随机值，数据库只存其哈希。两者通过同站点 `HttpOnly` Cookie 传递，不放在 `localStorage` 或 `sessionStorage`。生产环境 Cookie 使用 `Secure`，修改数据的请求配置 CSRF 防护；Cookie 的 `SameSite` 不是 CSRF 防护的替代品。
- 建议默认值：访问 JWT 15 分钟；勾选“记住我”时，续期凭证和持久 Cookie 最长 30 天；未勾选时使用非持久 Cookie，服务端续期凭证最长 24 小时。具体有效期由配置集中管理，可在实现任务评审时调整。
- 每次续期原子轮换续期令牌；退出登录撤销当前续期凭证并清除 Cookie。已签发访问 JWT 在其剩余有效期内可能仍可用；如未来要求所有设备或所有令牌立即失效，需增加令牌版本或撤销校验，不得声称清 Cookie 等于全局撤销。
- 认证授权使用 Spring Security；保留项目自己的 `identity` Service、DTO、VO 和 Mapper 分层。JWT 只包含必要的身份、签发方、受众和时间信息，不放密码、DeepSeek Key 或其他敏感业务数据。
- “完成烹饪”只追加一条 `recipe_cook_record`，不扣库存。每次真实烹饪是一个新事件；同一事件的网络重试使用幂等键，不能重复计数。
- 收藏是独立的 `favorite_recipe` 关系；做菜次数由完成事件汇总。熟悉度是基于次数的展示等级，不代表实际烹饪技能。喜爱程度由用户主动给出 1～5 分，未评分时不推断为 0 分或“讨厌”。

## 理由

- “记住我”不应通过延长访问 JWT 有效期来实现；可撤销续期凭证把长期风险限制在一个可管理的入口。
- 与传统 Spring Boot + MySQL 的 Session 对比，JWT 验证访问请求时不需要查登录会话；但“记住我”、续期、主动退出仍需要服务端状态，这并非纯无状态设计。
- 烹饪事件与收藏、聊天消息是不同事实，不应混进 `identity` 用户表或 `conversation` 消息表。
- 不可靠的自由文本食材匹配或单位换算不能作为自动扣减依据。已有食材在 MVP 只作为本次对话/购物计算的用户声明，未确认的匹配不得自动抵扣。

## 影响

- 阶段 2 需要新增 `app_user`、续期凭证、`user_llm_credential`、`favorite_recipe`、`recipe_cook_record` 的新 Flyway 迁移；不得改写已执行的 V1～V3。
- `recipe_source.owner_user_id` 继续预留，用户自定义菜谱仍不在 MVP；如添加用户外键，应通过新迁移并先验证既有数据。
- 历史对话仍由后续 `conversation` 负责；`pantry` 及库存相关 API 不属于阶段 2 或 MVP 验收。
- 购物清单只能根据本次用户确认的已有食材计算缺口；不能声称已经读取或扣减家庭库存。
- 需要覆盖跨用户访问、Cookie/CSRF、续期轮换和撤销、收藏去重、烹饪事件幂等、评分边界的测试。

## 复审条件

- 需要跨域前端或移动客户端，而非同源 Web；
- 用户要求立即撤销所有已签发访问 JWT；
- 引入持久库存、自动扣减或多设备登录管理；
- 熟悉度需超出次数等级，变成真实技能评估。

## 参考资料

- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)：浏览器凭证存储与 Cookie 属性。
- [OWASP Cross-Site Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)：Cookie 认证的 CSRF 边界。
- [OWASP OAuth 2.0 Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/OAuth2_Cheat_Sheet.html)：续期凭证保护与轮换。
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/)：JWT 验证支持；默认读取 `Authorization`，本项目的 Cookie 读取需专门适配。
