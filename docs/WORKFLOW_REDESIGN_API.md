# 工作流重构：节点、识别与版本接口

## D-055 维修授权金额校验（2026-10-16）

上下文assessment新增lossFileId/revision/sourceCurrent，仅管理员/客服可见。CUSTOMER_SERVICE确认除expectedVersion外须提交assessmentId、lossFileId、amount（十进制字符串）、originalReviewed=true；INSURER代录请求保持原格式。后端比较当前核损版本/最新有效文件/已确认OCR采用金额与确认金额，不符返回409，缺少核对确认返回参数错误。确认快照保存绑定证据，开修重查。未经成功加载与人工核对不提交最终价格；核损修正沿用recognized-assessment接口。

为避免内置浏览器PDF插件空白，新增`GET /quotations/cases/{id}/formal-quotes/{quote}/preview?page=1`及`GET /materials/{id}/preview?page=1`，使用已有PDFBox逐页渲染同一原PDF为PNG，返回X-Page-Count，页码1起始、越界400、no-store/nosniff。仅管理员/客服可用，复用原正式报价和文件的鉴权、案件范围和下载审计。单页长边最多1800像素；前端可翻页，原始PDF及核损原文件仍受控下载，核损图片直接显示。

## D-054 原图及客服核对（2026-10-16）

报价上下文rawQuote增加originals（该提交版本关联原件的id/name/contentType，最多20份），通过既有受控下载加载。正式报价POST /formal-quotes新增可选reviewedLines，格式与网点lines相同；仅客服可写，验证当前raw版本/派单/状态，服务端计算核对后总额及加价，另存不可变quote_formal_review与审计。旧请求不带reviewedLines仍按网点原报价计算。formalQuote返回reviewedLines（有核对版本时），网点/车主不返回客服核对数据，FORMAL历史沿用分页和权限。新增V20迁移，旧迁移不改。

## D-053 网点资料接收（2026-10-16）

`GET /work-orders/{id}/workflow`的materials新增`shopUploaded`布尔字段：只统计当前派单有效材料，且上传审计角色包含REPAIR_SHOP。原present/satisfied含义及接口权限保持不变，不新增写接口。客服等待网点报价时每8秒读取workflow（后台页暂停），真实节点变化后重新加载案件；上传文件本身不推进，网点提交报价仍由既有报价接口完成。开发模式只读预览不调用写接口，生产不开放，后端门禁始终有效。

## D-052 城市与Mock地图（2026-10-16）

`GET /maps/cities?query=&page=1&size=20`仅返回启用的二级城市，名称字面包含搜索，按名称/id稳定分页；query不超过100字符，size沿用上限100。`GET /maps/cases/{id}/shops`保留分页/案件范围，新增address、distanceMetres并保留mockLocation坐标，候选顺序来自eligible-shops的全局距离排序。所有maps接口允许ADMIN/CUSTOMER_SERVICE，网点/车主仍拒绝，案件数据范围不变。定位沿用GET /maps/geocode，保存城市/接车地址沿用PUT dispatch-location的版本及confirmed检查，所有写入仍审计。地图读取不推进状态，无数据库结构变更。深圳10店仅本地管理配置，地址含模拟定位说明，当前地图及距离均为Mock。

## D-049 三项修正（2026-10-15，优先于以下旧描述）

接口结构不变。导入成功后前端立即调用既有`POST /work-orders/text/extract`并合并结果。`surveyorName`直接预填，明确标签优先聊天昵称，证据不再标记姓名需确认；前端不再单独要求姓名候选/冲突选择，已有手改姓名不覆盖。正式提交的`INTAKE_INCOMPLETE`不再包含vehicleColor、vehicleBrand、vehicleModel；V19移除数据库正式身份约束与正式报价抬头中的品牌/车型必填，旧迁移不改，PDF缺失字段留空。其他必填和照片门禁不变，稀疏草稿仍可保存，权限不变。

依据D-046 / CASE_WORKFLOW_REDESIGN V3。第一批提供服务端约束和只读流程快照；第二批增加页面及识别链路、V17追加迁移，V1—V16不修改。

