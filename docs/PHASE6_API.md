# 第6阶段 API：报价、核损与开修授权

2026-10-10；规则依据 D-032。继续使用 `/api/v1`、JWT、统一 ApiResponse/错误协议和 Trace ID。OpenAPI 同步由 QuotationController 提供。

## 协议与权限

全部写操作要求 `Idempotency-Key`（沿用材料接口的键格式）和当前 `expectedVersion`。原始报价/开修另带 `assignmentVersion`。同一操作者、操作和键的同载荷请求返回首次响应；键复用到不同载荷返回409。每次仍执行当前案件/角色/派单权限。版本冲突409；无权403；格式错误400；不存在404。

客服可审核、核损录入及两项确认；管理员只读内部版本、历史、审计和对外导出。当前已接单网点到店后可提交自身原始报价及开修；跨店/旧派单拒绝。网点响应只含自身本次派单原始报价、版本、状态及四项门禁布尔值，不含正式报价、内部加价、最终核损金额。车主报价接口全部拒绝，页面也无报价入口。

## 金额与分摊

币种CNY、单位元；金额传JSON字符串，例如 `"100.00"`，不能传数值100。金额/单价/固定加价/核损最多2位小数，多余位拒绝，负数拒绝，零允许。数量必须正整数，接口技术范围1..2147483647，每版1..1000行；描述1..255字符。NUMERIC(38,2)支持最多36位整数，超过数据库表示范围拒绝。前端不使用浮点数进行金额计算。

比例字符串 `"10.00"` 表示10%，0..100且最多2位，以本版原始合计为基数，派生加价HALF_UP到分。FIXED_AMOUNT仅填fixedAmount；PERCENTAGE仅填percentage，不能同时填。原始行金额占比分摊、分单位最大余数，余数相同按序号；零金额行不分摊，全零合计仅零加价。对外行金额精确且合计等于正式总额；展示单价HALF_UP保留6位，行金额为准。

## 接口

路径前缀 `/api/v1/quotations/cases/{id}`：

| 方法/后缀 | 使用者 | 请求与结果 |
| --- | --- | --- |
| GET 空后缀 | 客服/管理员/当前网点 | 当前上下文version/basisVersion、状态、按角色过滤的原始/正式/核损快照、四项gate/missing/authorized |
| POST /raw-quotes | 当前网点 | expectedVersion、assignmentVersion、lines[{description,quantity,unitPrice}]；生成不可变原始版本，来源SHOP_MANUAL，原价永久保留 |
| POST /formal-quotes | 客服 | expectedVersion、rawQuoteId、mode、fixedAmount或percentage；仅审核当前版本，须有通知单或客服缺失原因；不可编辑原价 |
| POST /assessments | 客服 | expectedVersion、amount；生成不可变金额及当前有效核损文件集合ID/版本快照 |
| POST /confirmations/INSURER | 客服 | expectedVersion；线下保司确认代录，保存确认基准及文件/报价/金额版本引用 |
| POST /confirmations/CUSTOMER_SERVICE | 客服 | expectedVersion；客服确认快照，满足四项时记录授权 |
| POST /start-repair | 当前网点 | expectedVersion、assignmentVersion；同事务锁案件并重读四项证据，成功进入REPAIRING |
| GET /history?type=EVENT&page=1&size=20 | 客服/管理员；网点仅RAW | type RAW/FORMAL/ASSESSMENT/CONFIRMATION/EVENT，稳定分页和分页上限；历史金钱用字符串 |
| GET /formal-quotes/{quote}/export | 客服/管理员 | 任一历史正式版对外CSV数据；只含明细序号/描述/数量/对外展示单价/行金额/CNY/总额；无原价或加价；描述CSV转义及公式防护 |

固定额正式请求示例：

```json
{"expectedVersion":1,"rawQuoteId":"原始报价UUID","mode":"FIXED_AMOUNT","fixedAmount":"10.00"}
```

## 失效、并发与边界

原始/正式报价新版本、最终核损金额新版本、核损附件新增/替换/作废均提升basisVersion，两项确认失效，已有授权撤销并追加事件及审计。旧快照、确认和授权事件不更新/删除。附件与报价操作共用work_order行锁；开修和附件作废竞争只能有一个合法先后结果。

开修的业务证据仅有效核损附件、最终金额（0也算已录入）、保司确认和客服确认，不添加正式报价/回款/网点二次确认门槛；角色、当前已接派单和到店状态仍须合法。开修后报价/核损/关联报价和核损材料禁止普通修订；完成/取消只读。其他照片遵循阶段5权限，后续完工规则留阶段7。

OCR候选不自动进入报价；本阶段手工填网点报价，记录价格来源与金额快照，不猜测价格库优先级。导出是受控CSV数据，正式定损PDF/Excel模板、维修交付、真实付款和通知留后续。
