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

阶段 2 的账号、JWT、四角色 RBAC、组织与审计已合并。阶段 3 正在开发：已实现价格基础资料（品牌、车型、配件、别名、适配、来源）及管理端查询/维护，管理员读写、客服只读。价格记录、历史版本与 Excel/CSV 导入尚未完成，等待数据正确性边界确认，见 [项目进度](docs/PROGRESS.md)。H5 无价格入口，未实现工单、派单、报价、核损、维修、OCR、地图或结算流程。