## 流程快照

`GET /api/v1/work-orders/{id}/workflow`，统一 `ApiResponse.data`，仅ADMIN/CUSTOMER_SERVICE；沿用既有工单数据范围，客服不能读其他客服草稿，网点/车主及未登录访问拒绝。读取使用只读REPEATABLE_READ事务，各项证据来自同一数据库快照，不创建报价上下文、不写状态/审计。

| 字段 | 含义 |
| --- | --- |
| caseId / status | 当前案件和真实状态 |
| workOrderVersion / quotationVersion / basisVersion | 服务端快照依据版本；写入仍按各原接口的期望版本与幂等键校验 |
| currentNode | CREATE、DISPATCH_ARRIVAL、SHOP_QUOTE、QUOTE_REVIEW、INSURER_ASSESSMENT、REPAIR、RECEIPT、FUNDS、COMPLETE；未知历史语义返回UNKNOWN |
| actorRole / action | 当前主任务所需角色和动作，等待网点时仍明确任务对象；NONE表示终态或未知状态无推进动作 |
| canAct | 当前账号有主任务办理权限，决定绿/黄，不取决于资料是否齐备 |
| canContinue / missing | 已保存证据是否齐备及中文缺失项；不包含尚未提交表单值的校验结果，不是写接口授权 |
| nodes | 固定9个节点，不是可分页业务列表；key、label、state、completedAt |
| materials | 当前已开放或有有效历史材料的有限分类集合；不枚举文件对象或返回姓名、手机号、内部金额 |
| legacyNeedsReview | 未支持的历史状态或缺少当前报价依据等情况提示，不补造历史或批量回退案件 |

节点state：DONE、CURRENT、WAITING、LOCKED、STOPPED、TERMINATED、UNRECORDED。CURRENT即轮到当前账号办理，缺材料也保持绿色；WAITING黄色；LOCKED灰色。节点不可点击。completedAt为真实历史/版本时间，无证据则null；重复派单不覆盖最初建单时间，撤回收车不覆盖原完工时间。

材料字段：category、required、present、satisfied、exceptionAccepted、canUpload。present只代表有效上传（绿勾）；satisfied另校验可用证据。完工照必须本网点/本次派单实际上传的JPG/PNG，客服上传照片不能使其满足。通知单缺失但已有原因时present=false、exceptionAccepted=true，仍显示文件红叉，但不阻塞报价审核。可选材料缺失不阻塞。

“继续”不能仅按canContinue隐藏或禁用：当前账号可办理时允许点击，合并当前输入与缺失项提示；真正提交时原写接口重新检查角色、状态、数据范围、版本和前置证据。滚动、读取接口、查看历史均不推进节点。

## 新写入门禁

| 原接口 | 新增条件 / 错误码 |
| --- | --- |
| POST /quotations/cases/{id}/assessments | 须有本案当前网点/派单的当前正式报价，否则FORMAL_QUOTE_REQUIRED（409） |
| POST /quotations/cases/{id}/confirmations/INSURER | 当前正式报价、对应核损金额、有效核损单齐备；缺项分别FORMAL_QUOTE_REQUIRED、ASSESSMENT_REQUIRED、LOSS_ASSESSMENT_FILE_REQUIRED（409） |
| POST /quotations/cases/{id}/confirmations/CUSTOMER_SERVICE | 同上，且保险已确认；否则INSURER_CONFIRMATION_REQUIRED（409） |
| POST /materials/cases/{id} | 不到适用阶段或当前报价未完成拒绝MATERIAL_STAGE_CONFLICT（409）；原角色、格式/大小、版本、幂等、冻证据校验保留 |

原始/正式报价新增版本后，清除当前核损指针和两项确认/授权，旧quote_assessment行、金额和审计不删除。须在新正式报价基础上记录核损新版本，不能重新复用旧确认。开修接口继续在锁定事务中重新核验原四项条件，不把回款、评级或OCR成功作为第五项条件；已有合法开修/完工记录不自动回退。

