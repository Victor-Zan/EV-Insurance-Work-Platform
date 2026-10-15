# 网点报价表单手写 OCR（2026-10-15）

## 已确认边界与映射

入口：维修网点 H5 → 本店案件 → 详情 → 原始报价新版本 → 上传手写清单识别。只在既有 QuotationLifecycle.EDITABLE 状态、当前 ACCEPTED 派单开放；不先提交报价、不跳客服页面。OCR 可选，失败/取消仍可手填。

| 单据内容 | 现有报价映射 | 边界 |
| --- | --- | --- |
| 项目/配件名称、数量、单价 | description、quantity、unitPrice | 程序候选预填；数量必须由原文或人工填写，不默认1，l不转1 |
| 单位、原始行金额 | OCR核对快照及行来源 | 正式报价结构无独立单位/原始金额输入；不改变现有 quantity×unitPrice 计算 |
| 工时/人工、拖车、其他费用 | 普通报价项目候选 | 没有汇总收费字段，禁止重复创建费用汇总；仅金额明确时数量/单价仍留空，由网点明确填写 |
| 单据合计 | 独立核对证据 | 不写合计、不跨图累加、不冒充程序计算 |
| 姓名、单号、日期、车辆信息 | 只读识别证据 | 不切换案件，不更新案件字段，不写平台/保险编号 |

空白表单可预填；已有内容必须逐候选明确添加、替换指定行或忽略，应用时比较最新表单，替换前再次展示当前值。每张图片独立任务/页次，同名不合并；同一案件/派单/分类/内容摘要去重。来源行可添加/替换一次，重复采用提示，移除表单行后可再次明确采用。提交前将实际使用的候选保存人工快照并确认，原“网点提交报价”继续调用 QuotationService.submitRaw，行来源关联任务/修订/候选索引及原件；不能覆盖历史报价。

## 权限与实现约束

