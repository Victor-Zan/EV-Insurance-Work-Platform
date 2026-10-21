# 第12阶段文档、对账与历史档案接口

## 正式报价 PDF

- `GET /api/v1/quotations/cases/{caseId}/formal-quotes/{quoteId}/pdf`：管理员和客服下载不可变报价版本对应的正式 PDF，记录 `QUOTE_EXTERNAL_EXPORT` 审计。网点、车主拒绝。
- PDF 只包含必要案件标识、对外明细、数量、对外单价、对外行金额和总额；不包含原始价格、加价、利润、结算及联系方式。新版本的抬头在签发时快照；旧版本的抬头由 V13 迁移时补录并在 PDF 上标注。保司仍线下接收，不增加登录角色。

## 工单资金对账 XLSX

- `GET /api/v1/funds/reconciliation/export?page=1&size=100&status=&query=&insuranceCompany=&shopId=&from=&to=`：管理员/客服按条件分页下载。每次最多 100 条；稳定按工单更新时间及 ID 倒序。日期为 `yyyy-MM-dd` 且按中国自然日筛选建单时间。
- 内容为工单号、报案号、保司、网点、状态、建单时间及应收/已收/差额、应付/已付/差额。目标为空时差额为空；金额以精确十进制文本输出，避免 Excel 浮点误差。无车主联系方式、内部报价加价及对象键。下载记录 `FUNDS_EXPORT` 审计。
- 资金流水批量导入仍使用第8阶段 `/api/v1/funds/imports` 协议；本接口不导入资金，也不改变工单或资金状态。

## 独立历史档案

2026-10-17 管理端入口统一命名为“历史档案”，默认列表使用 `GET /api/v1/work-orders?status=COMPLETED&page=1&size=20&query=` 展示当前已完成案件，并进入原案件详情。“导入档案”仍使用下列独立历史接口，不复制或迁移系统案件。工作台使用 `GET /api/v1/work-orders?excludeCompleted=true&page=1&size=20&query=`；可选布尔参数 `excludeCompleted` 默认 `false`，为 `true` 时在分页和计数前排除 `COMPLETED`，同时仍执行原角色数据范围、状态及搜索条件。

2026-10-19 补充：管理端普通案件队列及顶部搜索统一发送 `excludeCompleted=true`，忽略 URL 对该条件的关闭要求；状态选项不再包含 `COMPLETED`，仅历史档案使用已完成状态查询。管理端 API 包装默认排除完成，明确查询 `COMPLETED` 时保持历史查询。后端参数默认值、H5 与原鉴权不变。

使用 `ebike-history-order-import-template.xlsx` 的“历史订单”“字段说明”双工作表，导入第一张表的 17 列；第二张表只作填写说明。历史记录只供管理员/客服在管理端查阅，不进入现有 `work_order`、报价、派单、维修授权或资金台账。旧记录无须补造保险公司和事故时间；新建正式案件的原有必填规则不变。品牌、网点和原状态按来源文字保留，不自动创建或绑定系统字典/状态。历史定损金额仅作记录。

- `POST /api/v1/historical-cases/imports`，multipart `file`：上传并生成预览批次，不立即归档。仅 `.xlsx`，最多 10 MiB、2000 条非空数据行；列名、顺序和工作表名与模板一致。公式/错误单元格、缺失必填字段、无效手机号/坐标/日期/金额在行级标为 `INVALID`，不影响其他行。源文件经 `ObjectStorageService` 私有保存。
- `GET /api/v1/historical-cases/imports`、`GET /api/v1/historical-cases/imports/{id}`、`GET /api/v1/historical-cases/imports/{id}/rows?page=1&size=20`：分页查看批次及逐行状态、原字段、错误与同报案号不同内容提醒。
- `POST /api/v1/historical-cases/imports/{id}/confirm`：逐行归档 `READY` 行。17 列整行内容完全相同的记录标为 `DUPLICATE` 并跳过，包括同批和跨批；报案号相同但整行不同的记录保留并提示。报案号可以为空。数据库唯一行指纹防止并发重复；已归档记录不可更新或删除。重复确认不重复写入。
- `GET /api/v1/historical-cases/imports/{id}/source`：受控下载原 XLSX 并记录审计。
- `GET /api/v1/historical-cases?page=1&size=20&query=`、`GET /api/v1/historical-cases/{id}`：内部分页检索与只读详情。查询匹配报案号、车主姓名、电话或车牌。网点和车主均拒绝访问历史档案、批次和原文件。

单页上限 100。历史档案导入、确认、原件下载和重要金额/材料操作均保留操作者审计；接口不提供历史档案编辑或删除。
