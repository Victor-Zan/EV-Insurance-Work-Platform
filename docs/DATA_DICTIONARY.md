# 数据字典

## 1. 约定

- 数据库使用 PostgreSQL；结构变更只通过 Flyway。
- 时间点使用 `TIMESTAMPTZ` / Java `Instant`；中国业务自然日使用 `DATE` / `LocalDate`。
- 价格库金额已确认使用 CNY `NUMERIC(18,2)` / `BigDecimal`，额外小数位拒绝，不舍入。其他业务金额与百分比在对应阶段确认前不得自行套用规则。
- 敏感等级：S0 公开/系统元数据；S1 内部业务数据；S2 个人或商业敏感；S3 密钥、密码、Token 等秘密。S3 不得存入业务响应或日志。
- “可见角色”是服务端最大可见范围；还必须叠加本人、本店、草稿创建人、当前派单等数据范围。

## 2. 已实现核心实体（提示词 1—4）

### 2.1 用户、组织与审计

| 实体/字段 | 数据库类型 | 必填 | 敏感 | 可见角色 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `app_user.id` | `BIGINT` | 是 | S0 | ADMIN；本人会话 | 用户主键 |
| `app_user.username` | `VARCHAR` | 是 | S1 | ADMIN；本人 | 登录账号，全局唯一 |
| `app_user.password_hash` | `VARCHAR` | 是 | S3 | 无业务角色 | 仅认证服务使用，不返回、不记录日志 |
| `app_user.enabled/auth_version` | `BOOLEAN/BIGINT` | 是 | S1 | ADMIN；认证服务 | 停用或版本变化使旧 JWT 失效 |
| `app_role.code` | `VARCHAR` | 是 | S0 | 已认证用户 | 仅 ADMIN、CUSTOMER_SERVICE、REPAIR_SHOP、OWNER |
| `owner_profile.contact_phone` | `VARCHAR` | 否 | S2 | ADMIN；授权客服；本人 | 正式建单时用于精确匹配车主 |
| `region` | 多字段 | 是 | S0 | ADMIN、客服只读 | 省/市/区树；工单阶段使用已启用区域 |
| `repair_shop` | 多字段 | 是 | S1/S2 | ADMIN；客服必要投影；本店 | 网点及联系方式 |
| `shop_service_region` | 两个 `BIGINT` | 是 | S1 | ADMIN；客服必要投影 | 网点与服务区域多对多 |
| `audit_log` | 多字段 | 是 | S1 | ADMIN | 只追加；含操作者、角色、动作、对象、摘要、traceId、时间；摘要必须脱敏 |

### 2.2 价格数据库

| 实体 | 关键字段 | 必填/规则 | 敏感 | 可见角色 |
| --- | --- | --- | --- | --- |
| `price_brand` | code、name、enabled | code 唯一 | S0 | ADMIN、CUSTOMER_SERVICE |
| `price_model` | brand_id、code、name | brand + code 唯一 | S0 | ADMIN、CUSTOMER_SERVICE |
| `price_part` | internal_code、name | internal_code 全局唯一 | S1 | ADMIN、CUSTOMER_SERVICE |
| `price_alias` | part_id、name | 同配件别名唯一 | S1 | ADMIN、CUSTOMER_SERVICE |
| `price_part_model` | part_id、model_id | 多对多复合主键 | S1 | ADMIN、CUSTOMER_SERVICE |
| `price_source` | code、name、type | 来源快照基础 | S1 | ADMIN 维护，客服选用 |
| `price_record` | 配件、车型、类型、范围、区域/网点 | 逻辑价格维度，不保存可变金额 | S2 | ADMIN、CUSTOMER_SERVICE |
| `price_version` | amount、effective_from/to、version_no、source snapshot | CNY NUMERIC(18,2)，金额 > 0；版本不可覆盖，日期不重叠 | S2 | ADMIN、CUSTOMER_SERVICE |
| `price_import_preview/batch/error` | 文件摘要、暂存行、批次状态、错误 | 预览限创建者；确认整批原子 | S1/S2 | ADMIN、CUSTOMER_SERVICE，按创建者限制 |

价格类型固定为网点原始参考、平台对外参考、保险历史核损参考、网点结算参考。它们是参考数据库，不直接生成案件报价。

### 2.3 工单 `work_order`

