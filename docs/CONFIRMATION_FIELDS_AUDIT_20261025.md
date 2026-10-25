# 额外确认字段排查（2026-10-25）

## 用户选择与实施结果

用户随后明确要求：“删除除了A4以外的A类所有，bc类不要动”。已移除A1、A2、A3、A5～A10共9项确认复选框及其页面勾选状态；点击各自原有保存/提交/确认按钮时，仍发送原接口确认标识、保存来源快照并执行原有审计。A2管理端/H5识别组件同步；A4的金额输入及精确一致校验保持。B类与C类操作保留。

已完成两端lint、类型检查、测试与构建：管理端196项测试、H5 42项测试全部通过，无失败/取消/跳过；运行中页面、数据库集成及真实供应商调用未验证，未变更后端或数据库。具体命令与边界见 `PROGRESS.md` 的本次实施记录。下文表格保留排查时的原始行为，供对照编号。

## 范围与验收

- 用户要求：先找出类似截图“已核对原文与采用信息”的额外确认字段和操作，列明作用；用户指定哪些删除、哪些保留之后再实现。
- 本轮仅静态阅读管理端、H5、相关服务端与路由；未修改业务代码、接口、数据库或已确认业务规则，未操作运行中案件或调用地图/OCR。
- “删除”尚未成为逐项决策；下列编号供用户选择。复核事实、业务状态与来源审计是否保留，也须在具体实施范围中明确。

## A. 勾选框与重复输入字段

| 编号 | 页面/环节 | 当前字段原文 | 当前影响 | 代码位置 |
| --- | --- | --- | --- | --- |
| A1 | 新建工单：文字提取/文字文件导入 | 已核对原文与采用信息 | 有原文时必须提取、处理冲突/歧义并勾选；同时阻止保存草稿和提交正式工单。更改原文会清空确认。 | `admin-web/src/features/workorders/TextFormIntake.vue`；父级 `WorkOrdersView.vue` |
| A2 | 核损信息：核损原件抽屉 | 已核对原件与采用信息 | 不勾选不能通过“继续”保存本次核损采用信息；OCR成功或失败后人工补录均受此限制。 | `admin-web/src/features/materials/DocumentRecognition.vue`，由 `quotation/QuotationPanel.vue` 调用 |
| A3 | 开修前：最终价格核对 | 已核对正式报价单、核损单和最终金额 | 不勾选无法完成客服确认。 | `admin-web/src/features/quotation/RepairAuthorizationReview.vue` |
| A4 | 开修前：最终价格核对 | 确认授权金额（元） | 需要再次手输金额，且必须等于已保存的最终核损采用金额；这是金额输入框，不是勾选框。 | 同 A3 |
| A5 | 派单：人工核对或更正事故区县 | 已人工核实事故事实；不是根据停车位置或目标网点推断 | 选择事故区县并填写原因后，仍须勾选才能保存区县。 | `admin-web/src/features/workorders/AccidentDistrictReview.vue` |
| A6 | 派单：原人工派单/Mock人工派单 | 已人工核实当前停放位置，不修改事故地址/区县 | 停车区县、地址齐全仍须勾选才能保存停车位置；编辑位置后确认失效。 | `admin-web/src/features/workorders/ManualDispatchChoice.vue`；真实模式折叠入口和Mock模式共用 |
| A7 | 派单：登记本案临时门店 | 已核实该门店可维修两轮电动自行车、愿意处理本案，名称和地址正确且可用于本案登记。 | 名称、地址齐全仍须勾选才能登记并选择门店；包含实际维修能力和接案意愿声明。 | `admin-web/src/features/materials/PublicWorkshopPanel.vue` |
| A8 | 案件资料：邀请本案车主开通账号 | 已通过已核实联系方式核实本人和本案归属（非系统身份验证） | 必须勾选并填写核实说明，才能生成或重发邀请。 | `admin-web/src/features/account/OwnerInvitation.vue` |
| A9 | 管理员：网点地图设置 | 我已核对坐标系为GCJ-02及经纬度顺序；真实资料须来自自有可信来源，测试资料另勾选合成 | 必须勾选才能保存网点位置版本。 | `admin-web/src/features/materials/ShopMapSettings.vue` |
| A10 | 价格库：Excel/CSV导入 | 我已检查预览，确认提交此批次 | 预览完成后仍须勾选，才能点击确认整批导入/记录失败批次。 | `admin-web/src/features/pricing/ImportView.vue` |

## B. 形式不同但会额外要求确认的操作

