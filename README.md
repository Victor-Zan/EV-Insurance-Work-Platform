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
- [价格导入字段与操作说明](docs/PRICE_IMPORT.md)

## 当前范围

阶段 2 的账号、JWT、四角色 RBAC、组织与审计已合并。阶段 3 已实现价格基础资料、四类独立参考价、全国/区域/网点候选查询、自然日历史版本、Excel/CSV 预览与原子导入，以及管理端对应页面。在价格数据库模块中，管理员可读写，客服仅可查询，H5 无价格入口。无期限旧版本随新版本同事务补齐截止日，历史金额与来源保留；正式价格适用优先级尚待业务确认，不自动取价。交付验证与未验证项见 [项目进度](docs/PROGRESS.md)。未实现工单、派单、报价、核损、维修、OCR、地图或结算流程。

