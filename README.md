# 电动车保险维修案件协同平台

面向平台客服、管理员、维修网点和电动车主的保险维修案件协同平台 MVP。本仓库采用前后端分离的 Monorepo，并保持后端为模块化单体。

## 仓库结构

```text
.
├── backend/      # Java 21 + Spring Boot 3.5 后端
├── admin-web/    # 管理员与客服桌面端
├── h5-web/       # 维修网点与车主移动 H5
├── infra/        # PostgreSQL 与 MinIO 本地 Docker 配置
└── docs/         # 需求、架构、决策、进度和本地开发文档
```

## 快速入口

- [本地启动说明](docs/LOCAL_DEVELOPMENT.md)
- [需求基线](docs/REQUIREMENTS.md)
- [架构说明](docs/ARCHITECTURE.md)
- [技术与产品决策](docs/DECISIONS.md)
- [项目进度](docs/PROGRESS.md)
- [仓库协作规则](AGENTS.md)

## 当前范围

当前完成阶段 1 的工程骨架和健康检查，不包含用户、案件、报价、核损、维修、投诉或结算等业务功能。开始后续阶段前必须先确认对应需求，并在阶段完成后停止。