材料首次开放：NOTICE建单起；ARRIVAL_PHOTO已接单待到店起；SHOP_QUOTE到店后；ASSESSMENT/LOSS_ASSESSMENT须有当前正式报价且尚未开修；PROGRESS_PHOTO/COMPLETION_PHOTO仅维修中。前序补传受冻结/终态规则限制，COMPLETED/CANCELLED/CLOSED均不开放常规材料写入。上传重放仍先核对幂等记录，不因已合法推进到下一步骤而重复存储或记审计。

## 文字与识别采用接口

| 接口 | 条件和结果 |
| --- | --- |
| POST /work-orders/text/extract | 仅客服；粘贴/UTF-8 TXT原文，1—50000字符、最多1000行、拒绝NUL；标签候选冲突不自动取最后值，区域名称不猜ID；不外呼OCR、不建工单。 |
| POST /work-orders/text/draft | text、confirmed=true、draft及Idempotency-Key；走原草稿校验服务，原文/程序候选/当次采用值不可变保存，不自动正式提交；同键不同正文409。 |
| GET /work-orders/{id}/text-source | 仅管理员/客服且案件可见；回看原文和当次采用快照，后续修正不覆盖源记录；无文字来源返回null。 |
| POST /ocr、任务读取/复核/确认 | 网点仅本网点本次已接派单的SHOP_QUOTE，客服沿用文档能力，管理员只读；未来阶段、冻结/无效材料拒绝。SUCCEEDED可核对，FAILED须新增人工修订并originalReviewed=true才可确认；失败状态不改成功。 |
| POST /quotations/cases/{id}/recognized-quote | jobId、revision、quote（原Raw请求结构），只允许当前网点；原件ACTIVE且为本案SHOP_QUOTE、确认修订与采用lines完全一致，同事务写原始报价和来源关联。 |
| POST /quotations/cases/{id}/recognized-assessment | jobId、revision、assessment（原Assessment请求结构），只允许客服；原件为本案有效LOSS_ASSESSMENT，采用金额与已确认修订完全一致，仍要求当前正式报价。 |
| GET /quotations/cases/{id}/sources/{business} | 沿用报价可见范围；返回当次原件ID、确认修订、原始程序候选及提交时的供应商/成功或失败状态；不混用后续任务修订。网点白名单只含原始报价相关字段，不返回加价/利润等额外字段。同网点本次派单已提交报价的历史原件可只读回看，作废/替换的原件不可用于新识别或采用；旧无来源版本返回null。 |

识别采用请求有独立幂等记录，包含job/revision及业务正文的摘要；同键同正文重放原结果，同键不同依据409。OCR复核/确认/采用统一先锁案件再锁任务，原文件变更不能与采用竞争成功。原报价/核损接口保留兼容能力并遵守新阶段门禁，新页面采用关联接口；不为旧记录伪造识别历史，不把OCR成功加入开修第五项条件。

列表额外返回workflowNode、workflowActor，以单次批量证据查询生成，不逐行请求流程API。未开修核损/授权记录缺当前正式报价时显示QUOTE_REVIEW或SHOP_QUOTE，真实status保留；已开修、收车和完成记录不自动回退。

## 验证结果入口

回归覆盖越步拒绝且不落核损/确认记录、不保存未来材料、确认顺序、单条件缺失不能开修、版本变更、管理员只读、他人草稿拒绝、外部角色拒绝、完工证据归属。真实数据库/MinIO隔离验证与日志路径见PROGRESS；SQL聚合只返回本案件有限分类/状态，文件版本和历史明细仍走原有分页接口。

## D-047 建单与派单补充（2026-10-15）

追加V18，旧迁移不改。草稿写入/详情新增 surveyorName、surveyorPhone、vehicleColor；dispatchAddressConfirmed由服务端维护，不接受草稿自行赋值。查勘员电话只向管理端角色返回。正式提交缺失任一指定信息（牌照/VIN至少其一）或SCENE_PHOTO时返回409 INTAKE_INCOMPLETE，包含中文缺失清单；不要求保险公司、车主姓名、事故时间、区域/地址。照片沿用鉴权/版本/幂等上传，JPG/PNG、10MiB、每案20张；网点和车主不能上传/读取该类别，不创建OCR任务。

