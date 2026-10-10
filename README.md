# 电动车保险维修案件协同平台

面向平台客服、管理员、维修网点和电动车主的保险维修案件协同平台 MVP。本仓库采用前后端分离的 Monorepo，并保持后端为模块化单体。

## 仓库结构

```text
.
├── backend/      # Java 21 + Spring Boot 4.1.1 后端
├── admin-web/    # 管理员与客服桌面端
├── h5-web/       # 维修网点与车主移动 H5
├── infra/        # PostgreSQL 与 MinIO 本地 Docker 配置
└── docs/         # 需求、架构、决策、进度和本地开发文档
```

## 当前阶段

第8阶段已完成：人工应收/应付、分笔收付与冲销、私有CSV/XLSX导入及双编号匹配、受控导出、个人站内通知/待办及可选deadline。自动超时和真实短信保持关闭。阶段1—7完整保留，停止等待确认。参见 [阶段8API](docs/PHASE8_API.md)、[验证报告](docs/PHASE8_VERIFICATION.md) 和 [启动说明](docs/LOCAL_DEVELOPMENT.md)。

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
- [设计学习与适配记录](docs/DESIGN_RESEARCH.md)
- [阶段5—6可公开验证证据](docs/validation/README.md)
- [第7阶段验证](docs/PHASE7_VERIFICATION.md)
- [第8阶段验证](docs/PHASE8_VERIFICATION.md)
- [可用性修复与新版视觉复查](docs/USABILITY_VISUAL_RECHECK.md)
- [阶段8验证辅助脚本](scripts/validation/README.md)

## 当前范围

2026-10-10已完成独立Boot4.1.1迁移、第5阶段文件/MinIO/Mock OCR/Mock地图，以及第6阶段报价/加价/核损/四项开修授权。规则见[D-032](docs/DECISIONS.md)，接口见[PHASE6_API](docs/PHASE6_API.md)，证据和限制见[PHASE6_VERIFICATION](docs/PHASE6_VERIFICATION.md)。阶段7完成与验证见[PHASE7_VERIFICATION](docs/PHASE7_VERIFICATION.md)，阶段8完成与验证见[PHASE8_VERIFICATION](docs/PHASE8_VERIFICATION.md)。

阶段 2 的账号、JWT、四角色 RBAC、组织与审计及阶段 3 价格库能力已保留。阶段 4 在 `zd01` 分支实现案件草稿/正式提交、UUID 与展示编号、强重复和疑似重复判断、派单版本、网点接拒单、取消派单、改派、到店异常、继续等待、确认到店及取消整个工单，并接入管理端与网点/车主 H5。服务端执行角色、本人/本店范围、字段脱敏、状态机、乐观锁、幂等和审计。阶段5在本轮续建中接入真实附件API、私有MinIO、候选复核和Mock地图；阶段6新增不可变原始/正式报价、精确分摊、核损与授权历史；阶段7维修交付与阶段8资金/通知现已实现。交付验证与未验证项见 [项目进度](docs/PROGRESS.md)。
