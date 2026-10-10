# 第7阶段API

2026-10-10，按D-035/D-036/D-037开放维修进度、完工、收车撤回、可选评价及独立投诉。所有接口JWT认证，统一ApiResponse；不包含金额、成本、加价、存储对象键或原始文件名。收车/撤回/评价/投诉接口与只读例外见本页后半段D-037说明。

基础路径：`/api/v1/repairs/cases/{id}`。

| 方法/路径 | 请求/结果 | 权限 |
| --- | --- | --- |
| GET / | caseId/status/version/assignmentVersion/completion | 客服/管理员正式案件；当前网点本单；绑定车主本单 |
| GET /progress?page=1&size=20 | PageResponse进度记录，时间及ID倒序；page1—100000，size1—100 | 同上；网点只看当前派单版本 |
| POST /progress | expectedVersion、assignmentVersion、note、photoIds | 当前已接网点或客服代录；合法开修且REPAIRING |
| POST /completion | expectedVersion、assignmentVersion、photoIds | 当前已接网点或客服代录；合法开修且REPAIRING |

写接口必须携带`Idempotency-Key`（1—128字符），同操作者/操作/键同载荷重放原响应，不重复落库；不同载荷409。当前案件行锁串行化状态、进度、完工与材料变更；命令锁保护跨案件复用同键的竞争。维修version独立于报价与工单版本，每次成功追加/完工加1，过期version返回409。进度不会改动工单状态或状态计时起点。

进度note为1—2000字符；photoIds可空，可引用最多20个不同的当前网点本派单上传、ACTIVE、JPG/PNG的PROGRESS_PHOTO。完工photoIds至少1个、最多20个，类别COMPLETION_PHOTO，同样校验案件、网点、派单、有效状态、类型，并使用上传审计中保存的当时角色证明网点上传，不能仅依赖文件shop_id。

提交完工同事务保存版本快照和证据关联、转WAITING_OWNER_CONFIRMATION、写工单状态历史/审计。证据引用的版本禁止客服替换/作废，API返回409，数据库触发器防止绕过；私有MinIO下载继续经原材料接口后端权限验证。历史进度与完工为追加记录，数据库拒绝更新/删除。未冻结的材料继续遵守阶段5/6规则。

典型错误：无身份401、越权403、案件不存在404、参数错误400、REPAIR_STATE_CONFLICT/REPAIR_GATE_INCOMPLETE/REPAIR_VERSION_CONFLICT/INVALID_REPAIR_PHOTO/COMPLETION_EVIDENCE_FROZEN/IDEMPOTENCY_CONFLICT为409。

两端详情新增简洁真实API验证面板。照片ID来自材料面板；网络错误后同载荷重试保留幂等键。收车/撤回/评价/投诉通过下述真实接口操作，不能凭前端隐藏或任意状态修改绕过权限。

## D-037最终补充：收车、撤回、评价与投诉

D-037明确收车与反馈的最终规则。2026-10-10用户确认D-037后补齐以下真实接口，全部JWT、后端角色/案件范围、幂等键与不可变审计。维修进度/完工POST现在同时允许客服代录，仍须当前已接派单、合法开修及网点本派单上传的有效图片。

| 路径（/repairs/cases/{id}下） | 请求 | 权限与效果 |
| --- | --- | --- |
| POST /receipt | expectedVersion | 绑定车主或客服，WAITING_OWNER_CONFIRMATION→COMPLETED；非必填评价/评分/回款不作为门槛 |
| POST /receipt/withdraw | expectedVersion,reason | 仅客服；COMPLETED→WAITING_OWNER_CONFIRMATION；原因1—2000字符，原完工照片仍冻结 |
| POST /review | expectedVersion,text?,score? | 绑定车主、已收车完成后一次提交；文字可空、评分可空，但至少一项；score为JSON整数1—5，拒绝字符串/小数；可完全跳过不提交 |
| POST /review/corrections | expectedVersion,text?,score?,reason | 仅客服，必须已有车主原评价且当前收车有效；新增有原因的纠正版本，原文/评分不覆盖 |
| GET /review/history?page&size | 稳定分页 | 已有案件读取范围；外部角色移除客服内部纠正原因，标识CUSTOMER_SERVICE_CORRECTION来源 |

维修GET新增receipt（id/confirmedBy/createdAt/withdrawn）、review（original/current/eligibleForCurrentRating）。撤回后评价保留但不计当前评分；再次收车产生新receipt，旧评价不自动复活，车主不可再提；客服新增绑定当前receipt的纠正版本后恢复当前资格。没有虚构车主重新打分或后台覆盖。收车/撤回/评价独立版本递增，重放同键返回原响应但不重做状态操作。

投诉基础路径`/api/v1/complaints`：

| 方法/路径 | 请求/权限 |
| --- | --- |
| GET /cases/{caseId}?page&size | 案件范围与稳定分页，网点仅本次派单相关投诉 |
| POST /cases/{caseId} | description必填1—2000字符、photoIds可选最多20个不同的授权案件照片；仅绑定车主、已真实开修；COMPLETED可提交，CANCELLED/CLOSED拒绝新增 |
| GET /{id} | 原正文/照片快照/status/version；绑定车主、本派单网点、客服/管理员 |
| GET /{id}/history?page&size | 不可变处理历史；OWNER/REPAIR_SHOP不输出internalNote字段或内部内容 |
| POST /{id}/handle | expectedVersion,status,publicNote,internalNote?；仅管理员；严格SUBMITTED→PROCESSING→RESOLVED→CLOSED，每次公开说明必填，内部说明可选最多2000字符，不重开 |
| POST /{id}/corrections | expectedVersion,publicNote；仅客服追加纠正，不改车主原文，不推进状态 |

投诉写接口必须Idempotency-Key。独立投诉行锁/CAS/命令锁，不改变工单状态、资金或评价资格/评分；取消后已有投诉仍可跟踪，不新增投诉。所有说明均保留为追加事件，原始投诉在数据库层不可修改/删除；管理员内部说明仅ADMIN/CUSTOMER_SERVICE读取。不提供车主修改路由、无导出/通知旁路。管理员查看审计沿用既有ADMIN接口。

额外409错误：RECEIPT_STATE_CONFLICT、COMPLETION_EVIDENCE_MISSING、REVIEW_STATE_CONFLICT、REVIEW_ALREADY_SUBMITTED、REVIEW_MISSING、COMPLAINT_STATE_CONFLICT、COMPLAINT_VERSION_CONFLICT、COMPLAINT_ASSIGNMENT_MISSING。请求资源上限不是新增业务额度；仍无真实短信/资金/外部服务。

幂等回放也按当前认证角色过滤字段：管理员合法改变账号角色并重新登录后，旧客服收车响应中的评价纠正reason不向OWNER输出；角色与案件范围校验仍在读取缓存之前执行。