| 编号 | 页面/环节 | 当前操作 | 影响与说明 | 代码位置 |
| --- | --- | --- | --- | --- |
| B1 | 独立手写单据建单 `/ocr/intake` | 保存人工修正 / 当前表单 → 确认当前候选快照 → 用已确认表单创建草稿 | 独立入口存在分步确认；表单修改后须先保存版本，未确认或未存草稿快照时不能创建草稿。 | `admin-web/src/features/materials/OcrReviewPanel.vue`、`OcrIntakeView.vue` |
| B2 | 派单：地图停车位置候选 | 确认停车位置/确认模拟停放位置 | 搜索或地图选点解析后的候选须再确认，才会建立确认位置并请求父级保存停车地址；选择哪个候选本身有业务含义。 | `admin-web/src/features/materials/ParkingLocationPanel.vue` |
| B3 | 派单：手动补充门店 | 确认门店地址 | 搜索地址候选后须确认该地址，供手动补充门店使用。 | `admin-web/src/features/materials/PublicWorkshopPanel.vue` |
| B4 | 派单：折叠的原合作网点推荐 | 确认计算道路距离/确认模拟计算 | 先估算候选，再点击才执行路线计算。现公开门店主路径已在查询后自动计算道路距离，不存在这一步重复确认。 | `admin-web/src/features/materials/ParkingLocationPanel.vue`；对照 `PublicWorkshopPanel.vue` 的 `action` |
| B5 | 建单文字提取及图片识别冲突处理 | 采用候选值/保留当前输入；采用原文/保留当前；采用识别字段/保留当前字段 | 不是全表核对勾选；用于解决同一字段不同值或歧义。存在未解决项会拦截保存/提交。图片组件当前无全表确认框，但该图片组件未被当前新建工单弹窗引用。 | `admin-web/src/features/workorders/TextFormIntake.vue`、`admin-web/src/features/materials/OcrFormIntake.vue` |
| B6 | 网点H5：OCR报价候选行 | 替换指定行的确认弹窗；添加同名项目的重复计费确认；忽略已采用候选时的移除确认 | 均为针对覆盖、重复或删除当前行的二次确认。最终“网点提交报价”已自动保存OCR人工复核，无全表额外确认框。 | `h5-web/src/features/quotation/QuoteOcrPanel.vue`、`QuotationPanel.vue` |
| B7 | 建单/派单/改派重复案件提示 | 疑似重复时填写继续原因；部分改派再弹窗确认继续 | 正式建单正常路径不另弹确认；只有服务端检测到疑似重复时触发，原因与重复处理受后端校验。 | `admin-web/src/features/workorders/intake-save.ts`、`WorkOrderDetailView.vue` |
| B8 | 到店前改派 | 请确认车辆尚未到店。 | 待到店/到店异常状态下，输入改派原因后还须确认车辆尚未到店。 | `admin-web/src/features/workorders/WorkOrderDetailView.vue` 的 `reassign` |
| B9 | 价格维护/历史归档 | 保存参考价格版本/创建新版本确认；历史归档确认 | 表单保存或归档前有确认弹窗，涉及价格版本与归档不可修改。 | `admin-web/src/features/pricing/PriceEditorView.vue`、`historical/HistoricalCasesView.vue` |

## C. 业务状态确认：一并列出，尚未授权改变规则

| 编号 | 当前按钮/记录 | 作用 | 代码位置 |
| --- | --- | --- | --- |
| C1 | 记录保险确认 | 客服线下沟通后记录保险公司确认，属于现有四项开修条件之一。 | `admin-web/src/features/quotation/QuotationPanel.vue` |
| C2 | 客服确认 | 记录客服对当前核损及报价依据的确认，属于现有四项开修条件之一；A3/A4是它之前的额外勾选和重复金额输入。 | 同 C1；服务端 `quotation/application/QuotationService.java` |
| C3 | 确认已收车 | 车主或客服代录实际收车，将流程推进至资金环节。 | 管理端与H5的 `features/repair/RepairPanel.vue` |
| C4 | 确认到账完成 | 资金流水保存后另行确认当前保险到账阶段。 | `admin-web/src/features/funds/FundsPanel.vue` |
| C5 | 确认结算并结案 | 网点结算记录满足要求后，确认该阶段并完成案件。 | 同 C4 |
| C6 | 确认有效行 / 重试失败行 | 资金导入预览后的实际处理动作，不是“已核对”的附加勾选框。 | `admin-web/src/features/funds/FundsImportPanel.vue` |

## 代码留存与排除项

- H5也保留了 `DocumentRecognition.vue`（含A2同名框），但全仓引用排查未发现它被H5页面引用；不能称为当前H5实际展示字段。
- H5 `MaterialsPanel.vue` 留有客服OCR候选确认按钮，但H5工单路由仅向 `REPAIR_SHOP`/`OWNER` 开放，该操作区只对客服显示；不能称为当前正常H5用户可操作入口。
- `OcrFormIntake.vue` 已采用“点击保存/提交即确认当前表单”的实现，但未发现当前页面引用；当前新建弹窗实际使用 `TextFormIntake.vue`，所以不能据图片组件推断截图问题已经消失。
- 照片选择、角色选择、车型选择、网点启用、合成测试坐标、模拟首次识别失败等复选框属于数据选择、设置或测试标识，不是“已核对”确认字段。
- 删除草稿/目录资料/适配关系、解除识别关联、取消工单时选择通知对象、重新发起可能计费的OCR识别等提示属于删除、通知选择或外部调用确认，未归入可直接随提交合并的字段；本轮没有修改。
- A1、A2、A3/A4、A5、A6、A7、A8、A9、A10等还涉及后端确认标识或对应业务校验。后续即使取消页面额外动作，也应按用户选定方案处理接口、版本、审计与状态校验，不能仅隐藏UI而使提交失败。

## 验证与下一步

执行了全仓相关复选框、确认文字、确认弹窗、导入引用和路由的静态检索，并阅读了关键前后端调用链；仅证明当前工作区源码行为，未声称已完成运行中页面验收。未运行测试、lint、类型检查、构建、数据库迁移或供应商调用；本轮无功能改动，无需以测试通过代替范围确认。

以上为首次排查阶段的验证记录；随后用户已选定实施范围，结果见本文顶部及 `PROGRESS.md`。本次实施完成后停止，不扩展修改B类或C类业务流程。
