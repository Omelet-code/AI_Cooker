# PF1 技术学习说明：导航与个人档案

## 1. 页面与路由层级

`AppLayout` 是登录后的共享外壳。它只保留五个一级入口；“烹饪助手”指向 `/kitchen`。`/chat` 仍是旧版记录和未知操作核对页面，但通过厨房工作台的明确链接访问，避免两个同级入口造成含义重复。

个人档案路由使用父子结构：

```text
/settings/profile                 profile-settings → DiningProfileView
/settings/profile/favorites       favorites → FavoritesView
/settings/profile/cooked-recipes  cooked-recipes → CookedRecipesView
```

`ProfileLayout` 提供三个子页的导航和共同标题。子页面继续使用原有组件，不复制页面、不改 API 或 Store。模型设置继续是独立一级路由。

## 2. Vue Router 别名和兼容

旧 `/favorites`、`/cooked-recipes` 作为子路由的绝对 alias 指向相同路由记录。这样旧收藏按钮、历史链接和外部书签仍可解析，同时通过 `ProfileLayout` 展示新层级。路由记录沿用原名称，因此现存按名称构造的链接仍有效；`query` 与 `hash` 由 Router 正常保留。

个人档案和其子页仍嵌套在带 `requiresAuth` 的应用路由下。守卫使用 `to.fullPath` 记录登录跳转目标，未登录访问旧 alias 时也会保留原始路径、查询参数和锚点供登录后返回。

## 3. 复用边界

- `DiningProfileView` 仍通过 `useDiningProfile` 读取和保存默认人数。
- `FavoritesView` 仍使用 `useRecipeStore` 的收藏分页与收藏操作。
- `CookedRecipesView` 仍使用 `useCookingStore` 展示做菜记录。
- 模型设置和厨房/聊天的状态管理未迁入个人档案。

这类改版只改变路由组织和共同展示外壳。复用现有页面意味着无需复制认证、分页、幂等或写入行为。

## 4. 可讲清的设计问题

**为什么旧收藏/历史路径用 alias，而不是 redirect？**

Alias 把旧路径映射到同一个路由记录，页面名称、父级布局和守卫保持一致，query/hash 不需要手工拼装，也能继续支持既有具名路由。

**统一入口是否合并了两个助手？**

没有。一级导航默认到餐单助手；旧聊天仍由原页面、API、状态和路由提供。显式链接只改善可发现性，不改变历史数据或触发模型动作。

**为什么档案子页复用现有页面？**

子路由只需建立信息层级。复用现有页面和 Store 可保留已验证的账号隔离、分页与写操作语义，避免出现第二套状态。

## 5. 验证边界

PF1 专项测试使用 Vue Router 内存历史、真实路由表和服务端组件渲染。它验证路由与输出结构，不等于真实浏览器刷新、键盘可访问性或已部署页面验收；这些需在用户许可部署后人工核对。
