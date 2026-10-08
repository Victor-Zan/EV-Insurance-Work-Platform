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
- [业务流程与状态流](docs/WORKFLOW.md)
- [数据字典](docs/DATA_DICTIONARY.md)
- [架构说明](docs/ARCHITECTURE.md)
- [技术与产品决策](docs/DECISIONS.md)
- [项目进度](docs/PROGRESS.md)
- [待确认问题](docs/OPEN_QUESTIONS.md)
- [仓库协作规则](AGENTS.md)
- [价格导入字段与操作说明](docs/PRICE_IMPORT.md)

## 当前范围

阶段 2 的账号、JWT、四角色 RBAC、组织与审计及阶段 3 价格库能力已保留。阶段 4 在 `zd01` 分支实现案件草稿/正式提交、UUID 与展示编号、强重复和疑似重复判断、派单版本、网点接拒单、取消派单、改派、到店异常、继续等待、确认到店及取消整个工单，并接入管理端与网点/车主 H5。服务端执行角色、本人/本店范围、字段脱敏、状态机、乐观锁、幂等和审计。文件、MinIO、Mock OCR、Mock 地图及其后的报价/维修功能属于提示词 5 以后，当前分支没有提前实现。交付验证与未验证项见 [项目进度](docs/PROGRESS.md)。

