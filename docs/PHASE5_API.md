# 阶段5 API与权限

所有路径前缀/api/v1，需有效Bearer且实时账号启用。JSON沿用ApiResponse，列表page从1开始，size上限100；字段及在线协议见/v3/api-docs。

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
