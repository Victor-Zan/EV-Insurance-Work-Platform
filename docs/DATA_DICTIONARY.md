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

## 3. 早期概念实体与实际迁移索引

2026-10-09：文件/OCR已在V6落地，准确表名与字段见下文“第5阶段（V6）”。下表为早期概念边界，不作为实际表名。2026-10-10更新：报价已实现V7，维修/收车/评价/投诉已实现V8—V10，准确表名和字段见下方对应阶段；资金/通知仍未实现。现有V1—V5及阶段4工单字段保持原样，不将价格库NUMERIC(18,2)自动套用到后续报价或OCR字段。实施顺序见PHASE5_PLAN.md。

| 实体 | 核心职责 | 关键约束 |
| --- | --- | --- |
| `case_file` | 已实现，见下文V6 | 二进制不入PostgreSQL；按角色/案件鉴权 |
| `ocr_job/ocr_review/ocr_confirmation` | Mock OCR、候选字段、人工确认 | 原始结果与人工修改均可追溯，不直接污染正式字段 |
| `shop_quote/quote_item` | 网点原始报价和明细版本 | 单价/工时不可被客服改写；历史版本不覆盖 |
| `platform_quote` | 平台正式报价和总额加价 | FIXED_AMOUNT/PERCENTAGE 二选一，只作用总额 |
| `insurer_assessment` | 最终核损金额与确认 | 人工录入，需材料和双重确认 |
| `repair_authorization` | 维修授权事件 | 四项条件全部满足；开始维修时再次校验 |
| `repair_progress/completion_file` | 维修进度与完工照片概念 | 已实现V8，最低1张网点本派单有效图片并冻结 |
| `owner_acceptance/review` | 收车确认与评价概念 | 实际V9/V10；绑定车主或客服收车，车主评价、客服追加纠正 |
| `complaint` | 独立投诉工单 | 不是维修工单备注；管理员处理 |
| `receivable/settlement` | 保险回款与网点结算记录 | 不执行真实资金；最终网点结算金额可空且人工录入 |
| `notification` | 站内通知和已读状态 | 第一版不发送真实短信 |
| `async_task` | PostgreSQL Worker 任务 | 幂等、有限重试、并发领取；不引入 RabbitMQ |

上述后续实体只固定已确认边界，字段与迁移必须等对应提示词开始并解决 `OPEN_QUESTIONS.md` 中的前置问题后再设计。

## 第5阶段（V6）

| 表 | 关键字段及约束 |
| --- | --- |
| case_file | UUID id/work_order_id/group_id；category七枚举；version_no正整数；state ACTIVE/SUPERSEDED/VOID；同group/version唯一、每组至多一个有效版；object_key内部私有；original_name/content_type/byte_size/sha256；shop_id/assignment_version固定来源范围；uploaded_by/created_at。内容元数据不可更新、不可删除。 |
| case_file_command | (actor_id,command_key)主键；request_hash/file_id，重复请求返回原附件，不同载荷冲突。 |
| case_material_exception | (work_order_id,version)主键；reason/actor_id/updated_at，缺失原因追加保留。 |
| ocr_job | 每file唯一；provider MOCK_V1；QUEUED/RUNNING/SUCCEEDED/FAILED；attempts、simulate_failure(dev)、lease_token/until、error_code、candidate_json、review_version、created_by/created_at/updated_at。 |
| ocr_review | (job_id,revision)主键，candidate_json/actor_id/command_key/created_at；不可覆盖删除。revision0为机器候选，后续为人工修订。 |
| ocr_confirmation | (job_id,revision)主键；actor_id/created_at，关联对应复核快照；不可覆盖删除。 |

二进制只在私有MinIO。附件不向客户端输出对象键、哈希、存储凭据；非内部角色使用合成文件名，防止文件名泄露价格。任务确认不写正式业务字段。此段为阶段5边界；阶段6新增报价/金额表见下节。

为兼容阶段4物理删除草稿，case_file/work_order_id保留原UUID但不设置阻止草稿删除的外键；读取必须先解析仍存在的work_order，已删除草稿材料不可从业务API访问，保留对象/审计不清理。

## 第6阶段（V7）

| 表 | 数据与约束 |
| --- | --- |
| quote_raw / quote_raw_item | 案件/版本唯一；shop_id与assignment_version固定；来源SHOP_MANUAL快照；数量正INTEGER、单价及行金额NUMERIC(38,2)，行金额=数量×原价；不可UPDATE/DELETE |
| quote_formal / quote_formal_item | 正式版本与原始版引用；加价方式/值、原始合计/加价额/总额；原价快照、分摊额、external_amount NUMERIC(38,2)、external_unit_price NUMERIC(42,6)；总额=原始+加价；算法PRO_RATA_LARGEST_REMAINDER_V1；不可UPDATE/DELETE |
| quote_assessment | 不可变核损版本；amount NUMERIC(38,2)、file_snapshot有效核损附件ID/版本、source_formal_id可空、操作者/时间 |
| quotation_context | 每案件当前raw/formal/assessment指针；version乐观版本、basis_version确认依据、insurer_confirmed/service_confirmed/authorized；仅此上下文可事务更新 |
| quotation_confirmation | (案件,basis,kind)唯一；INSURER/CUSTOMER_SERVICE；报价/核损版本及file_snapshot、操作者/时间；追加不可变 |
| quotation_event | 追加确认失效、授权授予/撤销、开修事件；案件/basis/kind/summary/操作者/时间，summary不含内部金额 |
| quotation_command | (actor_id,operation,command_key)主键；请求hash和首次JSON响应，用于幂等，不可UPDATE/DELETE，不能从公开API直接读取 |

