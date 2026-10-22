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

## 当前状态（2026-10-22）

`main` 已包含阶段 1—8 的案件、派单、报价、维修、资金记录与站内通知能力，以及后续合入的受邀账号开通、统一登录和高德辅助派单。桌面端的新建案件页支持导入微信导出的文件夹或 ZIP，也可分别上传 TXT 与图片；导入结果仍须人工核对后保存。ZIP 在浏览器内读取，没有新增后端导入接口，也不代表第 13 阶段的智能分类已实现。

高德真实调用默认关闭；启用需要单独配置 Key、测试资料及明确授权的调用预算。此前三家**合成测试网点**的真实路线与派单验收不能当作真实合作网点已上线。当前代码的正式迁移已到 V26；拉取源码不会自动更新正在运行的后端或数据库。运行前按 [本地开发说明](docs/LOCAL_DEVELOPMENT.md) 核对环境、迁移与备份。验证结果和限制见 [项目进度](docs/PROGRESS.md)。

## 账号与车主关联

新账号由工作人员准备邀请，受邀人通过链接设置密码并激活，再使用**账号和密码**登录。客服可在有权限的正式案件中，根据已核实的车主联系方式为该案件邀请车主开通账号并绑定案件；邀请链接由工作人员通过已核实渠道手动发送。已有普通车主账号可由管理员维护联系手机号，唯一匹配时沿用原有的案件自动关联规则；受邀开通的账号不参与这种仅凭手机号的自动匹配，后续案件需要人工核实并明确绑定。当前没有公众自助手机号注册、短信验证码登录或系统自动发送短信。详见 [受邀开通与统一登录](docs/ACCOUNT_ACTIVATION.md)。

## 最近并入 `main` 的功能

- 高德辅助派单：事故区县独立人工复核、停车位置确认、驾车路线排序、原有人工选择与派单门禁并存；真实调用受持久预算约束，V25/V26 只增量扩展数据库结构，不自动发放调用额度。见 [高德接入说明](docs/AMAP_INTEGRATION.md) 与 [隔离验收记录](docs/AMAP_LIVE_ACCEPTANCE_20261022.md)。
- 桌面端建单资料导入：文件夹或 ZIP 的 TXT/JPG/PNG 读取、文字与图片分开追加、累计照片上限和失败后保留原表单内容；不对图片做 OCR，也不自动完成案件建单。
- 受邀开通与统一登录：服务端按角色引导进入桌面端或 H5，保留既有账号密码及权限范围。见 [账号开通说明](docs/ACCOUNT_ACTIVATION.md)。
- 移动端视觉：登录、首页、导航和案件详情采用与桌面端呼应的暖白与石墨色层次；登录页继续使用移动端原有的充电踏板车图，不替换为桌面端的拆解图。

## 快速入口

- [本地启动说明](docs/LOCAL_DEVELOPMENT.md)
- [现有Windows网站启动、停止与恢复](docs/本地网站使用说明.md)
- [特定 Windows 工作区的一键启动与自动恢复](docs/LOCAL_RUNTIME.md)
- [受邀开通与统一登录](docs/ACCOUNT_ACTIVATION.md)
- [高德辅助派单与调用边界](docs/AMAP_INTEGRATION.md)
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
- [功能全流程修复与验证](docs/WORKFLOW_FIXES.md)
- [阶段8验证辅助脚本](scripts/validation/README.md)

## 功能范围

管理端面向管理员与客服，移动 H5 面向维修网点与车主。服务端执行四角色 RBAC、本人/本店数据范围、字段脱敏、状态机、乐观锁、幂等和审计。案件支持草稿、正式提交、重复核对、派单与改派；附件存于私有 MinIO，OCR 和地图均有 Mock 路径。报价、核损与四项开修授权、维修交付、人工资金记录和站内通知按各阶段已确认规则运行。平台只记录保险公司、平台与网点之间的资金流程，不实际划转资金；车主不创建案件、不参与报价确认或维修付款。详细规则见 [需求基线](docs/REQUIREMENTS.md) 与 [架构说明](docs/ARCHITECTURE.md)。
