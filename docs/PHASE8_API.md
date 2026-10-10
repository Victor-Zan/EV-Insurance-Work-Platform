# 第8阶段API：资金、导入与站内待办

所有路径前缀/api/v1，JWT鉴权，JSON沿用ApiResponse；分页page从1起，size默认20、上限100。金额币种CNY、单位元，输入必须十进制字符串且最多2位小数；目标可0，实收/实付必须>0。PAY目标可null，RECEIVE不可null。业务时间occurredAt为带时区ISO时间。400参数、403权限、404不存在、409版本/状态/重复内容冲突。

## 资金（/funds/cases/{id}）

| 方法/路径 | 输入与效果 |
| --- | --- |
| GET / | 当前version、目标、净流水、独立资金状态；网点只有本次派单PAY |
| POST /targets | expectedVersion,direction(RECEIVE/PAY),amount,reason；追加不可变目标版本 |
| POST /entries | expectedVersion,direction,transactionNo,amount,occurredAt,note；追加流水 |
| POST /entries/{entry}/reverse | expectedVersion,reason；追加整笔冲销 |
| GET /entries | 分页流水及冲销标识；历史不覆盖 |
| GET /targets | 内部目标版本历史，仅客服/管理员 |
| GET /export | 分页CSV，默认100行、最多100行；获取全部需遍历页码 |

三个写接口均要求Idempotency-Key；相同操作者/操作/键和内容返回原结果，异内容409。资金context版本CAS、案件锁和流水号事务锁防并发超额。方向＋流水号全平台唯一，规范化内容相同返回duplicate，不再记账；冲销后编号仍占用。新增目标不低于净流水，PAY恢复未定仅净实付0。取消单仅允许历史冲销。

仅客服写。管理员读取内部台账/审计。当前网点仅本店当前派单PAY目标、净付款、结清和历史；不返回RECEIVE、内部流水备注或内部报价/加价。OWNER全部403。导出同样后端过滤，不含手机号、证件、内部报价/加价、存储键，并防CSV公式注入。客服备注仅内部详情，导出不带备注。

实付须实际收车COMPLETED、应付明确且对应当前派单、保险应收已SETTLED。撤回收车保留流水但阻止新实付。目标明确且净流水恰好相等才SETTLED；未知目标UNSET，资金不更改维修状态。

## 导入（/funds/imports）

| 方法/路径 | 说明 |
| --- | --- |
| POST / | multipart file，真实私有MinIO保存源文件并预览 |
| GET / | 本人批次分页 |
| GET /{id} | 批次摘要 |
| GET /{id}/rows | 分页原始行、匹配案件、状态、错误、version和entry_id |
| POST /{id}/rows/{number}/resolve | expectedVersion,caseId,reason；冲突人工选择正式案件，保留原编号 |
| POST /{id}/confirm | 行级事务确认READY/可重试FAILED；其他行保持原状态，重复确认不重复记账 |
| GET /{id}/resolutions | 分页不可变人工纠正历史 |
| GET /{id}/source | 本人批次私有原文件下载并审计 |

仅批次创建客服操作、查看/下载；其他客服、管理员、网点和车主403，管理员可查看审计但不获导入源文件权限。

格式XLSX单工作表或UTF-8 CSV，10 MiB/2000数据行，精确表头：

```csv
transactionNo,direction,claimNo,businessNo,amount,occurredAt,note
FLOW-001,RECEIVE,CLAIM-001,WORK-001,100.00,2026-10-10T10:00:00+08:00,合成样本
```

两编号必填且必须唯一指向同一正式案件，否则NEEDS_REVIEW，不猜归属。格式错误INVALID；有效READY；确认后RECORDED/DUPLICATE或FAILED，保留行级原因。公式不作为有效金额/编号。每行独立，失败不回滚已成功行；重复文件可产生新预览批次，但流水去重防重复记账。匹配批量查询、每块最多500行入库；确认最多2000行逐行事务，未进行2000行性能基准测试。

## 站内通知（/notifications）

| 方法/路径 | 说明 |
| --- | --- |
| GET / | 本人、当前仍有权限的历史通知分页 |
| POST /{id}/read | 个人已读；重复调用只保留一次记录；他人/失去权限403 |
| GET /todos | 当前有效待办分页；可选caseId查看有权案件 |
| POST /deadlines | 客服taskKey,expectedVersion,deadline,reason；deadline为ISO +08:00或null清除 |
| GET /deadlines/history?taskKey=... | 客服/管理员有权案件的分页变更历史 |
| GET /settings | automaticTimeoutEnabled/repeatedRemindersEnabled/escalationEnabled/smsEnabled均false，Asia/Shanghai |

无caseId时只取本人角色/责任范围待办。客服带caseId可查看有权案件全部待办以维护期限；管理员接收独立投诉处理待办。网点仅当前派单，车主仅当前绑定。待办key含业务阶段/派单或金额版本，状态处理及改派后原待办失效，旧deadline历史保留。修改已失效待办409。deadline可设过去时间，仅返回overdue标签，不自动通知或升级。

事件通知覆盖派单、到店、报价审核、核损确认、完工待收车、收车撤回、投诉和资金；客服接收当前负责案件，管理员接收投诉，绑定车主只接收可见的非资金事件，网点仅当前派单及本店付款事件。输出固定标题、案件及事件标识，不拷贝审计原文、金额或内部说明；每次读取重验权限。Worker只投影事件，并非超时提醒任务。

Swagger沿用/swagger-ui/index.html；具体请求字段以控制器/DTO与本文为准。验证面板挂在现有案件详情及登录后站内通知折叠面板，不替代后端权限验证。


可用性修复（2026-10-10）：照片选择复用分页materials/cases/{id}，人工匹配复用work-orders的query/page/size，不增加自动匹配规则。资金导入两请求前端超时120秒；服务端协议、事务、权限不变。

真实浏览器上传复查：两端共用Axios遇FormData清除默认JSON Content-Type，由浏览器设置multipart boundary，资金CSV/XLSX实际上传预览已验证；不修改后端协议/鉴权。
