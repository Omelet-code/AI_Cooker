# Cooker

Cooker 是一个面向家庭烹饪场景的单 Agent 项目。用户通过自然语言描述饮食需求，系统从 HowToCook 菜谱中进行混合检索，并结合家庭食材库存生成菜谱推荐、缺货清单和商品购买入口。

当前状态：**阶段 1 已完成 HowToCook 原始数据、结构化事实和 chunk 化检索投影向量化；检索接口与 RRF 融合待后续实现。**

## 目录

```text
Cooker/
├─ AGENTS.md            # 多 Agent 开发规则与分工
├─ 技术文档.md          # 架构、技术选型、数据模型和阶段计划
├─ backend/             # Spring Boot 后端
├─ frontend/            # Vue 3 + TypeScript 前端
├─ infra/               # PostgreSQL、Nginx 等基础设施
├─ docs/adr/            # 架构决策记录
└─ scripts/             # 本地辅助脚本
```

## 环境要求

- Java 21
- Maven 3.8.8+
- Node.js 24.12+
- npm 11+
- Docker 与 Docker Compose

当前机器已安装 JDK 21，但默认 `JAVA_HOME`/`PATH` 仍指向 Java 8；运行后端前需要切换到 `C:\Program Files\Java\jdk-21`。当前没有可用的 Docker 命令，开始容器联调前需要安装 Docker Desktop 或兼容的 Docker 环境。

项目在 `backend/.mvn` 内提供了仅对本项目生效的 HTTPS Maven 镜像配置，并将依赖缓存写入 `backend/maven-repository`，用于避开本机全局配置中遗留的 HTTP 镜像与异常用户目录设置。缓存目录已被 Git 忽略。

## 本地启动

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

启动 PostgreSQL：

```powershell
docker compose up -d postgres
```

启动后端：

```powershell
Set-Location backend
mvn spring-boot:run
```

## 显式构建检索投影

PostgreSQL 必须使用项目的 Docker Compose 镜像：它在 `pgvector` 基础上额外构建了中文分词扩展 `zhparser`。先导入 HowToCook 结构化数据，再在环境变量中提供百炼 Key，最后执行以下一次性索引任务；普通后端启动不会调用百炼。

```powershell
Set-Location backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DB_URL = 'jdbc:postgresql://localhost:5432/cooker'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = 'root'
$env:DASHSCOPE_API_KEY = '<仅在当前终端设置的百炼 Key>'
$env:COOKER_RETRIEVAL_INDEX_ENABLED = 'true'
mvn spring-boot:run "-Dspring-boot.run.profiles=local,recipe-index" "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
```

索引命令按 `content_hash`、Embedding 模型、维度和模板版本跳过未变化的块；失败块保留可重试状态。不要把真实 Key 写入 `.env.example`、配置文件或 Git。

启动前端：

```powershell
Set-Location frontend
npm install
npm run dev
```

## 开发入口

开始任何任务前必须先阅读：

1. `AGENTS.md`
2. `技术文档.md`
3. 与任务相关的 ADR