仅新报价 OCR 作用域允许 REPAIR_SHOP；不扩大通用 /ocr/** 角色。专用服务每次检查案件、当前已接单本店、派单版本、可编辑状态、有效 SHOP_QUOTE/ASSESSMENT 原件、本店本次上传。管理员/客服沿用内部授权只读此类任务，不可代网点修订、确认、重试或提交。OWNER 拒绝。Worker 调用前/写回均重新校验任务创建者启用、网点归属、派单与源文件；改派/取消/完成/材料失效后拒绝结果使用。

复用 OcrProvider、BaiduOcrProvider、Token缓存、OcrWorker、OcrTaskStore、ocr_job/review/confirmation/raw、FileService、私有 MinIO。只追加 V18（V17 已由独立地图分支占用但未合并；本迁移不依赖地图），原迁移/旧数据库不变。新增不可变作用域/应用快照/报价来源关联，任何外呼在事务外。自动测试只合成材料/模拟供应商，本轮真实百度鉴权与 OCR 均0，不读取或要求重新提供真实密钥。

所有界面显示供应商及“候选结果，须人工核对”。程序整理不是百度原生表格；原文、坐标、真实返回概率只读，人工修订独立。没有可靠行列关系时保留原文供人工录入，不接其他OCR/模型。图片10MiB平台限制和百度更严格限制继续生效，不裁剪或静默缩图。

## 验收计划

合成响应覆盖缺失/l/金额差异/费用/单位；集成覆盖四角色、跨店/案件/旧派单、不可编辑和失效、重试/租约、去重/并发、原文不可变、人工确认及原报价版本提交关联。H5 真实浏览器用合成图片验证等待期间手填、冲突选择、原图、增删候选、失败后手填、提交与历史。两端完整门禁、后端全测试及Flyway空库验证；真实识别质量未验证。

## REST 与权限

前缀 `/api/v1/quotations/cases/{caseId}/ocr`，统一 ApiResponse；错误沿用401/403/400/409。不是放开 `/api/v1/ocr/**` 给网点。

| 方法/相对路径 | 协议及作用 |
| --- | --- |
| GET configuration | 网点读取当前 provider/api/version，不返回密钥和内部调用计数 |
| POST 空路径 | multipart file、assignmentVersion、category（SHOP_QUOTE或ASSESSMENT）、pageNo（客户端页次提示1至20；服务端在案件锁内分配不可变原件序号并返回pageNo）；Idempotency-Key。受控上传和创建任务，按内容摘要/案件/本店/派单/分类去重；同幂等键不同原件409 |
| GET {job} / {job}/program | 当前任务及只读程序候选，含实际供应商；通用任务响应增加 shopQuote 标记，内部复核页据此保持只读 |
| GET {job}/image / {job}/raw | 鉴权原图流（no-store/nosniff）及不可变原始JSON文本，log_id字符串，不能转成JS Number |
| GET {job}/history?page&size | 修订及确认历史，稳定分页，size上限100 |
| POST {job}/retry | 当前网点明确重试，仅现有可重试错误/有限次数；不会把百度失败降为Mock |
| POST {job}/apply | Idempotency-Key；expectedVersion、items[{index,description,quantity,unitPrice,unit,lineAmount}]。只保存人工修订与确认快照，原文不变，尚不提交报价；当前表单后续改动须再次确认 |

任务/原文/程序/历史 GET：当前网点须当前有效业务范围；内部客服/管理员按原权限查看（包括历史状态）；OWNER拒绝。所有写操作仅当前网点，客服/管理员也拒绝。

原 `POST /quotations/cases/{caseId}/raw-quotes` 的每行可选 `ocr:{jobId,revision,itemIndex}`，其他字段和版本/幂等协议不变。提交事务重新校验作用域/当前派单/文件/确认版本/金额和数量与快照一致。同一OCR候选每版只能引用一次；不带OCR来源可纯手填。`priceSnapshot.source=SHOP_OCR_REVIEWED` 关联任务、修订、候选索引；原 `SHOP_MANUAL` 含义不改。

每案件本次派单最多20张不同OCR原件（两个允许分类合计，重复原件/重试不新增名额），是识别辅助资源上限，不限制原报价手填；附件原有每类20份规则不变。返回pageNo来自不可变作用域，刷新后复用任务不会重排页次。

V18三张表：shop_quote_ocr_scope（不可变任务与原件摘要/派单/页次）；shop_quote_ocr_command（actor+key幂等）；quote_raw_ocr_line（报价行→确认修订，外键/唯一约束）。原始对象、程序revision0、人工review、confirmation及quote_raw历史都保留。人工增加行明确 addedByHuman，不冒充OCR原行；单位/原行金额保存在人工证据快照，正式金额仍由原计算器生成。

## 配置及真实验收边界

不新增供应商和密钥文件。复用现有启动器 EnvironmentFile 注入 OCR_PROVIDER、BAIDU_OCR_API_KEY、BAIDU_OCR_SECRET_KEY，具体见 BAIDU_OCR.md / LOCAL_DEVELOPMENT.md。生产代码只用原百度客户端，HTTP超时、Token缓存、响应上限与真实任务租约保持原规则。默认Mock，缺凭据显式失败，没有失败降级。

本轮不加载百度私密凭据、不新增真实调用预算；集成测试以模拟 Baidu Provider 返回合成原始响应，HTTP客户端本身沿用既有本机HTTP模拟测试。浏览器只使用标注Mock的合成图/案件。要验收新的真实识别效果，须另行获得调用次数与单据样本授权，不能重启旧预算进程来增加次数。

页面暂停只停止轮询/采用，不取消已发出的外部请求，也不撤销可能已消耗的额度；提示清楚区分。页面临时编辑不跨刷新持久化，刷新后的同图上传复用已有任务，不重复外呼；已确认/已提交历史始终留在服务端。上传图片分类不是自动生效报价。