| 字段 | 数据库类型 | 草稿/正式必填 | 敏感 | 可见角色 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `UUID` | 始终 | S0 | 按数据范围 | 内部主键 |
| `business_no` | `VARCHAR(32)` | 草稿空；正式是 | S1 | 按数据范围 | `EVR-YYYYMMDD-######`，全局唯一且不复用 |
| `insurance_company` | `VARCHAR(120)` | 草稿否；正式是 | S1 | ADMIN、客服、当前网点、本人 | 强重复键之一 |
| `claim_no` | `VARCHAR(100)` | 草稿否；正式是 | S2 | ADMIN、客服、当前网点、本人 | 强重复键之一 |
| `claim_reported_at` | `TIMESTAMPTZ` | 否 | S1 | ADMIN、客服、当前网点、本人 | 报案时间不是正式门槛 |
| `data_source` | `VARCHAR(32)` | 系统必填 | S0 | 按数据范围 | 当前为 MANUAL，不接受客户端填写 |
| `current_responsible_id` | `BIGINT` | 系统必填 | S1 | ADMIN、客服 | 当前负责人 |
| `owner_user_id` | `BIGINT` | 否 | S1 | 服务端；ADMIN、客服 | 唯一手机号匹配成功时绑定 |
| `owner_binding_status` | `VARCHAR(16)` | 系统必填 | S1 | ADMIN、客服 | PENDING 或 BOUND |
| `owner_name` | `VARCHAR(100)` | 草稿否；正式是 | S2 | ADMIN、客服；网点列表脱敏/详情授权；本人 | 姓名 |
| `owner_phone` | `VARCHAR(32)` | 草稿否；正式是 | S2 | 同上 | 网点列表格式如 138****1234 |
| `policy_no` | `VARCHAR(100)` | 否 | S2 | ADMIN、客服；网点脱敏；本人 | 疑似重复条件之一 |
| `vehicle_brand/model` | 各 `VARCHAR(100)` | 草稿否；正式是 | S1 | 按数据范围 | 车辆品牌与车型 |
| `vehicle_vin` | `VARCHAR(100)` | 三者至少一项 | S2 | ADMIN、客服；网点列表脱敏/详情授权；本人 | VIN |
| `vehicle_plate` | `VARCHAR(100)` | 三者至少一项 | S2 | 同上 | 车辆牌照 |
| `vehicle_other_identifier` | `VARCHAR(100)` | 三者至少一项 | S2 | 同上 | 保险认可的其他编号 |
| `accident_at` | `TIMESTAMPTZ` | 草稿否；正式是 | S1 | 按数据范围 | 用于疑似重复窗口 |
| `accident_region_id` | `BIGINT` | 草稿否；正式是 | S1 | 按数据范围 | 必须关联已启用区域 |
| `accident_address` | `VARCHAR(255)` | 否 | S2 | ADMIN、客服；网点列表不返回/详情授权；本人 | 详细地址不是正式门槛 |
| `accident_description` | `VARCHAR(2000)` | 草稿否；正式是 | S2 | ADMIN、客服；网点列表不返回/详情授权；本人 | 事故简述 |
| `shop_id` | `BIGINT` | 派单后 | S1 | ADMIN、客服、当前网点、本人 | 当前网点投影 |
| `status` | `VARCHAR(40)` | 系统必填 | S1 | 按数据范围 | 见 `WORKFLOW.md` |
| `version` | `BIGINT` | 系统必填 | S0 | 服务端；管理端必要响应 | 乐观锁版本 |
| `created_by/updated_by` | `BIGINT` | 系统必填 | S1 | ADMIN、客服 | 草稿创建人决定可见性 |
| `created_at/updated_at/status_started_at` | `TIMESTAMPTZ` | 系统必填 | S0 | 按数据范围 | 时间线与稳定排序 |

### 2.4 派单、历史、幂等与配置

| 实体/字段 | 类型 | 必填 | 敏感 | 可见角色 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `work_order_config.possible_duplicate_days` | `INTEGER` | 是 | S1 | ADMIN；服务端 | 默认 7，范围 1—365，修改要原因和审计 |
| `work_order_number_counter` | date + integer | 是 | S0 | 服务端 | 原子生成当日流水，禁止 max + 1 |
| `work_order_assignment.assignment_version` | `INTEGER` | 是 | S0 | ADMIN、客服、当前网点 | 每工单递增且不覆盖 |
| `work_order_assignment.shop_id/status` | `BIGINT/VARCHAR` | 是 | S1 | ADMIN、客服、当前网点 | PENDING、ACCEPTED、REJECTED、CANCELLED |
| `work_order_assignment.reason` | `VARCHAR(500)` | 视动作 | S1/S2 | ADMIN、客服；受控网点 | 拒单、取消、改派原因 |
| `work_order_assignment.transfer_description` | `VARCHAR(1000)` | 到店后管理员改派必填 | S2 | ADMIN、客服、相关网点 | 车辆转运说明 |
| `work_order_status_history` | 多字段 | 是 | S1/S2 | 按数据范围；车主隐藏原因/操作者 | 只追加状态时间线，含 traceId |
| `work_order_command.idempotency_key` | `VARCHAR(128)` | 是 | S1 | 服务端 | 按操作者 + 操作唯一；不得记录 Token |
| `work_order_command.response_json` | `TEXT` | 成功后 | S2 | 服务端 | 保存第一次响应供重复请求原样返回 |

## 3. 后续阶段主要实体（尚未实现）

| 实体 | 核心职责 | 关键约束 |
| --- | --- | --- |
| `work_order_file` | 案件材料元数据与 Object Key | 二进制不入 PostgreSQL；访问必须鉴权；提示词 5 实现 |
| `ocr_job/ocr_result/ocr_confirmation` | Mock OCR、候选字段、人工确认 | 原始结果与人工修改均可追溯，不直接污染正式字段 |
| `shop_quote/quote_item` | 网点原始报价和明细版本 | 单价/工时不可被客服改写；历史版本不覆盖 |
| `platform_quote` | 平台正式报价和总额加价 | FIXED_AMOUNT/PERCENTAGE 二选一，只作用总额 |
| `insurer_assessment` | 最终核损金额与确认 | 人工录入，需材料和双重确认 |
| `repair_authorization` | 维修授权事件 | 四项条件全部满足；开始维修时再次校验 |
| `repair_progress/completion_file` | 维修进度与完工照片 | 完工照片门禁规则待确认 |
| `owner_acceptance/review` | 收车确认与评价 | 仅绑定车主 |
| `complaint` | 独立投诉工单 | 不是维修工单备注；管理员处理 |
| `receivable/settlement` | 保险回款与网点结算记录 | 不执行真实资金；最终网点结算金额可空且人工录入 |
| `notification` | 站内通知和已读状态 | 第一版不发送真实短信 |
| `async_task` | PostgreSQL Worker 任务 | 幂等、有限重试、并发领取；不引入 RabbitMQ |

上述后续实体只固定已确认边界，字段与迁移必须等对应提示词开始并解决 `OPEN_QUESTIONS.md` 中的前置问题后再设计。