文字提取/复核建草稿允许ADMIN和CUSTOMER_SERVICE，原文范围不变；按受损方标签提取，标的车牌按D-048例外采用，标的/司机电话不采为车主电话。独立长编号为报案号候选，昵称为requiresConfirmation候选；证据包含原文行号，多值不自动选择。浏览器文件夹导入只读取TXT文字，图片单独通过材料接口关联同一草稿；部分成功重试沿用每张照片的幂等键，不重传成功项。

## D-048 号牌与提交恢复（2026-10-15）

不改变API字段或新增迁移。`vehiclePlate`/`vehicleVin`仍分别存储，正式提交至少一项；仅有车牌且结构明显无效时409 `INTAKE_INCOMPLETE`，草稿仍可保存。`/work-orders/text/extract`返回`CASE_TEXT_PARTY_LABELS_V2`：新增标的车牌（可无冒号），规范化全角/空格/分隔符，特殊地方号牌保留候选并警示人工核对；明确的查勘员手机号直接预填，昵称仍须确认。

前端单个车辆编号输入映射上述二选一字段，单个查勘员输入映射姓名/电话。正式提交失败后读取`GET /work-orders/{id}`；已是正式状态则使用已保存结果，不再提交。真正仍为草稿时显示原错误，重试复用草稿ID和提交幂等键；照片失败独立提示。保存后的前端路由异常单独处理，必要时使用详情地址重新加载。

格式参考：[北京市新旧电动自行车号牌并行说明](https://www.beijing.gov.cn/zhengce/zcjd/202606/t20260606_4688955.html)、[上海交警新能源号牌说明](https://www.shanghai.gov.cn/nw5827/20200905/0001-5827_1179681.html)。仅做结构兼容，不代替登记核验，不能用单一全国电动自行车正则阻止所有地方格式。

PUT /api/v1/work-orders/{id}/dispatch-location 请求 expectedVersion、regionId、address、confirmed=true；仅ADMIN/CUSTOMER_SERVICE，派单/到店异常处理阶段；已到店仅ADMIN可在异常改派时核对，其他阶段拒绝；版本冲突409，成功审计不记录完整地址。派单未经核对地址返回409 DISPATCH_ADDRESS_REQUIRED。GET eligible-shops在确认后使用现有本地Mock编码与直线距离，分批读取服务区域候选，先全局按距离/网点ID稳定排序再分页，缺地址网点距离为null排末。响应新增distanceMetres；不调用真实地图、不自动选店。Mock全局排名仅用于本轮本地模拟；未来真实服务需缓存/批量编码，不应逐网点外呼。

报价审核用SCENE_PHOTO代替NOTICE/缺失原因门禁，缺失返回409 MISSING_SCENE_PHOTO。旧通知单和原因保留。正式报价抬头保险公司/车主姓名允许空值，原快照不回写；PDF空值显示为空，避免编造资料。


### D-056 资金节点
POST /funds/cases/{id}/confirmations，Idempotency-Key；请求 {expectedVersion,direction: RECEIVE|PAY}。仅客服；当前收车有效、人工目标已定且净流水等于目标；PAY还须当前网点派单及先前到账确认有效。返回资金视图（flowStage、receiveConfirmed、payConfirmed、canConfirmReceive、canConfirmPay），仅员工可见。收车进入WAITING_INSURER_PAYMENT，到账确认进入WAITING_SHOP_SETTLEMENT，结算确认进入COMPLETED。修改依据自动重新打开资金步骤，幂等与版本冲突沿用资金协议。


### D-057 资金页面使用约定
无新增API。核损原件/最终采用金额复用GET quotations/cases/{id}与受控materials下载、PDF预览；仅员工页面请求。最终确认金额须sourceCurrent和客服确认均有效。方向由flowStage推导，不暴露选择框；备注可空，时间默认Asia/Shanghai当前日期时刻。targets.reason仍必填，由正常办理页面提供当前步骤操作说明；冲销reason仍人工必填。资金金额精度、幂等、版本和顺序检查不变。
