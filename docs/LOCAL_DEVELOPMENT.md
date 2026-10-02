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

`.env` 已被 Git 忽略。示例值只用于本地开发；如电脑可被他人访问，请先修改密码。

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
npm run build
```

## 7. 说明

- 两个前端从仓库根目录读取 `.env`，只有 `VITE_` 前缀变量会进入浏览器构建。
- 后端的数据库密码没有源码默认值；未设置 `DB_PASSWORD` 时不会带着隐式密码启动。
- 当前安全配置只公开健康检查和 OpenAPI 路径，其他未知路径默认拒绝。身份认证和 JWT 业务将在下一阶段实现。
- Compose 中的固定 Bitnami Legacy MinIO 镜像只用于本地开发；生产镜像与对象存储部署方案需在部署加固阶段重新评估，见 `docs/DECISIONS.md` D-017。
- `infra/docker-compose.yml` 仅用于本地开发，不是生产高可用部署方案。
