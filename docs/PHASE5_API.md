## 2026-10-21 高德增量整合说明

下文Mock地图协议只在Mock模式可用；真实模式拒绝旧任意地址/逆编码接口，使用[AMAP_INTEGRATION](AMAP_INTEGRATION.md)的广东结构化候选、人工位置确认、资格筛选、道路排序与推荐关联派单。停车字段与事故字段分离，地图读取/初始化/分页不触发外呼。供应商配置接口返回构建提交、Mock标识和共享预算状态，不返回凭据。测试网点的位置候选仅dev显式开关下由ADMIN操作；CS负责当前案件停车位置及派单，网点/车主无新增权限。

# 阶段5 API与权限

2026-10-15 D-047新增限定网点报价OCR，现行专用API及权限矩阵见[SHOP_QUOTE_OCR.md](SHOP_QUOTE_OCR.md)。以下“网点全部禁止OCR”仍适用于原通用 `/ocr/**`，不阻止本次经确认的quotation作用域入口。客服/管理员对网点任务仅查看，不能代写。普通客服建单OCR原权限不变。

所有路径前缀/api/v1，需有效Bearer且实时账号启用。JSON沿用ApiResponse，列表page从1开始，size上限100；字段及在线协议见/v3/api-docs。

## D-045 百度与建单前补充

| 方法/路径 | 语义 |
| --- | --- |
| GET ocr/configuration | 当前供应商/API/适配版本与开发模拟开关，不含凭据 |
| POST ocr/intake | 客服 multipart JPEG/PNG，Idempotency-Key；私有上传并建任务，不需要案件/fileId，不创建案件 |
| GET ocr/{id}/image | 授权原图流，no-store；客服仅自己的建单前来源、管理员只读 |
| GET ocr/{id}/raw | 授权原始响应，text 保留原始 JSON、logId 字符串，客户端不得将大整数转 Number |
| GET ocr/{id}/program | 最新不可变程序候选（初始 revision=0 及后续历史保留），字段含文字行及坐标证据；人工版本不能冒充程序来源 |
| POST ocr/{id}/reextract | 客服以 expectedVersion、Idempotency-Key 从已存原文追加程序候选版本，不再调用百度；初始与人工版本均保留 |
| POST ocr/{id}/draft | expectedVersion + draft；只允许建单前任务，须与已确认表单一致；调用原草稿服务，每任务一个关联，重复返回同草稿；不正式提交 |

GET ocr/{id} 增加 intakeId、providerApi、providerVersion、retryable；失败只返回安全代码。旧 fileId 任务兼容，供应商与来源不可修改。review 接口仍保存有界对象，但管理端使用字段/明细编辑而非 JSON 编辑；建单前表单保存至 candidate.draft，服务端对已确认值规范化比对。确认仍只保存快照；创建草稿是明确独立动作。管理员不能创建、重试、修正、确认或从 OCR 创建草稿，网点/车主全部禁止。


| 方法/路径 | 语义 |
| --- | --- |
| GET materials/cases/{id}?page&size | 按案件、角色、当前派单、分类、版本过滤 |
| POST materials/cases/{id} | multipart file/category，可选replace、assignmentVersion；Idempotency-Key必填。客服替换同类有效文件，网点必须提交当前派单版本。 |
| GET materials/{id}/download | 每次鉴权，流式二进制附件；no-store/nosniff，不发公开桶链接 |
| POST materials/{id}/void | 客服作废，历史对象保留 |
| GET/POST materials/cases/{id}/notice-status 或 missing-notice | 前者内部查询；后者客服body {reason}追加历史 |
| POST ocr | 客服body {fileId,simulateFailure?:boolean}，同附件去重，模拟失败仅dev |
| GET ocr/{id} | 客服/管理员查询任务、候选、版本、确认状态 |
| POST ocr/{id}/retry | 客服重试FAILED且未超过OCR_MAX_ATTEMPTS（默认3次） |
| POST ocr/{id}/review | {expectedVersion,candidate:object}，Idempotency-Key必填；追加人工快照，旧确认不适用于新版本 |
| POST ocr/{id}/confirm | {expectedVersion}，确认当前SUCCEEDED候选，只有快照无正式业务写入 |
| GET ocr/{id}/history?page&size | 内部候选修订及确认历史 |
| GET maps/search?query；geocode?address | 客服合成地址搜索/正编码 |
| GET maps/reverse?latitude&longitude | 客服逆编码，验证有限数及坐标范围 |
| GET maps/distance?fromLat&fromLon&toLat&toLon | 客服Mock直线距离（米），非导航距离 |
| GET maps/cases/{id}/shops?page&size | 实际符合案件区域的网点，附Mock坐标 |

七分类及角色矩阵见D-030。完成/取消案件禁止上传、替换、作废、缺失原因和OCR变更。鉴权失败403；缺失404；无效文件/参数400；版本、数量、只读、幂等冲突409；存储不可用503。错误和审计不包含文档正文、凭据或完整个人信息。

Mock OCR的数字均是合成候选字符串，不代表真实OCR输出；置信度为空。两端详情页可验证附件；管理员用任务ID只读查看；客服可建立、刷新、重试、编辑JSON并确认快照。地图页面明确为模拟网格，不接收费服务。