上述版本、明细、确认、事件及幂等行均有数据库不可变触发器。币种CNY/元，金额接口字符串；输入最多2位，比例0..100、最多2位，百分比派生金额HALF_UP到分。原始行占比最大余数分摊，零行不分配，行金额为对外依据；6位单价仅展示。金额容量为技术表示上限，数量正32位整数，每版最多1000行；历史版本无自动删除。历史分页字段按角色过滤，网点仅自身当前派单原始版，车主不可读。

## 第7阶段V8：已确认的维修进度与完工

- repair_context：work_order_id主键，version独立CAS版本；成功进度或完工加1，不改变报价版本。
- repair_progress：不可变id、案件、网点、派单版本、note（1—2000字符）、photo_snapshot、创建人/时间；按案件时间及id倒序分页。
- repair_completion：每案件唯一不可变完工记录，网点/派单版本、photo_snapshot及创建人/时间。快照只含file id/version/category/contentType，不含内部价格、文件名或MinIO对象键。
- repair_completion_file：completion_id/file_id复合主键，保留version_no；引用版本在材料API与数据库层禁止变更，文件物理保留。
- repair_command：actor_id/operation/command_key复合主键、SHA-256请求hash及原响应；事务命令锁、同载荷重放、异载荷冲突。
- 进度/完工/证据关联/命令为追加记录，触发器拒绝UPDATE/DELETE；repair_context允许受版本保护的递增。
- 上传身份按不可变audit_log的FILE_UPLOAD/CASE_FILE/actor_roles核验，避免客服上传因携带网点字段而误作网点证据。

收车/撤回/评价/投诉按后续D-037及V9/V10条目补齐，原V8不修改。

## 第7阶段V9/V10：收车、撤回、评价及独立投诉（D-037）

- repair_receipt：BIGSERIAL主键、案件/完工证据关联、真实操作者和OWNER/CUSTOMER_SERVICE来源、时间；每次收车新增，不修改前次。
- repair_receipt_withdrawal：receipt_id唯一、必填原因、客服操作者/时间；追加且不可变。当前receipt有撤回记录即失效，工单回待收车。
- repair_review：UUID、每案件唯一原始评价、原receipt_id、owner_id、可空文字/可空1—5整数score（至少一项）、时间；原始评分由车主提供，不可更新/删除。
- repair_review_revision：追加纠正版、review_id及当前receipt_id、文字/评分、必填原因、客服操作者/时间。输出标记客服来源，不覆盖原评价。当前评分资格根据COMPLETED、最新有效receipt与评价/纠正版receipt一致计算；无额外可被覆盖的评分缓存字段。
- complaint：UUID、案件、车主、网点与派单版本、原正文/照片版本快照、status/version/创建时间。只允许状态和CAS版本更新，触发器保护原始内容与归属；删除拒绝。
- complaint_event：追加处理/纠正事件，kind为PROCESSING/RESOLVED/CLOSED/CORRECTION；公开说明、可空管理员内部说明、操作者/时间。原正文不改，事件不改。
- complaint_command：操作者/操作/键复合主键、请求哈希及原响应；事务命令锁保护跨案件同键。投诉独立CAS版本，处理不推进案件或评分。

V1—V8不修改。上述追加表均由触发器拒绝更新/删除；照片对象继续真实存于私有MinIO，不把正文/内部说明写到公开桶、全局日志或无权限响应。

## 第8阶段数据结构（V11/V12）

| 表 | 主键/主要内容 | 约束与历史 |
| --- | --- | --- |
| funds_context | work_order_id,version | 案件独立资金CAS版本 |
| funds_target | id,案件,direction,amount,shop_id,assignment_version,reason,actor/time | NUMERIC(38,2)；RECEIVE必填、PAY可空；不可变金额版本 |
| funds_entry | UUID,案件,direction,transaction_no,amount,occurred_at,派单,note,content_hash,actor/time | amount>0；方向＋流水号全局唯一；不可变 |
| funds_reversal | UUID,entry_id,reason,actor/time | 每笔最多整笔冲销一次；不可变 |
| funds_command | actor/operation/key,hash,response | 幂等结果不可变；鉴权先于回放 |
| funds_import | UUID,actor,file_name/format,sha256,object_key,total_rows,time | 最多2000行，私有源对象；API不输出存储键 |
| funds_import_row | import_id/row_number,raw_json,case_id,status,error_code,entry_id,version | 原始内容不可变；匹配/确认CAS；行级结果 |
| funds_import_resolution | id,import/row,case_id,reason,actor/time | 原编号不覆盖，追加人工纠正 |
| notification_event | audit_id,case,kind,派单,created/projected_at | 同事务outbox；并发锁领取/重启恢复 |
| in_app_notification | id,event_id,recipient_id/role,time | 唯一事件＋人＋角色；不可变，实时权限输出 |
| notification_read | notification_id/user_id,read_at | 个人唯一，不影响其他用户 |
| todo_deadline | task_key,case,deadline,version | 当前可选期限；TIMESTAMPTZ，接口+08:00/空 |
| todo_deadline_event | id,task_key,deadline,reason,actor/time | 不可变变更历史，旧待办失效仍保留 |

资金净额=未被冲销流水总额，状态UNSET/UNPAID/PARTIAL/SETTLED分别计算两方向，不写维修状态。待办为业务状态实时查询，不另设可漂移的“已完成任务”表。通知不存价格/金额或审计内部原文。历史保护采用数据库触发器，业务接口不提供删除/覆盖。

前端可用性补充（2026-10-10）：报价单价输入维持十进制字符串，数量经正整数校验；照片选择保存现有fileId，人工匹配保存现有caseId，列表可读名称不替代服务端ID/授权校验。deadline输入按中国时区，清空发送null。无新增存储字段。
