# 经营看板 API

2026-11-11。本机管理端路径 `/funds/analytics`，资金模块默认入口。接口使用现有统一响应和认证机制，控制器同步提供 OpenAPI Operation。

| 接口 | 参数 | 返回 |
| --- | --- | --- |
| GET /api/v1/funds/analytics | from、to 必填；shop、creator 可选；pending 默认 false；page 默认 1；size 默认 20；detailFrom、detailTo 同时提供或同时省略 | summary、grain、trend、details 分页 |
| GET /api/v1/funds/analytics/groups | from、to 必填；shop、creator 可选；dimension 为 shop 或 creator；metric 为 profit 或 completed；page 默认 1；size 默认 20 | 分页排名，可用作筛选候选 |

日期使用 YYYY-MM-DD，按 Asia/Shanghai 的起日零点至结束日次日零点前取数，最长 3660 天差。分页上限沿用 PageResponse。范围超过 92 天差按月，否则按日；无数据日期补零。detailFrom/detailTo 必须位于总日期范围内。pending 和明细日期仅约束 details，不改变 summary 或 trend。

summary 和 trend 包含 completed、accounted、insurance、payable、profit、margin，trend 另有 bucket。金额和百分数是精确十进制字符串；零分母 margin 不提供数值。未核算数量为 completed 减 accounted。groups 记录增加 id、name，以所选指标降序并以分组 ID 稳定排序。

details 包含 id、business_no、creator_name、shop_name、completed_at、insurance、payable、profit、pending_reason，以及 completion_id、confirmation_id、receive_target_id、pay_target_id 追溯字段。缺依据记录不提供金额。完成时间展示精度为分钟。

ADMIN 可查询所有维度。CUSTOMER_SERVICE 强制只查询本人创建工单，指定其他 creator 或访问 creator 排名返回 403。REPAIR_SHOP、OWNER 均拒绝访问。未认证沿用 401；无效日期、维度、排序和分页返回参数错误。

金额规则和历史兼容以 REQUIREMENTS、DECISIONS 为准。独立历史档案不计入本接口。
