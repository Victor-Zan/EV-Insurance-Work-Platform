# 本地开发与启动

## 1. 环境要求

- Git 2.40+
- Java 21（构建目标固定为 Java 21）
- Node.js 24 与 npm 11
- Docker Desktop，支持 Docker Compose

后端包含 Maven Wrapper，不要求全局安装 Maven。

## 2. 准备环境变量

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

`.env` 已被 Git 忽略。填写本地数据库和 MinIO 凭据，并注入 JWT_SECRET_BASE64（至少 32 字节随机数据的 Base64）及 JWT_TTL_SECONDS=1800。JWT 配置没有源码回退密钥；不得提交真实 .env。

## 3. 启动 PostgreSQL 和 MinIO

在仓库根目录执行：

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml ps
```

服务地址：

- PostgreSQL：`localhost:15432`（容器内仍为 `5432`，避免与本机 PostgreSQL 冲突）
- MinIO API：`http://localhost:9000`
- MinIO Console：`http://localhost:9001`

验证 MinIO HTTP 健康端点：

```powershell
Invoke-WebRequest http://localhost:9000/minio/health/live -UseBasicParsing
```

停止本地依赖但保留数据卷：

```powershell
docker compose --env-file .env -f infra/docker-compose.yml down
```

删除数据卷会清空本地数据，因此不作为常规停止命令。

## 4. 启动后端

后端从进程环境变量读取数据库连接。以下 PowerShell 片段把根目录 `.env` 加载到当前进程，然后启动应用：

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
Set-Location backend
.\mvnw.cmd spring-boot:run
```

健康与接口文档：

- 应用健康接口：`http://localhost:8080/api/v1/health`
- Actuator 健康接口：`http://localhost:8080/actuator/health`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

后端验证命令：

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd clean package
```

## 5. 启动管理端

```powershell
Set-Location admin-web
npm ci
npm run dev
```

默认地址：`http://localhost:5173`。

质量检查：

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

## 6. 启动移动 H5

```powershell
Set-Location h5-web
npm ci
npm run dev
```

默认地址：`http://localhost:5174`。

质量检查：

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

## 7. 说明

- 两个前端从仓库根目录读取 `.env`，只有 `VITE_` 前缀变量会进入浏览器构建。
- 后端的数据库密码没有源码默认值；未设置 `DB_PASSWORD` 时不会带着隐式密码启动。
- 当前公开健康与 OpenAPI、账号密码登录。身份入口按端限制，管理 API 仅 ADMIN；未知 API 默认拒绝。JWT 无刷新功能，过期重新登录，每次请求读取账号状态。
- Compose 中的固定 Bitnami Legacy MinIO 镜像只用于本地开发；生产镜像与对象存储部署方案需在部署加固阶段重新评估，见 `docs/DECISIONS.md` D-017。
- `infra/docker-compose.yml` 仅用于本地开发，不是生产高可用部署方案。

## 8. 阶段 2 本地账号与基础数据

只在独立本地数据库设置 SPRING_PROFILES_ACTIVE=dev，并通过本地环境变量或已忽略的 .env 注入 DEV_ADMIN_PASSWORD、DEV_CUSTOMER_SERVICE_PASSWORD、DEV_REPAIR_SHOP_PASSWORD、DEV_OWNER_PASSWORD。值不在此文档提供。非空密码须满足 BCrypt 的 72 字节 UTF-8 输入上限；本阶段不增加复杂度策略。

dev 配置在 Flyway 执行前将注入值转换为 BCrypt 哈希，V2.1 开发迁移只接收哈希。账号为 dev_admin、dev_customer_service（管理端）及 dev_repair_shop、dev_owner（H5），显示名明确标识开发测试；同时创建无真实个人信息的模拟省、市、区和网点服务区域。dev 数据库不可作为生产数据库复用。初始化完成后，修改环境密码不会覆盖现有账号，应调用管理员重置 API；其他环境只执行 V1、V2，不创建测试账号。

第一位生产管理员的安全初始化属于上线加固阶段，本阶段不提供公开注册或无认证初始化 API。

管理端默认 http://localhost:5173，H5 默认 http://localhost:5174；两者通过 Vite /api 代理后端。部署时配置同源反向代理或明确配置 VITE_API_BASE_URL 与 CORS_ALLOWED_ORIGINS，不能用通配 CORS 替代授权。

API 契约详见 Swagger：POST /api/v1/auth/login 输入 username、password、portal（ADMIN 或 H5）；GET /api/v1/auth/me 读取实时身份。Token 过期、账号停用或认证版本变化返回 401。管理员用户/角色、区域、网点及服务区域、审计 API 均在 /api/v1/admin 下。密码重置只返回成功状态；退出登录清理浏览器会话状态，已签发 Token 在到期或认证版本变化前仍遵守服务端校验。

## 9. PostgreSQL 集成测试

在专用测试 PostgreSQL 创建空测试数据库，通过环境变量设置 TEST_DB_URL、TEST_DB_USERNAME、TEST_DB_PASSWORD（账号应能创建该测试库的 schema）。不得指向生产或共享业务库。测试随机生成 schema 和凭据，迁移、权限与审计均使用真实 PostgreSQL，不替换为 H2，不跳过集成测试。测试 schema 保留用于复核，可由测试库维护者清理。

在 backend 运行：

~~~powershell
.\mvnw.cmd -B clean verify -Ppostgres-it
~~~

该命令包括所有单元测试、真实数据库集成测试、空 schema Flyway migrate/validate 和 JAR 构建；缺少 TEST_DB_URL 时明确失败。日常无数据库单元测试可用 .\mvnw.cmd test，但它不代表本阶段完整门禁。
