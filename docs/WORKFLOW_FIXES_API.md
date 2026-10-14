# 功能全流程修复 API（2026-10-13，D-044）

所有路径以 `/api/v1` 为前缀，沿用 JWT、统一响应和稳定分页；`page` 从1开始，`size` 上限100。金额仍为 CNY 元的十进制字符串，最多2位小数。新增接口不改变派单、报价、开修、收车或资金处理权限。

| 接口 | 权限及字段 |
| --- | --- |
| POST `/admin/users` | 仅管理员；原字段外可附 `ownerPhone`，只适用于包含 OWNER 的账号，最长32字符。不自动创建案件或匹配历史工单 |
| PUT `/admin/users/{id}/owner-contact` | 仅管理员；`phone` 非空、最长32字符；`reason` 非空、最长2000字符。仅维护 OWNER 账号资料，追加维护记录和审计，不改变已绑定案件 |
| GET `/admin/users` | 仅管理员；OWNER 用户的 `ownerPhone` 返回当前值，其他角色不输出车主手机号。登录和 `/auth/me` 不新增手机号字段 |
| POST `/work-orders/{id}/owner-binding` | 客服/管理员；`expectedVersion`、`reason`、`Idempotency-Key` 必填。只处理正式、待绑定、未完成、未取消/关闭案件；唯一启用 OWNER 精确匹配成功返回 BOUND，无匹配或多匹配返回 PENDING |
| GET `/funds/cases/{id}/targets` | 现有客服/管理员接口；补充 `actorId`，每行包含 id/direction/amount/reason/createdAt。amount 可空，不覆盖历史 |
| GET `/funds/workbench` | 客服/管理员；query 匹配工单号/报案号，status 过滤维修状态，receivableStatus/payableStatus 分别过滤 UNSET/UNPAID/PARTIAL/SETTLED。返回 caseId/businessNo/claimNo/status、两方向 target/net/status；不返回联系方式、原始报价或加价 |
| GET `/complaints` | 客服/管理员；query 匹配工单号/报案号，status 过滤 SUBMITTED/PROCESSING/RESOLVED/CLOSED；分页返回投诉及工单标识。处理仍通过原单投诉接口，客服不能执行管理员处理动作 |

补绑定请求示例：

```json
{"expectedVersion":1,"reason":"车主账号建立后核对并重新匹配"}
```

补绑定鉴权先于幂等回放，同键同内容返回原响应，同键不同案件/版本/原因返回 `IDEMPOTENCY_CONFLICT`。不同键并发处理同一案件，行锁与版本检查保证仅一次成功；已绑定返回 `OWNER_ALREADY_BOUND`，失效版本或只读状态返回409。手机号维护、账号启停/角色调整与正式提交/补绑定共用匹配锁，避免唯一性判断在并发变化中漂移。

取消请求继续使用原 `reason`、`description`、`notifyOwner`、`notifyShop`。新增不可变取消快照，记录取消前的绑定车主和当前派单；同事务生成 `CASE_CANCELLED` 事件及勾选的站内收件人。通知公开字段为业务编号、公开取消原因和时间，标准通知 id/caseId/kind/title/read 元数据仍保留；不复制内部 description、审计原文、金额或联系方式。未绑定车主或无当前派单时管理端提示无接收对象。

取消后的通知仍可读取、标记个人已读。服务端重新检查当前账号启用状态、角色、车主绑定或网点账号归属；取消通知允许使用取消前的派单快照，其他网点通知继续要求当前有效派单。历史取消数据不凭字符串推断接收对象或批量补发。

管理端入口：管理员在“用户与角色→车主手机号”维护资料；客服/管理员在待绑定案件顶部“重新匹配车主”；“资金→跨案件资金查询”；“案件→投诉跟踪”。区域/网点选项按20条分页加载，使用“加载更多”继续取下一页；搜索作用于已加载选项。状态历史和资金目标版本按20条翻页。草稿时间控件按浏览器本地时区回填并转换为 UTC 保存，保留毫秒。
