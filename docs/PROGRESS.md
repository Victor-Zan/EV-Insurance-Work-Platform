## 2026-10-22 纯手工建单核查与重试恢复（独立功能分支）

- 基于远程main `f3e3357a03dbc298a9ee6bfda06e082e1fd44fa8`（已含PR #7拖拽上传），独立工作区/分支 `manual-case-creation-fix` / `codex/manual-case-creation-fix`。交付前显式fetch main仍为该提交；默认fetch因配置引用已删除的旧网点OCR分支失败，未修改共享fetch配置或覆盖工作区。作者沿用本机Sherry070719。
- **严格原投诉未复现**：修复前实际浏览器全新表单，不输入原文、不提取、不OCR，纯手填保存草稿和上传合成照片后正式建单均成功；不能宣称找到了原用户环境的统一根因。原本机5194→18089为不同旧预览，本轮保留，未将独立测试站冒充已更新的当前网站。
- **相关缺陷已复现并修复**：原手工草稿POST忽略Idempotency-Key，同体/同键重放生成两个草稿。现复用既有命令表/请求摘要及事务锁，同操作者同体同键返回同一案件，改体同键409，4并发只创建1条；创建/审计/命令保持事务一致。旧无头客户端兼容，当前管理端每次尝试稳定复用键。无迁移变更。
- 前端保留首次未知响应的请求体，恢复同一草稿ID后应用后续修改；上传/正式提交失败保留表单、照片和已知ID，先读状态再重试。新增持续缺项说明与明确取消文字导入，迟到的文件读取/提取/候选响应不再回填已取消资料；实际未处理冲突仍阻止保存。权限、必填、至少1张有效现场照片、重复检测、状态机、OCR来源与确认均未放宽或伪造。
- 最终审查补齐原有显式删除草稿兼容：权限/状态校验后，同事务清理该草稿CREATE_DRAFT命令再删除，审计保留；不让新外键引用阻塞既有功能。新增删除及越权拒绝回归。补充构建首次因Windows运行中JAR占用而repackage失败，改用新backend-final-build目录完整重跑，于2026-10-22 22:30通过78+37测试与构建，未跳过检查。
- 实际独立前端5210→后端18105，schema `manual_case_20261022_204936`，仅合成资料。修复后纯手填草稿DRAFT/v0；全新纯手填正式工单 `EVR-20261022-000002`（ID `869bdaaf-8f25-40b6-a531-afdfb76e484d`）为PENDING_DISPATCH/v1/MANUAL，有效照片1张。后端detail/source/materials读取均200，SQL复核案件1条、创建/提交审计各1条、文字来源0、OCR任务0。同键实际接口重放200/同ID，改体409/IDEMPOTENCY_CONFLICT。该工单仅在本轮schema，未碰旧高德同编号案例。

| 本轮实际重新执行 | 结果 |
| --- | --- |
| 后端 `mvnw.cmd -B -ntp -Dvalidation.build.directory=C:/Users/Sherr/Documents/Codex/manual-case-runtime/backend-final-build -Dit.test=WorkOrderPostgresIT,OcrIntakePostgresIT,IdentityPostgresIT verify -Ppostgres-it` | BUILD SUCCESS；78单元+37选定PostgreSQL/MinIO集成测试，失败/错误/跳过0；完成Maven构建，不冒称全部领域IT |
| 管理端 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 全部通过，157测试，失败/跳过0；包含新增实际建单组件与取消/恢复回归 |
| H5相同四条命令 | 全部通过，42测试，失败/跳过0；H5源码未修改 |
| Flyway | 新schema完整迁移；修复后JAR在本轮schema再次启动校验28资源/当前V26，无待执行迁移；所有旧schema/迁移文件保留，无repair/清库 |
| 浏览器与读取核对 | 真实本地后端及私有存储：手工草稿、必要照片、正式提交、详情跳转、具体缺项、实际文字冲突及取消保留手填均通过；后端读取确认唯一性及审计 |
| 安全/未验证 | 百度/高德真实调用0，未加载真实供应商凭据/改变预算；未复现原报错站点，浏览器断网/上传故障注入未做（恢复由组件测试+实际接口重放覆盖），实体手机/其他浏览器及Compose本轮未重跑，无生产部署 |

详细规则/API兼容、复现条件和证据见[MANUAL_CASE_CREATION.md](MANUAL_CASE_CREATION.md)。仓库外manual-case-runtime保存实际截图6张、acceptance.html、脱敏final-readback及本轮日志；不提交原始日志、凭据或真实材料。运行包SHA256及源码基线见该说明，当前是独立测试实例，未替换旧网站。

2026-10-23交付补记：实现提交 `9900f7bc307a4074a8dab900f397b73fe4be9440` 已正常推送。只重启本轮18105隔离后端，最终JAR再次Flyway校验28资源/当前V26/无需迁移；实际接口返回buildCommit=9900f7b、Provider=MOCK_V1，5210前端请求18105目标匹配。已有合成工单只读核对仍为PENDING_DISPATCH/v1/MANUAL/有效照片1张/无文字来源。旧5194/18089实例、数据库与真实外呼台账保留，未新建或推进其他案件。

- 分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/codex/manual-case-creation-fix
- GitHub集成仅尝试一次创建Draft PR，返回 `403 Resource not accessible by integration`（FORBIDDEN）。未创建PR、未绕过或重复尝试，未合并/部署。可复制PR说明在仓库外manual-case-runtime/PR_DESCRIPTION.md。
- 有权限人员手动创建Draft PR：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...codex/manual-case-creation-fix?expand=1 ，base=main，compare=codex/manual-case-creation-fix。该交付状态随后以文档提交推送原分支；不是PR已合并。

## 2026-10-22 现有上传入口拖拽交互增强（独立功能分支）

- 从最新远程main `da081bdd3ddfc796a8b12182aeb25578d94a4f4c` 创建独立工作区/分支 `codex/file-drop-import`；原高德工作区仍干净，既有运行网站、数据库和真实调用台账未触动。该main已包含PR #6，下面早期“尚未合并”的记录仅代表各自历史时点。
- 覆盖管理端建单TXT、ZIP、两处照片选择、可写案件材料、核损原件、单据建单、价格库、资金对账、历史订单导入；H5网点材料、手写报价清单。保留识别组件同步增强，但未新增页面/角色入口。逐项限制、准确处理函数、未覆盖项见[FILE_DROP_IMPORT.md](FILE_DROP_IMPORT.md)。
- 两端使用同样的轻量FileDropZone及文件传输辅助器（独立构建各自注册，测试保证内容一致）；原input保留，拖入分发一次原生change到同一处理函数。点击/拖入共用类型、空文件、大小及单多选校验；批次错误列明文件名，保留已有内容。原累计数量、服务端校验、权限、预览、移除、重试、版本与幂等仍适用。文件列表显示字节大小、待处理/处理中及原入口结果，不将选择误报成上传成功。
- 只拦截Files拖入导航，普通文字拖动不拦截；嵌套计数避免闪烁，离开/释放/Escape/失焦/拖拽结束复位；锁定和上传中不能绕过原input禁用。键盘按钮、原文件选择器及H5点击选择保留。
- 文件夹拖入明确拒绝，不遍历、不扁平化；原webkitdirectory选择保留相对路径及既有解析。ZIP只复用原readChatZip，未新增解压或业务流程。H5资金导入仅有不可达的客服条件组件，本轮未给H5角色新增入口。
- 不增加收费调用：需手动点击上传/解析的入口仍需原按钮；原“选择即识别”的报价和保留OCR组件在拖拽区明确提示相同行为与可能费用。未改OCR Provider、预算、权限或确认方式。

| 本轮实际重新执行 | 结果 |
| --- | --- |
| 管理端 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 全部通过；143测试，失败/错误/跳过0（含新增10项拖拽/传输测试及原ZIP、照片、OCR、权限、业务回归） |
| H5 同四条命令 | 全部通过；42测试，失败/错误/跳过0（含新增10项及原报价/OCR/权限回归） |
| 浏览器离线验收 | 实际TextFormIntake、ScenePhotoIntake、QuoteOcrPanel、ImportView＋合成文件＋模拟HTTP；批量照片预览、取消高亮、非法文件保留原件、锁定拦截、H5失败保留手填及原按钮重试通过；原生文件选择器选CSV后不自动解析通过 |
| 安全/协作 | 无后端/迁移/配置/依赖清单修改；真实百度/高德请求0，无凭据读取，无数据库或正式案件修改；git diff --check通过 |
| 未验证 | 操作系统文件管理器原生拖放、Edge/Firefox/Safari、实体手机/拍照、真实后端端到端；浏览器拖入为合成DragEvent、H5为390px容器，未冒充这些验收。后端/Flyway/Compose本轮未重跑，因为没有相关改动 |

验证日志在仓库外 `file-drop-runtime/final-{admin-web,h5-web}-{lint,typecheck,test,build}.log`，实际截图7张和可查看报告在同目录screenshots、acceptance.html。初次依赖安装遇沙箱DNS失败后成功补齐；初轮回归发现H5识别叶组件一致性断言失败，已同步同一交互并完整重跑通过，未删/跳过/放宽测试。没有重启、替换或部署当前网站。

提交身份沿用本机Sherry070719；实现提交 `a4987f2`，主题 `feat(upload): support file drag and drop in existing upload flows`，已正常推送 `codex/file-drop-import`。GitHub集成仅尝试一次创建Draft PR，返回 `403 Resource not accessible by integration`（FORBIDDEN）；未创建PR，未换渠道/凭据绕过或反复尝试。不合并、不部署。此交付记录随后以文档提交推送原功能分支。

- 分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/codex/file-drop-import
- 有权限人员手动创建Draft PR：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...codex/file-drop-import?expand=1
- base为main，compare为codex/file-drop-import；可复制说明保存在仓库外file-drop-runtime/PR_DESCRIPTION.md。当前现有网站未切换版本，需审查和后续部署安排后才会看到本分支界面。

## 2026-10-22 PR #6 冲突整合（本地合入 main，不是 PR 合并）

- 核实 PR #6：源分支 codex/amap-main-integration、原提交540eb82，目标main e50c114；PR open、merged=false。初始工作区干净，沿用本机提交身份。仅将最新main正常合入原功能分支，保留双方提交历史，不新建PR、不合并PR、不强推。
- 唯一文本冲突是本文件首部：双方在相同位置分别追加高德验收和ZIP/统一登录进度。逐段保留两侧完整记录，下文“历史执行记录”中0次、未执行和403均是当时状态；最新真实结果以13时完成记录及AMAP_LIVE_ACCEPTANCE_20261022为准。现已有PR #6，早期“尚无PR/需手动创建”不代表当前状态。
- main新增ZIP、文字/图片导入、累计照片校验、统一登录错误提示及启动器检查原样保留；自动合并的需求同时保留地图与ZIP规则。共享建单/派单、角色及事故区县/停车独立规则未放宽。最新main迁移到V24，本分支V25/V26无撞号；不改任何既有迁移、旧库或执行历史。
- 本轮首次完整后端回归78单元通过，134集成中1失败：IdentityPostgresIT的完整迁移清单漏列V26，实际迁移没有失败。仅补齐该明确版本断言，保留所有既有身份、权限和业务断言；不修改迁移。完整重跑于18:08:29 BUILD SUCCESS，78单元＋134集成、失败/错误/跳过0。首次失败日志与第二轮报告均保留，未把历史结果记作本次通过。
- main的ZIP、建单页面、共享HTTP和启动器与远程原文一致；本轮额外手工修改仅进度、PR说明、常驻运行边界和上述测试清单。常驻启动器target=24不等于隔离V26，未提高target、升级旧库或替换JAR。双方原PROGRESS的466/458段全部保留；24份main迁移blob一致、26个正式迁移版本唯一，隔离验收库V23—V26执行校验值与源码相符。
- 高德与百度新增真实调用均0，原权威run仍关闭，原槽7次＋追加2次的attempted_at及旧库迁移历史在前后只读快照中完全一致；新业务schema无新预算run。EVR-20261022-000001仍网点2/PENDING_ACCEPTANCE/工单版本5，未修改或重做验收。当前网站没有重启或更新，历史真实验收不冒充本次浏览器回归。

| 本轮重新执行的检查 | 结果与证据 |
| --- | --- |
| 后端完整验证 | `backend`下`mvnw.cmd -B -ntp -Dvalidation.build.directory=C:/Users/Sherr/Documents/Codex/amap-main-runtime/pr6-backend-build- clean verify -Ppostgres-it`；78单元＋134 PostgreSQL/MinIO集成，全部通过，跳过0；包含共享建单/派单、四角色、账号开通、OCR、报价及地图回归。第二轮Maven记录37:11含中断恢复，不作性能指标；报告位于仓库外pr6-backend-build-，日志pr6-backend-verify-final.log（原文件pr6-backend-verify-.log） |
| Flyway | 完整新随机schema迁移/validate、V24→V26保留数据且无默认预算、再次migrate为0均通过；日志校验28迁移资源。旧V17、本地旧V23和最终V26库仅只读核对，没有repair、清库或修改历史 |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm test`、`npm run build -- --outDir C:/Users/Sherr/Documents/Codex/amap-main-runtime/pr6-admin-web-build`全部通过；133测试、失败/跳过0，构建1.40秒；覆盖ZIP安全/等价/累计照片、统一登录、停车隔离、人工改选及原派单适配器 |
| H5 | 同四项命令，outDir为仓库外pr6-h5-web-build；32测试、失败/跳过0，构建1.26秒。两端产物不替换现有dist或运行网站 |
| 依赖及启动脚本 | 首次`npm ci --ignore-scripts --no-audit --no-fund`因现有预览锁住native文件EPERM失败，未终止该进程；`npm install --ignore-scripts --no-audit --no-fund`补齐成功，package-lock仍与main一致。启动器PowerShell语法解析错误0，未执行Start/Watch |
| 安全与文档 | `git diff --check`、冲突标记/未解决索引检查、暂存私密值扫描通过；无私密配置、图片、全文或数据库备份进入提交。每侧原进度段落全保留，无单边覆盖 |
| 未验证与后续 | 本轮无Docker CLI，Compose未验证；未新增浏览器端到端或真实接口验收。正常合并提交仅推送原功能分支以更新PR #6；PR是否无冲突、检查和审批须分别读取GitHub最新结果，不能把本地合入main说成PR已合入main。仍需正式代码审查、仓库要求检查和人工审批；本轮不合并PR或部署 |

详细本轮日志、前后只读快照均在仓库外amap-main-runtime的pr6-*文件，未提交私密运行资料。下文各日期/时刻记录保留原貌，均是对应时点的状态。

## 2026-10-22 13时 三目的地真实驾车排序与合成案件派单完成（未合并main，外呼关闭）

- 最终事实/截图见[AMAP_LIVE_ACCEPTANCE_20261022](AMAP_LIVE_ACCEPTANCE_20261022.md)。代码380c854，四入口沿用确认方案。深大唯一粤海立德门、深技大唯一1号门；港中深逐条比对唯一匹配用户选定原候选1，未盲选首条；南科大追加解析唯一1号门由客服确认。
- 同南科大起点、车辆→网点、AMAP_WEB_V1真实、v5策略32主方案0：13:23返回深大13543m/1388s、港中深30001m/2584s、深技大50565m/3712s。完整排序深大→港中深→深技大，按shopId关联；当时页面公里13.54/30.00/50.56，精确米数为核对依据。
- 默认深大ID3，客服实际改选港中深ID2，13:23:57点原按钮正式派单，早于13:25:17到期。读库EVR-20261022-000001：shop_id2、PENDING_ACCEPTANCE、工单版本5、派单版本1/PENDING；派单审计ID87正确。原键重放仍1条派单/1条派单审计、路线3次。停车不改事故区县/地址，无学校或外部系统通知。
- 累计解析6（原4+用户追加2）、路线3、逆编码0，共9/10；仅逆编码1未用且不可挪用。原run8槽已用7、关联追加2条已用；原2条尝试时间不变，新库run0。13:24:55关闭原run，13:30:26仅本轮清单内后端以上限0再启动，旧库/包/Git与私密V25检查点保留；不因重启恢复额度。
- 实际截图保存在仓库外runtime：continuation-origin-confirmed.jpg、continuation-real-ranking.jpg、continuation-manual-selection.jpg、continuation-dispatch-success.jpg、continuation-three-results.png、continuation-formal-dispatch-history.jpg。路线已到期，仅历史验收证据。原始响应/坐标/凭据不提交；站内提示仅隔离测试账号流程。
- 380c854的78单元+51相关集成及Maven通过；前2cab477完整78+133通过记录保留，未混称新代码全量集成重跑。两端无改动，本轮未重复构建；无Docker/Compose未验证。V26新增与再次启动校验见最终报告。关键真实验收已完成，正式PR/代码审查/CI/审批待完成；不重试GitHub403、不合并main，不进入其他阶段。手动PR和复制说明见AMAP_PR_DESCRIPTION。
- 13:35关闭后API复核运行380c854/AMAP_WEB_V1/非Mock/callsEnabled=false，工单仍网点2/版本5/待接单/派单版本1；重复启动28迁移资源校验/无需迁移通过。交付前fetch发现main新增ZIP前端提交e50c114，无地图后端/组件/迁移变化及V25/V26撞号；本次未整合/覆盖队友成果，正式PR仍需核对最新main及相称前端回归，不称最新main已运行验收。

## 2026-10-22 原高德预算恢复验收（历史执行记录）

- 13:08用户确认港中深原候选1；只读后端返回港中深409/MAP_LOCATION_EXPIRED、南科大会话404/已清理，未补查。用户随后明确批准已用两个入口各追加一次解析。先fetch核实main仍94173d2、V26未占用；V26仅新增空的追加尝试表，旧台账/旧库结构不改，仍在原run锁下校验并占用，原2次不重置。新增配置默认关闭、无迁移授权种子。正在相称的后端验证，真实累计仍2/0/0。
- V26前已在仓库外保存私密最终V25 schema检查点（private-pre-v26-final-schema.dump），不输出/提交其内容。追加预算首轮验证78单元通过、32相关集成中1失败，原因仅为原测试最新迁移版本仍断言25；已将三处受影响的版本断言明确更新为26，保留所有权限、隐私和业务断言。最终命令clean verify -Ppostgres-it -Dit.test=MapRecommendationPostgresIT,MapMigrationPostgresIT,WorkOrderPostgresIT,AccountActivationPostgresIT,ShopQuoteOcrPostgresIT：78单元＋51相关集成、失败/错误/跳过0，BUILD SUCCESS（13:18:51，2分36秒）。本次选择性回归未冒称新代码已重跑全部集成；此前2cab477完整78＋133通过记录保留。新代码不改前端/H5或既有迁移。

- 实际部分结果见 [AMAP_LIVE_VALIDATION_20261022](AMAP_LIVE_VALIDATION_20261022.md)：修复2cab477已完整verify通过（78单元＋133集成，失败/错误/跳过0，12:06:31，9分23秒），仅替换清单内本轮隔离后端；运行2cab477、最终业务schema不变，预算服务直接使用原权威schema，无第二份预算。前端/H5/迁移未改，不重复其构建。
- 12:07:25原GEO_SUSTECH持久占用后真实调用，返回唯一南科大1号门兴趣点/440305；12:07:44客服页面确认并保存停车，工单版本2→3。事故区县/事故地址原样，MAP_LOCATION_CONFIRM及停车保存审计正确。12:08:02原GEO_CUHK占用后返回两个“龙翔大道2001号”候选（门址/门牌号、均440307），位置明显不同且均未明确东门；未自动选第一条，立即暂停原预算并集中询问用户核对。
- 累计解析2/4、逆编码0/1、路线0/3；深大/深技大解析未执行。没有路线、完整排序、默认推荐、人工改选或派单。EVR-20261022-000001仍PENDING_DISPATCH、版本3、shop_id空、派单0（无派单版本）；新库预算run0、推荐查询0、网点确认位置0。没有外部通知或真实人员操作。已用槽位保留，未重新查询/重试或按新进程恢复额度。
- 实际页面截图在仓库外runtime：resume-origin-confirmed.jpg（12:07:53）和resume-paused-origin.jpg（12:09:31），显示真实南科大候选、停车确认和预算关闭；无距离/派单后截图可提供，不以模拟图替代。原PR403不重复，手动创建链接及可复制说明见 [AMAP_PR_DESCRIPTION](AMAP_PR_DESCRIPTION.md)，关键验收缺失，保持Draft门禁，不合并main。
- 12:13:48结束本轮外呼并记录原run关闭时间；全部8槽及两条attempted_at原样保留，地理编码2、逆编码0、路线0，新库预算run0。保留运行进程中的候选至其正常短期到期，不为关闭预算而无谓重启；台账关闭持久拒绝所有外呼，开发重启默认上限0，不影响已保存案件/审计查看。新增可读实际视口截图resume-real-origin-viewport.png。用户确认入口前不推进；会话失效后若要重查已用入口须单独批准对应新增次数，不能重置原额度。

- 用户明确恢复本轮原未用4/1/3槽位，沿用EVR-20261022-000001及原四入口，不等待PR/main合并。外呼前只读确认原run关闭、8槽均未尝试，最终业务库预算run0；工单PENDING_DISPATCH/版本2/无派单，三个测试网点启用、南山区服务关系及车型1齐全，无学校账号或联系方式。
- 预检发现原台账在旧schema而V25业务库无预算；新增可信配置AMAP_BUDGET_SCHEMA直接限定原两张表，默认原行为不变，不复制run/slot、不升级旧业务库。另修复完整专有名“香港中文大学（深圳）”被省外词误拒绝，保留原文及其他省外标记/返回地域校验。新增跨schema共享额度/不可重置及专有名/省外冲突测试，正在完整后端verify；未改前端、H5或任何迁移。
- 首次完整verify的78单元通过，133集成中最后15个工单测试因PostgreSQL `too many clients already` 无法加载上下文（失败0、错误15、跳过0），日志backend-live-resume-verify.log保留。未降低测试要求；仅为本测试进程设SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3后完整重跑，独立日志backend-live-resume-verify-pool3.log。原台账仍关闭，真实尝试0。
- 真正启用/调用/关闭次数及验收结论以本节后续实际记录和结果报告为准，此处不提前宣称执行成功。GitHub既有403不再重试或绕过，不合并main。

## 2026-10-22 11时 高德main包含范围、真实验收与当前页面复核

- 完整核查见 [AMAP_STATUS_AUDIT_20261022](AMAP_STATUS_AUDIT_20261022.md)。远程main仍94173d2：基础地图代码已通过普通整合提交5076429进入main；本轮安全整合代码7cb469e不是main祖先，V25仍未进入main，不把两者混称“全部已合并”。GitHub全部PR核查无地图PR；本轮创建Draft PR再次返回403 `Resource not accessible by integration`，未换渠道绕过或重试。手动创建链接见报告。关键真实验收未完成，不能普通合并。
- 本轮真实调用0：旧权威run关闭，4地理编码/1逆编码/3路线槽均未尝试；新最终schema预算run0、推荐查询0、位置0。当前可执行授权0，不重置或复制预算。历史另一次深圳湾科技生态园解析1次/2候选与学校验收无关。
- 原本机预览及存储进程已停止；本轮恢复同一已验证隔离包（SHA256匹配）、原PG/MinIO，前端5194→后端18089、代码7cb469e、schema amap_main_final_20261022、AMAP_WEB_V1且外呼关闭。启动再次校验27迁移资源、无需迁移，V23/V24/V25成功；旧库/检查点保留。不是main发布，也没有升级旧库。
- 实际浏览器只读截图11:06:17/11:06:59保存在仓库外runtime的audit-current-full-page.jpg、audit-current-candidates.png：事故南山区与停车位置分开，三家虚构网点“距离未计算”。本轮仅输入未保存公开入口文字、展开列表；没有解析、查询、保存或派单。11:10:59只读SQL确认EVR-20261022-000001仍PENDING_DISPATCH、工单版本2、shop_id空、assignment记录0（无派单版本）；停车字段仍空。
- 四入口沿用用户已确认方案（南科大1号门、港中深东门、深大粤海立德门、深技大坪山1号门）。真实道路距离、耗时、排名、推荐、人工改选和正式派单均未执行，不填0/不编造结果或截图。学校不是合作维修商。
- 本轮无代码/迁移变化，未重复完整Maven/npm；沿用同一代码此前77单元+132集成、管理端124/H5 32测试及两端lint/typecheck/build通过的历史证据，本轮另做运行包/启动Flyway/只读DB/浏览器验收与git diff检查。Compose缺Docker仍未验证。下一步需要手动PR（或集成权限修复）及用户另行明确是否重开原剩余额度，完成真实验收/审查/审批后再合并。

## 2026-10-22 最新main整合与V25迁移（自动验证通过，真实预算保持关闭）

- GitHub交付：分支`codex/amap-main-integration`已正常推送，代码7cb469e、验证记录4066783。推送前再次fetch确认main仍94173d2，无V25新撞号。GitHub集成创建指向main的Draft PR返回`403 Resource not accessible by integration`；未用其他通道绕过、未重试创建或合并。可[手动创建Draft PR](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...codex/amap-main-integration?expand=1)。当前main仍未合入本轮整合，关键真实验收未执行也不满足合并条件。

- 最终代码提交`7cb469ed24529770157f585075a5db273ab6f1cd`，运行与迁移证据见[AMAP_MIGRATION_VALIDATION](AMAP_MIGRATION_VALIDATION.md)。新schema `amap_main_final_20261022`首次启动完整迁移、第二次启动27资源校验/无需迁移均通过；仅停止清单内本轮两个旧预览进程，旧V23/V17库、旧JAR、其他服务保留。
- 实际前端`http://127.0.0.1:5194`，后端`http://127.0.0.1:18089`，运行7cb469e、AMAP_WEB_V1、mock=false、callsEnabled=false。新库预算run/slot0；旧权威run仍关闭/真实尝试0。新schema不会重分配额度；本轮真实调用仍0/8，未执行真实道路距离及派单验收，不合并main。
- 浏览器以合成客服从统一登录进入现有派单页：分别展示事故区县与停车位置；人工核对南山区、明确原因/确认后保存，版本1→2且审计正确，事故地址未变、停车字段仍空。三个虚构网点在原人工列表明确“距离未计算”，定位/路线按钮禁用。合成案件a2a6e74d-ffdf-4301-b943-730de307c5bd保持PENDING_DISPATCH、assignment0；截图在仓库外runtime保留。并未把此页面核对记为真实地图/派单验收。

- 按用户新确认重新fetch：main 94173d2，V25未占用。普通合并main并保留其账号开通、OwnerInvitation、统一登录、H5、网点维护与权限成果。旧本地迁移在a6aa673及9701373保持可恢复，仅从最终目录撤下；main V23/V24内容原样。V25只增量ALTER已有地图模型并新增预算表，没有重复建区域/网点/地图基础表。
- 迁移对应和升级边界见AMAP_MIGRATION_PLAN。最终隔离运行只计划使用新schema amap_main_final_20261022，已只读确认创建前不存在。旧amap_main_validation_20261021/V23及amap_browser_20261015/V17均保留，旧本轮run关闭且真实尝试0。新库不分配预算，最终进程上限0，不能用新库/跨日/重启恢复额度。
- 新增MapMigrationPostgresIT，仅在新随机schema验证V24→V25保留旧位置/账号、旧推荐版本-1明确未知、来源互斥、新库无预算、再次migrate为0。首次合并验证记录11失败/1错误：原有角色测试发现管理员配置读取权限放宽（已恢复main边界），地图测试缺少main原有的每例限流隔离（已恢复），新迁移用例缺开发哈希占位（已补随机BCrypt）。未删除/跳过测试或放宽权限，backend-verify-merged-first-failed.log保留。正在完整重跑，不把首次失败写成成功。
- 两端合并版lint/typecheck/test/build已通过：管理端124测试、H5 32测试，失败/跳过0。后端完整clean verify -Ppostgres-it已通过：77单元＋132集成，失败/错误/跳过0，Maven成功（00:50:06，4分51秒）；含V24→V25新增用例、地图14集成、账号开通、四角色与事故区县/派单回归。Docker CLI不存在，Compose配置未验证。
- 真实地址解析0/4、逆编码0/1、路线0/3。四入口继续沿用用户已确认方案，但按最新指令不启用真实外呼，因此三路线排序/客服真实改选派单尚未验收，不满足普通合并条件。不把新隔离预览称为main已更新，不部署生产。

## 2026-10-22 高德整合续做：事故区县独立核对、远程新增迁移待确认

- 中断后核对本功能分支仍为codex/amap-main-integration，检查点a6aa673；原工作区/库保留。按用户七条确认新增独立事故区县入口和后端/API/前端回归，停车不改事故地址/区县；新增高德不可用时原人工选择入口，不伪造距离或扩大地图角色权限。
- 管理端本次lint/typecheck/119测试/build通过，失败/跳过0。后端完整verify -Ppostgres-it为76单元＋120集成、失败/跳过0，Maven成功（00:34:05，4分23秒）。普通沙箱一次中断及数据库连接池耗尽的一次失败日志已保留；仅测试进程限制连接池3后完整重跑成功，未跳过测试。H5前次27测试/lint/typecheck/build通过，本次H5源码未改。此处结果属旧基线检查点，最新main合并版另行重跑。
- 重新fetch发现默认refspec引用已删除的旧功能分支，未改共享Git配置，改为明确读取main引用成功。远程main已从d28d19e推进至94173d2，正常包含账号开通及原高德合并；新增V23__account_activation_login.sql、V24__amap_shop_recommendations.sql，与本地已执行V23撞号且地图表重复。尚未合并此新main，以免静默改迁移。
- 已只读核验PG：旧地图schema amap_browser_20261015/V17成功，本轮amap_main_validation_20261021/V23成功，均保留。run amap-three-gates-20261021 enabled=false、真实尝试0。本轮地理编码0/4、逆编码0/1、路线0/3，未因跨日重置。
- 用户已批准[AMAP_MIGRATION_PLAN](AMAP_MIGRATION_PLAN.md)：保留main迁移原样，旧本地V23留在提交/旧库，从最终目录撤下；新增V25增量并在全新schema验证，不repair/清库/升级旧库。操作前再次fetch确认main仍94173d2、V25未占用。真实高德预算继续关闭，不跨日/重启/新库重分配。旧基线测试不代表新main合并版。

## 2026-10-21 高德接入最新main：整合检查点（执行中）

- 基线重新fetch为main d28d19e，旧地图分支ecf2ddf保持干净；新工作区/分支codex/amap-main-integration，身份沿用Sherry070719。没有切换或重置队友工作区，没有合并其他未合并功能。GitHub相关/打开PR查询未发现已有本任务PR。
- 当前本机仅历史高德预览5187→18088运行，来源旧地图分支，schema amap_browser_20261015/V17；main文档里的5173/8080及其他电脑数据库不能当成本机现用服务。账号5190预览没有运行。新5194/18089将仅用于本轮独立整合验收，不提前称main已更新。
- 以最新DispatchLocation/工作流为基础增量接入，既有Mock实现提取为MockDispatchLocation保留，图片/导航/H5无业务变更。新增V23，旧V1—V22及旧地图V17均未修改，旧库保留。新增停车独立字段、共享持久预算、明确来源的dev短期位置会话及只读运行版本标识。需求/决策D-065和API说明先行更新。
- 用户集中确认四个入口：南科大1号门、港中深龙翔大道校区东门、深大粤海立德门、深技大坪山1号门；详见AMAP_VALIDATION。本轮授权4次地理编码/1次可选逆编码/3次指定方向路线，总8次，不含历史单次联通。至本检查点本轮真实请求0，预算尚未启用。
- 离线首轮76单元通过但XML中未转义比较符导致集成启动失败，已修复并保留backend-verify-first-failed.log。第二轮76单元及117集成中116项通过，1项旧测试依赖停车写事故区域；按本轮明确规则增加事故字段不被改写的断言，并将原排序/派单/报价断言完整保留到明确填写事故区县的独立测试中，未删除测试或放宽资格。正在完整重跑。
- 管理端首轮113测试通过；增加预算关闭和手工选择API回归后116测试通过，lint/typecheck/build通过；H5 lint/typecheck/27测试/build通过。后续必要重跑及真实验收以本节后续记录为准。无Docker CLI，Compose未验证。
- 2026-10-22用户确认独立“人工核对事故区县”入口及七条边界，已先更新需求/决策并实现：沿用ADMIN/客服案件权限与派单阶段，完整启用区县树分页、明确原因/核对、版本审计；停车与事故地址保持独立，不自动扩展城市关系；拒绝使当前已派网点失去服务资格的变更。新增后端集成与前端交互回归，正在运行完整验证。本轮合成案件仍预先明确南山区，不为匹配网点改事故事实。
- 代码审查已检查角色/案件/网点范围、外呼前持久计数、不可重置台账、provider无降级、短期供应商坐标不入库、查询采用前读取的案件版本以避免并发停车变化冒用新版依据，并追加内存过期清理。真实验收/PR/合并/本地main运行更新尚未完成，不能写成通过。

## 2026-10-22 新建案件聊天资料上传入口更新

- 用户先要求只出计划，确认后授权限定范围实施。本轮仅调整新建案件资料导入：浅蓝“聊天记录整包导入”提供文件夹/ZIP，浅米色“文字 / 图片分别上传”提供相邻TXT/图片入口，原始文字仍可粘贴与核对。两组有独立标题、边框及键盘焦点，图片计数显示所有入口累计数量。
- `TextFormIntake.vue`复用既有提取/人工确认及photos事件，`WorkOrdersView.vue`只增加当前照片列表属性，原照片预览、删除及幂等保存组件未改。整批校验后再采用资料，超量或读取失败保留旧原文、核对结果和照片；新增图片不重提取或清空已核对文字。所有入口累计最多20张，其余限制沿用本轮确认计划。
- 新增`intake-zip.ts`及锁定依赖`@zip.js/zip.js@2.24.0`，仅ZIP选择时动态加载（构建129.37kB，gzip59.01kB）。浏览器按条目/块读取并检查实际解压大小、CRC、路径、加密及文件类型，保留中文相对路径与原图片字节，随后共用原TXT解析。ZIP不发送至新的存储或接口，不启动第13阶段智能分类/PDF/OCR。依赖用法核对[zip.js官方文档](https://gildas-lormeau.github.io/zip.js/api/classes/ZipReader.html)。

| 检查 | 实际结果 |
| --- | --- |
| 专项回归 | `admin-web`下`node --test tests/intake-zip.test.mjs tests/text-intake.test.mjs tests/intake-files.test.mjs tests/intake-recovery.test.mjs`，23项通过；覆盖中文嵌套目录、ZIP/文件夹等价、损坏/加密/异常路径/不支持文件、缺TXT、文本/照片上限、伪造解压大小、累计超量原子拒绝、重试保留、锁定及图片不OCR |
| 全量管理端测试 | 首次`npm --prefix admin-web test`有测试子进程`spawn EPERM`，未记为通过；改用`admin-web`下`node --test --test-concurrency=1 tests/*.test.mjs`后127项全部通过，0失败/取消/跳过，72.67秒。未删除、跳过或放宽任何测试。日志`tmp/intake-upload/test{,-serial}.log` |
| lint/类型/构建 | `npm --prefix admin-web run lint`、`run typecheck`、`run build`均退出0，最终构建1.43秒；日志`tmp/intake-upload/{lint,typecheck,build}.log`。首次lint指出catch转换错误须保留cause，已补齐后重跑通过 |
| 浏览器 | 使用真实WorkOrdersView及其子组件的本地隔离验收页，所有API以合成适配器响应，不登录或写入业务库。ZIP导入后原文、字段和1张照片出现；独立图片入口追加后计数2，人工核对后保存调用原文字草稿与两张图片流程。1280×720两组入口清晰；390×844页面scrollWidth=390，组内无横向溢出。截图`tmp/intake-upload/{desktop,mobile,saved}.jpg`。首次依赖预构建刷新导致选择丢失，重试成功后才计为通过 |
| 改动范围 | 父页面仅新增照片属性；其他页面、登录、共享HTTP、后端、数据库、H5、启动脚本均未由本轮修改。开始前已有的登录修复和文档变更保留。`git diff --check`通过，仅换行提示 |

未验证：真实后端新工单写入、生产/跨浏览器/实体设备；本轮后端/数据库/H5无改动，相关门禁未重复执行。合成验收页已移除，截图/夹具/日志留在Git忽略的tmp目录；没有提交、推送或部署。本阶段完成并停止。

## 2026-10-22 统一登录错误提示与常驻后端版本同步修复

- 用户反馈既有管理员和客服账号使用原密码均显示“账号或密码错误”。范围：确认账号／服务版本原因，恢复新版统一登录，保留密码、角色、启用／激活状态和全部页面设计；验收为两个账号通过实际浏览器登录、真实错误密码及越权仍拒绝、运行入口不再仅凭普通health宣称登录可用。
- 实际对照：同两个账号经5173代理、带真实127.0.0.1 Origin调用旧 `/auth/login` 均200且角色正确，新 `/auth/sign-in` 均401／UNAUTHORIZED。运行中JAR不含UnifiedLoginService；旧后端仍为26592，前端已随拉取94173d2切换新版接口。数据库只读查询确认public修复前已是V24，账号密码并未失效，问题是常驻运行包没有随源码更新。
- 保留当前数据库备份 `tmp/login-runtime-sync/db-before-runtime-sync.dump`（19,882,571字节，Git忽略），在专用测试库随机schema验证当前后端。核验原守护／后端的命令路径和启动时间后，停止两者，用watch.lock隔离替换窗口，保留旧JAR，安装已验证新包；启动新版守护28220和后端22588。原管理端4264不动。旧包、来源和新SHA256见 LOCAL_RUNTIME；数据库启动校验25条、public V24、No migration necessary，没有执行新的迁移／清库／账号重置。
- 启动器增加LoginApi检查，以空对象访问统一登录接口，验证新版参数校验400，旧运行包会401；不提交凭据、不执行登录或消耗其限流。修复前Status记录LoginApi False，其余True；修复后七项全True。Start／守护增加对应错误提示，启动参数匹配已确认target24；重启守护加载了新源码，重复Start复用同PID健康服务。
- 管理端错误转换区分 `/auth/sign-in` 的UNAUTHORIZED（登录服务暂未就绪）和INVALID_CREDENTIALS（账号或密码错误），保持401处理及错误码，不自动重试或降级到旧接口。新增回归在旧转换下先失败，修复后完整测试通过。

| 检查 | 本轮实际结果 |
| --- | --- |
| 后端 | Java21；`backend`下 `.\mvnw.cmd -B -ntp '-Dvalidation.build.directory=../tmp/login-runtime-sync/backend-build' '-Dit.test=AccountActivationPostgresIT,MapRecommendationPostgresIT' verify -Ppostgres-it`通过；77单元＋21真实PG／MinIO集成，0失败／错误／跳过。隔离测试库、Mock供应商及真实地图预算0，无真实高德调用；日志 `tmp/login-runtime-sync/backend-verify.log` |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm test`、`npm run build`全通过；119测试0失败／跳过。日志 `tmp/login-runtime-sync/frontend-{lint,typecheck,test,build}.log`；先红记录 `frontend-red.log` |
| 真实统一登录 | 两个原账号分别通过 `/auth/sign-in` 200、`/auth/handoff` 200，activated均True，角色分别CUSTOMER_SERVICE／ADMIN；没有输出或保存密码／交接码／Token |
| 实际浏览器 | 原管理员进入“管理工作台”，退出后原客服进入“客服工作台”，当前保留客服已登录页。截图 `tmp/login-runtime-sync/{admin-login,customer-service-login}.png`仅取顶部，不含密码／Token／案件列表；没有代办业务 |
| 权限 | 客服访问admin/session200、admin/users403、h5/session403；随机错误密码401／INVALID_CREDENTIALS，未放宽授权 |
| 启动与文件 | Windows PowerShell语法解析通过；新增检查实测先False后True，重复Start七项True、PID未变；`git diff --check`通过，数据库备份保持忽略 |

仅修改管理端错误转换／回归测试、当前电脑启动器和对应文档；没有修改登录页设计、账号数据或远端业务源码。H5无改动，未重复执行H5门禁；未执行全量后端集成、生产／跨设备／系统重启验证。旧包和本地备份保留，未提交、推送或部署生产。本阶段完成并停止。

## 2026-10-21 本次改动提交、同步远端及合并发布

- 用户明确授权提交本次完成的改动，抓取远端，无冲突时合入main并上传GitHub。本轮只做集成与发布，不新增界面或业务功能。
- 分支 `codex/workbench-login-notifications-20261021`，提交 `2a17f98`，包含最终金属登录页、工作台快捷入口／仪表盘、导航图标配色、未读通知及登录可用性修复与相应测试／文档。`.env`、私密凭据、运行包、状态文件和临时日志均被忽略，未提交。
- 提交后执行 `git fetch --prune origin`，发现远端main从 `d3e887b`更新至 `6888816`，新增车主H5修复 `6f2b566`及整合提交。合并预演无冲突；本地main先fast-forward至远端，再以no-ff合入本次分支，合并提交 `bd2fd0f`。文档自动合并，无手动冲突处理。核验远端main、本次分支均为新main祖先，H5、远端管理端案件详情小修及DECISIONS与抓取版本一致，未覆盖远端成果。

| 合并后检查 | 实际结果 |
| --- | --- |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm test`、`npm run build`全部通过；106项测试0失败／跳过，构建1.68秒 |
| H5 | 同四项命令全部通过；27项测试0失败／跳过，构建6.03秒；保留既有构建回调耗时提示，未屏蔽 |
| 后端 | 本轮合并未再改后端；本次对话中已执行56项单元＋2项PG／MinIO集成及Maven verify通过，详见前文；没有把它们写成此次重新运行。未重复执行全量后端集成 |
| 本机健康 | 并行构建时3秒Status后端探针一次False，其余True；随后15秒只读数据库健康探针返回UP，未据此重启或修改服务 |
| Git | `git diff --check`通过；合并后工作区干净；原私密配置和运行数据仍被忽略 |

最终执行 `git push --atomic -u origin main codex/workbench-login-notifications-20261021`成功，没有强制推送。`git ls-remote --heads`确认远端main为 `bd2fd0f88df8072a537faffe73cf749fa1089e64`，功能分支为 `2a17f98ed495b9defa6d29fd724a0795df4e25e7`。本条发布记录随后单独提交到main并普通推送，最终main以Git记录和本次最终汇报为准。日志在本机忽略目录 `tmp/github-final-{admin,h5}-{lint,typecheck,test,build}.log`。

本阶段完成并停止。没有创建PR、生产部署、清库或修改业务记录；本机页面与服务保持运行。跨浏览器、物理设备及生产环境未新增验证。

## 2026-10-21 工作台深色快捷入口与仪表盘动效完成

- 用户授权两处前端调整：工作台四个快捷入口统一为第一个按钮的深色；将所给金属仪表盘处理为PNG，并放在标题卡片右侧留白，让速度条与中央数字对应变化。范围、装饰属性及验收写入 REQUIREMENTS；不改其他区域。
- `HomeView.vue` 仅增加三处按钮的primary样式、仪表盘引用及标题区局部定位。现有按钮跳转、客服资金入口条件、待办／案件／通知内容和背景图保持原样。新增 `WorkbenchSpeedometer.vue`，同一速度值驱动双位数码和16段速度条，10秒0→60→0循环；卸载取消动画和监听，系统减少动效偏好下静态30／8段，动态偏好变更即时生效。没有新接口或依赖。
- 内置 imagegen 从用户JPG编辑透明PNG动画底图，保留金属外壳及其余仪表细节，移除固定45和固定速度条后由SVG叠加同步动画。资源 `admin-web/src/assets/workbench-speedometer.png`，1254×1254 RGBA，1,837,715字节；原图未覆盖，完整提示词及方式见 [素材记录](design/workbench-speedometer/PROMPT.md)。
- 浏览器实际核验常规桌面、900×640、1440×900及390×844。所有素材加载成功，四个按钮背景均 `rgb(53,56,59)`，无横向溢出；桌面仪表128px，在按钮上方不相交；窄屏112px放在按钮下方靠右，避免遮挡。读取真实SVG：38对应10段、31对应8段，与同一速度计算一致，数字随时间变化。截图 `tmp/local-website/workbench-speedometer-desktop.png`；临时视口覆盖已清除，保留客服工作台。未提交或修改业务记录。

| 检查 | 本轮结果 |
| --- | --- |
| 管理端lint／类型 | `npm run lint`、`npm run typecheck`通过 |
| 自动测试 | `npm test`通过，106项0失败／跳过；新增两项直接执行实际仪表组件，覆盖0／30／60／30／0整循环、数字与条段同步、卸载取消、减少动效静态及偏好变化 |
| 生产构建 | `npm run build`通过，1.95秒，PNG进入产物 |
| 文件检查 | `git diff --check`通过，仅既有换行提醒；登录组件blob仍 `b53e8a4d6d9a8b2acdd9394604c2b7d637d295e5` |

日志 `tmp/workbench-speedometer-{lint,typecheck,test,build}.log`。系统减少动效通过自动测试验证，未切换操作系统实际偏好；未验证跨浏览器／物理设备／弱网。后端、数据库、H5、登录页及其他区域无改动，相应门禁不适用。本阶段完成并停止；未提交、推送或部署。

## 2026-10-21 左侧导航图标语义配色完成

- 用户仅授权登录后左侧导航图标上色；客服工作台右侧留白只提供动效建议，暂不实现。范围和验收：仅图标线条按功能区分颜色，文字、选中背景、尺寸、导航行为及通知红色数字不变；登录页、工作台内容、后端和H5不改。
- 生产代码只在 `AppShell.vue` 的导航SVG添加模块颜色类，在 `styles.css` 添加图标颜色。工作台蓝、案件暖橙、价格库紫、资金绿、通知金、管理员管理图标灰蓝；选中时用同色系浅色适配原深色背景。无新资源／依赖，无新测试（可逆的纯外观变更）。
- 实际浏览器用用户此前提供的客服账号正常登录，核对五个导航图标颜色、文字保持原色；工作台选中为浅蓝，通知选中为浅金，红色39条未读角标保持原色／计数。没有标记通知已读或更改业务数据，检查后返回客服工作台。截图 `tmp/local-website/rail-icon-colors.png`；管理员专有第六项的配色已定义，但未登录管理员做本轮视觉验证。
- `admin-web` 下 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 全部通过；104项测试0失败／跳过，生产构建1.20秒。日志 `tmp/rail-icon-color-{lint,typecheck,test,build}.log`。`git diff --check`通过，仅既有换行提示；登录组件blob仍为 `b53e8a4d6d9a8b2acdd9394604c2b7d637d295e5`。
- 动效建议优先为银灰电动车＋小型诊断仪，局部指示灯／扫描线变化；其他候选为保险盾牌＋勾选或客服耳机＋消息气泡。等待用户选择素材，本轮未编辑工作台右侧。

本阶段完成并停止，未提交、推送或部署。后端／数据库／H5无改动，相应门禁不适用；未进行跨浏览器或物理设备验证。

## 2026-10-21 纠正回退目标，按用户截图恢复金属素材登录页

- 上一次把“对话前”错误理解为已提交的工程线稿版本。用户随后上传截图并明确：恢复截图中的前端登录页，登录后的页面／后端内容与登录故障修复不改变。本轮以该截图及此前金属素材验收图为依据，不再以HEAD作为外观目标。
- 仅恢复 `admin-web/src/views/LoginView.vue` 和三张既有PNG：顶部双齿轮持续反向转动、扳手静止钳住右齿轮中心、左下整车拆解图、右侧表单、右下横向电池充放电，无暂停按钮。素材从本机保留目录复制回原资源路径，未重新生成。组件Git blob为 `b53e8a4d6d9a8b2acdd9394604c2b7d637d295e5`，与误回退前的金属素材版本一致。
- 登录提交及错误处理函数与原版逐段比较一致；未改登录请求、CORS、账号权限、服务守护、后端源码／运行包、登录后页面、通知角标或H5。REQUIREMENTS 已按用户最新截图纠正。
- 浏览器1440×900实际核验与此前金属素材桌面验收图一致，所有图片成功加载、无横向溢出、暂停按钮0；两次读取确认齿轮矩阵变化、扳手矩阵相同且animation为none、电芯透明度变化。截图 `tmp/local-website/login-screenshot-restored.png`；检查后清除临时视口覆盖，保留登录页供查看。未提交真实账号或改业务数据。
- 本轮管理端 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 全部通过；104测试，0失败／跳过。日志 `tmp/login-screenshot-restore-{lint,typecheck,test,build}.log`；构建有既有回调耗时提示，未屏蔽。`git diff --check`通过，仅换行提醒。

本阶段完成并停止。仅恢复登录前端，后端／数据库／H5无改动，不重复执行相应门禁；未提交、推送或部署。下方上一次回退记录作为过程历史保留，其外观目标已由本节纠正。

## 2026-10-21 登录页恢复至本次对话前，通知角标完成

- 用户最新要求回退登录页。范围限定为本次对话的登录视觉改动；验收为登录组件与对话前已提交版本一致、原工程线稿／布局／暂停控制恢复、页面不溢出。通过 `git show HEAD:admin-web/src/views/LoginView.vue` 恢复该组件，`git diff --numstat -- admin-web/src/views/LoginView.vue` 为空。三张新增金属 PNG 移至本机忽略目录 `tmp/login-metal-assets-reverted/` 保留，生产资源不再引用；原始用户图片未改。金属素材的历史验收记录保留，REQUIREMENTS 已标记撤回。登录故障修复与已授权通知功能保留。
- 浏览器实际访问5173登录页，确认原三处 SVG、暂停控制存在，金属装饰数量0，无横向溢出。截图 `tmp/local-website/login-before-conversation.png`。导航至登录页仅为查看，不主动退出或修改账号。
- 同时完成此前已进行的通知角标：左侧通知及顶部站内通知显示当前用户有权查看的未读数；0隐藏，超过99显示99+，可访问名称保留完整数字。进入站内通知列表，不自动标记已读；显式标记成功后刷新，页面可见时每30秒及聚焦／重新可见／路由变化时检查。账号变化和组件卸载使旧请求失效，失败保留最后确认值。
- 新增 `GET /api/v1/notifications/unread-count`，仅返回 `{count}`，复用现有收件人／实时权限查询，不接受其他用户参数。无数据库迁移、H5修改、角色扩大或金额逻辑变更。工作台右侧动效仅给素材建议，尚未添加。

| 检查 | 本轮实际结果 |
| --- | --- |
| 管理端门禁 | 回退后 `admin-web` 下 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 全通过；104测试，0失败／跳过。日志 `tmp/login-rollback-{lint,typecheck,test,build}.log` |
| 前端角标回归 | 新增4项测试覆盖0／正常／99+、标记后新计数、乱序响应、账号重置、失败保留最后确认值；包含在104项内 |
| 后端单元与真实集成 | Java21，`backend` 下 `.\mvnw.cmd -B -ntp '-Dvalidation.build.directory=../tmp/unread-badge-backend-build' '-Dit.test=QuotationPostgresIT#stationNotificationsAreRecipientScopedReadIdempotentAndContainNoFinancialOrInternalFields+reassignmentInvalidatesOldShopNotificationsAndProjectorsDeduplicateConcurrently' verify -Ppostgres-it`通过；56单元、2项PG／MinIO集成，均0失败／错误／跳过。隔离测试数据库／随机schema，覆盖匿名401、跨用户隔离、失去车主／网点归属权限、改派失权、重复已读不重复扣数。日志 `tmp/unread-badge-backend-verify-rerun.log` |
| 失败如实记录 | 首次PS5测试包装器将Mockito的stderr警告当作异常中断；直接运行后，既有OCR超时测试因模拟认证超时失败（期望识别超时，实际认证超时）。未修改测试、超时或跳过检查；同一完整命令重跑后以上56+2全部通过，保留首次日志 `tmp/unread-badge-backend-verify.log` |
| 本机生效 | 核验后端PID、命令路径及启动时间后替换已验证JAR，旧包保留；守护恢复新后端26592，数据库健康UP。23条迁移校验、public仍V22、无新迁移；新包来源及SHA256见 LOCAL_RUNTIME |
| 实际页面 | 原客服会话仍有效，两处角标均为39，实际背景色为红色；点击顶部入口进入 `/notifications?tab=notices` 的站内通知列表，未标记真实通知已读。截图 `tmp/local-website/unread-badge.png`。0／99+及已读同步经自动测试，未人为修改真实通知验证这两种画面 |
| 文件检查 | `git diff --check`通过，仅换行提醒 |

最终页面留在恢复后的登录页供用户查看。本阶段完成并停止；未提交、推送或生产部署。未执行H5门禁、全量后端集成、跨浏览器／物理设备验证；这些不作为通过项。

## 2026-10-21 客服登录403与管理端Origin遗漏修复

- 用户反馈现有客服账号登录提示“没有访问权限”。同一账号／用户提供的密码实际对照：无Origin直连8080、Origin=`http://localhost:5173`均200／CUSTOMER_SERVICE；Origin=`http://127.0.0.1:5173`经5173代理返回403。账号／密码／启用状态和角色正常，原因是本机显式CORS白名单和源码默认值只有localhost，上一轮切到127.0.0.1时未覆盖真实浏览器Origin。此前无Origin的HTTP验证不能证明浏览器登录可用。
- 本机私密 `.env` 仅补充准确的 `http://127.0.0.1:5173` 与已存在5191预览地址，原来源保留；通过守护重启已核验后端加载配置（37696→6748，31.7秒），未改密码／账号／角色／数据范围。源码默认值及 `.env.example` 增加准确的127.0.0.1:5173，5191只留在本机显式配置，不用通配符。运行仍为前一已验证JAR，显式环境配置已生效；本轮新默认源码另经完整单元与构建验证。
- 当前页面真实输入用户提供的账号／密码并点击登录，已从 `/login` 进入 `/`，看到“客服工作台”、案件队列与待办，保留已登录页面。没有代办案件、修改资金或变更业务权限。验证截图仅取工作台顶部、不含密码／Token／案件列表，保存在本机忽略目录 `tmp/local-website/customer-service-login.png`，不提交。
- 启动器的API代理健康探针增加与实际地址相符的Origin，常驻脚本源码也检查管理端带Origin代理结果，防止以后再次误报无Origin健康即可登录。新Status实测六项全True；现有守护进程下一次启动加载新探针，不为此打断用户已登录会话。

| 检查 | 实际结果 |
| --- | --- |
| 回归先红后绿 | 新增HealthControllerTest同时验证localhost／127管理端预检、认证仍必需、未知本机端口／外部Origin拒绝；旧默认5项中1项失败（127预检403），修复后通过。首次未给Maven `-D` 参数加引号被PowerShell拆分，未运行测试，改为带引号后才得到上述有效复现 |
| 后端单元与构建 | Java21.0.11；`backend`下 `.\mvnw.cmd -B -ntp '-Dvalidation.build.directory=../tmp/login-cors-red' verify`通过；56测试，0失败／错误／跳过，JAR构建成功。日志 `tmp/login-cors-verify.log`；先红日志 `tmp/login-cors-red.log` |
| 真实登录 | 5173代理带127.0.0.1 Origin登录200／SUCCESS，角色CUSTOMER_SERVICE，Access-Control-Allow-Origin为准确请求地址；实际浏览器进入客服工作台 |
| 权限边界 | 同一客服Token访问管理端session200；管理员专用users、H5专用session仍403。未知127端口5199及untrusted.invalid来源的实际代理POST和直连后端预检均403；前端Vite可能自行响应OPTIONS，故不把其预检状态代替后端授权 |
| 运行与文件 | 数据库健康UP，重启校验原23条Flyway／V22，无新迁移；`git diff --check`通过，仅既有换行提示；`.env`保持Git忽略 |

本轮只改后端CORS默认值／回归测试、示例配置、启动探针及对应文档。本机配置不含于提交；未重新执行未改动的前端／H5门禁、完整PG／MinIO集成、生产或跨设备验证，未重置密码、主动调用真实OCR、提交、推送或部署。本阶段完成，保留用户已登录客服工作台并停止。

## 2026-10-21 反复登录“请求未完成”修复与本机自动恢复

- 故障现场：8080拒绝连接、5191页面可打开但API代理返回502；没有Java后端进程，Docker Linux引擎管道不存在。Docker 4.79启动日志明确报 `dockerInference`，随后 `docker-secrets-engine/engine.sock` 失效通信端点访问错误1920。旧临时后端日志的时钟跳变不足以证明原Java进程退出原因，未把休眠或本轮登录图片认定为其退出原因。
- 非破坏恢复：单socket改名／删除、重解析标记修复均被Windows拒绝；仅将已核对、只含零字节socket的两个临时通信目录原地改名保留，让Docker自行创建新目录。最终Docker29.5.3引擎正常，原 `ev-insurance-platform` PostgreSQL／MinIO healthy；没有factory reset、prune、WSL注销、卷删除、清库或账号重置。保留目录在原用户Docker临时路径下。外部同类故障参考与处理边界见 [当前电脑运行说明](LOCAL_RUNTIME.md)。
- 修正本机入口：原启动说明依赖另一台机器不存在的 `.env.phase5`、D盘路径和Android JDK。新增 `scripts/Manage-LocalWebsite.ps1` 与根目录启动／检查／停止三个CMD，读取本仓库 `.env`，使用已验证发布构建的JAR副本及Corretto21；固定Flyway target22、只监听本机。依赖及数据库健康后才启动5173管理端；守护10秒检查，身份同时核对命令路径和启动时间，不停止未知进程。状态／运行包／日志均保留在Git忽略的 `tmp/local-website/`。
- 注册当前用户普通权限任务 `EVInsurance-LocalWebsite`：登录后启动、每分钟兜底触发、重复实例忽略、不保存密码。停止入口移除启用标记并仅停止本项目登记进程，后续任务直接退出；不停止Docker、不删除业务数据。5191仍为独立预览，建议用户固定使用5173正式托管入口。
- 管理端新增502／503／504明确后台不可用提示，兼容HTML／空响应，保留503具体中文业务错误和错误码；不自动重试登录或业务写请求，不因网关故障清除会话。新增测试先复现原泛化提示失败，再修复通过。H5、后端业务代码、权限、金额与迁移文件未改。

| 检查 | 本轮实际结果 |
| --- | --- |
| 真实登录 | 管理员／客服通过5191当前代理首次恢复验证成功；最终通过5173及5191分别再次登录均SUCCESS且角色正确。凭据及Token只在内存使用，不输出／落盘；仅正常认证审计 |
| 后端／数据库 | `/actuator/health` UP；Flyway成功校验23条、public版本22、No migration necessary，无新迁移 |
| 后端退出恢复 | 最终任务配置下，仅停止经身份核验的后端，44408→37696，23.3秒自动恢复数据库健康；未手动重启代替验证，结果 `tmp/local-website/recovery-check.json` |
| 守护退出兜底 | 仅停止已核验守护，19504→38620，30.4秒被定时任务自动恢复；后端37696保持健康，任务Running；结果 `watch-recovery-check.json` |
| 重复启动 | 连续执行两次 Windows PowerShell `Manage-LocalWebsite.ps1 -Action Start`，backend／admin／watch PID均不变；Backend、Admin、AdminApi、PreviewApi、WatchRunning、AutoRecoveryEnabled全部True |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm test`、`npm run build`全部通过；100测试，0失败／跳过，构建2.40秒；`tmp/login-availability-*.log` |
| 运行／文件 | PowerShell语法解析通过；`docker compose --env-file .env -f infra/docker-compose.yml config --quiet`通过；`git diff --check`通过，仅既有换行提醒 |

首轮启动遇到Windows PowerShell5把Docker正常stderr进度当作异常，已隔离原生命令返回码处理；第一次恢复验证暴露守护本身退出（任务退出码0xC000013A）而未恢复，保留超时失败记录，补充每分钟兜底后重跑以上两项真实退出测试通过。外部PowerShell7的CIM时间表示与Windows PowerShell5不同，严格身份检查曾拒绝停止；没有放宽断言或结束未知PID，最终所有入口和生命周期验收均使用同一Windows PowerShell5。

最终浏览器已从独立5191预览切换至常驻5173登录页；确认没有用户已输入内容后才导航。页面正常，截图在本地忽略目录 `tmp/local-website/login-restored.png`。最终任务Running、Limited权限、两个触发器、IgnoreNew；六项运行状态均True，原PG／MinIO均healthy。

未验证：真实注销／重启电脑、断电、休眠恢复、Docker再次发生系统级通信损坏、跨浏览器／物理手机及生产部署。自动恢复覆盖本项目进程退出，不承诺外部系统故障永不发生，也不循环删除系统文件。本轮使用先前已验证后端构建，未修改后端源码，未重复跑完整后端／PG集成或H5门禁，不引用历史通过冒充本轮通过。未主动触发真实OCR、未提交／推送／部署或进入其他阶段。任务计划、数据库、后端、5173和既有5191预览保留运行。本阶段完成并停止。

## 2026-10-21 登录页扳手钳合右侧齿轮调整完成

- 用户追加要求：扳手虎口钳住右边齿轮，齿轮转动时扳手不动。仅调整登录组件的装饰结构／样式：将扳手放入齿轮组合，虎口锚定右齿轮中心轴，手柄固定向右上倾斜 30 度；动画继续只施加在两张齿轮图上。移除旧独立扳手的定位和矮窗口覆盖规则，窄屏随齿轮组合一起隐藏。没有重新生成图片或新增依赖。
- 浏览器核验同一 1280×640 视口两次读数：齿轮 transform 已变化，扳手 transform 与完整边界矩形相同，animation 为 none。900×640 错误状态、1280×640 错误状态、1440×900 正常状态及390×844窄屏已复查，实际画面无文字／表单遮挡或横向溢出。旋转后图片的透明留白矩形可能与表单矩形相交，判断以可见像素和实际截图为准。截图与读数见 [组合验收](design/login-metal-assets/README.md)。
- 管理端 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 均通过；98 项测试，0 失败／跳过；最终角度调整后重跑构建通过（1.26 秒），`git diff --check` 通过。日志 `tmp/login-wrench-clamp-{lint,typecheck,test,build}.log`。
- 未修改 H5、后端、权限、资金、数据库或基础设施，相应门禁不适用；系统偏好／跨浏览器／物理设备未新增验证，未实际登录。本机 5191 预览保留；未提交、推送、部署或进入下一阶段。

## 2026-10-21 登录页金属 PNG 素材与动效完成

- 用户确认本阶段方案：仅客服／管理员登录页；齿轮可调整视角；不需要暂停按钮。范围和验收已写入 REQUIREMENTS。
- 使用内置 ImageGen 将用户提供的三张 JPG 编辑为透明 PNG，保存至 `admin-web/src/assets/login-battery.png`（1774×887）、`login-gear.png`（1254×1254）、`login-wrench.png`（2172×724）；透明通道、图片加载及实际显示均检查。原图未覆盖，生成提示词见 [PROMPTS](design/login-metal-assets/PROMPTS.md)。
- 电池正极朝右，内部五段以低饱和绿色叠加电量，8 秒从左到右充满、从右到左减少，外壳静止。近正面齿轮素材复用为两只同尺寸齿轮，各自围绕中心以 28 秒周期反向旋转；扳手静态展示。移除原暂停按钮及其状态、样式和旧 SVG 装饰，不新增运行依赖。保留系统减少动效 CSS 规则、整车主图及登录逻辑。
- 浏览器正式页面检查 1280×640、1280×701、1280×761、1440×900、900×640、640×720、390×844；空账号提交显示既有校验提示，全部装饰边界不与卡片重叠，无横向溢出。矮桌面在错误状态下曾挡住电池，已通过对应尺寸的表单间距／电池大小修正并复查；窄屏沿用收起齿轮和扳手的方式，仅保留小电池。实际读取齿轮矩阵和电芯透明度随时间变化，扳手 animation 为 none，暂停按钮数量为 0，装饰不接收指针事件；控制台 error 为空。截图及几何记录见 [验收说明](design/login-metal-assets/README.md)。

| 检查 | 实际命令与结果 |
| --- | --- |
| 管理端 lint | `admin-web` 下 `npm run lint` 通过；最终样式修正后重跑通过 |
| 类型检查 | `npm run typecheck` 通过 |
| 前端测试 | `npm test` 通过；98 项，0 失败／跳过 |
| 生产构建 | `npm run build` 通过；最终样式修正后重跑，1.13 秒；三张 PNG 均进入产物 |
| 改动检查 | `git diff --check` 通过；生产代码仅登录组件和三张新图片 |

日志在本地忽略目录 `tmp/login-metal-{lint,typecheck,test,build}.log`。浏览器仅检验空输入校验，没有提交真实账号或创建业务记录。系统减少动效仅核对 CSS，未实际切换系统偏好；跨浏览器、物理设备及弱网性能未验证。三张原始透明 PNG 合计约 4.56 MB，未进行有损压缩。

本轮未改 H5、后端、业务权限、数据库或基础设施，相应门禁不适用，未重复声明其通过。仅本机预览 `http://127.0.0.1:5191/login` 保留供用户查看；未提交、推送或部署，不进入其他阶段。

## 2026-10-21 最新更新 GitHub 发布与 main 合并验证

- 用户明确授权将最新更新上传 GitHub 并合并 `main`。范围为工作区已有桌面端视觉升级、登录页配图与工程动效、已完成案件归档入口及服务端分页筛选，以及对应测试、设计样板、截图与文档；本轮没有新增业务功能或进入后续升级阶段。
- 拉取远端后，本地 `main` 与 `origin/main` 均为 `9e13bb5`，没有分叉。保留全部既有改动，建立发布分支 `codex/desktop-upgrade-archive-20261021`；更新提交 `54db4a4` 已推送 GitHub。
- GitHub 连接器创建 PR 返回 HTTP 403（`Resource not accessible by integration`），未创建 PR。Git 推送权限正常，按用户既有合并授权改用 `git merge --no-ff` 合入 `main` 后普通推送，保留发布分支及完整历史，不使用强制推送。

| 检查 | 本次实际命令／结果 |
| --- | --- |
| 管理端 | `admin-web` 下 `npm run lint`、`npm run typecheck`、`npm run test`、`npm run build` 全部通过；98 项测试，0 失败／跳过 |
| H5 回归 | `h5-web` 下同四项命令全部通过；26 项测试，0 失败／跳过 |
| 后端单元与构建 | Corretto 21.0.11，`backend` 下 `.\mvnw.cmd -B -ntp -Dvalidation.build.directory=../tmp/github-release-20261021/backend-build verify` 通过；55 项单元测试，0 失败／错误／跳过，可执行 JAR 构建成功 |
| 设计样板 | 样板目录使用已有 `eslint` 与 `vue-tsc` 检查通过；根目录 `node admin-web/node_modules/vite/bin/vite.js build --config docs/design/desktop-upgrade-preview/vite.config.mjs --configLoader native` 和 `node docs/design/desktop-upgrade-preview/package-preview.mjs` 通过；离线产物继续由 Git 忽略 |
| Compose 配置 | `docker compose --env-file .env -f infra/docker-compose.yml config --quiet` 通过 |
| 发布文件检查 | 检查 56 个待提交文件，未发现常见 Token／私钥模式或本地环境敏感值泄漏；无超过 10 MiB 的文件，`.env`、临时日志和构建产物保持忽略；`git diff --check` 通过 |

日志保存在本地忽略目录 `tmp/github-release-20261021/`。设计样板约 1.02 MB JS 的分包警告、管理端构建耗时提示与后端已有弃用 API 提示保留，未放宽检查。

本次未验证真实 PostgreSQL／MinIO 集成、Flyway 迁移和容器健康：Docker daemon 不可连接，`docker desktop start --timeout 45` 启动超时，随后状态检查仍不可用。没有跳过集成测试后宣称全量通过；2026-10-17 已记录的案件分页、四角色权限及迁移验证属于历史结果。补救条件是恢复 Docker 及已有本地测试容器后，在专用测试库重跑 `tmp/workflow-stage1/verify.ps1`（内部执行 `verify -Ppostgres-it`）。本次不修改数据库迁移、不连接业务库、不调用真实 OCR，也不重新宣称浏览器／物理手机验收或生产部署完成。

## 2026-10-19 登录页工程风格动态点缀完成

- 按用户要求，在登录页留白加入浅灰工程线稿齿轮、扳手和电池；保留已放大的整车拆解图、原排版和登录流程。生产源码仅修改 `admin-web/src/views/LoginView.vue`，使用内联 SVG 和 CSS，无新增依赖或图片请求。
- 双齿轮缓慢反向旋转（32s／23s），扳手围绕螺栓小幅拧动（5.5s），电量依次增长并回落（8s）；动画只改变 transform／opacity。右上角提供暂停／播放按钮，支持键盘操作；系统减少动效时静止并隐藏暂停按钮。窄屏收起齿轮和扳手，保留小电池。装饰不接收指针事件，表单显示在装饰上方。
- 正式组件隔离浏览器验收：动画随时间变化；暂停后各动画数值保持，Enter 可恢复。空账号／密码校验保留；带错误提示时，1280×640、1280×701、1440×900、640×720 均无整体横向溢出，装饰边界不与登录卡片重叠；窄屏保持正常纵向滚动。浏览器 error 日志为空，临时工具链既有 vue-router 弃用警告保留。
- `admin-web` 执行 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 全部通过；98 项测试，0 失败／跳过，构建 3.23s。日志 `tmp/login-motion-*.log`，相关文件 `git diff --check` 通过（既有 LF/CRLF 提示）。系统减少动效规则已核对源码，未实际切换操作系统偏好；未进行跨浏览器和性能专项测量。
- 截图见 [登录页工程点缀验收](design/login-engineering-motion/README.md)。后端、H5、数据库和业务权限无变更，相关构建、迁移、基础设施检查不适用；本轮不重复声明真实登录端到端验证。临时预览收尾关闭，原业务服务保留。未提交、推送、部署或进入其他升级阶段。

## 2026-10-19 配图目标澄清：登录页放大、工作台恢复

- 用户澄清“我说的是登录页的图”。撤回上一轮工作台插图宽度／定位三处误改，仅恢复这些样式值，保留案件筛选和工作区既有改动。
- 登录工程拆解图高度从容器 44% 改为 50%，宽度从 88% 改为 94%，微调位置；`object-fit:contain` 保持整车完整，实际线性尺寸约增大 14%。矮桌面窗口文案适当上移，避免图文重叠；登录行为和窄屏原规则不变。REQUIREMENTS 和交付说明已纠正目标。
- 浏览器正式组件隔离预览：1280×640 图片高 320px（原约 282px），文案底约 299px、图顶 308px、图底 628px，按钮底约 505px；1440×900 图高 450px，两者无横向溢出。640×720 窄屏无横向溢出。截图见 [登录配图与归档入口记录](design/home-archive-adjustment/README.md)。
- `admin-web` 执行 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 全部通过；98 项测试，0 失败／跳过，构建 2.18s，日志 `tmp/login-image-*.log`。相关文件 `git diff --check` 通过，仅既有 LF/CRLF 提示。本轮仅登录／工作台样式和文档纠正，后端、H5、数据、权限与已完成案件归档规则未改；相关构建、迁移不适用。临时预览收尾关闭，原登录业务服务保留。

## 2026-10-19 首页配图放大与已完成案件统一归档入口（配图目标随后澄清，已撤回）

- 按用户补充要求，首页工程配图宽度由 `min(820px,68%)` 调为 `min(980px,78%)`，微调位置；原 214px 横幅和快捷入口位置保持。1280px 视口下配图宽约 893px，增大约 15%。
- 普通案件队列固定使用现有服务端 `excludeCompleted=true`，覆盖普通导航、顶部搜索、重置和旧 false 参数；状态下拉移除已完成，旧完成状态筛选按未选择处理。旧选中完成案件链接转至历史档案，历史档案保持 `COMPLETED` 分页和原详情入口。先更新 REQUIREMENTS，接口使用补充同步 PHASE12_API；不修改状态、权限与通知／资金追溯记录。
- `admin-web` 最终执行 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 均通过；98 项测试，0 失败／跳过，构建 19.57s。日志 `tmp/home-archive-*.log`；测试同步验证普通列表默认排除、历史完成查询保留。`git diff --check` 通过，仅既有 LF/CRLF 提示。
- 浏览器正式组件＋合成数据验证：21 未完成／22 完成，普通队列 20＋1 分页；顶部搜索完成编号无结果，重置恢复 21 条，状态选项不含完成，旧 URL 的完成筛选／关闭排除参数无效，旧选中完成案件转历史档案，历史列表 20＋2 分页。1280×640 与 1440×900 横幅 214px、无横向溢出，矮窗口快捷按钮完整。error 日志为空；临时工具链 vue-router 旧入口弃用提示保留。
- 本地真实 5173 代理＋已恢复后端只读核对：排除完成查询 13 条、无完成记录；历史完成查询 1 条且为完成。没有创建或变更案件，仅产生正常登录审计。后端／H5／数据库源码未修改，相关构建、迁移、Compose 本轮不适用，未重复执行。
- 截图和说明见 [首页与归档入口验收](design/home-archive-adjustment/README.md)。本轮临时预览服务收尾关闭，原业务 5173／8080 保持运行。未执行完整阶段 D，未提交、推送或部署。

## 2026-10-19 本地登录“请求未完成”故障恢复

- 用户反馈登录显示“请求未完成，请稍后重试”。实际排查：5173 前端正常，`/api/v1/auth/login` 经前端代理返回 HTTP 502；8080 无监听、无 Java 后端进程。PostgreSQL／MinIO 容器健康。直接原因是本地后端未运行，不是登录页样式或账号校验代码变化。旧后端日志有休眠／时钟跳变及数据库连接关闭记录，但不足以认定进程退出原因。
- 恢复前只读核对数据库：public 已为 V22，共 23 条成功迁移（含开发 V2.1），修正此前进度中的 V21 运行状态描述；以本次实测为准。旧 `repair-funds-release-runtime.jar` 仅包含到 V21，因此未重启旧包。
- 使用此前已完成构建与验证的 `tmp/archive-backend-build/backend-0.0.1-SNAPSHOT.jar` 恢复本地 8080，JDK21、原 `.env`、仅绑定 127.0.0.1，保留 Flyway 校验并限制 target=22。启动日志确认 `Successfully validated 23 migrations`、当前版本 22、`No migration necessary`；未更改用户密码、数据库结构或业务记录。正常登录／失败登录产生既有认证审计。
- 临时恢复脚本 `tmp/restore-login-backend.ps1` 检查数据库目标与端口占用，不停止其他进程；隐藏运行窗口，日志 `tmp/login-backend.stdout.log`／`stderr.log`，进程记录 `tmp/login-backend-pid.json`。服务保留运行供用户继续登录。

| 验证 | 实际结果 |
| --- | --- |
| 后端健康 | `GET http://127.0.0.1:8080/api/v1/health` → 200 |
| 当前网页代理 | `GET http://localhost:5173/api/v1/health` → 200 |
| 管理员／客服登录 | 通过 `http://localhost:5173/api/v1/auth/login` 分别使用已有开发账号及原环境密码，均 200 / SUCCESS；密码与 token 未输出、未落盘 |
| 认证会话 | 两个账号携带响应 token 请求前端代理 `/api/v1/admin/session`，均 200 |
| 错误凭据 | 随机测试字符串请求登录，401 / INVALID_CREDENTIALS，认证未放宽 |
| 数据库版本 | 启动前查询、启动时 Flyway 校验：V22、23 条成功；无迁移执行 |

本轮为运行环境恢复，未修改前后端源码，无需重复源码 lint／类型／构建；未将此前 98 项测试作为本次新执行结果。未进入视觉升级阶段 D，无提交、推送、生产部署。用户可刷新原登录页后重新登录。

## 2026-10-19 桌面端视觉升级：阶段 C 全局视觉与基础组件完成

- 用户在三类样板交付后要求“开始做吧”，并要求继续未完成内容；本轮完成阶段 C 正式接入。范围为客服／管理员电脑端，沿用暖白玻璃＋石墨灰、适中动效、现有排版与工程插图，不改变业务流程、API、路由或权限规则。
- 全局颜色、字体、控件尺寸、表格、浮层、状态反馈与焦点统一；Element Plus 默认文案使用简体中文。导航保留原尺寸与断点，顶栏使用已精修插图；登录精简说明、完整展示拆解插图，取消搜索／登录按钮指针跟随。访问失败文案精简但保留关键说明。
- 验收发现管理员角色复选框被通用 label 样式排成上下结构，局部排除 `.el-checkbox` 后恢复；不改变角色操作。保留工作区已有案件、历史档案和后端等未提交改动，无提交、推送或部署。

| 检查 | 命令／方法与结果 |
| --- | --- |
| 管理端门禁 | `admin-web` 下 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` 最终全部通过；98 项测试，0 失败／跳过；构建 2.98s |
| 日志与差异 | 本地 `tmp/desktop-upgrade-stage-c/final-*.log`；`git diff --check` 通过，仅既有 LF/CRLF 提示 |
| 登录与尺寸 | 正式组件隔离预览：空值、合成失败、密码清空、客服登录／退出通过；1280×640 错误提示及按钮完整，1440×900 外壳原尺寸不变，640×720 登录重排且无整体横向溢出 |
| 组件与角色 | 客服／管理员入口区分、价格筛选、加载禁用、空／错态、金额校验及原确认框、管理员角色弹窗与 Tab 焦点通过；最终业务写入未执行 |
| 前后对照 | 登录同尺寸截图、价格页和管理员弹窗截图，见阶段 C 交付记录；画面标识合成验收，生产代码不含验收工具 |
| 后端／H5／基础设施 | 本轮未修改，不适用；未执行相关构建、Flyway、Compose，不计为通过 |

生产源码门禁全部成功。隔离预览曾因临时 alias 错误匹配中文语言包子路径失败，修正后恢复；临时 vue-router 旧入口弃用提示保留，未宣称控制台零警告。样式有减少动效／透明度及不支持磨砂的降级，但系统偏好切换、200% 缩放、跨浏览器、性能和全站视觉回归尚未验证。浏览器采用真实组件＋合成网络数据，不代替真实业务／服务端授权／上传导入的端到端验证。

交付详见 [阶段 C 截图与验收记录](design/desktop-upgrade-stage-c/README.md)，设计规范与 D-062 已更新。临时验收服务收尾关闭，不影响原有业务服务。按阶段门禁停在阶段 C；下一阶段 D 为工作台、案件与滚动体验，阶段 E 为其余页面布局／文案，尚未实施。

## 2026-10-18 桌面端视觉升级：阶段 B 三类样板交付

- 用户确认先做工作台、案件详情、典型表格／表单三个样板，并要求推荐合适的插件／skills；后续要求检查进度并继续生成。沿用已确认的暖白玻璃＋石墨灰、适中动效、略放松密度和保留工程插图。
- 新增 `docs/design/desktop-upgrade-preview/`，使用仓库已有 Vue 3 / Element Plus 依赖制作独立交互预览。工程插图经 imagegen 精修，原资产未覆盖；交付 README、5 张截图、可重建的离线 HTML。主设计方法采用 redesign-existing-projects，现有工具足够，无新插件或依赖安装。
- 工作台包含待办、队列与通知；详情包含稳定摘要、九节点、正文独立滚动、完整右栏、单据对照与金额校验；价格库包含查询分页、加载/空/错态、新版本表单及反馈。所有数据合成，保存和确认不调用业务 API。其余导航明确标记样板范围。
- 浏览器发现并修复：跨页搜索在转场完成前执行、示例列表误跳到同一案件、工程插图底部裁切；同时提升辅助文字可读性，收紧矮窗口右栏留白，减少动效模式覆盖定位滚动。

| 检查 | 实际命令／方式与结果 |
| --- | --- |
| ESLint | 样板目录下 `node ../../../admin-web/node_modules/eslint/bin/eslint.js . --max-warnings 0` 通过；后加 `package-preview.mjs` 单独同规则检查通过 |
| 类型检查 | 样板目录下 `node ../../../admin-web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p tsconfig.json` 通过 |
| 构建 | 根目录 `node admin-web/node_modules/vite/bin/vite.js build --config docs/design/desktop-upgrade-preview/vite.config.mjs --configLoader native` 通过；完整 Element Plus 使 JS 约 1.02MB，大包警告保留，未宣称完成生产性能优化 |
| 打包 | `node docs/design/desktop-upgrade-preview/package-preview.mjs` 生成约 3.08MB 的 `preview.html`（Git 忽略产物）；图片/样式/脚本内联，无外部 script / CSS 引用；提取的内联 JS 经 `node --check` 通过 |
| 差异检查 | 本轮文档执行 `git diff --check`，新增文本逐个 `git diff --no-index --check -- /dev/null <文件>`，无空白错误，仅既有 LF/CRLF 提示 |
| 工作台、表格和表单 | 内置浏览器本机 HTTP 预览验证：查询清除、跨页搜索、分页、空/加载/错态和重试、必填金额报错与有效输入确认通过；无业务数据写入 |
| 案件详情 | 空金额及不一致金额拦截，1378.60 + 勾选进入确认，确认后不改变状态；资料/记录切换和单据放大关闭通过。1280×640 右栏 bottom 612.45、操作栏 bottom 624；正文滚动后摘要/右栏/操作栏不移动。1366×768 右栏 bottom 710.22、操作栏 bottom 747.67，页面无整体横向溢出 |
| 动效与浏览器 | 减少动效／实体背景开关可用，实体背景 backdrop-filter = none；浏览器 warn/error 为空。临时视口已恢复 |

首轮检查出现表格 slot 类型推断、缓存进入 lint 和 Node 全局变量问题；修正类型及显式 Node 导入，仅排除第三方生成缓存后复验通过。未删除测试、放宽源码规则或为 CSS 添加镜像测试。详情、参数与截图见 [样板说明](design/desktop-upgrade-preview/README.md)。

本阶段只修改样板与计划/进度文档，保留已有未提交工作；正式管理端、后端、H5、数据库与业务服务未改，无提交、推送或部署。管理端全量门禁、真实业务/权限、上传导入、性能基准、跨浏览器、200% 缩放及登录后前后对照尚未验证，不能以合成预览代替。内置浏览器因协议限制拒绝直接打开离线 HTML，未绕过，离线运行未验证；用户可用系统浏览器打开交付文件。临时预览服务在收尾关闭。

当前停在阶段 B 样板审阅入口，待用户对材质、密度、插图及动效反馈后再进入阶段 C，不自动修改正式业务页面。

## 2026-10-18 客服与管理员网页版升级计划（讨论阶段）

- 用户要求先沟通并形成计划书，业务流程不变，主要排版沿用现有页面；两轮沟通已确认仅改客服／管理员电脑网页版，采用暖白玻璃＋石墨灰，动效适中、操作区稳定，信息密度略微放松但首屏保留主要任务，保留并精修工程插图作为平台特色。
- 已形成 [前端升级计划书 V1.0](FRONTEND_UPGRADE_PLAN.md)，包含现状依据、配色与玻璃分层、组件统一、插图精修、滚动与动效、文案精简、全站页面范围、阶段安排和验收清单。具体参数为待预览确认的设计提案，不代表已实施；H5 不在本轮范围。
- 核对既有 DESIGN、D-058 滚动方案及 D-059／D-060 局部实现，保留顶部摘要、完整流程与右栏稳定的要求；完整正文单滚动方案仍待实施，没有将其记作已完成。保留工作台队列／历史档案等当前工作区已有未提交改动。
- 实际只读查看 localhost:5173 登录页；浏览器原预填登录返回账号或密码错误，未继续尝试、重置账号或修改运行环境。业务页审视依据源代码和已有验收记录，本轮未完成登录后的全站视觉核对。
- 本轮只新增计划文档并补记本进度，不修改前后端、H5、数据库、运行数据或已确认业务规则。文档检查：`git diff --check -- docs/PROGRESS.md` 与 `git diff --no-index --check -- /dev/null docs/FRONTEND_UPGRADE_PLAN.md`，均通过，新增文件单独检查；Git 仅提示既有换行转换策略。测试、lint、类型检查、构建、Flyway 与 Compose 本轮不适用，未执行，不计为通过。
- 计划讨论阶段止于此；待用户确认后再进入代表页面预览，不自动开始前端实现，无提交、推送或部署。

## 2026-10-17 工作台队列与历史档案调整

### 范围与完成结果

- 按本次用户要求：工作台队列排除 `COMPLETED`；案件导航改名“历史档案”，默认分页查看系统已完成案件，另可检索独立导入档案并进行历史案件批量导入；标题下说明句替换为快捷入口，底部入口删除。取消、关闭和既有角色权限不变。
- 系统完成案件直接查询当前状态，不复制历史表；撤回完成后退出归档列表。已导入档案仍独立只读，XLSX 上传预览、确认去重、原件下载保持原接口。导入面板默认收起，确认归档后切到导入档案。
- `GET /work-orders` 新增可选 `excludeCompleted`（默认 false），在 SQL 分页、总数计算前筛选，保留原权限、稳定排序、状态和搜索条件。从工作台搜索、选择案件及查看完整队列时携带此条件；“全部案件”仍为原查询。
- 先更新 REQUIREMENTS，再修改实现；同步 PHASE12_API 与 DESIGN。无新增数据库迁移、金额规则或外部服务。

### 实际验证

| 检查 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 管理端门禁 | admin-web 下 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build`；最后按钮颜色修复后再跑 lint/typecheck/build | PASS；98 项测试，0 失败/跳过；日志 `tmp/archive-admin-*.log`、`tmp/archive-admin-final-*.log` |
| 后端单元、案件集成与构建 | `tmp/workflow-stage1/verify.ps1 -IntegrationTest WorkOrderPostgresIT -LogName archive-isolated-final -BuildDirectory <仓库>/tmp/archive-backend-build`，内部 JDK21 执行 Maven `verify -Ppostgres-it` | PASS；55 单元 + 12 真实 PG/MinIO 集成，0 失败/错误/跳过，JAR 成功；`tmp/workflow-stage1/archive-isolated-final.log` |
| 报价与历史导入回归 | 同脚本 `-IntegrationTest WorkOrderPostgresIT,QuotationPostgresIT -LogName archive-verified` | QuotationPostgresIT 41 项 PASS，含 XLSX 预览、去重归档、原件及历史权限。该次整轮因新增查询夹具污染既有重复检测而失败；修正后仅重跑受影响的 WorkOrderPostgresIT，结果见上一行，不将该整轮记为成功 |
| 分页与权限回归 | 新增真实 API 测试：已完成占据排序前部、size=1 翻页、筛选总数、全部案件兼容、完成状态查询、完成撤回、四角色本范围、跨店/其他车主不可见；既有敏感字段测试一并运行 | PASS；筛选先于分页与计数，不放宽权限或门禁 |
| Flyway | 专用 `ev_insurance_workflow_stage1_test` 中随机 schema 空库 migrate/validate | PASS；日志验证 24 条迁移；未修改迁移文件，未升级本地业务库 |
| 浏览器页面复查 | 内置浏览器，原 Vue 页面 + 独立合成数据适配，临时 localhost:5190 | PASS；标题下仅一处快捷入口、队列显示 2 条未完成，历史 21 条已完成翻至第二页、搜索重置页码、导入档案和批量导入展开/收起；修复标题全局 span 颜色影响按钮文字对比度后截图复查通过 |
| Git | `git diff --check` | PASS；仅本任务源码、测试和文档，无提交/推送 |
| 基础设施 | `docker compose --env-file .env -f infra/docker-compose.yml config --quiet`；读取已有容器健康状态 | PASS；PG/MinIO healthy，未启动或重建容器；临时合成预览进程已关闭 |

测试过程如实保留：首次新增夹具用 JDBC 不支持的 Instant；改 Timestamp 后发现直接把草稿改终态违反工单约束，改为先调用真实提交；新夹具共用原测试车主手机号又触发既有派单疑似重复，最终使用独立合成手机号。一次既有 BaiduOcrProviderTest 本机 HTTP 超时模拟偶发失败，保持原断言和超时设置，原样重跑通过。未跳过测试、放宽约束或调用真实百度。

### 本地运行与边界

- 现有 8080 后端仍运行旧的 repair-funds-release-runtime.jar；当前业务库 public 仅应用到 V21。直接更新完整后端会连带此前合并的 V22 网点 OCR 迁移，新 OCR 链路依赖该表，不能仅锁定 V21 启动来声称兼容。本轮保留旧进程和业务库，工作台服务端排除完成案件须在新版后端更新后实际生效；历史列表/导航/快捷入口前端代码已更新。
- 页面视觉使用明确标记的合成数据，不能等同于当前 8080 真实运行包端到端通过。新后端查询已通过真实隔离数据库 API 测试；批量导入后端由真实 MinIO/PG 回归验证。本轮未在业务库导入数据、改变案件状态或执行资金划转。
- H5 未改且未重复运行前端门禁；未做物理手机、多浏览器或全站视觉回归。无生产部署、提交、推送或下一阶段实施。

## 2026-10-15 网点报价表单手写OCR完成（D-047）

- 基线：origin/main 674edf2431062288307bd14f2425ac8e5d6fa6e0，交付前再次fetch仍一致。独立分支 feature/sherr-shop-quotation-ocr；本机工作区 shop-quotation-ocr；原地图分支、旧OCR库及资料目录未覆盖/切换/重置。提交署名使用现有 Sherry070719 / Sherry7191088@outlook.com，不冒用队友。
- 入口：维修网点H5 → 本店案件 → 案件详情 → 报价授权 → 提交原始报价新版本 → 上传手写清单识别。复用百度Provider、Token缓存、原任务/Worker/私有对象存储与候选历史，不新增供应商或独立OCR页面。
- 新增限定作用域和服务端校验：当前本店ACCEPTED派单、允许报价状态、本次有效SHOP_QUOTE/ASSESSMENT。查询/原图/原文/重试/人工确认/提交再验范围；Worker调用前和写回复核。改派、完成、取消、源失效拒绝旧结果。通用 /ocr/** 没有给网点加角色；客服/管理员对网点任务只读，车主拒绝。
- 项目、数量、单位、单价及单据行金额候选；费用仅作普通项目候选，缺数量/单价留空、不造1/0、l不转1、不新增汇总收费模型。原始金额/单据合计与程序计算分开。初始程序/原文/坐标/真实概率不可变，人工版本、确认和最终原始报价来源分层。明确标签或可靠表头/唯一行列才能整理，复杂自由手写仍需人工补充。
- 空白预填、手填冲突添加/替换/忽略、等待期间输入保护、原图对照、人工增删候选和金额差异提示；重复采用/上传幂等，跨图同名不合并、合计不累加。原按钮提交，QuoteOcrLineage在原submitRaw事务内重验已确认内容及范围；只追加不可变网点原始报价，不写正式对外价/核损/价格库/资金。
- 只新增 V18__shop_quote_ocr.sql：任务来源/页次、幂等命令、报价行→确认版本关联，历史追加不可改；V1—V16及dev SQL未改。V17由未合并地图分支保留，本分支不依赖地图。每次派单最多20张不同识别原件（两允许分类合计，重复/重试不占新名额），服务端稳定分配页次；不限制继续手填。字段映射、API、配置和边界见SHOP_QUOTE_OCR.md。

### 实际验证

| 检查 | 实际执行 | 最终结果 |
| --- | --- | --- |
| 后端全量 | JDK21下 backend/.\mvnw.cmd -B -ntp -Ppostgres-it verify；注入专用TEST_DB与TEST_MINIO环境 | PASS：43单元、85真实PostgreSQL17.6/私有MinIO集成测试，失败/错误/跳过全部0；Maven JAR成功。新增5候选单元和9网点OCR集成测试；既有身份/价格/文件/客服OCR/报价/工作流回归保留。最终日志在仓库外shop-ocr-runtime/backend-release-verify.log。 |
| Flyway | 随机空schema迁移至V18、validate；普通/dev迁移、V15→V16原升级测试保留精确target16 | PASS：19份校验记录含schema记录，SQL版本含dev2.1共18份；旧迁移未改，未repair，旧OCR V15数据库未连接或升级。 |
| H5 | npm ci --offline --no-audit --no-fund；npm run lint；npm run typecheck；npm run test；npm run build | 全部PASS，26测试，0失败/跳过。 |
| 管理端 | 上述安装及四项完整门禁 | 全部PASS；网点任务标为内部只读，客服建单OCR原入口不改。 |
| 供应商测试 | 既有16项BaiduOcrProvider本机HTTP模拟 + 新合成文字/坐标/单位/费用/缺失/金额差异测试 | PASS；没有真实密钥或额度依赖。 |
| 权限/并发/历史 | 9项新增PG集成：四角色、跨店/案件/旧派单、ASSESSMENT、失效/冻结、禁用创建者、并发确认、重复提交/上传/多图、失败后手填、租约、原文和价格不可覆盖、确认内容与报价一致、OpenAPI | PASS。人工确认不写报价；原提交生成新版本并存OCR来源；旧版本更新被数据库拒绝。 |
| 手机浏览器 | 新隔离Mock后端18086/H5 5188，shop_ocr_browser_20261015，合成图与两张本店到店案件，360/390像素 | PASS：原图实际加载，无横向溢出；保留手填3×8并明确采用/人工修正2×12.50，通过原按钮提交v1/49.00；另一空表自动预填后改数量4，经原按钮提交v1/40.00。只读API核对两版均有OCR来源，网点无formalQuote/assessment金额。截图在仓库外；最终构建更新同一隔离Mock预览且Flyway重启验证，不重启旧真实OCR进程。 |
| Compose | 检查Docker CLI可用性 | 未验证：本机无Docker CLI。真实PG/MinIO验证不等于Compose验证；需有Docker环境执行原compose config/健康检查。 |

首轮新增集成测试因合成网点编码超出既有VARCHAR(32)失败，修正夹具长度，未放宽数据库约束。H5首轮类型检查发现幂等键默认参数推断过窄，显式标注string，未降类型检查。浏览器同名输入框的自动定位冲突改为按报价/候选分区定位；开发热更新会重载临时表单，稳定代码后复用原任务重做并完成两条提交链路，不声称浏览器刷新可保存临时编辑。

### 未验证与交付边界

本轮真实百度鉴权/OCR均0次，没有读取或重新要求提供百度密钥，没有购买、开通付费或增加旧真实预算。功能实现及离线联动通过，不代表新的真实手写识别准确率验收；未来须另获次数/样本授权。物理手机、所有浏览器与生产并发/部署未验证。页面临时编辑刷新不持久化；同图重传可复用任务，已确认/正式提交历史在服务端保留。与未合并地图V17的完整部署迁移顺序未在本轮验证，应合并时用完整迁移集在隔离库复验，不修改历史或执行repair。

需求、D-047、架构、API和本地说明已同步；下一步仅检查暂存安全、提交推送本功能分支并尝试Draft PR，不合并、不部署、不进入其他阶段。暂存安全检查：30个本任务文本文件，私密运行值/Token/私钥及私密文件模式扫描0命中；既有迁移变动0。Git交付结果在收尾记录补齐。

# 项目进度

## 2026-10-16 本轮提交及main合并范围

- 按用户要求，将codex/case-workflow-redesign当前已完成且未提交的D-051至D-060变更一并提交，范围包括派单Mock地图、网点报价接收与核对、核损授权对照、维修及顺序资金确认、顶栏压缩和右栏完整显示，以及相关测试、迁移与文档。
- 合并前核对main没有独有提交，分支已有d22f617、43ac52d及本轮待提交改动，采用快进方式合入本地main；本次不推送远程。沿用各阶段已记录的测试、构建和浏览器验收，提交前检查差异格式，不重复运行无新变化的构建。
- .env、tmp运行数据、构建产物和本地验收截图均不纳入提交。D-058尚未实施的整页滚动改造保持待实施。

## 2026-10-16 D-060 各流程页面右侧任务栏完整显示完成

- 先更新需求/决策及滚动方案；修改管理端案件详情共用任务栏，取消max-height限高、overflow:auto及滚动条占位，内容按实际高度完整展开，不使用裁切或隐藏溢出来掩盖滚动条。所有节点和案件资料/记录共用该侧栏。
- 收紧标题和材料行间距，矮桌面窗口进一步减少留白，保留12px材料文字、全部必需/已有材料及绿勾红叉。按D-058方案移除当前查看阅读标记、补充材料展开区及冗长缺项列表，补充材料从案件资料查看，右框提示缺项数量；正文提交校验和业务门禁保持原样。移除仅服务于滚动阅读标记和compact的观察器，保留顶栏尺寸监听。
- 实际浏览器只读检查000004资金页与000005完成页，未写案件、材料或资金：1280×720资金页右栏top332.33/bottom615.07；1280×640同页完整可见，正文滚动后top259/bottom541.74；完成页1280×640右栏bottom591.07，1366×768右栏bottom643.68。各次overflowY=visible、scrollHeight=clientHeight，无内部滚动条。截图workflow-sidebar-fully-visible.png位于本任务visualizations目录；浏览器错误日志为空，临时窗口尺寸已恢复。
- 管理端node --test tests/workflow.test.mjs tests/quote-review.test.mjs tests/funds-flow.test.mjs：13项通过，0失败/跳过；npm run lint、npm run typecheck、npm run build通过，日志tmp/workflow-stage2/sidebar-{tests,lint,types,build}.log。未新增仅镜像CSS实现的测试，布局以真实浏览器验收；Git diff --check通过。

本轮仅改共享右框与相关文档，后端/H5及状态金额规则未改，未重复其构建测试；无依赖安装、运行数据写入或服务重启。其他节点通过同一模板统一生效，未逐节点新建工单验收；物理手机及其他浏览器未验证。D-058整页单滚动和正文预览方案仍待实施。仍在codex/case-workflow-redesign，未提交/推送，本阶段完成并停止。

## 2026-10-16 D-059 流程顶栏压缩与结案按钮反馈完成

- 按本次范围局部实施：调整顶栏排版和间距，1280×720同一页面实测高度245.59px→164.33px（66.9%），完整保留摘要和九个节点，滚动时摘要不淡出；首屏高度回退同步为164px。D-058整页单滚动及右框方案仍待实施，本次未扩大范围。
- 查明000004无法结案的原因是应付目标尚未设置、有效结算流水为0。确认按钮改为可点击检查，缺项在按钮附近明确提示并定位到金额或流水输入；刷新后版本或资金方向变化要求重新核对，不会用原来的点击确认新金额或下一方向。现有服务端顺序、结清、权限及版本门禁保留，不自动补金额或结案。
- 000004实际点击后提示先保存应付金额、再记录结算流水，未发起资金确认写入，最终只读复核仍WAITING_SHOP_SETTLEMENT、资金版本3、应付为空/实付0。仅已有合成000005通过正常冲销和重录模拟PAY520.00，再从浏览器点击确认结算成功COMPLETED，版本13，两步确认均有效；没有真实资金转账。

| 验证 | 结果 |
| --- | --- |
| 管理端定向测试 | node --test tests/funds-flow.test.mjs tests/funds-assessment.test.mjs tests/workflow.test.mjs tests/authorization-price.test.mjs tests/quote-receipt.test.mjs tests/quote-review.test.mjs：25项通过，0失败/跳过。新增覆盖缺目标/未结清反馈、定位输入、不发送确认、合法结算、并发版本/方向变化及零额。日志tmp/workflow-stage2/header-settlement-tests-final.log。 |
| 前端门禁 | npm run lint、npm run typecheck、npm run build最终通过；日志tmp/workflow-stage2/header-settlement-{lint,types,build}-final.log。Git diff --check通过。 |
| 浏览器/API | 顶栏压缩后摘要完整，滚动前后透明度均为1；000004缺项点击提示并聚焦金额，000005满足条件后实际结案。浏览器错误日志为空。截图settlement-validation-and-compact-header.png位于本任务visualizations目录，最终只读快照tmp/settlement-fix-final-snapshots.jsonl。 |

本次仅修改管理端及相关设计/进度文档，未改后端/H5、未安装依赖、未重启服务，故未重复后端/H5构建。保留独立Vue测试夹具既有非挂载上下文警告，未屏蔽错误。未验证物理手机或其他浏览器。仍在codex/case-workflow-redesign，未提交/推送，本阶段完成并停止。

## 2026-10-16 D-058 客服案件页滚动方案完成，待实施

- 用户确认顶部案件摘要及完整流程固定，滚动不改变形态；右侧任务框固定、完整显示、无内部滚动；矮窗口仍保留右框，优先精简文字和压缩间距。
- 已形成[客服案件链路滚动交互方案](CUSTOMER_SERVICE_SCROLL_PLAN.md)：正常页面仅中间正文纵向滚动，固定头部/右框/操作区；减少右框重复说明，完整缺项仍由正文和继续校验展示；取消单据纵向嵌套滚动，放大层独立阅读并锁定背景，保留必要表格横向查看。列明1280×720、1366×768、1280×640验收及节点切换/位置保持规则。
- 当前阶段仅讨论和文稿，没有修改前端代码、后端接口或运行数据，没有执行构建/测试。代码实施与浏览器验收均待方案确认，未提交/推送。

## 2026-10-16 D-057 资金页面核损参考与顺序录入完成

- 先更新REQUIREMENTS/DECISIONS/设计稿。资金页增加核损原件及最终确认金额，只显示有关联且客服已确认的采用金额；来源缺失/未确认/下载失败均明确提示。图片可放大与受控下载，PDF按页预览，过期请求不能覆盖新数据，页面切换/卸载清理Blob URL。已确认核损金额只作参照，应付仍由客服人工录入。
- 移除方向选择，当前方向根据后端真实阶段计算：先保险到账，再网点结算；阶段切换清空上笔金额、流水号和备注，时间刷新为中国时间当天/当前时刻，允许手改。已完成的新流程只显示结果。正常办理删除“金额依据”输入，目标版本使用准确的系统操作说明满足现有审计字段；冲销通过独立窗口填写原因，不能空原因提交。备注明确选填。旧资金记录的记账能力保留。
- 只改管理端的资金组件、日期/方向小函数与定向测试；后端接口、数据库、资金顺序与权限门禁沿用现有实现，无依赖安装，无新迁移或后端重启。

| 验证 | 结果 |
| --- | --- |
| 管理端最终定向测试 | node --test tests/funds-flow.test.mjs tests/funds-assessment.test.mjs tests/workflow.test.mjs tests/authorization-price.test.mjs tests/quote-receipt.test.mjs tests/quote-review.test.mjs：23项全部通过，0失败/跳过。覆盖无方向选择、状态决定方向、禁止未结清确认、目标操作说明、空备注、默认中国时间/跨日、步骤清空、旧记录及独立冲销原因、图片/多页PDF/错误来源/过期响应/URL回收，以及现有报价、授权、时间轴回归。日志tmp/workflow-stage2/funds-simple-tests-final.log。 |
| 前端门禁 | npm run lint、npm run typecheck、npm run build最终通过，日志funds-simple-{lint,types,build}-final.log。最初发现冲销弹窗可空时间类型不匹配，已修正；未放宽类型检查。Vue独立渲染夹具的非挂载上下文警告保留，实际浏览器错误日志为空。 |
| 浏览器/API验收 | 仅在已有合成000005记录一次带说明的冲销并重录恢复完成：看到有效核损原件、620.00最终确认金额，放大成功；无方向select；实际结算520.00，备注留空、时间使用自动默认2026-10-16T17:32，保存后仍须人工确认结算。GET复核note为空、PAY/520.00/发生时间留档、COMPLETED且两步确认有效。API记录tmp/funds-simple-verification.json，截图funds-simplified-payment.png。 |

000004只读核对时已处于WAITING_SHOP_SETTLEMENT，运行验收没有修改或回退该工单；没有真实资金转账或使用外部OCR。最终单据预览、已完成视图和控制台再次复查通过。PDF分页主要由定向测试及既有预览API验证，本轮真实浏览器使用PNG核损单；未验证物理手机或其他浏览器。后端未改，未重复后端构建测试。前端5173热更新已生效，Git diff --check通过。阶段完成并停止，仍在codex/case-workflow-redesign，未提交/推送。

## 2026-10-16 D-056 维修合并、资金顺序及滚动修复完成

- 已按用户两项答复实施：时间轴“维修授权＋维修”合为“维修中”，保留内部授权状态、核损最终价格校验与网点开始维修；网点当前派单完工照片提交后进入收车。收车改为WAITING_INSURER_PAYMENT，客服人工确认到账后WAITING_SHOP_SETTLEMENT，人工确认结算后COMPLETED。记录完整流水不会自动确认或结案。
- 新增V21资金确认不可变记录，关联收车、人工目标、有效流水ID、金额依据哈希、操作者、版本及时间；零额也须先设置目标并按序人工确认。幂等与版本锁复用资金接口，付款须当前派单及有效到账确认。目标修订、冲销、收车撤回/重新确认会使旧确认失效并退回相应步骤，保留原账目与审计；历史已结案不批量改状态，时间轴资金节点无记录则显示未留档。资金始终仅记账，不执行真实付款，结算额不自动推算。
- 管理端资金当前任务分为到账、网点结算两步，后一步在到账确认前禁用；录入人工金额、流水/凭证编号和中国时间，结清后另点确认。历史分笔及修订折叠保留，两端收车提示、可选评价和客服撤回兼容新状态；资金确认信息仅员工可见，网点只看本店结算。收车后材料与旧通知单缺失说明冻结。
- 移除报价底栏IntersectionObserver固定/普通布局切换及尺寸监听，改为留在文档流内的CSS sticky，底栏用不透明底色。右栏移除位移/过渡与毛玻璃，固定头部事实区高度，限制右栏高度并独立滚动，预留页面底部空间，避免底部受父容器挤压到头部下方。保留阅读标记，无依赖安装。

| 验证 | 结果 |
| --- | --- |
| 后端完整检查 | verify.ps1 -IntegrationTest QuotationPostgresIT,MaterialsPostgresIT,WorkOrderPostgresIT -LogName repair-funds-release：50单元＋58集成通过，0失败/错误/跳过，Maven构建、23份Flyway校验通过。覆盖有/无权限、顺序/部分到账/过期版本/幂等、零额、修订/冲销退回、重新收车、历史结案兼容及确认快照不可修改。日志tmp/workflow-stage1/repair-funds-release.log（delivery同样通过）。列表办理人补正后，repair-funds-list-actor-final又通过50单元＋1项完整资金链路集成，Maven构建通过，覆盖列表FUNDS/客服及网点无资金待办。 |
| 管理端 | 最终17项定向测试、lint/typecheck/build均通过，日志tmp/workflow-stage2/repair-funds-admin-*-release.log。包含跨资金步骤清空金额、凭证、时间和备注的断言，避免将到账输入误用于网点结算。Vue独立渲染夹具非挂载上下文警告保留，实际浏览器错误日志为空。 |
| H5 | 20项测试及lint/typecheck/build通过；日志tmp/workflow-stage2/repair-funds-h5-*.log。保留网点开始维修，收车后车主可选评价不被资金进度阻塞。 |
| 本机真实API及浏览器 | 已核验并更新8080开发运行时，V21迁移成功。仅原合成案件542e6cbb-9335-48c0-b884-1db678ed3d79：授权620.00→网点开修→网点完工照→客服页面收车→两笔模拟到账600+20仍停资金节点→页面确认到账→页面人工应付520/时间/凭证/实际流水→仍待确认→页面点击确认结算并结案。API复核COMPLETE/COMPLETED及两项确认true。未修改真实案件或做真实支付。 |
| 滚动验收 | 本机1280×720浏览器在新版维修授权长页面反复到底/向上/到底，页面高1983、底部scrollY1262.67、右栏top340、底栏top562.25保持一致，右栏在头部bottom321.59以下，单个底栏且无固定/普通切换。截图workflow-scroll-stable.png；完成截图funds-workflow-complete.png，均在本任务visualizations目录。未验证物理手机/其他浏览器。 |

初次构建发现旧节点测试引用已移除枚举，按合并后的节点语义更新；首轮集成发现资金阶段仍可写旧通知单缺失说明，修复材料冻结后完整通过。合成脚本初次开修漏传assignmentVersion被403拒绝，补齐真实派单版本后通过，未放松后端权限。曾因开发登录过期重新登录；未使用真实用户图片或外部计费OCR。

收尾复核发现新资金结算待办PAY_SHOP被旧“名称以SHOP结尾”规则分到网点，已明确分给客服并补充网点待办不可见断言；release完整回归通过。案件列表的资金阶段办理人同步补为客服，定向完整资金链路验证通过。已再次核验运行进程并切换8080至repair-funds-release-runtime.jar；5173显示最终前端。进度及主设计流程/API节点枚举已同步，阶段完成并停止。仍在codex/case-workflow-redesign，未提交/推送。

## 2026-10-16 D-055 维修授权单据与最终价格校验完成

- 用户已确认“定损单”是上一阶段正式报价单，不增加独立ASSESSMENT材料必传条件。维修授权默认对照正式报价和当前最终核损原件，最终授权以核损单经人工核对采用金额为准，允许与正式报价总额不同。
- 客服最终确认须填写授权金额、勾选已核对两份单据；提交assessmentId/lossFileId/amount/originalReviewed。服务端在案件锁、报价版本和当前派单校验下，检查金额对应的正式报价、当前最新有效核损文件、已确认OCR修订及采用金额；金额不一致、文件/金额版本不符、缺核对来源均拒绝，旧客户端仅传expectedVersion不能完成最终确认。OCR失败后的人工补录/原件确认仍有效，不能直接信原始OCR数值。
- 复用不可变quotation_confirmation.file_snapshot保存最终金额、核损ID、文件ID和识别修订，审计摘要记录核损版本及金额；核损材料变更继续撤销确认，重新确认和网点开修均重查当前来源。历史无原件关联的直接金额录入须返回“修正核损”补做核对，不伪造历史识别关系。
- 浏览器发现原PDF iframe为空白，已用现有PDFBox增加受控逐页PNG预览，正式报价和PDF核损单都支持页码切换，原件仍可下载，图片核损直接显示。复用鉴权/案件范围/审计，页码越界400，no-store/nosniff，单页长边最多1800像素。旧请求/组件卸载不覆盖新单据，Blob URL清理；单据加载失败禁止最终确认，只有源版本变化才清除已填写核对，保险代录等同源刷新不清空输入。无新依赖或数据库迁移。

| 验证 | 结果 |
| --- | --- |
| 后端最终 `verify.ps1 -IntegrationTest QuotationPostgresIT,MaterialsPostgresIT -LogName authorization-preview-delivery` | 50项单元+45项集成全部通过，0失败/错误/跳过，Maven构建成功，22份Flyway迁移校验通过。新增覆盖核损金额≠报价允许、错误价格/错文件/错金额版本/未勾选/无识别关联拒绝、材料更新旧来源失效、重新核对后恢复、幂等及确认快照、三种非客服角色拒绝、真实PDF转PNG/多页与页码边界、跨案件及原文件权限。日志tmp/workflow-stage1/authorization-preview-delivery.log。 |
| 前端定向测试 | `node --test tests/authorization-price.test.mjs tests/quote-receipt.test.mjs tests/quote-review.test.mjs tests/workflow.test.mjs`最终15项通过，覆盖原件未加载/价格不符/未核对拒绝、报价与核损不同、合法金额规范化、下载失败/旧响应/URL清理、PDF核损与正式报价翻页、只读角色以及既有流程和报价回归。模板夹具的Vue非挂载上下文警告保留，真实浏览器错误日志为空。 |
| 管理端 lint / typecheck / build | 最终全部通过。最终前端日志tmp/workflow-stage2/authorization-{tests,lint,types,build}-delivery.log。 |
| 本机真实API和浏览器 | 核验进程身份后将8080更新至authorization-preview-runtime.jar，5173热更新。仅新合成案件542e6cbb-9335-48c0-b884-1db678ed3d79：两张合成网点报价→正式报价628.61→Mock核损原件及人工采用620.00。实际看到左侧正式报价PDF原页图像、右侧核损原图；记录保险确认后填628.61被提示“确认金额必须与最终核损单采用金额一致”，填620.00并核对后授权成功、进入等待网点。GET复核authorized=true/sourceCurrent=true，报价628.61和核损620.00均保留，未开始维修。 |

初次完整检查被既有百度OCR超时测试偶发的认证超时中断（预期识别超时），未改/跳过该测试，重跑通过。旧集成夹具直接记金额后上传文件，不符合本轮已确认规则，已改为通过真实Mock识别/人工核对/关联接口准备合法证据，并相应断言两份不可变金额版本及缺文件同时使来源失效；初次旧数量断言失败日志保留。最终价格版与新增PDF预览版均完整验证通过。启动期间首次合成脚本连接被拒、热更新期间预览404均在健康启动/重载后恢复，未修改真实案件。

截图authorization-price-blocked.png、authorization-price-final.png位于本任务visualizations目录；合成API记录tmp/authorization-{prepare,verify-final}-verification.json。用户参考图与真实资料未用于本轮验收，未触发外部计费OCR、未改变现有正式案件状态。阶段完成并停止，仍在codex/case-workflow-redesign，未提交/推送。

## 2026-10-16 D-054 原图对照报价审核完成

- 用户当次确认允许客服纠正项目、数量和单价，保留网点原报价及修订记录，覆盖旧单项不可改规则。先更新REQUIREMENTS/DECISIONS/API/设计稿后实现。
- 案件流程关联导航仅保留当前任务、案件资料、记录，所有节点不再显示资金/投诉按钮；独立模块与旧合规路由权限不改。
- 报价审核改为左侧原件、右侧四列核对表，下方加价率。支持多原件切换、100%—250%放大/还原、受控下载、加载/空白/失败状态；保留“项目、数量、单价、金额”列及行序，可修正项目/数量/单价和补齐/删除误识别行，行金额由数量×单价计算。窄屏上下排列，未关联历史原件明确提示，不把后上传文件当成原件。
- 新报价在网点提交事务中将当前派单有效报价原件快照到quote_raw_file（上限20，排除客服代传）；V20历史仅回填已有OCR明确关联。上下文返回该版本originals，客服复用网点已提交核对明细，不重做OCR、不改变其原始识别记录。
- /formal-quotes可选reviewedLines，客服限定，后端仍校验状态、当前报价、派单、版本及物损照片；另存不可变quote_formal_review和QUOTE_REVIEW审计，正式报价行采用核对值，原raw和历史正式版本保留。原固定金额API兼容。前端以BigInt按分预览0—100%加价，服务端BigDecimal沿用HALF_UP重算；保存后的百分比和核对行能再次带回。并发新报价到达时保留未提交输入但禁止套用到新版本，需明确放弃修正并重新载入。

| 验证 | 结果 |
| --- | --- |
| 后端 `verify.ps1 -IntegrationTest QuotationPostgresIT,MaterialsPostgresIT -LogName quote-review-final` | 49项单元+42项集成全通过，0失败/错误/跳过，Maven构建成功；22份Flyway迁移校验通过。新增覆盖多图版本绑定/排除代传/后上传不混入、核对原价保留、修订金额/HALF_UP、非法数量与精度、仅客服可写、幂等/陈旧版本拒绝、快照不可修改、网点看不到客服修订。日志tmp/workflow-stage1/quote-review-final.log。 |
| 管理端定向测试 | `node --test tests/quote-review.test.mjs tests/quote-receipt.test.mjs tests/workflow.test.mjs`最终11项通过；覆盖精确大额/小额半分/零价/非法输入、编辑不覆盖源、切图URL清理/过期响应、开发预览只读、并发报价禁止静默套用、核对与百分比回填、5种节点导航无资金/投诉、既有时间轴/轮询。独立模板渲染夹具的Vue非挂载警告保留，真实页面无错误日志。 |
| `npm run lint` / `npm run typecheck` / `npm run build` | 最终通过，最终测试与检查日志为tmp/workflow-stage2/quote-review-{tests,lint,types,build}-final.log。无依赖安装。 |
| 实际浏览器及API | 更新已核验8080项目Java进程为quote-review-runtime.jar，开发库新增V20迁移成功，5173使用新界面。新合成案件a6504123-6ff7-4b23-bfd4-257e9dbcea51，网点提交两张合成报价原件和3行明细；客服切第2张、放大、回第1张成功，导航仅3项。修改项目名、第2项数量3/单价48.25并输入7.50%后即显示584.75+43.86=628.61；实际点击继续成功进入保险核损，GET确认正式报价628.61、修订行留档、原网点报价533.00/原数量2及单价46.50不变、两张原件仍关联。 |

验收只用标有“合成验收”的自制图片和模拟案件，未上传用户参考图或真实聊天资料、未修改真实案件。浏览器登录曾过期，使用既有开发账号重新登录后验证完成。截图quote-review-comparison-final.png和quote-review-submitted-final.png位于本任务visualizations目录，API记录tmp/quote-review-submitted-verification.json。本轮未再次浏览器运行真实OCR供应商识别，OCR核对链路由原有集成测试覆盖；任意表单版式逐像素复刻与手写识别准确率不在本轮验证范围，当前按用户示例四列表格对照。历史无明确关联的原件不补造关系，需网点核对重提。

仍在codex/case-workflow-redesign；未提交/推送，本阶段完成并停止。开发只读预览入口沿用D-053，后端状态检查保持启用。

## 2026-10-16 D-053 客服网点报价接收与只读开发预览完成

- 客服网点报价当前任务改为报价原件、到店照的只读接收状态和报价提交确认，红叉待上传、绿勾网点已上传。报价办理页共享MaterialsPanel整体不渲染，避免进入报价审核后又出现同一上传区；建单物损照片、案件资料及核损单专用识别/核对窗口保留。
- workflow材料新增shopUploaded，复用当前派单有效文件汇总及上传时不可变审计角色；客服历史代传、已作废文件不显示网点已上传，不改变present/satisfied和既有上传权限。无数据库迁移、依赖或生产配置变更。
- 等待节点每8秒只读刷新，可见页面且无正在提交操作时才请求；离开/卸载清理定时器，旧请求响应失效，失败提示手动刷新。收到网点正式提交报价后刷新真实节点进入报价审核；只上传文件不制造报价或推进。
- 用户答复确认临时下一步仅预览。Vite开发模式客服专用“预览报价审核”显示只读表单和明确提示，可返回，时间轴保持真实网点报价等待态；不能提交、不写案件、不生成测试金额，生产构建不开放，后端门禁始终有效。结束客服开发时应删除此预览入口，无需重新开启后端检查。

| 验证 | 结果 |
| --- | --- |
| 后端 `verify.ps1 -IntegrationTest MaterialsPostgresIT,QuotationPostgresIT -LogName quote-receipt-final` | 49项单元+41项集成通过，0失败/错误/跳过，Maven构建成功，21份Flyway迁移校验通过。新增回归验证客服代传≠网点上传、上传不推进、缺报价审核被拒、作废取消接收标记、网点正式提交进入QUOTE_REVIEW；既有报价/越权/跨网点与OCR集成通过。日志tmp/workflow-stage1/quote-receipt-final.log。 |
| 前端 `node --test tests/quote-receipt.test.mjs tests/workflow.test.mjs` | 最终6项通过：接收来源、预览无写入/禁用字段、生产/非客服无入口、轮询更新/隐藏/过期响应、报价办理共享上传区隐藏、真实时间轴与旧链接回归。初次测试夹具组件引用/TS模板编译/含箭头表达式匹配的失败已修正并保留日志，未删除或跳过测试；独立渲染夹具会输出Vue非挂载上下文警告，实际页面错误日志为空。 |
| 管理端 lint / typecheck / build | 最终通过，日志tmp/workflow-stage2/quote-receipt-{tests,lint,types,build}-delivery.log。生产构建成功，不安装依赖。 |
| 实际开发环境浏览器与API | 8080后端核验进程身份后更新为通过验证的quote-receipt-runtime.jar，5173热更新。仅新合成案件41df9e3b-9b98-4952-a763-e690541b7b10：等待页上传框=0；打开预览后加价字段禁用、无提交按钮、GET确认仍SHOP_QUOTE/canAct=false；开发网点上传两份合成PNG后页面自动出现两项绿勾仍未推进；提交100元合成报价后无需手动刷新即进入报价审核，时间轴由你办理，审核页文件框/案件附件标题均=0。 |

截图quote-receipt-waiting.png、quote-review-preview.png、quote-receipt-received.png、quote-review-auto-entered.png保存在本任务visualizations目录；本地合成记录tmp/quote-receipt-*-verification.json。人工原始报价提交链路与自动刷新已现场验证；OCR原件关联链路本轮由既有集成测试验证，未在本轮浏览器重新走OCR。未推进真实案件、未提交/推送。本阶段完成并停止。

## 2026-10-16 D-052 地图定位与深圳合作网点推荐完成

- 用户最新要求覆盖D-051附件只读展示：派单到店当前任务完全不渲染案件附件，案件资料和右侧材料状态保留，不删除原文件或更改门禁。
- 定位区域改为城市输入筛选、聊天文字地址自动带入、地址可改/确认、Mock地图定位。复用MapProvider，新增/maps/cities分页字面搜索、地图候选坐标和距离字段；服务端全候选排序后分页，最近有距离的网点为推荐。地图标记与列表都可选择，修改城市/地址清空推荐并禁用派单，重新打开已确认地址保留确认及实际派单网点。
- 通过管理员接口在实际开发库新增/配置广东省、深圳市和SZ-PARTNER-01—10，名称逐字采用用户名单，全部启用并覆盖深圳市，不创建“active”网点。未提供真实地址，因此门店地址明确含“模拟定位，待补详细地址”，无外部地图请求、无虚构真实道路距离、无生产种子迁移。
- 管理员/客服可使用城市和地图接口；网点/车主拒绝，案件范围和原派单状态/版本/地址确认约束不变。通过已核验身份的进程重启更新8080实际开发后端，5173热更新及生产构建使用新版，保留原业务数据和本地存储连接。

| 验证 | 结果 |
| --- | --- |
| 后端 `verify.ps1 -IntegrationTest WorkOrderPostgresIT,MaterialsPostgresIT -LogName dispatch-map-final` | 49项单元+16项集成全部通过，0失败/错误/跳过，Maven构建成功；验证城市筛选/空结果/分页上限、ADMIN/客服允许、网点/车主拒绝、地图候选全局排序/距离与Mock坐标、既有材料和案件隔离；21份迁移校验通过，本轮无迁移。日志`tmp/workflow-stage1/dispatch-map-final.log`。 |
| 前端 `node --test tests/dispatch-map.test.mjs tests/workflow.test.mjs` | 最终6项通过，覆盖地址候选、定位顺序、最近推荐、手动覆盖、改地址清空、定位失败不可派单、已确认地址重开不重复保存/不覆盖原派单网点及地图坐标边界。日志`tmp/workflow-stage2/dispatch-map-tests-delivery.log`。 |
| `npm run lint` / `npm run typecheck` / `npm run build` | 最终全部通过，日志`dispatch-map-lint-delivery.log`、`dispatch-map-types-last.log`、`dispatch-map-build-last.log`在tmp/workflow-stage2。 |
| 实际浏览器 | localhost:5173，新合成聊天记录建单并带入“深圳市南山区科技园合成接车位置”；输入“深圳”搜索城市成功，定位出现10个合作网点和最近推荐，地图与列表改选均生效；修改地址后标记=0、派单禁用。最终人工改选“小哈快修白石洲”后派单成功，GET复核PENDING_ACCEPTANCE、对应网点ID、assignmentVersion=1。案件附件区域=0，浏览器错误日志为空。 |

所有验收仅合成案件`5617236e-497f-4031-a484-36f205ec0be4`，未推进真实用户案件。截图`dispatch-map-final.png`、`dispatch-map-submitted.png`保存本任务visualizations目录；本地记录`tmp/shenzhen-partners.json`、`tmp/dispatch-map-recommendation.json`、`tmp/dispatch-map-submitted.json`。初次组件测试编译别名未转换、初次后端地图全局安全匹配仍限客服的失败已修复，保留失败日志，最终检查未跳过测试。`git diff --check`通过。

尚需补齐：10家店真实详细地址或坐标，随后选择真实地图供应商/凭据并另行验证真实定位和路程。当前Mock范围实现、回归和页面验收完成；仍在codex/case-workflow-redesign，D-051与本轮改动未提交/推送。本阶段停止。

## 2026-10-16 D-051 派单只读材料与模拟网点完成

先按用户要求提交此前D-048—D-050改动：`43ac52d fix: complete case intake validation and submission recovery`，提交后工作区为空；私密.env和tmp未提交。仍在`codex/case-workflow-redesign`，本轮以下新改动未提交/推送。

- 派单当前任务的MaterialsPanel传入空写入类别，仅保留已上传材料列表与受控下载，上传框/上传按钮/替换/作废全部消失。其余节点沿用原类别，建单照片、服务端状态/权限门禁、右侧材料绿勾不改。
- 在实际开发后端8080通过既有管理员接口新增启用网点`DEV-DISPATCH-DEMO`，名称“模拟维修网点（派单测试）”，本地ID=2，覆盖DEV-PROVINCE/CITY/DISTRICT。地址和联系人明确为模拟，未新增账号或生产种子迁移，原开发网点不修改，管理员接口留有创建/服务区域审计。
- 合成案件`195e5d7f-3292-47ae-931f-65f169cbb3d2`上传合成照片、正式建单、确认模拟区域与接车地址后，API候选包含新网点并返回Mock距离。实际localhost:5173页面选择新网点并点击派单成功；只读GET复核状态PENDING_ACCEPTANCE、currentAssignment.shopId=2、assignmentVersion=1。真实案件未改派或推进。

| 验证 | 结果 |
| --- | --- |
| 管理端 `npm run lint` / `npm run typecheck` / `npm run build` | 通过，最终日志`tmp/workflow-stage2/dispatch-ui-{lint,types,build}-final.log`。 |
| `node --test tests/workflow.test.mjs` | 2项流程/时间轴回归通过，`tmp/workflow-stage2/dispatch-ui-tests.log`。 |
| 实际浏览器与API | 本机5173/8080，合成案件实际派单成功并持久化；派单页文件输入框=0、替换按钮=0、作废按钮=0，仍显示dispatch-demo.png及受控下载，等待网点为黄色。记录`tmp/dispatch-demo-result.json`、`tmp/dispatch-demo-verification.json`；截图`dispatch-demo-ready.png`、`dispatch-readonly-final.png`位于本任务visualizations目录。 |
| 范围 | 后端源码/依赖/迁移未改，无需重复后端构建或数据库迁移；本轮未新增测试账号，不改变现有数据范围，Mock地图不调用真实地图。`git diff --check`通过。 |

操作方式：在“模拟覆盖省/市/区”中选择服务区域，填写并确认接车地址，再选择“模拟维修网点（派单测试）”派单。开发站点按既有CORS配置使用localhost:5173；初次用127.0.0.1地址登录被原CORS限制拒绝，未放宽配置，切换文档指定地址后登录与派单通过。本轮完成并停止。

## 2026-10-15 D-050 开发环境STORAGE_UNAVAILABLE已恢复

D-049三项任务已完成后，用户报告正式建单在物损照片上传阶段返回503 STORAGE_UNAVAILABLE。本轮仅处理实际运行配置与恢复验证，不绕过文件落库/必填/权限门禁。

- 定位：MinIO容器healthy、9000健康端点200，8080/18095均健康UP。使用合成资料分别创建草稿并上传同一PNG：8080返回503 STORAGE_UNAVAILABLE，18095返回200。根目录私密.env只有MINIO_ROOT_*容器配置，未提供应用MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY；应用MinioObjectStorage因此拒绝操作。
- 修复：仅本地.env补齐现有MinIO连接、访问账户和默认私有桶配置，未输出或提交凭据；.env确认被Git忽略。更新.env.example和LOCAL_DEVELOPMENT，说明容器配置不自动映射应用配置。核验8080原Java/Maven进程身份后重启该开发后端，使用D-049已通过验证的构建和原ev_insurance数据库；先前验证的V19随启动正常应用，保留数据、对象卷和用户草稿，不重启无关服务。预览18095未改。
- 恢复验收：`node tmp/check-storage-runtime.mjs`复现对照后，`node tmp/verify-storage-recovery.mjs`在其原8080合成草稿上重试上传200，同键重放返回同一文件ID；鉴权下载200且PNG字节完全一致；正式提交成功为PENDING_DISPATCH；通过5173代理读取附件200，文件仅一份。结果在忽略目录`tmp/storage-runtime-result.json`、`tmp/storage-recovery-result.json`；服务日志`tmp/storage-fixed-backend.stdout.log`。未代用户提交真实案件。
- 本轮无业务源码、依赖或测试规则改动，未重复已通过的Maven/前端构建。D-049的49单元+29集成、15前端定向测试及lint/类型/构建记录继续有效；本轮补充真实开发环境的上传/下载/幂等/提交/代理验证。`git diff --check`通过，未提交/推送。

原弹窗保留草稿ID、所选照片及上传键，可直接重试原提交，不应重新建单。恢复完成，本阶段停止。

## 2026-10-15 D-049 仅三项建单修正完成

- TXT/文件夹导入成功后自动提取并填入，包括标的车牌。用户两份原模板在本地只读解析均返回号牌，原入口只读文件未自动调用提取；已修正该入口。保留原文与其他字段冲突核对，不读取照片文字、不调用真实OCR。
- 查勘员采用“姓名 电话”合并格式。明确姓名优先，否则采用聊天查勘昵称；直接预填、可手改，无单独姓名候选确认，已有手改姓名不覆盖。原文整体核对与其他信息冲突机制不变。
- 颜色、品牌、车型改为选填，其他必填/照片门禁不变。V19移除工单身份约束中的品牌/车型必填，以及正式报价抬头对应NOT NULL，缺失值PDF留空。旧迁移、历史记录、报价金额/权限规则不改。

| 验证 | 结果 |
| --- | --- |
| 管理端 `node --test tests/intake-files.test.mjs tests/intake-recovery.test.mjs tests/text-intake.test.mjs` | 15项全部通过，实际组件编译执行覆盖TXT/文件夹自动提取、姓名不确认/不覆盖手改、三项留空及其余必填。 |
| 管理端 `npm run lint` / `npm run typecheck` / `npm run build` | 通过。日志 `tmp/workflow-stage2/intake-three-{lint,types,build}.log`。 |
| 后端 `verify.ps1 -IntegrationTest WorkOrderPostgresIT,IdentityPostgresIT,OcrIntakePostgresIT -LogName intake-three-delivery` | 49项单元+29项集成全部通过，0失败/错误/跳过，Maven构建成功。覆盖空颜色/品牌/车型正式建单→派单→报价、逐个缺必填项拒绝、权限、PDF空值和旧迁移升级兼容；21份Flyway迁移校验通过。日志 `tmp/workflow-stage1/intake-three-delivery.log`。 |
| 模板与浏览器 | 两份用户原TXT本地只读解析均有号牌/姓名，未输出个人信息或将原件复制入仓库。17185/18095预览已更新；浏览器上传合成TXT后未点提取按钮，号牌/昵称姓名/电话自动填入，无姓名待确认提示，手改姓名成功；三项选填标识正常。截图 `case-intake-three-final.png` 保存到本任务visualizations目录。未提交浏览器中的合成草稿。 |

两次失败保留记录：首次空品牌/车型建单被V18数据库约束拒绝，新增V19；随后建单成功但正式报价抬头NOT NULL阻塞，在尚未进入共享预览前完善V19并补充PDF空值断言，最终全量上述定向回归通过。迁移清单/升级版本断言同步更新，未删除或跳过测试。预览仅重启已核对身份的本任务进程，未改变原案件状态。

仍在 `codex/case-workflow-redesign`，本轮未提交/推送，无依赖安装。三项修改及验证完成，本阶段停止。

## 2026-10-15 D-048 号牌、合并输入与提交恢复完成

先按用户要求将此前工作流/建单实现提交为 `d22f617 feat: implement guided case workflow and intake`。本轮仍在 `codex/case-workflow-redesign`，下面修复为该提交之后的本地改动，未再次提交或推送。

- 已更新REQUIREMENTS、DECISIONS、CASE_WORKFLOW_REDESIGN与接口说明。按最新答复采用“标的车牌”及无冒号写法，支持普通/新能源机动车与电动自行车号牌；规范化常见分隔符，地方特殊格式提示按原件核对，明显无效号牌不预填。标的/司机电话仍不作为车主电话，不把结构校验等同登记真实性验证。
- 建单/草稿编辑改为七项输入，车牌号/车架号共用一格、二选一；保留自动分类和手选类型，底层字段保持兼容。查勘员姓名+电话共用一格，明确电话直接预填，缺姓名提示人工补齐。新提取车牌与手填车架号冲突必须选择，不覆盖原输入。
- 用户指出的原案件只读查验为PENDING_DISPATCH，2张物损照片有效，未更改其数据。已确认保存成功后详情旧哈希资源失效也会被旧代码统一catch误报草稿；未将此单一路径宣称为所有服务器错误的根因。现分离草稿保存、上传、正式提交和导航错误；响应不确定先GET核对，已正式建单就使用现有结果，重试保留草稿ID和提交键；旧详情资源异常改用已保存地址重新加载。

| 验证 | 实际结果 |
| --- | --- |
| 后端 `pwsh -File tmp/workflow-stage1/verify.ps1 -IntegrationTest WorkOrderPostgresIT -LogName intake-correction-verify -BuildDirectory tmp/workflow-stage2/backend-build` | Java21/Maven verify成功，48项单元+11项工单集成通过，0失败/错误/跳过；包含标的号牌、无冒号、电话角色、非法号牌拒绝、缺项和完整建单链路。Flyway校验20份迁移，本轮无新增或修改迁移。日志 `tmp/workflow-stage1/intake-correction-verify.log`。 |
| 管理端 `npm run lint` / `npm run typecheck` | 通过；日志 `tmp/workflow-stage2/intake-correction-lint-final.log`、`intake-correction-types-final.log`。 |
| 管理端 `npm test` / `npm run build` | 69项测试全部通过、生产构建成功；提交响应丢失、重复确认后响应丢失、已提交草稿重试、照片上传失败、稳定提交键、过期详情模块恢复及合并输入保护均覆盖。日志 `tmp/workflow-stage2/intake-correction-tests-verified.log`、`intake-correction-build-verified.log`。 |
| 实际页面 | 独立Mock预览17185/18095、合成文字+1张合成PNG：无冒号标的车牌填入唯一编号框，查勘员自动显示姓名+电话；地方电动自行车示例显示人工核对提示。正式提交成功进入案件详情，平台编号EVR-20261015-000002，节点为派单到店，现场物损照片显示已上传，原文地址保留到派单确认；浏览器错误日志为空。未在浏览器故意破坏资源，过期模块/丢响应由自动化回归覆盖。 |
| 其他范围 | H5源码、依赖、权限模型、数据库结构未改，本轮不重复无关全项目测试。`git diff --check`通过。 |

验收图保存在本任务visualizations目录：`case-intake-correction-form.png`、`case-intake-correction-submitted.png`。原聊天记录/照片未复制入仓库，未调用真实OCR或地图。中途一次模板引号转义导致类型/构建失败，修正后全部检查通过；一次测试进程在鉴权用例计时器退出前被中断，最终完整测试等待退出并全部通过。浏览器验收曾因自动审批服务额度不足暂停，用户要求继续后已恢复并完成。

需后续检查：真实地方特殊号牌样本的人工核对反馈；真实部署更新期间的旧页面恢复。当前实现、回归、页面验收与文档已完成，本阶段停止。

## 2026-10-15 D-047 建单调整完成

六项答复已落入REQUIREMENTS、DECISIONS及最新设计。当前在 `codex/case-workflow-redesign`，本轮与此前工作流实现均仍为本地改动，未提交/推送；不改变已上传main的侧栏修复。

- 首屏仅案件号（保险报案号）、车主手机号、牌照/VIN、查勘员姓名/电话、颜色、品牌、车型。草稿允许缺项，正式提交指定信息全部必填（牌照/VIN至少一项）及至少1张有效物损照片，服务端与当前节点缺失清单共用校验；不再要求建单填写保险公司、车主姓名、事故时间、区域或地址。
- 支持粘贴/UTF-8 TXT配合批量照片，以及整文件夹导入TXT+照片。只提取文字，标的牌照/司机电话不混填受损方，昵称必须人工选择，多值不自动决定；原文/证据行号/采用值保留。照片单独SCENE_PHOTO分类，沿用鉴权、私有存储、大小/类型/数量校验及幂等；每张上传使用明确文件名，失败保留草稿、已成功照片与重试键，错误持续显示。
- 取消通知单及缺失原因前置要求；报价审核改查物损照片，旧附件保留。地址候选留档，派单核对区域与实际接车地址；未确认不能派单，确认后区域内网点按Mock直线距离全局稳定排序再分页，人工选择。旧案件可在派单处理补确认；已到店仅管理员可在异常改派窗口补确认，不伪造历史确认。
- V18追加查勘员/颜色/地址确认及照片类别，移除旧建单时机的事故/保险资料约束，保留历史有效身份约束；新必填在案件锁内执行。报价抬头姓名/保险公司允许空值，PDF不编造资料；旧迁移不修改。查勘员电话不返回网点/车主。

| 验证 | 实际结果 |
| --- | --- |
| 后端完整回归 `verify.ps1 -LogName intake-delivery-final`（Java21、Maven verify、独立PostgreSQL/MinIO、Mock） | 45项单元通过；86项集成中85项通过，唯一失败为既有站内通知测试只处理单批50条、夹具积压。新建单完整链路11项工单集成全部通过，包括逐个缺项拒绝、照片不OCR、无保险/地址建单、地址权限/版本/排序、报价审核不要求通知单。 |
| 通知夹具修正后 `verify.ps1 -IntegrationTest QuotationPostgresIT -LogName intake-notification-regression` | 45项单元+35项报价集成通过，0失败/错误/跳过，Maven构建成功（10:42:32 +08:00）。测试按实际批次投递完积压并新增积压为零断言，保留原收件人/幂等/敏感字段断言，未修改通知业务。结合前一完整回归，86项集成均已覆盖通过；不将分次验证写成一次全量成功。 |
| Flyway | 隔离schema校验20份迁移，应用V18；既有V15校验和/主迁移升版兼容用例通过。 |
| 管理端 `npm run lint / test / typecheck / build` | 全部通过；61项测试，新增双入口、编码/容量、照片不解码、必填项及部分失败复用上传键等验证。末次旧案件地址窗口条件修正后再跑lint、类型检查、构建和8项工作流/导入定向测试，全部通过。最终日志 `tmp/workflow-stage2/intake-*-final.log` 及 `intake-last-ui-tests.log`。 |
| H5 | 本轮未修改H5源码；沿用上一批20项测试及lint/类型/构建通过记录。后端集成继续覆盖网点数据范围及报价链路。 |
| 浏览器 | 合成文件夹含TXT/2张照片：三者电话正确、标的车牌空白、昵称人工采用、照片全部关联；正式建单成功，地址仅派单核对，确认前按钮禁用，确认后展示模拟9.41km网点；材料绿勾、九节点、1280宽无横向溢出。滚动抬头/控制面板正常。截图 `case-intake-dispatch-final.png` 保存到本任务visualizations目录。 |

失败记录均保留于忽略目录：初轮DTO参数缺项编译失败；旧附件计数/迁移清单/管理员提取权限断言需按新规则调整；原工单测试未配置真实测试MinIO导致503，补充已有TEST配置；新增测试的空字段省略与lines请求字段修正；一次既有OCR本地超时单测在鉴权阶段先超时，保持原断言重跑通过。浏览器导入暴露“查勘员电话”被拆成姓名候选，已修正并新增回归。预览构建切换期间旧页面引用过期哈希资源，刷新后使用最终构建重新验收；未据旧页面断言建单失败。

全部样例验证使用合成资料，用户两份聊天原件/照片未写入仓库、未传给真实OCR；真实地图仍未接入。实现与验证完成，本阶段停止。

## 2026-10-15 原版工作流实施验证及新增建单调整

用户已授权文稿全部实施，以下旧记录中的“第二批待确认”是当时状态，不再是当前门禁。当前仍在 `codex/case-workflow-redesign`，工作流代码尚未提交/推送；main此前推送结果不变。

原版第二批已实现：管理端九节点只读时间轴、当前任务及绿黄办理状态、按阶段材料窗口、文字建单原文/候选/采用值留档、网点报价OCR与核损OCR人工确认、原件及确认修订绑定业务版本、失败人工补录保留失败记录；管理端查看网点识别来源而不重复OCR。关联工作区保留未提交输入；玻璃抬头随原生滚动收拢、控制面板停驻并标记阅读位置；滚动不推进业务。服务端保留权限、阶段、幂等、审计和四项开修条件；新增V17保存不可变来源与命令记录，不修改旧迁移。

验证记录：完整后端回归42项单元+85项集成测试通过；后续资金展示门禁与网点配置隔离修改后，最终定向重跑42项单元+42项报价/OCR集成测试通过（`workflow-scope-final.log`，09:29:55 +08:00），0失败/错误/跳过，Maven verify成功。隔离schema完成Flyway验证，测试使用独立数据库/存储桶与Mock，不调用真实OCR。管理端57项、H5 20项测试及两端lint、类型检查、生产构建通过。日志在忽略目录 `tmp/workflow-stage1/` 和 `tmp/workflow-stage2/`。

浏览器以合成案件核对：未来入口回到当前任务、缺项提示、核损上传/人工确认、等待网点无客服推进操作、关联页返回保留输入、未收款付款按钮禁用、滚动面板不覆盖抬头；窄屏与H5无横向溢出。仅验证本地Mock链路，真实OCR和真实地图未验证；原版完整交付收尾尚未完成，不能据此声称新增建单要求已实现。

用户随后新增建单调整，已定向检查两份TXT模板及照片数量，没有对照片做识别、没有将样例个人信息纳入仓库。已在设计顶部记录明确要求，并就编号含义、角色映射、必填条件、通知单门禁、地图及导入方式集中询问。等待答复后再修改相关业务代码；本次澄清阶段只更新设计、问题和进度文档。

## 2026-10-14 工作流重构第一批完成（第二批待确认）

用户要求先上传main侧栏修复，再在新分支实施V3。上传前发现远程main已有团队OCR更新，正常合并并保留双方代码、迁移与完整进度历史；合并提交 `674edf2431062288307bd14f2425ac8e5d6fa6e0` 已推送到 `Victor-Zan/EV-Insurance-Work-Platform` 的main，远端SHA已核实。侧栏修复 `bf4a597` 包含于该提交；设计分支 `codex/case-workflow-redesign` 已同步此基线，第一批新代码仍在本地，未提交或推送。

第一批范围：按D-046收紧核损、保险确认、客服确认的前置条件；原始/正式报价换版使旧核损退出当前依据但保留历史；材料按阶段开放，未知/关闭状态拒绝上传，保留原有权限、幂等与冻结条件。新增管理端只读九节点流程接口，输出当前办理角色、缺失项、材料有效性与真实历史时间；不返回内部金额，不用展示接口代替写入授权。开修继续执行原四项条件，存量案件不批量回退，不新增迁移。接口与错误码见 [WORKFLOW_REDESIGN_API.md](WORKFLOW_REDESIGN_API.md)。

验证使用独立测试数据库、随机schema和独立MinIO测试桶，OCR固定 `MOCK_V1`，构建输出隔离于 `tmp/workflow-stage1/backend-build`；没有外呼百度、修改旧OCR数据库或重启业务服务。

| 本批实际运行 | 结果 |
| --- | --- |
| 定向回归：核损/确认越步、未来材料、流程接口 | 修复前3项按预期失败（错误接受越步或接口不存在），修复后通过。 |
| `pwsh -NoProfile -File tmp/workflow-stage1/verify.ps1 -LogName full-verify-final`；实际执行Java21 `mvnw.cmd -B -ntp -Dvalidation.build.directory=… -Dit.test=*IT verify -Ppostgres-it` | 42项单元测试、82项真实PostgreSQL/MinIO集成测试通过，0失败/错误/跳过；Maven打包成功，结束时间17:14:05 +08:00。 |
| Flyway | 新隔离schema校验18份迁移并完成集成测试；无迁移修改。 |
| 定向权限、状态与证据回归 | 未登录、外部角色、他人草稿、缺少正式报价/金额/核损单、确认顺序、换版失效、网点完工照归属、取消/未知历史状态均覆盖；原四项开修缺一拒绝断言保留。 |
| Git范围与文档 | `git diff --check`通过，无未解决合并条目；需求、D-046、设计状态与API同步。 |
| 前端/H5、浏览器及Compose | 本批无前端或基础设施改动，未重复这些门禁；不宣称新时间轴、滚动视觉或OCR业务链路已验收。 |

失败记录如实保留：首轮runner使用错误枚举 `mock` 导致启动失败，改为已有 `MOCK_V1` 后得到真实回归结果。第一次全量验证有3项失败，原因是旧测试夹具提前上传未来阶段材料及核损字段空值断言；改为合法前序夹具、保留原权限/开修拒绝断言后完整重跑通过，没有删除或跳过测试。日志位于忽略目录 `tmp/workflow-stage1/`，不提交本机配置、凭据或测试数据。

第一批完成后停止。下一批入口为管理端时间轴/当前任务/玻璃滚动布局、文字建单、客服核损OCR及网点报价OCR→核对→原件结果同步；须获得第二批确认后实施。聊天整包尚未确认的容量、匹配与正式写入范围继续独立，不随本次授权开放。

## 2026-10-14 工作流设计V3美学优化（待整体确认）

按用户指定 `design-taste-frontend-v1` 在最新V2文稿上更新为V3。第5节细化排版、字体、品牌/流程语义色、玻璃边缘、主任务/窄控制面板、滚动抬头收拢、面板自然下滑后停驻、当前阅读分组反馈、底部操作区接续、短时动效及响应式/无障碍降级。沿用Vue/Element Plus与已确认绿黄节点、绿勾红叉、防越步和识别职责；滚动不改变业务状态。第9节补充视觉/滚动验收，第10节新增E设计审阅项，OPEN_QUESTIONS同步最新版本与门禁。

本轮没有生成图片、实施页面、安装依赖或执行项目测试/构建，也未提交/推送。只修改原有3份设计相关文档，不更新全站已确认视觉规范或业务需求。文稿完成后停止，待整体方案确认后才开始第一批实施；实际浏览器视觉和滚动性能仍为实施后的待验收事项。

文档验证：`git diff --check`通过，`git status --short`仅3份文档；PowerShell结构/链接检查确认V3、11个主章节、9个视觉子节、4个成对代码围栏，无缺失相对链接。基础色按相对亮度公式计算，正文12.20:1、次文字5.31:1、按钮白字5.51:1、材料绿/红图形5.02:1/6.57:1；亮绿/黄填充对浅底不足3:1，已在设计补充2px深色边界与文字。计算仅覆盖不透明基础色，不宣称玻璃叠底或运行界面已验收。

## 2026-10-14 工作流设计V2（细节已答复，待整体确认）

按用户补充，将设计更新为V2澄清稿：可自行推进亮绿色、等待其他端黄色；材料有效上传绿色对勾、缺失红叉，上传结果与OCR/复核结果区分；前序未完成不能进入后续办理。识别职责调整为文字建单不做OCR、网点报价OCR后同步原件与识别信息、客服核损单OCR。当前基线的Mock与复核快照能力不冒充完整新链路。

先提出Q1—Q5后已收到答复：节点不可点击，当前“继续”提示缺失；自己能补齐办理则亮绿，等待其他角色黄色；核损识别报案号、保险公司、车牌或车架号、最终核损总额；OCR失败可人工补录确认并保留记录；网点报价OCR、核对及原件/结果提交的必要链路纳入本次设计和后续实施。完整V2已同步这些结论、办理窗口与验收项，OPEN_QUESTIONS关闭Q1—Q5，保留整体方案及第一批实施门禁。

本轮仍只改3份文档，不开始业务编码，不运行项目测试或构建、不安装依赖、不提交或推送。用户答复不等于开工授权；设计交付后停止等待整体确认。

V2文档验证：`git diff --check`通过；`git status --short`仅设计、待确认项和进度3份文档；定向`rg`检查不再残留“待Q答复”的旧约定。PowerShell结构/链接检查确认11个二级章节、4个成对代码围栏、相对链接无缺失。

## 2026-10-14 案件工作流重构设计（待确认）

按用户要求，先将侧栏空白修复提交到本地 `main`，提交 `bf4a597`（源码、5项回归用例与既有验收记录共3文件），随后从该提交创建并切换至 `codex/case-workflow-redesign`。未推送远端。

本轮仅定向核对案件分区、报价操作与状态/权限/材料规则，交付 [案件工作流重构设计](CASE_WORKFLOW_REDESIGN.md)：九节点只读时间轴、当前任务面板、按步骤上传和识别、管理员/客服权限、服务端防越步、资金/投诉独立处理、历史兼容及验收计划。报价/核损/确认顺序和上传时机的新增限制列为A—D待确认项，已同步 `OPEN_QUESTIONS.md`，未改写已确认需求或业务决策。

本阶段验收为文档结构、相对链接、Git范围与空白字符检查；没有修改业务代码、安装依赖或运行项目构建/测试。前一修复已通过的40项测试与前端门禁沿用上一条记录，不宣称本设计已通过运行验收。当前停止在设计确认门禁，待用户批准后才启动第一批规则与接口工作，不进入第13阶段或其他功能。

验证结果：`git diff --check`通过；`git status --short`确认仅3份文档待提交；`git log -1`和`git branch --show-current`确认修复提交及设计分支。PowerShell文档检查确认11个二级章节、4个成对代码围栏、3个相对文档链接均存在，无缺失链接。

## 2026-10-15 高德广东合作网点推荐：实现与离线验收

范围为用户授权的高德Web服务接入及现有客服派单辅助，不自动派单、不接JS交互地图、不进入聊天导入或其他业务阶段。本机聊天初始目录是资料目录的master且有未跟踪用户图片/tmp，未改动；实际共享仓库核对后从最新origin/main 674edf2建立独立工作区及feature/sherr-amap-integration。原OCR工作区、两份旧OCR V15数据库、原始证据和已合并PR #4保持原样。Git身份沿用本机Sherry070719，不冒用仓库所有者。

用户集中确认D-065：车辆→网点用于送店；资格仍为事故区县服务关系＋启用＋精确车型，停车位置只用于距离。管理员维护自有GCJ-02位置/车型适配；能力未配置显示资料未完善，仅排除地图推荐，原人工派单保留既有校验。官方v5 strategy=32主方案道路米数升序、ID稳定破同距，不声称理论最短路径。无半径/直线截断，最多50个合格候选全部计算后分页；超过50明确按网点所在城市收窄或申请预算，不改真实事故区域/车型，不取前50。真实联调另确认预算和地址，本轮仅实现/离线测试。

已完成：

- 复用MapProvider，新增AmapMapProvider、默认Mock及显式配置切换，固定官方geo/regeo/v5-driving；连接/读取/总超时3/8/10秒、响应1MiB、禁重定向及自动重试、每秒限流及并发安全进程预算。缺Key明确启动失败，默认0预算不外呼，无失败回退Mock。
- 广东结构化城市/区县/详情输入，服务端复用region父级链/启用/adcode；保留多候选，省市区冲突/粗粒度不可确认，不用城市中心代替停车位置，不覆盖事故地址。停车位置确认仅当前交互、绑定操作者，修改输入清除推荐及选择。
- 仅追加V17：网点车型能力、不可变自有定位版本、推荐查询幂等和定位关联元数据；复用区域、网点、价格目录、审计与派单。原地址变更使定位失效，能力未配置不推断为无能力。V1—V16和开发迁移均未修改。
- 全资格集合先计数后计算，最多逐网点50次、550ms有界间隔、60秒查询窗口；异常距离为空，凭据/权限/额度/限流/预算失败停止后续外呼。分页及同键重放不外呼；内存快照丢失/重启拒绝旧查询重算。持久层不保存客户位置、道路结果、供应商正文或Key，短期内存只服务当前确认/分页流程，详见AMAP_INTEGRATION。
- 客服工单详情→派单与异常显示事故区域与独立停车位置、明确查询范围、候选/资格/距离/时间/失败状态及资料未完善提示。管理员维修网点→车型与位置可分页选择品牌/车型、维护GCJ-02自有坐标或显式dev合成数据。推荐选择复核归属、有效期、服务区域、启用、车型和定位版本，再调用原WorkOrderService，原状态、锁、幂等、审计保留；网点/车主无地图权限。
- 更新需求、D-065、架构、OpenAPI注解及API说明、安全和本地启动说明。创建本机被Git忽略的.env.amap空模板，Start-Local自动加载，未读取或填写真实高德Key。

| 本轮实际执行 | 最终结果 |
| --- | --- |
| backend：Java21 `./mvnw.cmd -B -ntp -Ppostgres-it verify` | 58单元＋88真实PostgreSQL/MinIO集成测试通过，0失败/错误/跳过，生产JAR构建成功；其中新增地图11项数据库测试。最终日志backend-delivery-verify.log在仓库外。 |
| Flyway空schema、重复validate、既有回归 | 通过，最新V17；Flyway校验19项含schema记录，dev SQL版本清单明确至17，原迁移内容不变。旧OCR升级测试明确目标V16以保留其原验证语义，当前全量迁移由其他完整测试验证至V17，未跳过检查。 |
| 管理端 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 最终全部通过，58项测试；新增停车确认、输入失效、重复查询、稳定重试键、分页不重算、手工派单适配及错误标识测试。 |
| H5上述四项 | 全部通过，20项测试；无新入口或权限变化。 |
| 锁文件依赖安装 | 两端npm ci --offline --no-audit --no-fund最终通过；首次沙箱专用缓存缺包，使用本机已有缓存完成，没有改依赖版本/锁文件或降低检查。 |
| 浏览器：新隔离Mock schema、合成案件/网点 | 通过：当前派单页确认位置→查询→选择仅填控件→原派单按钮后待接单；修改地址清除确认与旧网点选择；事故区域与停车位置明确区分。管理员保存车型关系并追加第二个合成位置版本，SYNTHETIC_TEST明确显示。原网点、客户数据未用于验收。 |
| 供应商与权限测试 | 模拟HTTP成功/空/异常/超大/重定向/超时、官方QPS与配额错误含义、方向倒置拒绝、多结果/地域冲突/粗粒度；数据库资格/能力/地址改变、未知来源、50+超限、部分无路线不串网点、并发同键/重启/过期、四角色及跨客服、人工派单边界与审计隐私全部通过。 |
| 配置加载与Git安全 | .env.amap由.gitignore排除，白名单加载器实际验证；git diff --check与旧迁移差异检查通过。暂存安全扫描和提交交付结果见本条后续补记。 |
| Compose配置/容器 | 本机没有Docker CLI，本轮未验证。补救：在具备Docker的环境按LOCAL_DEVELOPMENT运行配置及健康检查；本轮实际PG/MinIO测试不能替代Compose验收。 |

早期失败如实保留：新集成测试缺JWT有效期、合成网点编码超长/坐标浮点展开、原地图总权限拦截管理员路径、Mockito重设带Answer桩触发空参数；均补齐测试配置或修正实现/夹具，未删除断言、跳过测试或放宽既有权限。最终完整门禁重新运行成功。浏览器退出后曾短暂显示“登录失效/重试”，刷新后正常重新登录；本轮不把该既有会话页面表现记为已修复，也未做无关重构。

未验证：真实高德接口请求数为0，Key/调用额度/真实网点资料/实际道路推荐效果均未验收；未接JS交互地图，未做物理手机/多浏览器/生产压测或部署。用户明确本轮只做离线，无需用Mock冒充真实距离；首次真实联调必须另确认地理编码、逆编码、路线分别预算及允许发送的测试地址/坐标，不购买或开通付费。

分支feature/sherr-amap-integration，目标main；实现提交主题 `feat(map): add Guangdong Amap driving-distance recommendations`。仅提交本任务文件，推送及Draft PR结果将按实际补记，不自动合并、不部署生产。

交付补记：实现提交9d8d630067dec7d79b2fb1aa6161b89b8850fa6a已正常推送至origin/feature/sherr-amap-integration。48个暂存文本逐一读取成功，运行时私密值/高德配置值/JWT/敏感二进制检查0命中，.env.amap未跟踪，原迁移不变。再次fetch确认origin/main仍为674edf2。分支链接：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/feature/sherr-amap-integration 。

GitHub插件create_pull_request（base=main、draft=true）本轮尝试一次，返回403 Resource not accessible by integration；未创建PR，没有改用其他渠道绕过、重复重试或合并。可手动在 https://github.com/Victor-Zan/EV-Insurance-Work-Platform/pull/new/feature/sherr-amap-integration 创建并选择Draft；也可先修复集成Pull requests写权限再明确重试。PR标题建议“feat(map): 广东合作网点高德驾车距离推荐（离线验收）”，范围与未验证项使用本节及AMAP_INTEGRATION说明。本交付补记单独提交并正常推送，不重写实现提交。

本机新隔离Mock验收服务使用8080/5187与amap_browser_20261015，仅合成资料、外呼预算0、OCR Worker关闭；没有替真实网点配置车型/坐标。管理员界面已实际追加SYNTHETIC_TEST位置版本2。截图及验证日志仅在仓库外保留，未上传。已停止本阶段开发，不进入其他阶段；真实联调等待未来明确预算及地址授权。

## 2026-10-14 管理端侧栏切换空白修复

范围仅为本次用户反馈的侧栏切换后页面不显示。根因：全局 `Transition mode="out-in"` 直接包裹路由组件，案件队列等多根节点页面退出时无法完成动画回调，后续路由内容停留为空；刷新绕过退出流程，因此恢复。Vue 实际渲染生命周期回归测试在修复前复现案件页切出及快速切换两项失败（页面文本为空），修复后通过。

`admin-web/src/App.vue` 为两种布局的路由内容增加固定、按路径标识的元素根节点，保证退出动画完成；保留原动画及同路径 query 更新时的页面状态，组件尚未就绪时显示加载提示。不改变业务规则、权限、接口或数据库。

验证：在 `admin-web` 执行 `npm.cmd run lint`、`npm.cmd run typecheck`、`npm.cmd run test`、`npm.cmd run build`，均退出0；全套40测试通过，无失败或跳过。新增5项运行时回归覆盖多根页面切出、退出动画中连续导航、筛选参数更新不重挂、无会话布局和加载提示。随后完善筛选用例，`node --test tests/page-navigation.test.mjs`（5/5）与 lint 再次通过；`git diff --check` 通过。构建仅提示 Vite 插件耗时，没有降低检查标准或安装依赖。

本地浏览器使用已有开发客服测试账号，实际点击验证案件、价格库、资金、投诉跟踪、历史档案、通知与案件草稿，均显示内容；连续点击资金→价格库→通知→案件→价格库后最终页面正常，未刷新、未提交业务数据，无 `non-element root` 动画警告。草稿页截图保存在本次聊天的本地可视化目录 `sidebar-navigation-fixed.png`。未做全部角色、全部详情状态或多浏览器验收；后端、H5及基础设施无改动，未重复其门禁。本次修复完成后停止，不进入第13阶段；未提交、推送或部署。

## 2026-10-14 PR #4 合并冲突与OCR V16重编号验收
交付确认：合并提交a62abc05c9cbc032ff7bf9bc09833c4a45ec5048已正常推送到feature/sherr-phase13-baidu-ocr。GitHub随后确认PR #4的mergeable=true、merged=false，冲突已解除，main仍为c6c9820。旧运行工作区保留在77a2971，代码/旧库/原始证据不切换；临时合并验收服务已停止，隔离测试数据保留。此交付补记单独提交，不重写合并提交。

当前目标仅解决PR #4与main的集成冲突，并执行用户明确批准的“仅将OCR迁移重编号为V16，保留旧数据库”。在新隔离工作区从77a2971合并main c6c9820，保持原运行工作区、现有功能分支和旧库不切换、不重置、不清理；没有rebase、强推、修改远程main或自动合并PR。

三处文本冲突已人工合并：WorkOrdersView同时保留OCR建单前预填/手填冲突选择/原业务提交和main的区域分页；LOCAL_DEVELOPMENT保留百度配置与main启动器说明；PROGRESS保留两侧完整历史。独立OCR复核页同步适配main分页API，可继续加载后续区域。main的工作流D-044保留，OCR决策改为唯一D-045，业务规则不变；旧进度记录原编号作为历史保留，现行引用以D-045为准。

main V15__owner_contact_cancellation_notifications.sql内容保持不变。OCR文件仅从V15重编号为V16__baidu_ocr_and_pre_case_intake.sql，Git判定100%相同内容，旧新Git blob均为6247b5c6fe4baa56ffbbae62cacada54417e5ee0；main V15 blob仍为1a88f366544fbcdbd13cbe94ae880f09481f83db。数据库使用分支与旧库限制见PR4_MERGE_NOTES.md。只读核实原phase13_baidu_live_20261014与phase13_form_20261014仍存在，旧OCR V15成功记录及真实任务保留，没有改Flyway历史或执行repair。

| 本轮实际运行 | 结果 |
| --- | --- |
| backend：Java21 `./mvnw.cmd -Ppostgres-it verify` | 最终通过，38项单元、76项真实PostgreSQL/私有MinIO集成测试，0失败/错误/跳过；Flyway18份校验、空库至V16、Maven打包通过。新增main V15→V16升级测试验证V15 checksum不变、原车主表保留及OCR表追加。 |
| admin-web：锁文件离线安装，`npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 最终全通过，46项测试，包含双方已有测试。 |
| h5-web：锁文件离线安装及上述四项门禁 | 全通过，20项测试；main通知修复保留。 |
| 浏览器：新隔离Mock实例，合成图片/区域/案件 | 28个区域可分页加载并选中第一页以外区域；同一表单保留手填姓名、OCR冲突阻止提交、明确选择后经原服务创建合成正式案件。恢复后再次验证列表→详情正常跳转，main“重新匹配车主”入口可见，原OCR页面不操作。 |
| 原数据库与真实调用 | 旧schema、V15历史和材料保留；本轮不新增百度请求、不重启原真实进程、不把旧Mock改成百度。 |
| 暂存隐私与冲突检查 | 68个暂存文件（包括中文文件名）全部可读；凭据/当前真实样本姓名及运行日志扫描0命中，无敏感二进制，凭据文档未跟踪；无冲突标记或未解决条目。 |
| Compose | 本机缺少Docker CLI，本轮未验证；不借用main另一台电脑的历史通过结论。 |

首轮后端失败仅为原IdentityPostgresIT显式迁移清单仍截止V15；保留containsExactly断言并追加实际V16，完整重跑取得最终38+76通过，不跳过或放宽测试。首轮日志保留为pr4-backend-validation.log，最终为pr4-backend-validation-final.log，管理端最终结果为pr4-admin-validation-final.log（均仓库外）。浏览器曾遇到本地AccessView模块请求超时/失败，服务恢复为200后重新登录并重验导航成功；未将首次尝试写成通过。

提交主题：`merge: resolve PR #4 conflicts and assign OCR migration V16`。以正常merge提交保留双方历史，再推送到feature/sherr-phase13-baidu-ocr更新已有PR #4；不创建重复PR、不自动合并。当前运行目录继续使用旧包/旧OCR库；合并版仅使用新隔离schema验证。旧OCR V15环境的带数据升级若以后需要，必须先单独确认方案，禁止直接启动新迁移集或repair。

## 2026-10-14 D-044 实际启用百度：用户追加1次真实识别

用户明确确认“切换百度，允许新增1次真实识别”。当前5185管理端所连接的18084隔离后端已切到 BAIDU_HANDWRITING_V1，凭据仅从用户指定本机私密文档注入后端内存；本次进程硬上限1次OCR、关闭Token重发、任务最多1次。切换前核实没有其他百度待执行任务或正在处理任务，只停止本任务原Mock进程。旧Mock任务及用户已有表单保留，另开空白创建表单上传与用户当前图片摘要一致的已授权原样本。

实际结果：百度鉴权1次、handwriting请求1次，SUCCEEDED，63段原始文字、14条维修明细候选。程序初始因下一行较大文字框边缘跨入客户姓名行而判为多候选，未强填；新增保守文字框中心同一行校验和合成测试。用临时无凭据、Worker关闭的实例通过既有受保护重整接口重新整理该私有原文，追加 revision=1、ruleVersion=LABELLED_COORDINATES_3，旧原文与候选未改。已验证原文内容完全一致，原真实进程OCR计数仍为1；临时实例已停止，未重启真实进程重置预算。

浏览器已验证新百度任务进入原创建表单、真实姓名自动预填、受控原图可见，14条维修明细独立保留；其余缺少可靠依据的基础字段仍为空，未猜手机号/车辆信息，未将清单编号/单据日期填成报案号/事故时间。未确认快照、未创建或正式提交真实个人资料案件，留给用户核对。只保存不含单据原图、姓名和表单值的供应商/成功状态截图于仓库外。

| 本轮验证 | 结果 |
| --- | --- |
| backend：Java21 `./mvnw.cmd -Ppostgres-it verify` | 通过，38项单元、71项 PostgreSQL/MinIO集成测试，0失败/错误/跳过，Maven打包成功。新合成测试覆盖下行大框交叠与跨两行大框拒绝。Flyway空schema和17份校验通过，未改任何迁移。 |
| admin-web：`npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 通过，44项测试；新增本机调用上限与百度账户配额的不同提示验证。 |
| 真实运行与本地重整 | 当前供应商及任务均百度，原创建表单姓名非空、其他缺失项留空；原文不变；重整后仍仅鉴权1/OCR1，无自动重试。 |
| H5、Compose | 本轮未改H5或基础设施，不重复运行H5门禁；前次20项测试及构建通过记录保留。Compose仍因本机无Docker CLI未验证。 |

这1次追加预算已用完，原两次联调与本次合计3次OCR；这不代表百度账户免费额度耗尽，本轮没有查询供应商账户剩余额度。当前真实进程保留1次硬限制，不通过重启/换进程继续外呼。下一张新识别须另行取得调用授权；下一次授权启动应使用包含新坐标规则的构建。当前任务已保存的新规则候选可继续复核，不需要再调用百度。前端超过本机上限会明确提示，不误报供应商配额用尽。

分支继续为 feature/sherr-phase13-baidu-ocr，保留 eda0a72；本轮提交主题为 `fix(ocr): reject cross-row label candidates`。提交前7个暂存文件与运行日志的受保护值扫描均0命中，无敏感二进制，凭据文档未跟踪；只包含本任务代码/合成测试/文档，没有真实图片、姓名或识别全文。正常推送，不合并；GitHub Draft PR既有403集成权限阻塞未绕过或重复尝试。阶段13聊天导入仍待业务确认，未进入其他阶段。

## 2026-10-14 D-044 补充验收：原创建工单表单内识别、冲突保护与提交

本轮按用户描述逐项核对代码，发现上轮完成的是独立 `/ocr/intake` 页，原“新建草稿”只有跳转链接，尚未满足“在当前创建表单预填且保护手填值”的完整页面要求。现已补齐，不将旧的独立页面验收等同于本轮链路完成。

- 入口：工作台“新建案件草稿”，或“案件 → 案件队列 → 新建草稿”，打开原创建表单，客服点击“上传单据识别”。无需先有案件或 fileId。复用私有 `/ocr/intake`、任务、程序候选、原图、人工修订/确认和草稿关联接口；没有另建上传、OCR 或正式业务通道。
- 原图与现有表单在桌面并列，字段可修改，程序候选/来源证据与当前人工值可对照；日常流程不编辑 JSON。只填当前空字段，非空不同值逐项选择保留/采用，识别等待期间的手填内容同样保护。未识别项留空，区域人工选，维修清单编号/日期不映射报案号/事故时间，明细不生成正式报价。
- 原“保存草稿”保留；同表单增加“提交正式工单”，明确点击后才保存人工快照与关联并调用原正式提交 API，执行原必填、重复检测、权限、状态机和审计。识别本身不创建案件。正式提交失败或取消疑似重复确认，保留已创建草稿并进入原详情页修正，避免再次建草稿。修复案件列表多根节点导致路由过渡后详情空白的问题，已实测正常跳转。
- 独立复核页仍保留，能将已授权建单前任务带入原表单继续，不再次调用百度；已关联任务不能创建第二个案件。解除识别关联须明确确认，不删除服务器原件或历史，不自动重试。

| 本轮实际验证 | 结果 |
| --- | --- |
| backend：Java21 `./mvnw.cmd -Ppostgres-it verify` | 通过，36项单元与71项真实 PostgreSQL/私有 MinIO 集成测试，0失败/错误/跳过，可执行 JAR 构建成功。空隔离 schema 迁移与17份（含开发迁移）Flyway 校验通过，正式版本至 V15；本轮无后端或迁移变更。 |
| admin-web：`npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 通过，最终完整测试43项。新覆盖无案件上传、文件/幂等键、空值合并、冲突、等待期间手填、编号/日期隔离、修订→确认→关联顺序、丢失响应重试和非法任务拒绝。另运行10项 OCR 定向测试，全通过。 |
| h5-web：同上四项 | 通过，20项测试；本轮无H5入口或权限变更。 |
| 实际浏览器：独立 Mock 环境、合成图片与合成案件 | 无案件状态打开原表单并先手填姓名→上传→自动填空字段→姓名保留且提交被冲突阻止→明确保留/采用→修正/补齐→直接提交正式工单成功，状态待派单。第二次故意缺少必填信息，正式提交被拒绝，原草稿及来源保留并正常进入详情。两种冲突选择和原图查看均验证。 |
| 真实百度已有结果的页面复用 | 从私有已保存任务载入原创建表单，真实候选姓名自动填入，原图可见；另一张未提取到可靠基础字段则保持空白。本轮新增百度鉴权/OCR请求均为0，沿用上轮累计2/2请求，不宣称新页面又做了一次全新真实外呼验收。 |
| `git diff --check` 与暂存隐私检查 | 通过。13个暂存文件和运行日志的受保护值扫描均0命中；无敏感二进制文件，凭据文档未跟踪。 |

当前可操作验收实例为本地 Mock（5185/18084、独立 schema），明确标注合成演示。保存的真实结果复核实例5184/18086保持 Worker 关闭且没有百度凭据。下一次“新上传→新百度外呼”的真实验收需用户另行授权额外请求预算并显式启用真实模式；本轮未暗中追加调用。复杂手写布局准确率、PDF、物理手机、Docker Compose及生产部署仍未验证；本机没有Docker CLI，未重复宣称通过。阶段13聊天导入B仍待业务确认，与本次手写建单入口分开。

继续使用 `feature/sherr-phase13-baidu-ocr`，保留上轮提交 `fdab8db`，不切换或重写历史。本轮提交主题为 `feat(ocr): prefill original work order form with conflict review`。仅提交本任务代码/测试/文档，不提交合成截图、私密配置、真实样本或识别全文。GitHub Draft PR 创建的既有阻塞仍是集成 `403 Resource not accessible by integration`，本轮不绕过或反复尝试，分支正常推送后可使用上轮手动PR链接；不合并、不进入下一阶段。

## 2026-10-14 D-044：百度手写 OCR 与建单前预填；阶段13聊天导入安全检查点

本轮在 `feature/sherr-phase13-baidu-ocr` 继续，基线 `2000f61`，远程 main 核对后仍为该提交；未切换、重置、rebase、强推或修改他人成果。实现与验证分别如下，不能将 A 的完成视为整个阶段13完成。

| 部分 | 当前结果 |
| --- | --- |
| A 百度接入与预填 | **已实现并验证**：默认 Mock，显式百度 Provider、Token 内存缓存/并发刷新、连接/读取/总超时与响应上限、安全错误分类、有限手工重试；任务固定供应商/API/适配版本，旧 Mock 历史不重命名。私有原始响应、程序候选、人工修订及确认分层保留。 |
| A 建单前链路 | **已验证**：客服无案件时受控上传到私有 MinIO，创建原 OCR 队列任务；按明确标签/唯一坐标关系预填草稿表单及独立维修明细。人工保存、确认后调用既有草稿服务，再从原页面正式提交；原必填、重复检测、权限、状态与审计保留，不自动派单或写报价/资金。 |
| A 真实联调 | **已验证联通与初步链路**：仅用户指定两张样本，均 SUCCEEDED，累计 OCR **2次**、鉴权 **2次**，不自动重试、无额外接口。第一张浏览器自动预填姓名并展示2条维修候选，人工脱敏修正及补齐缺失字段后创建草稿、提交为合成正式案件，状态待派单。第二张保存63行原文，本地重新整理后14条维修候选，页脚候选0。未证明整体准确率。 |
| B 阶段13聊天导入 | **待业务确认、未实现正式导入**：依据 D-043 / PHASE13_PROMPT 整理十二项集中清单，见 PHASE13_CONFIRMATION.md。用户补充确认的是独立手写建单流程，没有确认全部聊天整包规则；未擅自决定字段白名单/分类/匹配/只读案件/保留/增量等正式写入规则。未自动 OCR 聊天图片、未接外部大模型。 |

关键实现：新增 V15，复用既有 case_file、ocr_job、ocr_review、ocr_confirmation、审计与 WorkOrderService；建单前来源与案件文件二选一，新增私有来源、原始响应元数据与已确认草稿关联。供应商/来源不可修改，历史追加不可变。客服仅操作自己的建单前来源，管理员只读，网点/车主禁止；已关联的完成/取消案件也阻止继续修订。真实任务租约过期不自动外呼，外部 API 无法保证 exactly-once。

字段映射及限制见 BAIDU_OCR.md。维修单号不当报案号、单据日期不当事故时间；缺手机号、日期或数量不编造，l 不转1；字段/明细带原始文字及坐标证据。维修行只在表头明确时整理，跨行数字歧义保持空并警告，合计、付款/签名等页脚不当维修项目。复核主要使用字段/明细表单，原始 JSON 只读高级查看。可从既有原文追加程序提取版本，不重复调用百度；人工来源由服务端固定，不能伪装程序版本。

实际执行命令与结果：

| 范围 | 命令 / 环境 | 结果 |
| --- | --- | --- |
| 后端单元与构建 | backend 中 `./mvnw.cmd test package`，Java21 | 36项单元测试通过，0失败/错误/跳过，可执行JAR成功。初次 Windows 验收进程锁住 JAR 后已仅停止本任务进程、改用仓库外运行副本重建通过。 |
| 全部后端与数据库回归 | backend 中 `./mvnw.cmd -Ppostgres-it verify`；真实 PostgreSQL17.6与隔离私有 MinIO | 36项单元、71项集成测试全部通过，0失败/错误/跳过；包括既有65项和新增6项OCR集成测试。Flyway 空 schema 迁移至 V15 并 validate 通过，既有迁移未修改。 |
| 管理端 | admin-web 中 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 全通过，37项测试；浏览器发现并修复人工保存后带毫秒日期未回填问题，已加往返测试。 |
| H5 | h5-web 中上述四项命令 | 全通过，20项测试；无价格/OCR额外入口或权限扩展。 |
| OpenAPI | 实际读取 `/v3/api-docs` | 建单前上传、原始结果、程序候选、本地重整及草稿创建路径均存在。 |
| Compose | 尝试 `docker compose --env-file .env.example -f infra/docker-compose.yml config --quiet` | **未验证**：本机没有 Docker CLI；需安装/启动 Docker 后用有效私密环境配置复验。不沿用队友电脑的通过结论。 |
| 浏览器 | 真实登录、独立 Mock 上传/预填/修订；真实百度任务预填、人工确认、原页面正式提交 | 已验证；正式验收记录全部使用明确标识的合成字段，原样本/识别全文只在私有存储与授权复核中，不进入 Git 或公开报告。 |

验证中修复了两张 JPEG 带合法应用尾部却被误报损坏的问题：完整解码并检查告警/结束标记，原字节及摘要保留，不裁剪或静默截断。第一次真实识别暴露标签/表格映射缺口后，只从私有原文重整，不重复外呼；更新程序时将剩余进程预算降为1，累计两次用完即停止真实进程。后续复核实例未注入百度凭据，Worker关闭；安全计数保存在仓库外。凭据仅从用户指定的本机私密文件加载至后端内存，文件已Git忽略且不提交；凭据、Token、真实图片和识别全文不作为交付文件。

尚未验收：复杂手写布局的整体准确率、PDF逐页识别（本版百度模式明确拒绝PDF）、物理手机、生产部署及Docker启动。缺少明确标签/可靠坐标时保留原文待人工补充，不宣称所有资料自动填满。B 的十二项业务决策等待确认，确认后需先更新需求和决策再实施。没有进入后续阶段或部署生产。实现提交为 `7d7331e7ba25cf5edc45dd0237f8a69c48fb696c`（`feat(ocr): add Baidu handwriting and reviewed pre-case draft intake`），分支已正常推送至 origin。37个暂存文件与本任务运行日志的受保护值扫描通过，未发现凭据/样本姓名，未暂存敏感二进制，用户凭据文档未跟踪。GitHub 集成创建指向 main 的 Draft PR 时返回 `403 Resource not accessible by integration`；没有绕过权限或尝试合并。请在 [手动创建 PR](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-phase13-baidu-ocr?expand=1) 页面选择 Draft，标题建议 `feat(ocr): add Baidu handwriting and pre-case draft prefill`。本文交付补记单独提交，不改写实现提交。

## 2026-10-14 功能修复GitHub交付

用户明确授权提交到 `Victor-Zan/EV-Insurance-Work-Platform`。本轮61个文件以实现提交 `38dca0d684a9841e8fccc6d50027b393f9305a37` 普通推送到 `main`，远端由 `2000f61` 前进到该提交，`git ls-remote origin refs/heads/main` 核验一致。包括八项业务修复、V15迁移、测试、API/业务文档、独立后台启动器、三个快捷入口、网站使用说明及可公开验证摘要。源码检查及既有验收结果见下方修复记录；本次仅补交付文档与快捷脚本副本，没有新增业务改动或重跑完整业务测试。

本机真实环境凭据、数据库/MinIO数据、构建产物和运行日志未上传。快捷脚本副本与上级已验证文件哈希一致，解析及暂存 whitespace 检查通过；未创建PR、强推、部署或进入第13阶段。远端新出现的其他功能分支未合并。本条交付记录单独提交并同步，最终分支提交以Git历史为准。

## 2026-10-13 功能全流程修复

按用户“把前面发现的问题逐个修复”及补充确认 D-044，完成车主手机号维护与待绑定工单补匹配、草稿事故时间正确回填、区域/网点加载更多、资金目标与状态历史分页、取消站内通知，以及跨案件资金和投诉查询。补绑定保留行锁、版本、内容哈希幂等与审计，禁止转移已有绑定；取消通知按勾选和取消前派单快照生成，取消后仍可读且只返回公开原因等允许字段。原四项开修门禁、报价/资金/维修权限不变。新增 V15，未改旧迁移或清空数据。

最终 Maven `verify -Ppostgres-it` 在 Java21 下成功退出0：22单元+69真实 PostgreSQL 集成测试，0失败/错误/跳过，Flyway及JAR构建通过。两端 `npm run lint/typecheck/test/build` 全过，管理端35/H5 20测试；Compose校验通过。17项真实 HTTP 业务链路在独立合成 schema、私有 MinIO 通过，包含重启保留、角色隔离、金额/幂等及新增功能。首轮补绑定死锁已修复并保留断言重跑；PowerShell5 runner把标准错误警告误作异常的失败原样保留，最终用明确要求 PowerShell7 的 runner 取得真实成功退出码。未跳过或放宽测试。

管理员浏览器代表性验证包括真实登录、手机号表单与必填拒绝、资金/投诉筛选和空态。未做全部状态、多浏览器或物理手机验收。另将本地启动器改为三个独立后台进程，并新增状态/停止入口及恢复说明；生命周期测试覆盖启动命令退出后存活、单端停止不联动、补启动不重复、完整重启及数据库/MinIO保留。Windows PowerShell5的登记数组读取差异已修复，按用户实际入口重新核验。

本机报告分别为 `local-validation/workflow-fixes-20261013-222854`（后端）、`workflow-fixes-20261013-221841`（前端）、`workflow-http-20261013-222401`（HTTP）和 `website-lifecycle-verified-20261014-095121`（最终进程检查，先前记录仍保留）。详细逐项结果、失败记录及限制见 [WORKFLOW_FIXES.md](WORKFLOW_FIXES.md)，接口见 [WORKFLOW_FIXES_API.md](WORKFLOW_FIXES_API.md)。需求/决策/流程/数据字典/待确认项同步更新。仍为本地未提交修改，未推送、PR或部署，不进入第13阶段。

2026-10-14收尾复验：最终启动器7项生命周期检查全部通过、命令退出0。保留前端工作进程启动超时失败证据，以实际启动时间修正运行工具的可配置等待；未修改业务测试断言。网站已留下正常运行，启动、停止、检查说明同步到第二阶段使用说明。

## 2026-10-12 提示词 12 补充验收：JDK 21、四角色浏览器与部署冒烟

根据用户要求，对提示词 12 原记录中的四项未验证内容继续复验。本轮没有修改业务源码、接口或数据库迁移；使用本地 PostgreSQL 17.6、私有 MinIO 和明确标识的合成案件。浏览器验收产生了两条 `E2E` 合成正式案件、一条模板示例历史档案和一份合成定损单，均只位于本地开发环境。为了浏览器自动化，四个 `dev_*` 账号曾临时使用合成验收密码；验收后已按根目录 `.env` 恢复原密码，并逐一验证原密码可以登录。

| 验证 | 结果 |
| --- | --- |
| JDK 21 完整后端门禁 | 通过；Amazon Corretto 21.0.11 实际运行 `clean verify -Ppostgres-it`，22 项单元测试和 65 项 PostgreSQL 集成测试全部通过，0 失败/错误/跳过；Flyway 16 个迁移校验通过，可执行 JAR 构建成功，总耗时约 5 分钟 |
| 四角色真实浏览器链路 | 通过；管理员、客服、维修网点、车主均通过真实 Vue 页面和 18080 后端登录。客服完成合成草稿创建、正式提交和派单；网点完成接单、确认到店并在“上传反馈”看到 `ASSESSMENT` 定损单；车主只看到手机号绑定给本人的案件，详情没有报价、加价或资金入口；管理员和客服均可进入历史档案页面 |
| 提示词 12 文件与导出链路 | 通过但有设备边界；客服实际触发资金对账 XLSX 导出。用户提供的历史模板经真实 multipart 上传后，在浏览器完成“1 行 READY 预览 → 人工确认 → IMPORTED → 只读档案”链路。网点使用项目内合成 PNG 通过真实附件 API 上传到本地 MinIO，浏览器显示 `定损单 · v1 · ACTIVE` 并提供受控下载。桌面自动化无法代替手机系统文件选择器，因此真实手机选文件仍待用户在物理设备操作 |
| 前端生产构建与 Compose 配置 | 通过；管理端和 H5 `npm run build` 均成功，根目录 `docker compose ... config --quiet` 成功。该 Compose 仍按架构文档只属于本地开发配置 |
| 生产形态启动冒烟 | 通过；JDK 21 JAR 在不启用 `dev` profile 的独立空 schema `phase12_prod_smoke` 上应用 14 个正式迁移并启动，健康接口 `UP`；显式关闭 Swagger 后 `/v3/api-docs` 返回 404，开发账号登录返回 401 |
| 真实生产环境部署 | **未验证**；仓库没有生产 Dockerfile/镜像发布、反向代理、域名与 HTTPS、备份恢复、CI/CD、监控告警或首位生产管理员初始化方案，也没有可用的生产主机、凭据和域名。上述本地冒烟不能替代真实生产部署 |
| 物理手机上传 | **等待用户操作**；本机没有 ADB 或已连接手机。已临时开放同一 WLAN 的 H5 `http://10.30.92.83:5176/` 和后端 18082，主机访问及 CORS 预检均为 200。用户需在同一网络的真实手机登录 `dev_repair_shop`，打开 `EVR-20261012-000001` 的“上传反馈”，选择任意非敏感 PDF/JPG/PNG 上传，并确认列表出现新文件。完成后停止临时 5176/18082 服务 |

阶段判定：JDK 21 和四角色浏览器验收已从“未验证”关闭；本地上传、MinIO、历史归档、对账导出及生产形态启动已有证据。真实生产部署因缺少目标环境和上线方案仍不能关闭；真实手机文件选择因需要物理设备操作仍不能关闭。不得把后两项记录为通过。

## 2026-10-12 第13阶段聊天资料智能导入需求同步

根据 Windows 微信实际导出样本和用户逐项确认，已将后续聊天资料智能导入写入 `REQUIREMENTS.md` 的第13阶段候选范围和 `DECISIONS.md` 的 D-043，并新增可直接交接的 `PHASE13_PROMPT.md`。已确认同时支持从资料生成新工单草稿和向已有工单增量补充；管理端提供聊天记录整包导入与逐项识别上传两个入口。整包接受文件夹和 ZIP，解析 TXT 消息顺序及附件引用；图片依据聊天文字上下文分类，不对图片做 OCR/视觉识别；文字负责产生带证据的字段候选。逐项入口由客服先选择分类。

本轮仅同步需求与下一阶段提示词，没有新增接口、数据库迁移、前端入口或真实识别能力，也没有把含真实手机号、车牌和地址的样本复制进仓库。尚待确认项目已集中写入 `OPEN_QUESTIONS.md`，在这些问题关闭及用户明确授权前不得开始第13阶段实现。当前代码阶段仍停在提示词12；提示词12工作区改动尚未提交的事实不变。

## 2026-10-11 提示词 12：正式文档、对账与历史档案导入

按已确认的范围完成第 12 阶段。维修网点可上传其出具的定损单；客服仍须另行上传核损单并完成既有四项开修确认，定损单不替代核损单。客服/管理员可下载按签发版本生成的正式报价 PDF 和分页工单资金对账 XLSX；报价 PDF 不暴露原价、加价或平台利润，导出均留审计。资金流水导入仍沿用第 8 阶段流程，不因本阶段导出而变更资金状态。

根据用户对历史订单模板的补充确认，新增独立的历史案件批量导入与只读档案：上传模板后先预览逐行校验，再由客服/管理员确认归档；报案号可空，只有 17 列整行完全相同才跳过，同报案号而内容不同的行保留并提示。历史档案不写入正式工单、报价、派单、维修授权或资金台账，不要求补造保险公司或事故时间；之后新建的正式案件继续保留原有保险公司、事故时间等必填规则。源 XLSX 私有保存，可受控下载。需求见 `REQUIREMENTS.md`，决策见 D-042，接口见 `PHASE12_API.md`。

| 验证 | 结果 |
| --- | --- |
| 后端 `mvn -Ppostgres-it verify`（独立构建目录、隔离 PostgreSQL schema 和对象桶） | 通过；65 项 PostgreSQL 集成测试，0 失败/错误/跳过；Flyway 16 个迁移校验通过，包含 V13、V14。覆盖历史导入预览、无报案号、同号不同内容、整行重复、无效行、重复确认、角色拒绝，以及 PDF、定损单和对账导出 |
| 后端 `mvn package` | 通过；22 项单元测试，0 失败/错误/跳过，可执行 JAR 构建成功；使用本机 JDK 24 按 `--release 21` 编译，未在 JDK 21 运行本轮构建 |
| 管理端 `npm run typecheck`、`npm run lint`、`npm test`、`npm run build` | 通过；33 项前端测试通过，历史档案入口与权限检查包含在内；最终改动后生产构建再次通过 |
| H5 `npm run typecheck`、`npm run lint`、`npm test`、`npm run build` | 通过；20 项测试通过，覆盖网点定损单分类及原角色边界 |
| `git diff --check` | 通过；仅 Windows LF/CRLF 提示，无空白错误 |

补充验收结果见本文件顶部“提示词 12 补充验收”：JDK 21 完整门禁和四角色真实浏览器链路已通过；物理手机文件选择仍等待用户操作，真实生产环境部署仍因缺少部署目标与上线方案未验证。当前阶段仍停在提示词 12，等待剩余验收和阶段确认后再进入下一阶段。

## 2026-10-11 localhost:5175 H5 登录权限修复

修复车主和维修网点在 `http://localhost:5175` 登录时被错误提示“没有访问权限”的问题。根因不是角色缺失，而是后端 CORS 白名单仅包含 5173、5174；Vite 在 5174 被占用后将 H5 启动到 5175，登录请求在进入账号校验前即被 Spring Security 以 403 拒绝。

本轮仅将明确的本地开发来源 `http://localhost:5175` 加入默认与示例白名单，并同步本机 `.env`；未使用通配来源，未扩大 `ADMIN`、`CUSTOMER_SERVICE`、`REPAIR_SHOP`、`OWNER` 的服务端角色权限。新增预检回归测试，验证 5175 放行且未列入白名单的 5199 仍被拒绝。另修复 `scripts/Start-Local.ps1` 的环境变量名解析规则，使其能读取包含数字的 `JWT_SECRET_BASE64`；该问题此前会使按文档重启后端失败。

| 验证 | 结果 |
| --- | --- |
| 修复前真实预检 | `Origin: http://localhost:5175` 对登录接口返回 403 `Invalid CORS request`，问题已复现 |
| 后端 Java 21 单元测试 | `mvn test` 通过；20 项测试，0 失败、0 错误、0 跳过 |
| CORS 回归 | `localhost:5175` 预检为 200 并返回对应 Allow-Origin；`localhost:5199` 仍为 403 |
| 后端构建与运行 | 可执行 JAR 构建成功；使用 Java 21.0.11、dev 配置和现有 PostgreSQL 启动，Flyway 13 个迁移校验通过，健康接口返回 200 |
| H5 浏览器联动 | 在 `localhost:5175` 使用 `dev_owner` 与 `dev_repair_shop` 分别真实登录；车主进入“我的案件”，网点进入“本店任务”；首页案件、通知及网点待办请求均正常，无“没有访问权限” |

未运行 PostgreSQL 集成测试全集：本次只修改 CORS 配置、对应 Web 层测试和本地启动脚本，不涉及数据库结构、查询、业务状态或金额规则；以现有真实 PostgreSQL 启动、Flyway 校验和双角色真实 HTTP/browser 链路作为相称验证。未修改当前工作区已有的管理端/H5 视觉改动，也未进入提示词 12。

## 2026-10-11 角色化工程插图接入

按用户确认，将五张银白工程手绘候选图分别接入对应场景，并用内置图像生成能力补充一张更大、更细致的整车拆解图。管理端顶栏与工作台使用横向整车蓝图；管理端空状态使用前轮与电机拆解图；管理端登录页改用整车级拆解图。H5 登录使用充电场景，网点首页/案件详情使用维修工位图，车主首页/案件详情使用进度主题图，H5 空状态使用小型零件拆解图。所有图片均为装饰性 `alt=""`，未增加业务字段、状态、接口、角色能力或页面入口。

项目保留生成的高质量 PNG 源文件；页面实际引用按目标显示尺寸生成的 WebP，避免弱网首屏加载原始大图。管理端实际引用资源约 49 kB、190 kB、450 kB；H5 四张实际引用资源约 38—73 kB/张。WebP 仅做尺寸和编码优化，未改动画面内容。

| 验证 | 结果 |
| --- | --- |
| 管理端 `npm run typecheck`、`npm run lint`、`npm test`、`npm run build` | 通过；31/31 测试通过；生产构建正常，三张引用图片合计约 690 kB |
| H5 `npm run typecheck`、`npm run lint`、`npm test`、`npm run build` | 通过；20/20 测试通过；生产构建正常，四张引用图片合计约 239 kB |
| `git diff --check` | 通过；仅 Windows LF/CRLF 转换提示，无空白错误 |
| 管理端浏览器视觉检查 | 默认桌面视口检查登录页；整车拆解图透明叠加正常，主体完整，未遮挡登录表单，无 console error |
| H5 浏览器视觉检查 | 390×844 检查登录页；充电图裁切、玻璃文案区和表单安全区正常，无横向溢出，无 console error |

未验证项：网点与车主真实登录后的全部数据状态截图、物理手机、完整屏幕阅读器、真实弱网和生产 CDN 缓存表现。角色插图按源码和测试确认切换，未通过伪造本地会话绕过真实登录。当前仍停留在提示词 11 的前端视觉补强范围，未进入提示词 12。

## 2026-10-11 提示词 9—11 视觉实现补强

根据用户补充的电动车工程制图与银白充电场景参考，重新审计提示词 9 设计方案在提示词 10、11 中的实际落地。确认原管理端线稿仅为简化路径、H5 为椭圆占位，玻璃和动效层级也不足；本轮只补强前端视觉资产、表面材质和交互反馈，不改 API、业务规则、角色权限、字段可见性、路由范围或数据结构。

使用 `design-taste-frontend-v1` 并继续按业务工作台降低变化度与动效强度：保持单一灰青强调色、反卡片滥用、实体表格/表单和完整状态；未引入 React、Tailwind、Framer Motion、GSAP 或新运行时依赖。另用内置图像生成能力基于两张参考图的风格分别生成原创资产，不直接复用用户参考图：管理端为透明底精密电动车工程制图，H5 为银白充电场景。最终 Web 资产为 `admin-web/src/assets/e-scooter-blueprint-web.png`（1440×481，约 203 kB）和 `h5-web/src/assets/e-scooter-charging-scene-web.jpg`（941×1672，约 86 kB）；原始生成文件保留在 Codex 生成目录。

管理端登录页、工作台顶部和上下文顶栏已使用工程图；命令轨道、当前模块导航舱、上下文顶栏、登录面板和选中案件面板增加白色内折射边、背景透出、轻量着色阴影和不支持透明时的实体降级。增加路由短时切换、导航舱展开、案件面板切换、列表级联和桌面细指针磁吸；磁吸只用于登录、搜索、新建草稿等非危险主操作，触屏、键盘焦点与减少动效环境保持稳定。H5 登录页使用竖版充电场景，角色顶栏、底部导航、首页上下文和案件摘要形成同一玻璃层级；H5 不启用磁吸。

| 验证 | 结果 |
| --- | --- |
| 管理端 `typecheck`、`lint`、`test`、`build` | 通过；31 项测试，含原创资产、玻璃、路由动效和磁吸降级检查 |
| H5 `typecheck`、`lint`、`test`、`build` | 通过；20 项测试，含移动视觉资产、玻璃层级和减少动效检查 |
| 管理端浏览器视觉检查 | 1280×720 登录页图像加载为 1440×481，车辆侧视与正视完整可见；无页面级横向溢出，无 console warning/error |
| H5 浏览器视觉检查 | 390×844、360×800 登录页图像加载为 941×1672；无页面级横向溢出，无 console warning/error |
| 无障碍与降级 | 保留焦点样式、键盘导航、安全区和加载/空/错状态；`prefers-reduced-motion` 关闭动画与磁吸，`prefers-reduced-transparency`/不支持 blur 时使用实体表面 |

未验证项仍为物理手机、完整屏幕阅读器、真实系统减少透明度、持续弱网及生产部署；生成资产已压缩到 Web 交付尺寸，但仍应在生产 CDN/缓存策略下复核实际首屏指标。本轮为提示词 9—11 的视觉质量修正，未进入提示词 12。

## 2026-10-11 提示词 11 H5 实施完成

在 `codex/stages-9-12` 按已确认的 [设计规范](DESIGN.md) 完成网点与车主 H5，未改动后端、管理端、业务状态、金额规则或权限边界。继续使用 Vue 3、Vant 与现有 `/api/v1` REST 接口；`design-taste-frontend-v1` 仅用于约束单一灰青强调色、低卡片化、完整状态与克制动效，未采用其中的 React、Tailwind 或 Framer Motion 技术约定。

新增角色化移动壳层与安全区底栏：网点为“任务 / 案件 / 通知 / 我的”，车主为“案件 / 通知 / 投诉 / 我的”。首页只读取真实分页案件、本人待办与站内通知，不制造经营统计。案件列表使用服务端 20 条分页、稳定排序、搜索、下拉刷新和继续加载；详情按角色提供分区深链，非法或越权分区参数立即归一到概览。网点只显示本店原始报价、四项维修授权布尔结果、本店材料/维修动作和本次派单结算记录，不显示平台加价或利润；车主只显示本人案件进度、服务端允许的照片、收车评价与投诉，不渲染报价、加价、核损金额、利润或结算字段。

材料上传沿用真实 MinIO 材料接口和 `Idempotency-Key`；失败后保留所选文件与同一幂等键，明确由用户手动重试，不自动重放。通知与待办使用现有分页接口并支持已读；投诉入口只从服务端可见案件进入具体案件投诉分区。登录、连接错误、空态、加载、禁用、成功和错误状态均按移动端规范重做，底部操作及页面内容适配安全区；没有提前实现提示词 12。

| 验证 | 结果 |
| --- | --- |
| `h5-web: npm run typecheck`、`npm run lint` | 通过；最终改动后执行，0 错误/告警 |
| `h5-web: npm test` | 19 项通过，0 失败/跳过；含 4 项本阶段角色导航、真实分页、字段隔离与上传幂等重试检查 |
| `h5-web: npm run build` | 通过；416 modules，JS 281.31 kB（gzip 99.55 kB），CSS 216.24 kB（gzip 56.95 kB） |
| `git diff --check` | 通过；仅有 Windows LF/CRLF 提示，无空白错误 |
| 隔离本地 PostgreSQL＋当前 JAR＋H5 浏览器联动 | 健康检查与真实登录通过；客服创建并派发设计示例案件，网点真实接单后状态为“待到店”；网点首页、案件、通知、原始报价/四项门禁与个人页均从真实 API 加载；浏览器无 console warning/error |
| 角色与字段可见性 | 车主首页、列表、详情、通知、投诉入口和个人页的运行时 DOM 不含报价、加价、核损金额、利润、应收/应付、实收/实付或结算字段；网点报价分区不含平台加价、平台利润或正式对外报价字段 |
| 响应式与路由降级 | 390×844 和 360×800 核验网点/车主关键页，代表页 `scrollWidth <= innerWidth`，无页面级横向溢出；车主访问 `section=quotation` 自动回到 `section=overview` |

浏览器业务数据位于独立 `phase11_visual_cb0b411b44d84942854348e8c628eee4` schema 与独立测试桶，案件号 `EVR-20261011-000001`、车辆和账号均为设计示例，不是生产数据。本阶段未修改后端，因此没有把此前后端测试重复记为本阶段通过；真实 API 联动只覆盖上述 H5 代表链路，不等同四角色完整 E2E 或后端 PostgreSQL 集成测试全集。

未验证项：物理手机与真实相机/相册文件选择、完整屏幕阅读器朗读、真实弱网/断网持续抖动、系统级 200% 字体缩放及生产部署。代码已覆盖安全区、长字段换行、上传失败保留与手动重试、`prefers-reduced-motion` 降级；上述设备级项目仍需对应环境验收。

当前阶段：提示词 11 完成，提示词 9—11 均在 `codex/stages-9-12`；等待用户明确授权提示词 12，不提前进入提示词 12。

## 2026-10-11 提示词 10 管理端实施完成

在 `codex/stages-9-12` 按已确认的 [设计规范](DESIGN.md) 实施管理端，未改动 H5、后端业务规则或权限边界。新增 76px 命令轨道、当前模块玻璃导航舱、全局案件搜索及响应式壳层；管理/客服首页只读取真实分页案件、本人待办与站内通知。案件页使用服务端筛选、排序与分页的队列＋选中案件面板；完整详情按概览、派单、报价核损、材料、维修、资金、投诉、历史分区深链，保留既有真实业务组件。面板从真实报价 API 显示已确认的四项开修条件，条件未齐时说明阻塞，不提供越权开修动作。

已接入管理员用户与角色、区域、网点及服务区域、审计日志、重复判断配置页面；客服资金导入与人工匹配独立成页；通知与待办从原有覆盖层改为独立页面。现有价格库、材料、报价、维修、资金和投诉能力继续调用既有 API，没有增加全局投诉台账、虚构指标或真实资金划转。登录页使用原创低对比工程线稿和占位品牌标识；表格、长表单、金额明细保持实体表面，导航与选中案件面板才使用有限玻璃效果。

| 验证 | 结果 |
| --- | --- |
| `admin-web: npm run typecheck`、`npm run lint`、`npm run build` | 通过；最终改动后再次执行 |
| `admin-web: npm test` | 30 项通过，含 5 项本阶段导航、真实 API 和四项门禁检查 |
| `backend: .\mvnw.cmd -B -ntp package` | 通过；19 项单元测试通过，JAR 构建成功；本阶段未改后端 |
| `git diff --check` | 通过；仅有 Windows LF/CRLF 提示，无空白错误 |
| 隔离本地 PostgreSQL＋当前 JAR 浏览器联动 | Flyway V1—V12 迁移及健康检查通过；客服/管理员登录、真实工作台/案件队列/通知/资金/管理员入口可访问；隔离测试案件从草稿提交正式案件，队列与详情状态一致，报价核损页和选中面板均显示四项未满足且不可开修；浏览器无 console error |
| 浏览器响应式 | 1440×900、1024×768、768×1024、390×844、360×800 核查主从布局折叠；代表页 `scrollWidth <= innerWidth`，无页面级横向溢出；窄屏点击导航舱可保持展开并可关闭 |
| 键盘与可访问性补验 | 账号到密码可用 `Tab` 顺序访问，密码框回车可提交；首个焦点为“跳到主内容”，激活后进入 `main-content`；命令轨道按钮具可访问名称，展开舱可用 `Escape` 关闭；代表页未发现可见但无名称的交互控件 |
| 角色入口补验 | 客服可正常登录管理端；网点、车主账号登录管理端均被明确拒绝，且不保存错误端会话 |
| 长字段、长文件名与金额极值补验 | 在隔离 schema 保存接口允许的超长案件字段，按 512×384 逻辑视口模拟 1024px 页面 200% 重排后，页面及描述区无横向溢出；158 字符 PNG 文件名经真实上传、元数据回显和 360px 重排后无页面级横向溢出；报价金额输入保持后端允许的最大 `9999999999999999.99` 十进制字符串，不转科学计数法、不丢精度 |
| 断网、错误与恢复补验 | 停止隔离后端后，刷新真实案件页进入专用连接错误页，说明不会自动重复提交并提供重试；后端在新随机 schema 恢复后，旧 JWT 被拒绝并回到登录页，重新登录后案件队列、空状态和 API 请求恢复；恢复后的浏览器 console 无 warning/error |

浏览器复查曾发现新管理员表格缺少 `v-loading` 指令注册的 Vue 告警；三个页面均补入 Element Plus 指令，重新登录并访问区域、网点、审计后未再出现新告警，类型检查、lint、构建再次通过。验证结束后已登出测试账号并停止本轮前端预览及隔离后端进程。

补验边界：内置浏览器不响应浏览器缩放快捷键，因此没有把“真实 200% 浏览器/系统缩放”标记为通过；改以 1024px 视口折半为 512px 的等效重排检查，结果通过。仍未验证物理手机、完整屏幕阅读器朗读及网络限速下的持续弱网体验，这些需要对应浏览器或设备。四角色跨端完整 E2E、后端 PostgreSQL 集成测试全集和生产部署也不属于本轮管理端补验结果。本阶段仅运行了后端现有 19 项单元测试，不能等同后端全集通过。

浏览器业务数据来自独立 `phase10_visual_493349d9e5cb435eaca5c4ca47ed5d25` schema 与独立测试桶，长字段数据明确为设计示例；断网恢复另使用 `phase10_retry_36a045fc52454998b13da48c578f2017` 随机 schema。两者均不是生产案件，未删除共享容器或数据。

当前阶段：提示词 10 完成；提示词 9 设计与提示词 10 管理端实现已提交为 `e528c1d`，未推送、未开 PR、未部署。下一阶段入口：等待用户明确授权提示词 11 H5 实施；不要提前进入提示词 11 或 12。

## 2026-10-10 提示词 9 设计方案已确认

已按用户授权完成提示词 9，使用 `design-taste-frontend-v1` 的单一强调色、完整状态、反卡片滥用和性能约束，并针对高密度保险业务工作台将设计变化度降至约 5、动效强度降至约 3；未采用 Skill 的 React、Tailwind、Framer Motion 或 React 图标约定，继续以 Vue 3、Element Plus、Vant 为实施基础。

新增 [前端设计规范](DESIGN.md) 和四份可审阅视觉稿：管理端客服工作台、案件队列与选中案件工作面板、网点 H5 案件页、车主 H5 案件页。方案明确银白/珍珠灰/雾灰/石墨灰与灰青强调色、中文及金额排版、间距/圆角/边框/阴影/玻璃层级、组件完整状态、动效、可访问性、四角色信息架构、路由映射和 1440/1024/平板/390/360 响应式规则。玻璃只用于导航、上下文和必要浮层；表格、表单、金额明细保持实体表面。磁吸仅限桌面细指针下的少量非危险主按钮；日常页面不接管滚动。

设计审计以现有前后端真实能力为边界：首页只使用案件、待办和通知，不制造经营统计；投诉因没有全局分页 API，仍通过待办深链到案件投诉分区；Mock 地图必须标记模拟位置/直线距离；四项维修授权条件原样保留。车主稿不含报价、加价、核损金额、利润或结算；网点稿只显示本店原始报价、门禁布尔结果、维修和本次派单结算信息，不显示平台加价或内部字段。

验证：完整阅读本阶段指定文档，检查 `admin-web`、`h5-web` 的路由、页面、业务面板、共享组件、样式及相关真实 API；四份 SVG 已转为 PNG 并逐张目视检查。因为本阶段未修改两个前端应用，未运行其 lint/typecheck/test/build；未做交互原型、真实浏览器响应式操作或物理手机验证，这些不记为通过，需在提示词 10/11 实现时补验。

| 本阶段检查 | 结果 |
| --- | --- |
| PowerShell 逐个将 `docs/design/phase9-*.svg` 解析为 XML | 4/4 通过 |
| 使用工作区内置 Node + `sharp` 将四份 SVG 渲染为 PNG | 通过；桌面稿 2880×1800，H5 稿 780×1688 |
| `view_image` 逐张检查四份 PNG | 通过；未发现裁切、重叠、不可读文字或错误页面层级 |
| 检查 `docs/DESIGN.md` 中 8 个视觉稿/源稿相对链接 | 全部存在 |
| `rg` 检查车主稿禁看金额字段、网点稿平台加价/利润字段 | 通过；车主稿无禁看词，网点稿只保留四项门禁中的布尔条件文字 |
| `rg -n '[ \t]+$' docs/DESIGN.md docs/PROGRESS.md docs/design` | 通过；无行尾空白 |
| `git diff --check -- docs/PROGRESS.md` | 通过；仅提示 Windows 下 LF 将转 CRLF |

用户于 2026-10-10 确认 `DESIGN.md` 第 16 节全部六项设计建议。正式产品名及 Logo 尚未提供，视觉稿中的“e保 / 保险协作台”仅为可替换占位，不阻碍方案确认。当前阶段：提示词 9 已完成并确认。下一阶段入口：等待用户明确授权开始提示词 10 管理端实施；不得提前进入提示词 10—13。

## 2026-10-10 第8阶段完成

D-038—D-040已确认，在feature/phase8-funds-reminders保留第7阶段未提交成果续建。新增V11资金与导入、V12个人通知/outbox/deadline，人工目标版本/分笔流水/冲销、双编号匹配与人工处理、全局流水去重、实际MinIO源文件、角色字段过滤、通知实时授权及必要两端验证面板。自动超时/重复/升级关闭，客服可选单待办期限，过期仅标签，短信不发送。维修状态与资金独立，实际付款记账守收车/应付/保险回款门禁，不执行付款。

最终完整clean verify -Ppostgres-it：19单元＋62真实PG集成，0失败/错误/跳过；Flyway V1—V12及dev迁移/validate和JAR通过。管理端21/H5 11测试，两端lint/typecheck/test/build全部PASS。最终JAR真实HTTP/MinIO14组链路与重启全部PASS，分笔账本、私有导入、个人已读、deadline历史保留。失败与修复、准确范围见[第8阶段验证](PHASE8_VERIFICATION.md)，接口见[第8阶段API](PHASE8_API.md)。

本阶段无阻塞未决规则；浏览器视觉QA、2000行性能基准和生产部署未执行。启动/复验见LOCAL_DEVELOPMENT.md，所有新增文件与报告均在第二阶段，第一阶段只读。未提交/推送、未开PR/部署、未进入阶段9；停止等待用户下一步指令。以下历史阶段“未开始阶段8”等文字仅描述当时状态。


## 2026-10-10 第7阶段完成

D-035/D-036/D-037全部已确认；本轮在feature/phase7-repair-delivery实现维修、收车、撤回、评价及独立投诉，保留阶段1—6和四角色。客服代录仍守开修授权及网点照片要求，收车后COMPLETED、客服有原因撤回待收车；评价与投诉原文保留、车主不再修改、客服追加纠正、管理员处理投诉。

新增V8—V10与repair/complaint模块、私有完工证据冻结、并发CAS与幂等、不可变审计和最小真实API页面。最终clean verify -Ppostgres-it通过14单元＋54真实PostgreSQL集成（零失败/错误/跳过），其中保留原44集成；Flyway V1—V10及dev迁移/validate通过。管理端21/H5 11测试，两端lint/typecheck/test/build全部PASS。最终JAR真实HTTP/MinIO 7组链路与重启保留全部PASS，合法角色变更后的幂等回放字段过滤也通过。准确结果及失败记录见[第7阶段验证](PHASE7_VERIFICATION.md)。

启动与复验命令见[本地开发](LOCAL_DEVELOPMENT.md)。未提交/推送、未开PR/部署、未进入第8阶段；浏览器视觉QA未执行。第一阶段资料保持只读，新增项目和证据全部留在第二阶段。

以下为实施过程与阶段1—6历史记录，较早的待确认/未完成描述已由本条及D-037更新。

## 2026-10-10 第7阶段已开始（规则确认与入口核验）

用户授权维修、收车、评价和独立投诉阶段。基线9619766已推送，入口工作区干净；feature/phase7-repair-delivery从阶段6成果继续。Java/Node/npm、Docker Client/Server/Compose、隔离PostgreSQL真实SQL和25432 TCP、MinIO ready200均通过，详见PHASE7_PLAN.md。

已核对计划与当前决策、状态枚举、案件绑定、派单/取消、附件权限和阶段6开修实现。维修/完工已确认D-035，已实施V8、repair模块、完工照片冻结及两端真实API验证面板，正在验证。收车/评价及投诉用户要求修改；已明确收车后COMPLETED、客服可撤回、评价评分非必填，但其余细节仍待答复，不开放未决写接口。不标记阶段7完成。

维修部分最终验证：后端完整clean verify为14单元＋48 PostgreSQL集成，0失败/错误/跳过；Flyway V8迁移/validate和JAR通过。管理端21/H5 11测试，两端lint/typecheck/test/build全部PASS。真实HTTP/MinIO完成进度幂等、缺照拒绝、完工冻结、绑定车主下载、JAR重启快照/文件保留；Compose config --quiet、PG/MinIO healthy、MinIO ready200。失败修复及未执行项见[PHASE7_VERIFICATION](PHASE7_VERIFICATION.md)。

第7阶段整体未完成：已收到车主/客服可收车、确认后COMPLETED、客服可撤回、评价评分非必填；等待客服代操作范围、撤回状态/原因及已有评价处理、评价修改规则、投诉修改细则。未推送、未开PR、未部署、未进入阶段8。HTTP验证进程已停止，专用PG/MinIO保留。

阶段1—6历史记录保留在下方；此前未提交/未推送表述描述当时状态，最终上传结果见文末。进入阶段7不代表进入阶段8或授权完整UI重设计。
## 2026-10-10 第6阶段完成

用户授权进入第6阶段并确认D-032金额、占比分摊、版本及确认规则。本轮保留阶段1—5、Boot4.1.1及既有暂存/未暂存改动，继续同一指定第二阶段仓库与分支；origin正确，HEAD85fa6f0，无推送/PR/部署。

- 新增V7和quotation模块：不可变网点原始版、客服固定额/比例审核、价格快照、精确外部行金额、核损版本/材料快照、确认、授权历史及幂等记录。客服不能改原单价，OWNER不看价格，网点不看加价/正式总额/核损金额。
- 四项开修后端门禁、当前派单/角色/状态验证，复用案件行锁/CAS/状态历史/审计。报价/核损/材料变更使两确认失效并撤销授权，开修后拒绝普通修订；无网点二次确认或回款条件。
- 两端案件详情必要验证面板、受控对外CSV数据；不做正式模板/UI重设计或阶段7—8。

| 检查 | 本轮结果 |
| --- | --- |
| 完整后端clean verify -Ppostgres-it | 12单元 + 44 PG集成，0失败/错误/跳过，JAR通过；保留此前39集成 |
| Flyway | 隔离PG随机schema，V7及普通/dev迁移与validate通过；V1—V6未改 |
| 两前端lint/typecheck/test/build | 全通过，管理21/H5 11测试 |
| 实际HTTP/MinIO | 固定额、比例、历史、权限、确认失效、重新授权/开修、开修后冻结通过 |
| JAR重启 | 正式金额101.23、核损105、REPAIRING与历史持久保留通过 |
| 并发 | 同键报价只一版；开修与核损作废的事务竞态通过 |
| git diff --check | 通过 |

默认target清理两次失败的日志保留，完整门禁改用第二阶段local-validation/phase6-build，无跳过/删除/放宽测试。浏览器逐项点击及视觉QA未执行。Git作者身份仍未设置，改动未提交，未更改全局配置、清库/删卷。详细失败/未执行与证据见PHASE6_VERIFICATION.md，接口见PHASE6_API.md。

启动：scripts/Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase6-dev-build'，两端npm run dev，详见LOCAL_DEVELOPMENT.md。实际测试JAR已停止，隔离PG/MinIO和数据保留。

第6阶段无阻塞规则。停在阶段6，等用户确认第7阶段，届时先确认维修状态、完工照片最低数量、收车、评价与投诉规则。


## 2026-10-09 独立Boot4迁移与阶段5完成

工作区为用户指定第二阶段仓库，origin正确，初始main/85fa6f0干净；现feature/phase5-files-ocr-map。本轮先独立迁移Boot4.1.1、MP boot4 starter/springdoc3.1.1/Jackson3并完整回归原阶段1—4，然后实现阶段5，见BOOT4_MIGRATION.md及PHASE5_VERIFICATION.md。

- 完成真实私有MinIO附件、分类/元数据/哈希/类型/大小校验、20有效件限制、不可覆盖版本、客服替换作废/缺失补传、真实鉴权下载；后端覆盖角色、当前派单与车主绑定、字段输出和审计。
- 完成可替换Mock OCR、持久化任务/去重/租约并发领取/有限失败重试、人工候选修订/确认/审计；确认只留快照，不进正式业务。
- 完成客服Mock地图五项操作，接实际区域网点；管理端/H5只增加本阶段必要验证页面。阶段4机制及共享迁移保留；修复COMPLETED仍可修改关键字段的直接冲突。
- 用户确认PDF/JPG/PNG、10MiB、每类20有效附件且无总量上限、七类权限、历史保留、不物理删除/自动清理、完成取消只读、OCR与地图范围；D-030/OPEN_QUESTIONS已同步。

| 本轮检查 | 最终结果 |
| --- | --- |
| 完整后端clean verify -Ppostgres-it | 5单元+39集成通过，0失败/错误/跳过，JAR构建通过，保留原34集成 |
| 补充权限边界focused verify | 5单元+5材料集成通过；最终完整门禁全部39集成再次通过 |
| PostgreSQL17.6 / Flyway | 隔离25432数据库随机schema，普通/dev迁移及validate通过，V6通过 |
| 两前端lint/typecheck/test/build | 全通过：管理18、H5 8测试 |
| 真实HTTP业务及重启 | MinIO下载哈希、车主越权403、定时OCR失败重试/人工修正确认、JAR与MinIO重启保留通过；实际业务对象匿名403 |
| git diff --check | 通过 |

失败与未执行项明确保留在PHASE5_VERIFICATION.md，未删除/跳过/放宽测试。浏览器逐项点击/视觉QA未执行，生产及真实外部服务不在范围。Spring精确维护窗口待官方API复核。Git本地提交因无作者身份失败，未修改全局配置，未推送/PR/部署。HTTP测试后端已停止，隔离数据库/MinIO保留。

启动：仓库根目录scripts/Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5'，另开两端npm run dev；详细见LOCAL_DEVELOPMENT.md。完成阶段5后停止，等待用户确认阶段6及财务规则。


## 2026-10-08 提示词 0—4 对齐与提示词 4 完成

- 以用户提供的《Codex_电动车保险平台_MVP_完整分步提示词》为准，重新核对提示词 0—4；当前分支只保留提示词 4 的工单核心、重复判断、派单及到店前后衔接，不进入提示词 5。
- 补齐 `WORKFLOW.md`、`DATA_DICTIONARY.md`、`OPEN_QUESTIONS.md`，并同步 README、需求、架构、决策和本地开发说明。原 D-025、D-026 已标记为被 D-027 取代，避免把提示词 5 的文件、OCR、地图和运输能力误记为提示词 4。
- 新增 V5 工单迁移：草稿、正式业务号日计数器、保险公司与报案号强重复约束、疑似重复规则配置、状态与指派历史、幂等首次响应、审计支撑。草稿不占正式编号；正式编号格式为 `EVR-YYYYMMDD-######`。
- 完成草稿创建/编辑/删除、正式提交、强重复阻止、疑似重复确认、关键字段带原因修改、派单、接单、拒单、取消指派、改派、未到店异常、继续等待、确认到店和整单取消。到店前改派需确认车辆未到；到店后改派及整单取消仅管理员可执行并要求原因。
- 正式提交必填保险公司、报案号、车主姓名/手机号、品牌/车型、事故时间/区域/描述，并要求车架号、车牌号或其他车辆标识至少一项；报案时间、保单号和详细地址可选。提示词 4 不要求上传附件。
- 服务端执行 ADMIN/CUSTOMER_SERVICE、REPAIR_SHOP 本店、OWNER 本人的角色、数据范围和字段可见性校验；普通客服不可执行到店后改派或整单取消。重复规则、状态变化、关键字段、指派和取消均有审计记录，敏感信息按规则脱敏。
- 管理端新增工单列表与详情、草稿/正式提交、重复确认、关键字段修改、派单与异常操作，以及重复判断天数配置；H5 新增网点/车主范围内列表和详情，网点可接拒单、上报到店异常、继续等待和确认到店。
- 新增可提交的 VS Code 默认构建任务：`Ctrl+Shift+B` 可并行启动后端、管理端和 H5，后端会等待 PostgreSQL 健康并读取根目录 `.env`；同时提供停止 Docker 基础设施的任务。个人 VS Code 设置继续保持忽略。
- 修复已有本地数据库先执行 V1—V5、之后才启用 dev 模式时 V2.1 开发账号迁移被 Flyway 拒绝的问题；乱序仅对 dev 配置开放，并新增“普通迁移后切换 dev”回归测试，生产迁移策略不变。
- 修复 H5 在旧 Token 或旧 `/forbidden` 路由下无法自行恢复的问题：H5 身份校验返回 403 时清除无效会话并回到登录页，合法车主/网点会话重新进入首页；服务端角色和数据权限不变。
- 修复 VS Code 一键启动时的前后端就绪竞态：管理端和 H5 在后端健康接口返回 `UP` 后才启动，等待 120 秒仍未就绪时明确指向后端终端，避免登录阶段出现 Vite `http proxy error` / `AggregateError`。
- 原先混入的提示词 5 文件存储、材料上传、MinIO 业务接入、Mock OCR、Mock 地图和运输功能已从当前工作树移除。纠正前内容保存在本地恢复分支 `codex/phase5-carryover`（提交 `2d4aa2139b9a6385bd6f9b1857c700d24392f59e`），当前分支不会提前使用。

| 本次实际命令/验证 | 结果 |
| --- | --- |
| backend `mvnw -B -ntp clean verify -Ppostgres-it`，Amazon Corretto JDK 21.0.11、临时隔离 PostgreSQL 18.6、随机 schema | BUILD SUCCESS；5 单元 + 34 集成（身份 11、目录 4、价格 13、工单 6），0 失败/错误/跳过；JAR 构建成功 |
| Flyway 空 schema 迁移与校验 | 通过；V1/V2/V2.1/V3/V4/V5 均校验成功，普通 schema 不含开发数据迁移；PostgreSQL 18 高于当前 Flyway 声明测试上限 17，产生版本提示但未影响迁移和测试 |
| 已迁移本地库首次启用 dev 的修复验证 | PostgreSQL 17.6 上从普通 V1—V5 补执行 dev V2.1 成功，后端健康接口为 UP，四个开发账号均真实登录成功；IdentityPostgresIT 11 项及相关单元测试 5 项通过，BUILD SUCCESS |
| 工单 PostgreSQL 集成测试 | 6 项通过；覆盖草稿隔离、正式必填/编号、强弱重复、可配置时间窗、关键字段审计、全状态流程、幂等、异常继续、改派/取消、跨网点越权、脱敏与车主范围 |
| admin-web lint、typecheck、test、build | 全通过；18 测试，0 失败/跳过；生产构建成功，存在非阻塞的单包超过 500 kB 提示 |
| h5-web lint、typecheck、test、build | 全通过；8 测试，0 失败/跳过；生产构建成功 |
| VS Code 快捷启动就绪检查、后端健康接口及 H5 `/api/v1/auth/login` 代理 | 任务配置解析通过；后端健康接口 HTTP 200/UP；H5 代理登录 HTTP 200；前端启动前等待逻辑通过 |
| `docker compose --env-file .env -f infra/docker-compose.yml config --quiet` | 配置校验通过；Docker 用户配置读取有环境提示，不影响配置解析 |
| `git diff --check` | 通过，仅有 Windows 换行提示 |

验证边界：后端已在真实 JDK 21 和真实 PostgreSQL 上完成自动化验证，临时测试数据保留在被忽略的本地目录；Compose 配置校验通过，当前本地后端健康接口和 H5 登录代理均返回 HTTP 200。管理端、车主端和网点端的本地登录问题已逐项修复，用户确认当前使用无问题；生产部署不属于提示词 4 的验收范围。

提示词 4 的实现和自动化门禁已完成，功能提交 `b884e30` 已由 `zd01` 快进合并至 `main`，合并过程无冲突；当前停在提示词 4，未经确认不得进入提示词 5。

## 2026-10-04 阶段 3 客服运营权限调整（D-023）

- 延续 feature/sherr-price-database，开始时工作区干净、HEAD=60e21bf。远程核对发现原阶段 3 PR #2 已由仓库协作者合并（c698d54），main 后续 README 更新为 71e5c87。本次不切换分支、不 reset/rebase/force push，保留共享提交及 README 的模块权限表达。
- 用户本次确认取代此前客服只读策略：客服与管理员均可维护品牌、车型、标准配件、别名及适配关系，查询价格、新增/追加版本、预览/确认 CSV 与 XLSX 导入、查看批次及错误报告。来源字典维护、用户/角色/组织/系统配置及审计管理继续限管理员；网点/车主仍被拒绝。
- 同步过滤链、Controller 授权及 PricingAccess 应用服务。来源字典写操作单独在请求、方法、服务三层限制，管理员与客服均受历史字段不可变、新版本追加、有效期不重叠、正金额和自然日规则保护；所有 Flyway 文件原样。
- 前端共享权限常量开放客服价格新增/版本/导入/批次路由和操作按钮；来源字典维护按钮单独限 ADMIN。H5 无新增入口，原用户管理路由和 Token/错误端处理不变。
- 导入预览新增 PRICE_IMPORT_PREVIEW 审计动作，UUID 存入对象标识；元数据、预览行及审计同事务。正式导入、资料维护、版本追加与自动截止沿用当前操作者及角色的既有审计，预览仍仅创建者可访问和确认。
- 新增客服资料 CRUD/关系操作审计测试，以及客服真实 CSV/XLSX 导入、版本追加、失败报告、历史保护和 H5 越权测试。更新前端按钮/路由测试，保留阶段 2 回归、管理员原能力、数据库约束及十万条查询测试。
- 已同步 DECISIONS、REQUIREMENTS、ARCHITECTURE、PRICE_IMPORT、LOCAL_DEVELOPMENT、README，完整门禁已通过。本次没有进入阶段 4。

| 本次实际命令/验证 | 结果 |
| --- | --- |
| backend .\mvnw.cmd -B -ntp clean verify -Ppostgres-it（通过仓库外 run-backend.ps1 注入专用测试环境） | 2026-10-04 18:38 BUILD SUCCESS；5 单元 + 28 集成（身份组织 11、目录 4、价格导入 13），0 失败/错误/跳过，JAR 构建成功 |
| 空普通/dev schema Flyway migrate/validate，既有迁移比对 | 通过；全部 V1/V2/V2.1/V3/V4 原样，不新增重复表或修改数据库结构 |
| 权限、历史保护与审计 | 客服资料 CRUD/关系操作、新版本及自动截止、CSV/XLSX 成功/失败导入、预览所有者、批次/错误查询、操作者角色和对象追踪通过；用户/角色/组织/来源字典写入/审计仍 403，历史 PUT/PATCH/DELETE 仍 409，H5 价格与导入请求仍 403 |
| 十万条价格与查询计划 | 非生产批量夹具、分页、编号/别名、品牌/车型、区域/网点、类型、日期筛选及 EXPLAIN 再次通过；产物在 backend/target，不提交数据 |
| admin-web npm run lint、npm run typecheck、npm test、npm run build | 全通过；14 测试、0 失败/跳过；保留 Vite 单包体积提示，无放宽门禁 |
| h5-web npm run lint、npm run typecheck、npm test、npm run build | 全通过；6 测试、0 失败/跳过，源码未改 |
| git diff --check；与 HEAD/origin/main 比较 db 迁移目录 | 通过 |

复跑过程中修正独立 PostgreSQL 实例端口配置、错误端前端测试设置，以及来源字典请求过滤链的授权顺序；不删除/跳过测试，不放宽历史保护。Docker/Compose、浏览器/手机手工验收与生产部署本次仍未执行，原因沿用前次环境记录。正式价格适用优先级仍待业务确认。

本次交付：实现提交 e83041c278296d6e8bb324fb99f30b9a7f928ade（feat(pricing): grant customer service daily operations with audit），正常合并 origin/main 的提交 41ef7456a76d7208cf543a0513b9feddcabf56bb。唯一 README 文案冲突采用用户确认的新权限，并保留 main 的“在价格数据库模块中”表达；合并后 backend/admin-web/h5-web/docs 与已验证实现树相同，未改变应用代码。git push origin feature/sherr-price-database 已成功，继续普通提交/推送本记录，不重写历史。

GitHub 集成读取确认此分支无开放 PR，尝试创建新的 base=main、draft=true 客服权限调整 PR 返回 403 Resource not accessible by integration；未创建、未绕过权限，未合并 PR。原 PR #2 的合并为已有远程状态。手动创建链接：[main ← feature/sherr-price-database](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-price-database?expand=1)，请选择 Draft，建议标题 feat(pricing): grant customer service daily operations with audit。

本次全部自动化门禁和分支交付完成，独立测试 PostgreSQL 已停止，数据保留；停止于阶段 3，仅余权限阻塞的 Draft PR 创建及已标注手工验收。

## 2026-10-04 阶段 3 完成与交付验证（权限调整前的历史记录）

- 当前分支 feature/sherr-price-database，基线为阶段 2 已合并的 5f6f1e0。中断恢复时保留 d10ad9f 基础资料提交，并先以 ab17835 提交已有价格版本/导入工作，再继续剩余实现；未切换分支、reset、rebase、force push 或覆盖队友工作。
- 复用账号、RBAC、区域、维修网点、服务区域关系与审计。新增 V3 价格目录和 V4 逻辑价格、版本、预览、批次及错误表，既有共享迁移未改；恢复后的 V3/V4 亦未改。品牌、车型、配件、全局唯一内部编号、独立别名、多对多适配与来源管理完整。
- 价格候选分页查询支持内部编号/别名、品牌、车型、四种价格类型、全国/区域/网点范围和适用日期；显示范围、金额、来源、有效期与版本，稳定排序且单页最多 100。组织投影使用既有服务区域关系，不返回联系方式。正式价格适用优先级尚待业务确认，只返回候选，不自动取价、回退或加价。
- 所有价格为正数 CNY NUMERIC(18,2)，API 金额字符串，拒绝多余小数位。有效期使用中国自然日 DATE，首尾包含。新版本开始日严格晚于最新版本；无期限旧版本在同一事务补齐新开始日前一天，并写既有审计与 previous_version_id / closed_by_version_id 关联。历史金额、来源快照、创建信息及有限截止日受 API 和数据库保护，拒绝更新、删除和重叠。
- 手工与导入复用 PriceRules 及批量版本应用 SQL。真实 UTF-8 CSV / XLSX 有界流式解析、字段/主数据/重复/版本校验、分页预览与显式确认；预览不写正式价格，确认重新校验，任意错误或数据库冲突整批价格/截止日/对应审计回滚，失败批次与逐行错误保留。重复确认返回同一批次。不保存文件或接入对象存储。
- 管理端完成候选筛选列表、品牌/车型/配件/别名/来源维护、适配关系、新增与追加版本、历史、预览确认、批次列表/详情/分页错误报告。ADMIN 读写，客服只读基础资料与价格/历史，导入与批次仅 ADMIN；网点、车主无任何价格权限，H5 无入口。沿用阶段 2 登录守卫、401 清理、403/网络错误处理。
- OpenAPI 描述与统一 410/413 响应已更新；导入模板、字段及重复规则见 PRICE_IMPORT.md，数据模型/版本/锁与索引见 ARCHITECTURE.md，确认规则见 D-021/D-022。

### 实际运行与结果

环境为 Temurin JDK 21.0.12.1、Maven Wrapper、Node 24/npm 11、独立 PostgreSQL 17.6（127.0.0.1:55432）；使用仓库外的测试启动脚本注入 TEST_DB_*，随机 schema 隔离，凭据不进入仓库或输出。

| 实际命令/验证 | 最后结果 |
| --- | --- |
| backend: .\mvnw.cmd -B -ntp clean verify -Ppostgres-it（通过仓库外 run-backend.ps1 注入环境） | 2026-10-04 15:56 完成，BUILD SUCCESS；5 单元 + 26 集成（身份组织 11、目录 3、价格导入 12），0 失败/错误/跳过，JAR 构建成功 |
| Flyway 空 schema migrate / validate，含普通配置与 dev 配置 | 通过；普通 V1/V2/V3/V4 不初始化账号，dev 另含 V2.1；旧 checksum 验证通过 |
| 版本/数据正确性 | 自动截止及双向链接、历史字段不可变、直接 SQL 正金额/日期/排他约束、最大金额精度、首尾包含、有限重叠/倒序/非法日期、并发同日追加 200/409 均通过 |
| 导入及权限 | CSV/BOM/真实 XLSX、文件内重复、既有有限区间冲突、过时预览重新校验、显式确认、预览所有者、批次幂等、失败错误报告、故障注入后的整批与截止审计回滚均通过；管理员/客服/网点/车主边界及阶段 2 回归通过 |
| 十万条真实 PostgreSQL 数据 | PriceDataFixture.generate 在 src/test 中按 20 × 5,000 条批量生成；分页、编号/别名、品牌/车型、区域、网点、类型、日期筛选全部通过，不进入 Flyway 或默认初始化 |
| EXPLAIN (ANALYZE, BUFFERS) | 通过；编号计划使用 price_part_internal_code_key、idx_price_record_part_type、idx_price_version_record；别名使用 idx_price_alias_name、idx_price_record_part_type。计划产物在 backend/target/price-query-plans.txt，不提交测试数据/构建产物 |
| admin-web: npm run lint、npm run typecheck、npm test、npm run build | 全部通过；14 测试，0 失败/跳过；Vite 有单包超过 500 kB 的提示，构建成功 |
| h5-web: npm run lint、npm run typecheck、npm test、npm run build | 全部通过；6 测试，0 失败/跳过；未改 H5 源码 |
| git fetch origin；git rev-list --left-right --count origin/main...HEAD | 通过；推送前 origin/main 无新增未包含提交，保持功能分支，不重写历史 |
| git diff --check；与 origin/main 比较 V1/V2/V2.1，与 ab17835 比较 db 迁移目录 | 通过；旧迁移及已保存检查点迁移原样 |

本次未执行 Docker/Compose（本机 CLI/Desktop 不可用，配置未改）、浏览器/手机手工验收或生产部署；需在可用环境补验。十万条验证仅证明测试环境下分页、筛选与索引方案可用，不作生产容量或固定时延承诺。未实现工单、案件、OCR、派单、自动核价、支付或结算，停止于阶段 3。

### 交付记录

- 基础资料提交 d10ad9fc36f32e05d92bcd98450c1007ce4fad2e；恢复保全提交 ab17835；完整实现与验证提交 cc5b7617710fa7e63509ced65a796104a65ade24，标题 feat(pricing): complete price database imports and verification。
- git push -u origin feature/sherr-price-database 成功，远程分支已创建并设置跟踪。随后普通提交/推送本交付记录，不重写历史。
- 使用 GitHub 集成尝试创建 base=main、head=feature/sherr-price-database、draft=true 的 PR，返回 HTTP 403：Resource not accessible by integration。没有创建 PR，没有尝试其他身份或绕过集成权限。
- 手动创建链接：[main ← feature/sherr-price-database](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-price-database?expand=1)。创建时选择 Draft；建议标题 feat(pricing): add versioned price database and atomic imports，变更和验证摘要见本节。不得合并。
- 自动化实现与交付已停止于阶段 3；仅剩集成权限之外的 Draft PR 创建及已标注的手工验收。停止本任务独立 PostgreSQL 实例，测试数据保留，不操作共享库。

## 2026-10-04 中断恢复检查点（历史记录）

- 保持 feature/sherr-price-database；原基础资料提交 d10ad9f 保留，未切换分支、reset、rebase 或覆盖已有工作。
- 新规则已写入 REQUIREMENTS / D-021：正金额、中国自然日、首尾包含、新版本追加、无期限旧版本同事务补齐截止日并审计及关联。
- 已新增 V4 价格维度/版本、预览暂存、批次和错误模型；已有 V1/V2/V2.1/V3 未改。价格候选查询、手工版本应用、CSV/XLSX 流式解析、批量解析映射与版本校验、预览和显式确认接口已加入，仍需专项测试验证。
- 中断前后台命令 .\\mvnw.cmd -B -ntp clean verify -Ppostgres-it 已正常结束：5 单元 + 14 既有集成测试通过，V4 空 schema 迁移及 validate 通过，JAR 构建成功。这不能代替价格/导入专项测试。
- 恢复后先提交现有阶段 3 工作，再补齐价格与导入专项测试、十万条夹具/查询计划、剩余管理页面和文档，完成全部门禁后推送和尝试 Draft PR；不进入阶段 4。

## 2026-10-04 阶段 3：基础资料检查点（历史记录，已由上方完成记录取代）

- 执行 git fetch origin、git switch main、git pull --ff-only origin main、git switch -c feature/sherr-price-database。基线为阶段 2 合并提交 5f6f1e0；操作前工作区干净，origin 为 Victor-Zan/EV-Insurance-Work-Platform。没有改动队友代码或既有 Flyway 文件。
- 已固化本次价格类型、CNY 精度、三种范围、客服只读与候选返回要求。正式价格适用优先级尚待业务确认，不实现自动取价或回退。
- 新增 V3__price_catalogue.sql：品牌、品牌下车型、全局唯一编号的标准配件、独立别名、多对多车型适配、四类来源字典；复用阶段 2 区域、网点、服务区域、用户、角色及审计。
- 品牌/车型/配件/别名/来源 CRUD、适配关系增删查、组织只读投影已实现。新增独立 pricing 权限入口，ADMIN 维护，客服只读，网点/车主拒绝。组织管理权限仍仅 ADMIN。维护与审计同事务，外键保护有引用的主数据；所有列表分页、稳定排序、单页至多 100。
- 管理端五类基础资料与适配关系页面接入真实 API，支持筛选、分页、维护、错误/加载/空态；客服隐藏维护入口。H5 无价格入口。页面通过现有登录守卫、Token 清理和统一错误客户端。
- 依用户“关键业务规则记录待确认，不自行猜测”及 AGENTS.md 第 1 节，价格事实、版本与正式导入暂不落地：无失效时间旧版本的后续处理、金额零/负边界、生效区间口径已提出确认（见 REQUIREMENTS 7.4）。这些会影响历史不变性、重叠判断、金额检查和批次原子校验。
- 尚未完成：价格记录及历史版本、组合价格筛选、XLSX/CSV 预览与原子导入、批次/错误报告、相关价格页面、十万条价格生成与查询计划。不能将当前目录测试视为这些能力的验收，不进入阶段 4。

### 当前实际验证（仅覆盖已实现的检查点）

环境沿用本任务目录的 Temurin JDK 21.0.12.1、Maven Wrapper、Node 24/npm 11、独立 PostgreSQL 17.6（127.0.0.1:55432），每个集成测试使用随机隔离 schema，不连接共享或生产数据库；凭据仅在仓库外与环境变量。

| 实际命令/方式 | 结果 |
| --- | --- |
| backend: .\\mvnw.cmd -B -ntp clean verify -Ppostgres-it（注入 TEST_DB_*） | 通过；5 单元测试 + 11 既有认证/组织集成测试 + 3 新增目录集成测试，0 失败/错误/跳过，JAR 构建成功 |
| 最后改动复核：backend .\\mvnw.cmd -B -ntp verify -Ppostgres-it；管理端重新运行 lint/typecheck/test/build | 通过，后端仍为 5 + 14 个测试、管理端仍为 9 个测试，无失败/跳过 |
| Flyway migrate/validate，空 dev 与普通 schema；重新初始化开发哈希再验证 | 通过；dev V1/V2/V2.1/V3，普通 V1/V2/V3 且无用户；旧迁移 checksum 正常 |
| 新增目录测试 | ADMIN CRUD、客服只读、H5 拒绝、匿名/非法 Token、内部编号唯一、别名前缀及通配符转义、品牌筛选、关系维护、约束失败无审计残留、分页稳定性/上限、组织投影、OpenAPI 通过 |
| admin-web: npm run lint、npm run typecheck、npm test、npm run build | 通过；9 测试，0 失败/跳过 |
| h5-web: npm run lint、npm run typecheck、npm test、npm run build | 通过；6 测试，0 失败/跳过；未修改 H5 源码 |
| git diff --exit-code origin/main -- 既有 V1/V2/V2.1；git diff --check | 通过；旧迁移原样 |

Docker CLI/Desktop 不可用，Compose 本次未验证且文件未改；未执行浏览器/手机手工验收、生产部署或十万条价格验证。价格库完整测试需待剩余能力实现后补齐。

当前分支 feature/sherr-price-database；本地基础资料检查点提交标题为 feat(pricing): add catalogue foundation and read-only access。此检查点仍未完成阶段 3，不进行完整阶段交付、推送或 Draft PR 创建。确认关键边界后继续当前分支，不改历史、不 force push，不合并 PR。

验证后停止本任务独立 PostgreSQL 实例，测试数据保留；只操作本任务 work/postgres-test-data，不操作共享数据库。

## 当前状态

- 当前阶段：提示词 9 设计交付物及六项设计取舍已确认，停在阶段门禁，等待用户授权提示词 10。
- 状态：提示词 0—8 已完成实现与相应验证；提示词 9 已完成 `docs/DESIGN.md`、四角色信息架构、路由映射、四份关键页面视觉稿、响应式/动效/可访问性规范和现状冲突审计，未修改业务页面。
- 状态日期：2026-10-10；当前工作分支 `codex/stages-9-12`。
- 阶段边界：设计方案不改变四角色、字段可见性、状态机、金额规则、四项维修授权、真实 API 和 MVP 产品边界；未实现提示词 10—13。
- 下一步门禁：由用户明确授权后开始提示词 10；提示词 11—13 仍不得提前进入。正式产品名与 Logo 待提供，当前仅使用设计占位。

## 阶段 1 完成内容

- 初始化 Git 仓库，默认分支为 `main`；未创建提交和远程仓库。
- 建立 `backend`、`admin-web`、`h5-web`、`infra`、`docs` 的 Monorepo 结构。
- 后端建立 Java 21 / Spring Boot 3.5.16 / Maven Wrapper 工程，接入 PostgreSQL、Flyway、MyBatis-Plus、Spring Security、OpenAPI 和 Actuator。
- 后端建立统一响应、统一异常、请求追踪、安全默认拒绝策略和健康检查；未实现登录、JWT、用户、案件或其他业务功能。
- 管理端建立 Vue 3 + TypeScript + Vite + Element Plus + Router + Pinia + Axios 骨架。
- H5 建立 Vue 3 + TypeScript + Vite + Vant + Router + Pinia + Axios 骨架。
- 建立 PostgreSQL 17.6 与 MinIO 的 Docker Compose；默认宿主机 PostgreSQL 端口为 `15432`，避免与本机已有 PostgreSQL 冲突。
- 建立 `.gitignore`、`.editorconfig`、`.env.example`、README 和本地启动文档；真实 `.env` 已忽略。
- Flyway `V1__baseline.sql` 只建立迁移基线，不创建业务表。
- 两个前端均提交依赖锁文件，依赖版本固定；未使用 `--force` 或 `--legacy-peer-deps` 绕过依赖检查。

## 阶段 1 验证记录

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| Compose 配置 | `docker compose --env-file .env -f infra/docker-compose.yml config --quiet` | 通过 |
| PostgreSQL | Compose healthcheck、`pg_isready`、宿主机端口检查 | PostgreSQL 17.6，`healthy`，`localhost:15432` 已发布 |
| MinIO | Compose healthcheck、`GET /minio/health/live` | `healthy`，HTTP 200，API/Console 为 9000/9001 |
| 后端测试与构建 | Java 21 容器内 `./mvnw -B clean verify` | 通过；3 个测试，0 失败、0 错误；JAR 构建成功 |
| 后端实际启动 | Java 21.0.12.1 容器连接 Compose PostgreSQL | 通过；Flyway V1 成功执行，应用启动成功 |
| 后端接口 | `/api/v1/health`、`/actuator/health`、`/v3/api-docs` | 均通过；状态为 `UP`，OpenAPI 可读取 |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm run build` | 全部通过 |
| H5 | `npm run lint`、`npm run typecheck`、`npm run build` | 全部通过 |
| Git 与秘密 | `git status`、忽略规则及配置复核 | `.env`、构建产物、依赖目录均不进入版本控制；未发现真实密码或 Token |

## 阶段 1 环境说明

- 主机只有 Java 24.0.2，正式 Java 21 验证通过 Eclipse Temurin 21.0.12.1 容器完成；日常本机开发仍应安装 Java 21。
- Docker Desktop 4.79.0 在本机受无效 Unix 套接字影响；已备份原设置和运行时目录，关闭无关的 Docker AI，并通过 WSL 重启恢复引擎。镜像和项目数据卷未被重置。
- 原有 Docker 镜像代理返回 403/400；已备份 `daemon.json` 并清空失效代理配置，Docker Hub 直连已验证可用。
- `minio/minio` 仓库当前拒绝拉取，Compose 改用固定的 Bitnami Legacy 社区版 MinIO 镜像；它只用于本地开发，生产部署前必须重新评估维护来源，详见 D-017。
- Compose 当前保持运行，PostgreSQL 与 MinIO 均健康；可按 `docs/LOCAL_DEVELOPMENT.md` 停止且保留数据卷。

## 阶段 0 完成内容

- 检查当前目录：检查前为空目录。
- 检查 Git：当前目录不是 Git 仓库，未执行 `git init`。
- 检查本地 Java、Maven、Node.js、npm、Docker 和 Docker Compose。
- 建立根目录 `AGENTS.md`。
- 建立需求、架构、决策、流程、数据字典、进度和待确认问题文档（流程、数据字典与待确认问题在 2026-10-08 审计中补齐）。
- 固化角色、正常主流程、报价与授权硬规则、价格库要求、技术栈、工程约束和 MVP 边界。
- 给出完整分阶段计划与不阻塞初始化的待确认事项。
- 未创建任何业务代码、页面、数据库迁移或运行配置。

## 阶段 0 本地环境实测（历史记录）

| 项目 | 实测结果 | 结论 |
| --- | --- | --- |
| 操作目录 | `C:\Users\zd070\Desktop\电动车保险工作平台` | 正确 |
| Git 工作区 | 不是 Git 仓库 | 阶段 1 需初始化（执行前确认） |
| Git | 2.54.0.windows.1 | 可用 |
| Java | 24.0.2，路径 `D:\Program Files\Java\jdk-24\bin\java.exe` | 可调用，但不符合目标 Java 21；未检测到 `JAVA_HOME` |
| Maven | `mvn` 命令不存在 | 未安装；阶段 1 可引入 Maven Wrapper，但正式构建仍需 JDK 21 |
| Node.js | 24.15.0 | 可用；具体项目支持版本与锁定策略在阶段 1 固化 |
| npm | 11.12.1 | 可用 |
| Docker CLI | 29.5.3 | 已安装 |
| Docker Compose | v5.1.4 | 已安装 |
| Docker daemon | 无法连接 `docker_engine` | 当前未运行（通常需启动 Docker Desktop） |
| Docker 用户配置 | 读取 `%USERPROFILE%\.docker\config.json` 出现权限警告 | 需在阶段 1 启动验证时复查，可能与当前受限执行环境有关 |

## 阶段 0 验证记录

- 已执行目录文件检查；初始目录无文件。
- 已执行 `git status --short --branch`；结果为“not a git repository”。
- 已执行 `git --version`、`java -version`、`mvn -version`、`node --version`、`npm --version`、`docker --version`、`docker compose version`。
- 已执行 Docker 服务端探测；CLI 存在但 daemon 当前不可连接。
- 已复核文档内容和文件清单（见本阶段结束前的最终检查）。
- 本阶段没有代码，因此无单元测试、lint 或应用 build 可运行；未将其标记为通过。

## 历史分阶段开发计划（已由 1—13 提示词路线取代）

本节是项目初始化时形成的 0—10 阶段历史记录，不再作为当前执行顺序。当前权威的提示词 1—13 路线、范围和验收条件见 `docs/WORKFLOW.md` 第 6 节；后续不得依据本节提前或合并阶段。

所有阶段都遵循：先确认该阶段未决业务规则；只实现本阶段；运行测试/lint/typecheck/build；更新本文件；汇报并停止。

### 阶段 0：需求、架构和规则（已完成）

- 交付：需求基线、架构、决策、规则、环境盘点、路线图。
- 验收：文档覆盖已确认范围，未写业务代码。

### 阶段 1：工程骨架与本地基础设施（已完成）

- 初始化 Git（经确认）、`.gitignore`、编辑器与格式化基础约定。
- 建立后端、管理端、H5 的最小可构建工程，不含业务页面与业务接口。
- 固化 Java 21、Maven Wrapper、Node/npm 版本策略和锁文件。
- 建立 PostgreSQL、MinIO 的 Docker Compose、环境变量加载和 `.env.example`。
- 建立统一响应/异常的基础契约、OpenAPI、健康检查和测试框架，但不实现具体业务。
- 验收：三个应用可构建；基础测试通过；Compose 配置有效且依赖健康；仓库无真实秘密。

### 阶段 2：身份权限、组织基础数据与审计底座

- 确认登录方式、账号生命周期、权限粒度和区域模型。
- 实现用户、四类角色、权限、网点、区域和必要系统配置。
- 实现 JWT 认证、RBAC、数据范围基础机制、审计写入框架。
- 提供明确标识的本地测试账号和模拟基础数据。
- 验收：认证、功能权限、跨网点/跨用户拒绝、权限变更审计测试通过。

### 阶段 3：正式价格数据库与导入

- 确认币种/精度、导入模板、去重键、匹配优先级、版本冲突和失效策略。
- 实现品牌、车型、标准配件、内部编号、别名、适配车型、区域/网点维度。
- 实现原始/对外/核损/结算价格事实、来源、有效期、历史版本和导入批次。
- 实现校验、批量导入、错误明细、分页检索和历史查询。
- 验收：历史不被覆盖；十万级数据导入与关键查询达到约定性能；错误批次可追踪。

### 阶段 4：工单、派单与材料管理（历史旧计划，不再适用）

- 确认工单字段、编号、异常流转、车主绑定与文件限制。
- 实现客服建单、选择网点派单、网点接单和确认车辆到店。
- 实现基于 `ObjectStorageService` 的 MinIO 文件上传、授权读取和材料关联。
- 定义 `MapProvider` 及 Mock，但只覆盖确认过的地图用例。
- 验收：正常主流程到“车辆到店”可走通；跨网点和车主越权被拒绝；材料访问受控。

### 阶段 5：OCR、维修项目与网点原始报价（历史旧计划，不再适用）

- 确认报价修订、OCR 字段、人工确认责任和材料组合。
- 定义 `OcrProvider` 并实现 `MockOcrProvider`。
- 实现报价单/清单/照片上传、异步 OCR 任务、人工确认修正、维修项目与原始报价版本。
- 使用 PostgreSQL 任务表和 Worker，验证幂等、重试和并发领取。
- 验收：Mock OCR 到人工确认再到原始报价完整可追踪；失败任务可诊断和重试。

### 阶段 6：报价审核、统一加价、保险核损与维修授权

- 确认加价精度/舍入/边界、正式报价展示和保险材料规则。
- 实现客服审核项目、固定金额或百分比二选一的总额加价、正式报价。
- 实现保险最终核损总额录入、定损/核损单、保险确认和客服确认。
- 实现四项条件全部满足才开放维修的服务端状态门禁。
- 验收：金额精度正确；敏感字段按角色隔离；任一条件缺失均不能开始维修；关键变化有审计。

### 阶段 7：维修、完工、收车与评价

- 确认维修进度枚举、完工照片类别/数量、车主确认安全方式和评价维度。
- 实现授权后开工、维修进度、必传完工照片、完工提交。
- 实现车主在线确认收车和可选评价。
- 自动确认只保留设计扩展点，不设置倒计时或自动任务。
- 验收：未授权不可开工、照片不足不可完工、非本人不可确认收车，正常交付路径可走通。

### 阶段 8：投诉与站内通知

- 确认投诉分类、证据、状态、处理权限/SLA 和通知事件。
- 实现车主发起独立投诉工单、管理员处理与审计。
- 实现站内通知、已读状态和权限；短信仅定义端口/测试实现，不真实发送。
- 验收：投诉独立可追踪；通知接收人正确；敏感信息不越权泄露。

### 阶段 9：回款、网点结算记录与工单完成

- 确认分笔/部分回款、冲正、结算字段和工单完成门槛。
- 实现保险公司回款记录、状态和凭证（如确认需要）。
- 实现可空、人工录入的最终网点结算金额及结算状态。
- 不实现真实支付、自动分账或自动结算计算。
- 验收：金额与状态可追溯、按角色隔离、修改有审计；满足确认条件后工单可完成。

### 阶段 10：全链路验收、性能、安全与部署加固

- 运行四角色端到端主流程和关键异常/越权用例。
- 完成价格库性能、分页、并发任务、文件访问和审计完整性验证。
- 校验 OpenAPI、数据库迁移、测试数据、环境变量、备份恢复和部署文档。
- 明确云端部署、HTTPS、日志监控、保留策略和恢复目标。
- 验收：所有阶段门禁通过，无真实秘密，MVP 验收口径逐项有证据。

## 阶段 1 时的待确认事项（历史记录；阶段 2 已确认项见文末）

完整清单及最晚确认阶段见 `docs/REQUIREMENTS.md` 第 10 节。当前优先级较高的包括：

- 四类角色的具体登录和账号开通方式。
- 区域层级与网点关系。
- 金额/百分比精度、币种和舍入规则。
- 工单及报价的异常流转和版本规则。
- 文件限制与完工照片具体要求。
- 回款/结算是否允许分笔及工单完成门槛。

这些事项未阻塞阶段 1 工程骨架，但不得在对应业务阶段被实现者自行默认。阶段 2 开始前至少需要确认登录方式、账号开通/停用、权限粒度、区域层级与网点关系。

## 进入阶段 2 前的检查清单（历史记录；本次已确认业务规则）

- 用户明确同意开始阶段 2。
- 确认四类角色的登录标识、初始密码/重置方式、账号创建者、停用规则和 JWT 生命周期。
- 确认 RBAC 权限粒度，以及管理员和客服的数据范围差异。
- 确认区域层级、网点与区域的关系及网点账号归属。
- 宿主机开发建议安装 Java 21；在此之前可继续用已验证的 Java 21 容器执行正式构建。

## 2026-10-02 GitHub 仓库发布

### 已完成

- 移除误建在项目根目录下的嵌套 Git 仓库 `EV-Insurance-Work-Platform/`；该目录仅包含 Git 元数据和 `.gitattributes`，不包含项目源码。
- 将项目根目录作为唯一 Git 仓库，并连接远程仓库 `https://github.com/Victor-Zan/EV-Insurance-Work-Platform.git`。
- 保留远程 `Initial commit` 历史，将当前阶段源码提交并推送至 `main` 分支。
- 本次仅整理和发布仓库，未进入阶段 2，未修改业务实现。

### 验证结果

- `git check-ignore -v .env`：通过，`.env` 仍由 `.gitignore` 排除，未进入提交。
- 暂存文件安全检查：通过，共 57 个项目文件，无疑似密钥文件，无超过 10 MB 的文件。
- `git push -u origin main`：通过，提交 `3e533ae` 已推送至 `origin/main`。

## 2026-10-03 阶段 2 开始

- 已获明确授权，已确认账号密码、管理员账号管理、四角色边界、JWT 生命周期、区域树与服务区域关系，见 REQUIREMENTS 第 11 节和 D-018～D-020；其他待确认项保留。
- 当前聊天目录初始不是 Git 仓库；在 work/EV-Insurance-Work-Platform 克隆指定仓库。工作区干净，origin 指向 Victor-Zan/EV-Insurance-Work-Platform。
- 已 fetch origin main，从最新 origin/main（470eb74）创建并切换 feature/sherr-auth-rbac；保留 V1 原样。
- 实施计划：先固化规则，再新增迁移和领域服务，接入真实认证与角色首页，最后验证权限、审计、迁移及三个应用构建并提交 Draft PR。
- 验收重点：停用立即拒绝旧 JWT、管理 API 仅 ADMIN、错误端登录被拒绝、敏感信息不进入审计，全部列表分页且稳定排序。

## 2026-10-03 阶段 2 完成与验证

### 完成内容

- 新增 V2 业务迁移：账号、固定角色、用户角色、车主资料、区域树、维修网点、多对多服务区域、单网点账号关联、只追加审计日志；阶段 1 的 V1 与 origin/main 完全一致。
- 新增显式 dev 配置和 V2.1 开发数据迁移，创建四类测试账号及模拟组织数据；密码由环境变量注入后转为 BCrypt 哈希，不在代码、迁移或文档提供明文。普通配置不创建开发账号，开发数据库不得用于生产。
- JWT 登录、当前身份及对应端入口，Spring Security 和 RBAC；密钥与有效期均由环境变量提供，示例为 1800 秒，无刷新 Token。每次请求读取数据库状态；启停、密码重置、角色及网点归属变化递增认证版本，旧 Token 失效。
- 仅 ADMIN 可管理用户、角色分配、区域、网点、服务区域和审计；客服仅管理端身份入口，网点/车主仅 H5 身份入口。跨端角色组合被拒绝，避免 H5 角色获得管理端入口。
- 服务端本店/本人校验方法仅提供身份范围基础，不开放案件数据接口。Controller 不承载业务规则；按 identity、organization、audit 领域组织，MyBatis-Plus 单表 CRUD 和 XML 关联查询，事务与审计同边界。
- 登录及失败、用户创建/启停/密码重置、角色/网点归属、区域/网点和服务区域变更均审计；摘要由受控 ID、角色和状态构造，不保存密码、哈希、JWT、完整联系方式或密钥；无审计修改/删除 API，数据库阻止更新/删除。
- 管理端/H5 真实 API 登录、端类型提示、角色首页、路由守卫、认证失败清理、过期计时与退出。管理员最小用户管理验证页可创建、启停、重置账号。无空业务页面或静态假案件数据。
- 统一参数/认证/权限/不存在/冲突响应、错误追踪与 OpenAPI Bearer/错误契约；更新需求、决策、架构和启动说明。

### 实际执行的验证

环境：Temurin JDK 21.0.12.1（下载到本任务 work，SHA256 校验通过）、Maven Wrapper 3.9.16、Node 24.18.0、npm 11.16.0、独立 PostgreSQL 17.6。数据库仅监听 127.0.0.1:55432，专用测试库，每次集成测试使用随机独立 schema；未连接共享或生产数据库。所有测试凭据在运行时随机生成，文件在仓库之外。

| 检查 | 实际命令/方式 | 最终结果 |
| --- | --- | --- |
| Git 基线 | git clone 指定仓库；git status --short --branch；git remote -v；git branch --show-current；git fetch origin main；git switch -c feature/sherr-auth-rbac origin/main | 通过；基线 470eb742a0598982ad666896c978fae5b04ba395，提交前再次 fetch 无变化 |
| 后端完整门禁 | 在 JDK 21 下 .\mvnw.cmd -B -ntp clean verify -Ppostgres-it，注入 TEST_DB_URL/USERNAME/PASSWORD | 通过；5 单元 + 11 PostgreSQL 集成测试，0 失败、0 错误、0 跳过；可执行 JAR 构建成功 |
| Flyway 空库与重启 | 集成测试内 Flyway migrate/validate；分别使用空 dev/普通 schema；新 BCrypt 占位值重新 validate/migrate | 通过；dev 执行 V1/V2/V2.1，普通配置执行 V1/V2 且无用户；重启无校验漂移或重复迁移 |
| 认证生命周期 | 四角色正确登录、错误/不存在账号同错误、停用拒绝登录与旧 JWT、重启用仍拒绝旧 JWT、重置密码/归属变更使旧 JWT 失效、无效/过期/缺少声明 Token | 全部通过 |
| 权限与审计 | 非 ADMIN 对用户/角色/区域/网点/服务区域/审计 GET 和用户创建返回 403，错误端入口拒绝；本店/本人校验、账号重置、组织更新、关联失败回滚、审计防修改/秘密检查 | 全部通过 |
| OpenAPI/错误 | 实际 MockMvc 调用 /v3/api-docs，检查登录/用户路径、Bearer 和 401 契约；参数 400、资源 404、唯一冲突 409 | 通过 |
| 管理端 | npm ci；npm run lint；npm run typecheck；npm test；npm run build | 全部通过；6 测试，0 失败/跳过 |
| H5 | npm ci；npm run lint；npm run typecheck；npm test；npm run build | 全部通过；6 测试，0 失败/跳过 |
| 迁移/工作区复核 | git diff --exit-code origin/main -- backend/src/main/resources/db/migration/V1__baseline.sql；git diff --check；暂存文件与秘密扫描 | V1 未修改；变更限本阶段；无真实个人/案件/生产数据或凭据入库 |

首轮健康测试因新增认证依赖缺少测试替身而失败，保留原断言并补齐依赖；管理端表格行类型已修正；Flyway 断言已明确排除自动 schema 记录并校验确切 SQL 版本。未删除测试、跳过测试、降低类型严格性或放宽角色权限。

### 未验证项和边界

- 本机器没有 Docker CLI/Desktop，Compose 配置/容器启动与健康检查本次未验证；Compose 文件未修改。数据库门禁已用真正 PostgreSQL 17.6 完成，不能把它记成 Docker 验证。补救：在有 Docker 的环境按 LOCAL_DEVELOPMENT 第 3 节运行配置校验和健康检查。
- 未做真实浏览器/手机手工视觉验收；前端认证客户端、路由策略通过源码测试，两个应用生产构建通过。未验证生产部署与管理员生产初始化（属于后续上线加固阶段）。
- 无案件、派单、价格库、报价、核损、维修、上传、OCR、地图、结算实现；本店/本人校验仅为后续服务端数据范围基础。

### Git 交付

- 分支：feature/sherr-auth-rbac；目标：main；不直接在 main 开发、不 force push、不 reset --hard、不改共享迁移。
- 实现提交标题：feat(auth): add role based authentication and organization foundation。
- 实现提交：20eb5f2eb824a49548765943779f4cb397a28a45，标题为 feat(auth): add role based authentication and organization foundation。
- git commit 与 git push -u origin feature/sherr-auth-rbac：成功；已设置跟踪 origin/feature/sherr-auth-rbac，工作区提交后干净。
- GitHub 插件 github_create_pull_request（base=main、head=feature/sherr-auth-rbac、draft=true）：失败，HTTP 403，Resource not accessible by integration。当前连接的集成权限无法创建 PR；未创建 Draft PR，未尝试其他渠道绕过权限，未合并。
- 可供人工创建 Draft PR 的分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/feature/sherr-auth-rbac 。需为 GitHub 集成配置 Pull requests 写权限，或由具有权限的协作者人工建立指向 main 的 Draft PR。
- 本任务创建的独立 PostgreSQL 测试实例已通过 pg_ctl -D work/postgres-test-data -m fast -w stop 停止，测试库数据保留；未删除或修改共享数据库。
- 本文件的收尾提交仅记录验证与 Git 交付结果；阶段 2 在此停止，不进入后续阶段。

## 2026-10-10 设计学习记录与GitHub交付

用户授权记录awesome-design-md学习并上传截至当前全部项目成果。新增DESIGN_RESEARCH.md，区分上游分析、自己的建议与未实施的视觉规范；本轮不改UI、不进入阶段7。阶段5—6及Boot4可公开验证摘要整理到docs/validation，保留历史失败和未执行说明，真实凭据/缓存/构建/数据库/对象数据不上传。

沿用现有成果分支feature/phase5-files-ocr-map；远端main核验仍为85fa6f0，与本地基线一致。Boot4迁移保留独立提交，随后提交阶段5—6和设计学习/验证记录；仅普通推送成果分支，不强推、不直接合并main、不创建PR或部署。提交署名取自仓库当前基线作者Victor-Zan，用本次Git命令参数注入，不修改全局Git配置。

### GitHub上传结果（2026-10-10）

已通过Git Credential Manager浏览器授权取得fishingfishinfisherence-fei账号，并使用仅本次命令生效的credential.username推送到Victor-Zan/EV-Insurance-Work-Platform的feature/phase5-files-ocr-map分支。此前旧账号fishingfishinfisherence的403已解决；GitHub连接器缺少Git对象写入权限的尝试未上传任何成果。

代码与记录提交：599df29（独立Boot4.1.1迁移）、44fcbb9（阶段5—6、设计学习与公开验证摘要）。普通推送成功并设置分支跟踪；后续本条交付记录单独提交。main未合并，未创建PR或部署；本地凭据、数据库/对象数据、缓存和构建产物未上传。本轮仅补记交付，没有改业务代码或重跑此前已通过的测试，仍停在阶段6。

## 2026-10-10 第8阶段视觉验收补充

用户授权浏览器视觉验收。Edge实际检查客服/管理员桌面及网点/车主390、360像素H5，数据来自独立合成schema和真实API。发现H5进度操作名称与右侧时间重叠，已改为独立时间行及说明换行；两种宽度截图复查通过。H5 lint/typecheck/11测试/build全部PASS。单待办deadline表单、逾期标签与历史实际可见，角色资金入口符合边界。

基础布局验收完成；JSON/英文状态、长详情、原生小按钮/英文错误、UUID手填等可用性问题保留，未标记正式UI定稿。详见[视觉验收报告](PHASE8_VISUAL_VERIFICATION.md)及截图。此条更新第8阶段此前“浏览器视觉QA未执行”的历史结论，仅覆盖报告列出的页面与尺寸；未做物理手机或全部状态验收，未推送/部署/进入下一阶段。

同次视觉验收补充：管理端案件列表v-loading未注册警告已通过局部ElLoading.directive声明修复。管理端lint/typecheck/21测试/build通过，浏览器实际重新进入案件列表后未再出现该指令警告；与H5重叠共修复2项，截图见视觉报告。仍有构建分包提醒，未修改警告阈值。

## 阶段8可用性修复（2026-10-10）

已实现报价行表单/明细表格、中文提示、分页照片及人工案件选择、顶部通知与分区导航、触控尺寸、期限预填和路由按需加载。完整后端19单元+62 PG集成/Flyway、两端24/14测试及8前端门禁通过；14真实HTTP链路通过；2000行XLSX首次记账/重复去重及金额准确性通过。首次时钟中断导致JWT过期的失败已留存并原样重跑通过。浏览器连接故障导致本轮新界面视觉复查未完成，未标为视觉通过。详情[修复记录](USABILITY_FIXES.md)。未推送/PR/部署，仍停在阶段8。

## 第8阶段继续复查（2026-10-10）

内置浏览器完成四角色代表性新版界面，H5 360/390照片和报价布局、实际提交、人工案件匹配、deadline预填与历史。CSV页面上传发现FormData被JSON化，已修复两端请求拦截器，真实CSV及2000行XLSX预览成功。两端25/15测试及lint/typecheck/build PASS，14真实HTTP链路再次PASS，独立API验证账目/报价/投诉照片/权限。详见[复查报告](USABILITY_VISUAL_RECHECK.md)。物理手机、Edge/多浏览器、全状态和生产压力未验证；未推送/部署。

## GitHub交付准备（2026-10-10）

用户授权将当前全部项目成果提交到Victor-Zan/EV-Insurance-Work-Platform；当前分支feature/phase8-funds-reminders。纳入第7—8阶段源码、迁移、测试、文档、合成截图与公开验证结果，并补齐报告引用的8份前端检查日志和3个辅助脚本。环境凭据、Token、数据库/MinIO运行数据、构建产物和工具缓存不提交。验证状态及局限按USABILITY_VISUAL_RECHECK保留，无部署或主分支合并。提交成功以远端分支和提交SHA核对为准。

### Git交付收尾（2026-10-15）

- 实现提交：76055c4a01b53cf108c4184224b15baf92bdec20，feat(quotation): add scoped shop handwriting OCR prefill。正常推送 feature/sherr-shop-quotation-ocr 成功，远端SHA核对一致。
- GitHub集成 github_create_pull_request（base=main、head=feature/sherr-shop-quotation-ocr、draft=true）仅尝试一次，返回HTTP403 Resource not accessible by integration。没有创建PR；没有换渠道绕过或重复尝试，无自动合并/生产部署。
- 分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/feature/sherr-shop-quotation-ocr 。人工创建入口：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-shop-quotation-ocr?expand=1 ，由有权限的协作者选择Create draft pull request，目标main。
- 本条纯文档收尾另行提交并推送，不改已通过门禁的源码。最终本任务工作区清洁；隔离Mock预览18086/5188、合成schema与原图/截图留在本机仓库外供复核，未提交截图或私密配置。旧真实OCR预算进程、旧数据库及地图工作区未改。任务在此停止。

## 2026-10-15 网点报价OCR开始（D-047）

已核对真实仓库main 674edf2；新独立工作区shop-quotation-ocr、分支feature/sherr-shop-quotation-ocr，原地图工作区及当前资料目录未修改。保留署名Sherry070719。复用百度链路，实施范围/映射/验收见SHOP_QUOTE_OCR.md；本轮不调用真实百度，不读取私密密钥。当前尚未完成，后续记录实际验证与交付。


## 2026-10-17 main 合并冲突处理完成

用户确认：网点端取远端，客服/管理员端取本地；OCR V18未在共享环境执行。9个文本冲突逐项整合，管理端页面恢复本地，网点QuotationPanel采用远端；共享报价服务保留本地原件快照/核损失效及权限门禁，接入OCR逐行来源。发现并处理自动合并遗漏的Flyway V18撞号，OCR迁移改为V22，旧迁移不变。范围仅本次合并；验收为两端门禁、后端单元及隔离PG/MinIO集成、全迁移与冲突索引检查；不推送、不部署、不调用真实百度。实际验证结果见下表。


### 合并方案与验证结果

- 本地父提交 5256bcf，合入父提交 a7d7a55（远端 main / PR #5）；不丢弃双方提交。管理端源码与本地父提交一致，H5 QuotationPanel 与远端父提交一致；共享后端只整合两端所需逻辑。
- 通用 /ocr/** 网点权限采用远端规则，仅客服/管理员进入；网点走专用报价OCR入口。保留本地客服报价审核、原件快照、核损确认、维修授权、资金确认和敏感字段隔离。网点ASSESSMENT清单在可报价阶段允许上传，客服核损材料阶段规则保留。
- OCR迁移仅由V18改编号为V22，SQL在Git中的内容与远端原文件一致；本地V1—V21未改。两边D-047决策撞号，保留本地建单D-047，远端OCR记录为D-061并注明历史编号。

| 检查 | 实际命令/环境 | 结果 |
| --- | --- | --- |
| 后端完整门禁 | JDK21.0.11；backend/ .\mvnw.cmd -B -ntp -Dvalidation.build.directory=../tmp/merge-jdk21-full verify -Ppostgres-it；专用ev_insurance_workflow_stage1_test、随机schema、专用MinIO测试桶、OCR_PROVIDER=MOCK_V1 | PASS；55单元+102真实PG/MinIO集成，失败/错误/跳过均0，JAR构建成功；tmp/merge-backend-final.log |
| 客服核损链路补充 | Maven test-compile failsafe:integration-test failsafe:verify -Ppostgres-it；-Dit.test=QuotationPostgresIT#shopUsesDedicatedOcrAndManualFailureFallbackWithoutSkippingGates，独立merge-assessment-final构建目录 | PASS；最终测试方法1项，含网点失败手填、幂等、原件快照、客服核损原件确认及开修链路；tmp/merge-assessment-final.log |
| Flyway | 完整门禁内空schema迁移与validate，普通/dev重启及V15→V16精确升级回归 | PASS；完整SQL编号至V22，dev含V2.1，当前无重复编号；未repair、未改业务库 |
| 管理端 | npm run lint；npm run typecheck；npm test；npm run build | PASS；97测试；tmp/merge-admin-{lint,types,tests,build}.log |
| H5 | npm run lint；npm run typecheck；npm test；npm run build | PASS；26测试；tmp/merge-h5-{lint,types,tests,build}.log |
| Compose与健康 | docker compose --env-file .env -f infra/docker-compose.yml config --quiet；docker ps读取已有PG/MinIO健康状态 | PASS；未启动/重建容器或重启应用 |
| Git与端别核对 | git diff --check；git diff --cached --check；git ls-files -u；源码/迁移与两父提交比较 | PASS；9个文本冲突已解决，无残留标记，管理端保留本地、报价H5保留远端，22个正式迁移编号唯一 |

首轮完整集成中，远端OCR夹具缺少本地已确认的查勘信息、物损照片和接车地址确认，9项在建单前置返回409。补齐测试数据/真实API前置操作，未放宽业务规则。随后网点通用OCR访问测试发现本地额外权限，按用户端别决定改回远端专用接口并保留越权断言；旧网点用例更新为当前入口及失败后手填，客服核损/开修后半链路原断言继续执行。原V18/V19迁移版本断言按精确目标V16及完整V22纠正。中途一次本机HTTP供应商模拟鉴权超时导致55单元中的1项失败，原样重跑通过，未修改超时或断言。首次默认JDK24及沙箱JDK21尝试因Mockito attach管道受限而中止；最终采用仓库指定JDK21在沙箱外完成验证，中止的测试JVM无残留。

未验证：本次浏览器/物理手机视觉验收、生产部署及新真实手写识别准确率。未调用真实百度、未升级已有业务库、未连接旧个人OCR V18库，未执行资金划转。已有个人OCR V18库保留，后续联调应使用隔离空schema，不执行repair。构建既有字体/分包/耗时提醒保留，不屏蔽。

方案和验证记录随本地merge提交保存；本轮不推送远端、不部署，不进入下一阶段。


## 2026-10-21 移动端产品化调整与 GitHub 同步
从 GitHub main 9e13bb5 建立独立同步工作树，推送前又合入远端 d3e887b 的桌面端视觉与完结案件归档，原旧分支及约269项交错未提交改动保留。修复通知图标、精简车主导航、调整收车后的评价入口、增加帮助中心/使用说明、精简开发说明，并在管理端案件详情显示车主绑定状态。旧版客服核损和最新资金状态保持现有逻辑。

本地既有验收：2026-10-16 两前端 lint/typecheck/test/build 通过；实际管理员与车主接口核验一个正式工单待绑定，开发车主账号未登记手机号，车主列表为空。该用户归属操作尚待确认，因此本轮未改账号或案件绑定。同步工作树迁入后重新执行：H5 lint、typecheck、27测试（失败/跳过0）、build 均通过；管理端 lint、typecheck、97测试（失败/跳过0）、build 均通过。管理端测试保留现有 Vue 组件渲染警告，未屏蔽。后端与迁移未修改，后端/PG/Flyway回归本轮未执行。离线 npm ci 因缺缓存失败，联网按锁文件安装后两端检查通过；依赖缓存移到第二阶段 local-validation，未入库。

合入 d3e887b 后管理端 lint、typecheck、全量测试和 build 再次退出0；保留既有 Vue 渲染警告，未屏蔽测试。移动端源码未被该次远端提交改动，其前述27项测试和构建结果仍对应最终源码。

## 2026-10-19 受邀开通开始

核对远程main 9e13bb5，无开放PR；新隔离工作区account-activation-login、分支codex/account-activation-login。当前迁移V22；计划追加V23，保留其他工作区。已阅读账号/JWT/权限/案件绑定代码与规则，先固化ACCOUNT_ACTIVATION.md及D-062，再实现并验证。

### 受邀开通实现与验收记录

范围：基于origin/main 9e13bb5，独立分支`codex/account-activation-login`。新增V23，V1–V22未修改；其他地图/OCR工作区未改动、未合并。本机作者沿用Sherry070719。仅账号邀请/激活/统一登录，不引入公众注册、外部发信或部署。

已实现：管理员准备ADMIN/CUSTOMER_SERVICE/有效既有网点账号并复制邀请；客服在可见正式案件概览中为明确车主开通或重发，固定OWNER、人工核实说明、版本锁、冲突停止。已有绑定不转移，已有激活账号复用原密码；新受邀车主激活前后均不参加仅手机号自动匹配。待激活与停用独立，旧账号默认保持激活。256位随机邀请只存摘要、24小时一次、重发失效、行锁原子消费；停用/角色归属/待激活车主联系方式变化使旧邀请不可用。邀请和匿名接口有数据库限流，审计不含凭据或核实原文。

两端增加手机适配开通页，打开只验证，设置密码后才激活，成功展示本人登录账号；统一登录按服务端角色进入管理端或H5，60秒一次性交接码而非JWT经片段转交并立即清除。目标端已有会话时，先处理交接并清除旧会话，避免把新客服登录误当成原管理员会话。未扩大客服通用用户管理权限，网点和车主仍沿用原案件数据范围。为兼容保留受保护旧管理员直接设密建号API；新的管理页面仅走邀请。非dev无默认管理员，本轮没有新增生产初始化入口。

| 检查 | 实际命令/方法 | 结果与证据 |
| --- | --- | --- |
| 后端全量 | JDK21；backend下 `.\mvnw.cmd -B -ntp -Ppostgres-it verify` | PASS，56单元＋112集成，0失败/错误/跳过，Maven BUILD SUCCESS；gates-backend.log |
| 最终激活审计 | 同上，附 `-Dvalidation.build.directory=<仓库外独立输出> -Dit.test=AccountActivationPostgresIT` | PASS，最终审计操作者断言补充后重跑56单元＋账号专项10项，0失败/错误/跳过，构建成功；gates-account-audit.log |
| 管理端 | `npm run typecheck`、`npm run lint`、`npm test`、`npm run build` | PASS，102项测试，0失败/跳过；gates-admin-*.log |
| H5 | 同上四项命令 | PASS，31项测试，0失败/跳过；gates-h5-*.log |
| 数据库/Flyway | PostgreSQL17.6独立测试实例；各测试新schema迁移/validate，普通配置不种账号、dev切换及已有账号保留检查 | PASS：空schema完整迁移至V23及validate，普通profile不自动建号，原有四角色保持可用；旧业务库/个人OCR库保留，没有repair/reset |
| 浏览器 | 本分支隔离服务；有效邀请脱敏预览、390px开通表单；网点从管理入口进入H5；管理员/客服从H5入口进入管理端；退出；客服拒绝/users；已有管理员会话被新的客服登录替换 | PASS；合成账号，截图仅保存在仓库外account-runtime。设置新密码提交由组件及API测试验证，未代真实人员办理开通 |
| 隐私与协作 | 暂存文件名/内容、实际本地测试凭据和邀请比对、git diff检查、已有迁移改动检查 | PASS；无私密配置、邀请明文、真实单据、真实个人资料进入暂存 |
| Compose | 检查本机Docker命令及安装路径 | 未验证：本机缺少Docker/Compose；未安装或重建容器，需有工具的环境补跑配置检查 |

测试夹具变更说明：新增限流后，各独立schema的@BeforeEach清理测试限流桶，价格权限用例复用登录结果而非重复登录每个接口；未删权限断言或提高限流阈值。OCR完整迁移版本断言更新为V23。通知测试保留“待投递事件为0”断言，用有界等待处理后台SKIP LOCKED批次尚未提交的竞态，不修改通知业务。

验证中曾因原本地测试库未运行而连接失败，后恢复测试；一次运行进程中断，未计为通过。最终改用本任务专用新数据库实例与schema，不升级/重置旧库。曾修复新账号准备中JdbcTemplate与MyBatis会话缓存导致待激活状态读取滞后，以及登录页旧会话阻拦交接的问题；对应回归保留。验证仅用合成资料及本地对象存储，未调用真实百度/高德，不使用历史泄露密钥。

未验证：生产公开域名/HTTPS反向代理、生产首管受控初始化、实体手机与真人联系方式交付。短信/邮件自动发送及系统实名验证不在本轮范围。使用入口、后台URL配置、密码规则、邀请状态/限流/API见[ACCOUNT_ACTIVATION.md](ACCOUNT_ACTIVATION.md)与[LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md)。Git提交/推送及Draft PR结果在下方交付记录补记。

#### GitHub交付结果（2026-10-19）

实现提交：`df58fff7b4d4e1e7b3bfa312cf511ff4c0b568e3`，`feat(identity): add invited activation and unified login`，作者沿用本机Sherry070719。已推送并核对远端`codex/account-activation-login`与实现提交一致。

GitHub集成已尝试创建以main为目标的Draft PR，返回准确错误：`403 Resource not accessible by integration`（FORBIDDEN）。本轮未创建PR，未改用其他凭据、CLI或网页绕过；未合并、未部署。此文档收尾提交随后推送同一分支。

- 分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/codex/account-activation-login
- 手动比较/创建PR：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...codex/account-activation-login?expand=1

有权限人员可从比较页选择Draft创建。任务在此停止，不继续其他阶段。

## 2026-10-21 待合并分支集成到 main

核对远端分支与 main：桌面升级、阶段5文件 OCR/地图、阶段8资金提醒、权限、百度 OCR、价格库和网点报价 OCR 七个分支的提交已包含在 main，无需重复合并。另有受邀开通与高德推荐两个分支尚未进入 main。本次从 main `6888816` 建立集成分支，先合入受邀开通，再合入高德推荐，保留两个分支各自的提交历史。

合并时保留主线的当前页面、案件工作流与派单地址确认，并整合受邀开通及网点推荐界面；账号决策改用 D-064，地图决策改用 D-065，避开主线既有编号。地图迁移从分支 V17 调整为 V24，保留主线工作流 V17、账号 V23 及之前迁移。旧 Mock 地图查询继续允许管理员和客服；新增位置、推荐接口按其原有角色范围控制。地图集成测试夹具补齐主线要求的查勘信息、现场照片，并在每个用例前清理隔离 schema 的登录限流计数；未放宽业务校验。完整迁移断言更新到 V24。

验收：管理端 lint、typecheck、test、build 通过；H5 lint、typecheck、test、build 通过；后端单元测试 76 项通过。隔离 PostgreSQL/MinIO 的完整集成回归中，地图推荐以外的 114 项通过；地图推荐夹具先因缺现场照片、隔离 MinIO 配置、派单接车地址确认失败，按主线前置条件补齐后单独重跑 11 项全部通过（`local-validation/amap-merge-focused/failsafe-reports`）。Flyway 在新 schema 迁移及 validate 均到 V24。完整回归与地图专项分两轮执行，没有将一次失败回归写成全量通过。GitHub 推送状态以本节后续记录为准。未调用真实高德、百度，不更新既有业务数据库，也未部署。

推送前发现远端 main 已增加工作台、金属登录图和通知未读数修复（`d28d19e`），本次继续合入该远端提交，保留工作台与登录视觉，同时保留受邀开通的统一登录及交接码路径；通知集成测试保留异步投影等待和新增未读数权限断言。合并后管理端 lint、typecheck、test、build 均通过；后端隔离回归（`local-validation/workflow-fixes-20261021-231104`）compose 配置通过、76 项单元及 125 项 PostgreSQL/MinIO 集成测试 0 失败/错误/跳过，JAR 构建成功，完整迁移及 validate 到 V24。H5 没有受该远端提交改动，前述门禁对应最终 H5 源码。

## 2026-10-23：本地5210分类预算开放（独立开发分支）

用户明确批准：在5210/18105本地开发环境新增地址解析10次、逆地理核验5次、驾车路线20次；由用户点击实际使用，代理不做真实高德测试。不恢复旧学校验收预算、不自动重试、不按日或重启补次数。

代码分支codex/amap-controlled-dev-budget，基线为当前5210实际代码：29b792d加既有手工派单修复；已用本地检查点681f5ee保留，不替换远程main，也不声称是最新main整合版。原工作区和未提交补丁保留。

实现：独立开发预算schema及单独Flyway历史，新增db/map-budget/V1和V2，不修改任何现有业务迁移。按分类固定槽位，调用前独立事务持久占用，失败/超时计次；关闭不可重开，启动不授予额度。路线查询先核对整个合格集合用量，再由用户确认；后台原子预留全部路线额度，正常结束释放未调用槽位；进程崩溃遗留预留不自动回收。原供应商/权限/广东/资格/派单门禁保留。

页面显示三类剩余/已用/预留次数，明确不是高德账户余额；预算不足不静默计算部分网点。普通禁用光标改为not-allowed，加载用aria-busy及文字区分，列出地址缺项。已有手工派单和客服建单修复保留。

本轮验证：管理端npm run lint/typecheck/test/build全部通过，166测试，0失败/跳过。后端mvnw verify -Ppostgres-it（DevelopmentMapBudgetPostgresIT、MapRecommendationPostgresIT、MapMigrationPostgresIT）最终80单元+22集成通过，0失败/错误/跳过，JAR构建成功。用新随机schema验证业务完整迁移和独立预算迁移、并发扣次、相同地址独立计次、重启不补额度、超额批次0扣次、未用槽位正常释放、崩溃保留、不可重置/扩容、非dev拒绝、旧台账机制回归、只读用量预估四角色权限。初次受限沙箱Mockito启动卡住后中止，未计通过；首次独立schema触发器作用域失败通过追加V2修复后重跑通过，未改旧迁移或删测试。

运行：5210前端已换为该独立分支，仍请求18105；后端使用同分支新JAR。数据库仍ev_auth_phase2_test/manual_case_20261022_204936，业务Flyway V26原样保留；仅新增预算schema amap_dev_budget_5210_20261023及独立history V2。run localhost-5210-20261023-approved-10-5-20由本地授权脚本一次性录入，当前三类已用/预留均0、剩余10/5/20。只读查看页面确认上述额度和not-allowed光标，未点击解析/路线/派单，未修改案件或网点资料。

本轮没有真实百度/高德请求，真实识别/地址/路线结果未验证，由用户测试；H5未改未重跑，未执行生产部署或GitHub推送/PR。当前案件仍缺事故服务区县；当前库无符合要求的广东区域资料，只有开发网点DEV-SHOP，不自动补充这些资料。开放额度不能代替资料维护，当前尚不能完成真实网点推荐全链路。

日志、安装前manifest、原JAR及页面截图保留在仓库外的mock-manual-dispatch-runtime。私密Key只从原被忽略的.env.amap读取，没有写入源码、文档或PR。后续重启使用Install-Controlled-Budget.ps1，不运行历史Mock或预算关闭脚本；Provision-Controlled-Budget.ps1可重复核对但不补已用次数，关闭或身份不符则拒绝。后续追加额度须另获确认。

## 2026-10-23：广东省官方区域资料导入5210隔离环境

用户授权整理、核实并导入。依据广东省民政厅49页公开《广东省乡级行政区划代码信息表》（https://smzt.gd.gov.cn/attachment/0/619/619623/4924190.pdf），从9位乡级码提取县、市编码及正式名称；用民政厅2025年度官方简表（https://smzt.gd.gov.cn/qhgk/gsqh/2025nd/index.html，明确截至2025-12-31）交叉核对21市及各市县级节点数。PDF元数据为2026年7月，正文未明确截止日，不据第三方转述宣称其统计日期；未宣称取得2026年10月实时全部变更。

目标只为5210→18105、ev_auth_phase2_test/manual_case_20261022_204936，通过既有受保护管理员区域API新增144条（省1、市21、县级122），144条REGION_CREATE审计；原3条开发区域ID和字段保留，当前共147。全部144条分页API读回匹配，二次只读预检新增0/复用144/冲突0。案件、网点、服务关系摘要导入前后相同；原业务Flyway仍V26，没有结构或旧迁移变化。真实高德/百度调用0，当前地图额度仍剩余10/5/20且无预留。

实际浏览器只读核对城市分页完整21个，深圳联动9个正式行政区；随后恢复未选择状态，未确认停车位置或补案件事故区县。东莞、中山只导入市级，未伪造县级或把镇街写为区县，当前要求县级节点的地图/服务链路在这两市仍需另行确认模型，不放宽校验。未新增合作网点、车型能力或服务关系。

原始官方资料、CSV/JSON、提取与预检/导入脚本、API结果、前后完整性摘要、页面截图及REPORT.md保存在仓库外guangdong-region-import-20261023目录。不包含Key、密码或JWT。应用代码未改、无需换包或重跑构建；未推送/合并/部署。本节为数据维护记录，未把建议改成既定业务规则。


## 2026-10-23：5210停车地址解析错误提示与脱敏诊断

用户先要求调查、同意后再修正，本轮获同意实施错误提示和安全日志，不重试旧地址。已确认的程序问题为管理端共享HTTP拦截器把所有HTTP502/504优先改成“后台服务暂不可用”，覆盖后端受控MAP_*错误。此前一次真实失败的具体错误码未被保留，因此仍不能事后断言原请求是Key、额度、超时或网络故障；传输层检查成功也不等于高德业务调用通过。

修改：管理端只展示本地白名单地图提示，附安全追踪编号与错误码，认证/权限响应仍优先；未知网关错误保持原通用提示。停车解析页面等待15秒以接收供应商10秒超时后的后端错误；浏览器等待超时明确结果未知、可能扣次。后端供应商调用及解析失败只记录操作类别、受控码、HTTP状态、数字infocode、耗时、追踪号，不附异常对象、URL、Key、地址、坐标或原文。原重试、扣次、停止与派单规则不变，无数据库迁移变化。

本轮最终验证：管理端npm run lint/typecheck/test/build均通过，170项测试0失败/跳过。后端执行仓库mvnw verify -Ppostgres-it（DevelopmentMapBudgetPostgresIT、MapRecommendationPostgresIT、MapMigrationPostgresIT），85单元+22集成0失败/错误/跳过，完整业务及独立预算迁移在新隔离schema执行/校验，JAR构建成功。新增合成HTTP测试验证网络/超时/格式/无结果/无路线、日志无敏感内容、单次失败不重复记录、不自动重试、预算拒绝与异常不改变既有行为。首次通过后补充预算异常保护与测试，最终源码重跑全部上述后端检查通过。H5、Compose未改未重跑；真实地址解析及路线未验证。

已安全更新5210→18105，运行标识485f54d+map-errors-9c532c5af2bd（485f54d基础上的本地未提交补丁，并非新Git提交），Provider仍AMAP_WEB_V1。进程身份、原JAR哈希及原迁移逐项核对后，只更换18105后端；新JAR在仓库外独立构建目录，旧JAR及manifest均保留。目标仍ev_auth_phase2_test/manual_case_20261022_204936，业务Flyway V26、独立预算V2不变；实际配置API返回运行标识与manifest相符，5210实际提供新前端模块。后续该版本重启应使用仓库外Install-Map-Error-Diagnostics.ps1，旧安装脚本保留仅作历史记录。

只读前后核对：147区域及144条区域新增审计保持；案件、网点、服务关系摘要一致；历史地图尝试仍0。本轮新增真实高德/百度调用0，地址解析仍已用1/剩9，逆地理已用0/剩5，驾车已用0/剩20，预留均0。未恢复、补发或重置任何额度，未创建/确认停车位置或派单。

前端热更新重载导致未保存停车地址清空，需重新填写；最后只读查看页面已回登录页，需重新登录。未为显示新错误而再次调用高德。此前广东区域导入未提交记录完整保留；本轮源码及文档仅留本地独立开发分支，未推送、未创建或合并PR、未部署生产。构建日志、安装核对、前后摘要和源码补丁保存在仓库外mock-manual-dispatch-runtime及guangdong-region-import-20261023目录，无真实地址或密钥。


## 2026-10-23：派单位置与候选界面简化（独立本地分支）

用户确认先完成不产生真实高德调用的界面与Mock交互。上一轮错误提示修复和区域导入记录先保存为本地检查点915d426，原工作区保留；从该实际5210代码建立codex/dispatch-map-simplification隔离工作区。只读fetch确认origin/main仍1ebb58c；本轮不是最新main整合，不合入主线或其他功能分支，不覆盖队友页面和业务。

正式派单位置面板改为位置卡、候选卡、原提交操作：城市/区县/详细地址搜索与人工确认、网点单选、选中摘要；技术额度及诊断收进默认折叠服务信息。原事故区县入口、人工派单、资格、范围、角色、用量确认和原提交服务保留。真实高德JS底图尚未配置，实际页明确显示示意状态，不用Mock网格创建正式位置或伪造坐标。新增两个轻量展示组件及开发预览HTML，未增加依赖；预览强制本地Axios adapter，无网络回退/业务提交，所有资料和排序数值为合成，不属于正式合作网点或道路验收。

修复相关状态：仅调整网点查询范围不把未改变的已确认停车位置误标为未保存；原位置保存期间仍阻止提交。地址改变后清除结果/选择，迟到确认和路线响应不能恢复旧值；过期推荐选择明确提示且解除旧选择。保留后端最终复核、原派单幂等及权限，没有伪造推荐ID或绕过服务资格。

本轮最终管理端npm run lint/typecheck/test/build均通过，180项测试0失败/跳过；涵盖模拟点击/键盘、单选/未知距离/过期/禁用、Mock标识、强制隔离传输、无自动提交、范围/地址状态及迟到响应。浏览器在独立5212纯合成入口验证默认推荐、改选、图标与单选同步、模拟提交、无候选提示、模拟失败保留输入后恢复、改地址清除旧选择且事故区县不变。生产dist没有dispatch-preview.html。后端/H5/Compose和迁移未改，本轮未重跑相应门禁；真实底图、真实道路、新的正式派单和手机实机未验证。

实际截图及最终前端门禁日志在仓库外dispatch-ui-evidence-20261023。后续运行切换及台账核对另记；本节不把模拟提交写成正式派单成功。私密配置、真实案件、既有学校验收及供应商授权均不修改。本轮尚未推送或创建/合并PR。


运行收尾：代码提交1546421661bbe16744434f678b6e60f86be3cba8。核对原Vite进程身份后，仅将5210前端切换到dispatch-map-simplification/admin-web；仍请求18105。后端进程/JAR哈希及运行标识485f54d+map-errors-9c532c5af2bd完全保留，业务schema manual_case_20261022_204936 / Flyway V26、独立预算V2均未升级。安装前后案件、网点、服务关系、147区域及持久用量摘要一致；地址解析已用1/剩9、逆编码0/剩5、路线0/剩20，预留0，本轮真实高德/百度请求0。

5210实际模块确认包含新候选组件，浏览器重新打开原案件确认新布局；四项未保存的停车输入只暂存在浏览器工具内存，更新后按原值恢复并比对一致，没有解析、确认停车位置或提交该案件。最终合成预览入口为http://localhost:5210/dispatch-preview.html；同一组件在此完成默认推荐、改选网点乙、明确模拟提交，结果提示未写业务数据。05/06-final截图为5210实际预览；01—04为本轮先前5212隔离预览，均为Mock合成数值，不是历史或本轮真实高德结果。任务自建5212进程已核对身份并关闭。

最终日志/截图与installed-runtime.json保存在仓库外dispatch-ui-evidence-20261023，源码/配置/数据库不含真实Key、真实图片或用户停车输入。本轮仅本地功能分支提交，未推送、未创建或合并PR，不声称进入远程main。真实JS地图/选点、真实网点数据及其道路验收为后续需配置和授权的事项，不能用本轮Mock预览代替。


## 2026-10-23：高德JS底图与受控选点接线

用户提供JS Key并回复已填写配套配置；检查发现值在批注而实际私密项仍空，按当次明确提供的内容补入原被Git忽略的.env.amap-js，只输出是否有值，未复制历史百度凭据。新分支codex/amap-js-map基于当前5210界面分支e2eb07f，保留原工作区，不合入main或其他未合并分支。

新增真实JS API 2.0显式加载组件、地图点击/拖动暂选和网点标记单选联动；未点击不下载SDK，不使用设备定位、POI或客户端路线。后端只给已登录客服返回公开JS Key，用短期HttpOnly同站资源Cookie、逐请求账号复核和固定官方资源路径代理保护jscode；Cookie不能用于其他业务接口。新选点核验复用原REVERSE台账和广东行政校验、候选/确认服务，不修改事故区县或自动派单。推荐仅增加短期可空网点point，无新表或Flyway变化。

最终验证：管理端npm run lint/typecheck/test/build全部通过，189测试0失败/跳过。后端mvnw verify -Ppostgres-it（DevelopmentMapBudgetPostgresIT、MapRecommendationPostgresIT、MapMigrationPostgresIT），94单元+23隔离集成全部0失败/错误/跳过，完整迁移及validate、JAR通过。首次测试编译的断言方法参数及前端shops同名lint错误已修正重跑，没有删除或放宽测试。最终后端包含回调注入和跨站防护后再完整重跑通过。后端/H5以外的全量业务集成及Compose未重跑；H5未改。真实JS联网、域名白名单、Key权限及真实选点效果未验证。本轮代理真实高德/百度请求0；运行切换和台账核对另记。

官方接口及安全方案、操作/API和限制见AMAP_JS_MAP.md。私密Key、安全码、Web服务Key、JWT和客户地址未入源码、文档、测试夹具或Git。仅本地功能分支，未推送、合并或生产部署。

运行收尾：功能提交6303a020f1504d6417fb1f87e1cdb18d2b544aa8已装入指定5210前端及18105后端；同源/api资源代理与前端实际API目标均核对为18105，后端配置API返回相同buildCommit，JAR哈希与manifest一致。被忽略的本机JS私密配置显式启用，配置API仅返回enabled/configured标志；浏览器已显示可点击的“加载高德地图”，未点击、未下载SDK、未加载真实底图。截图current-5210-js-map-ready.png是实际页面的加载前状态，不是联网通过证据。

安装前逐项核对业务、开发种子和独立预算迁移；两份预算SQL的Git检出仅存在CRLF/LF换行差异，逐行内容一致，未修改迁移。新后端在原业务schema manual_case_20261022_204936校验28条记录、独立预算schema amap_dev_budget_5210_20261023校验3条记录，均已是最新、无需迁移（业务V26、预算V2保持）。首次安装的localhost启动探测未完成，未更新前端即安全停止新进程；核对127.0.0.1直连健康正常后，从原manifest检查点恢复并完成安装，未更换数据库或生成第二份预算。旧JAR、工作区和manifest-before-amap-js.json全部保留。

安装前后案件、网点、服务关系、147区域及144条区域新增审计摘要完全一致；GEOCODE仍已用1/剩9、REVERSE已用0/剩5、DRIVING已用0/剩20，预留均0，历史尝试0。本轮新增真实高德/百度调用0。浏览器重载后四项停车输入与重载前一致，没有创建/确认位置、调用路线、修改事故区县或提交派单。当前案件事故区县缺失，真实合格合作网点资料仍需维护；启用底图不会自行补齐业务资料。

本机安装脚本、脱敏版本核对、原/新manifest及启动日志位于仓库外mock-manual-dispatch-runtime；完整性摘要位于guangdong-region-import-20261023（integrity-js-map-before/after.json），实际截图和检查日志位于amap-js-evidence-20261023。截图只保留地图加载区域，无手机号、车牌、密钥或客户地址。后续需由用户点击加载并核验真实底图、域名/权限与选点；本轮不把189前端测试、94单元+23集成或启动成功当成真实高德验收。未推送、未创建PR、未合并main、未部署生产。

## 2026-10-23：地点名称搜索与独立10次预算

用户明确要求实施地点名称搜索并自行测试；新增单独10次POI_SEARCH，不挪用GEOCODE/REVERSE/DRIVING。新分支codex/amap-place-search基于实际5210代码a0e0109，保留原工作区/main及队友修改。先更新需求/决策，再新增受客服JWT保护的后端places接口，复用原Web服务Key、HTTP超时/有界响应/脱敏异常及原位置候选确认。官方v5/place/text严格区划参数与返回行政复核，一页10条、最多20页，只有人工点击搜索或翻页才调用；不自动首选、确认、回退、重试、扩大区域或创建合作网点。

前端保留完整地址和地图选点，增加搜索方式、地点名称候选、明确地图查看和原确认操作、页次及调用提示；空结果与失败保留原输入，切换模式/输入/位置使旧候选与推荐失效。原事故区县、资格、方向、路线策略和正式派单边界不变。候选地图查看不追加搜索，但已加载底图的地图资源请求仍按供应商规则计量。

新增独立开发POI schema和Flyway目录db/map-place-budget/V1__separate_place_search_allowance.sql，不改既有业务或旧预算迁移。启动不发放额度；本轮本机配置以用户明确授权为依据，一次性准备10个持久槽位；使用、失败、超时均不归还，重启/日期变化不补次。无明文关键词或坐标入台账，Key始终在后端，POI错误不会静默使用Mock结果。

按用户要求，本轮不运行自动测试、lint、浏览器联调或真实接口；只做生成运行文件所需的TypeScript编译、Vite构建及Maven package（-DskipTests）。初次前后端编译构建已成功，随后预算错误隔离小修将再次打包；不沿用上一任务测试数量宣称本轮通过。实际运行切换与授权准备另记。本轮新增高德/百度请求0，真实搜索效果/Key高级搜索权限/地址质量由用户自行测试，未验证。未推送或合并，不部署生产。

运行收尾：功能代码f6ebaa095f315b2b2b2b903ce4366331d9250b59已安装到指定5210前端/18105后端，原amap-js-map工作区、旧JAR及manifest-before-place-search.json保留。原业务schema manual_case_20261022_204936（V26）和amap_dev_budget_5210_20261023（V2）启动校验后无需迁移；仅新建amap_place_budget_5210_20261023并执行其独立V1，未修改旧迁移。后端编译打包、前端TypeScript编译/Vite构建成功；用户要求不检验，因此未运行本轮测试、lint、浏览器搜索/选点/派单或真实接口，不引用上一任务通过数字。

用户单独授权的POI_SEARCH在独立run localhost-5210-place-20261023-approved-10一次性准备10槽，配置时已用0、剩10；启动脚本重跑只核对已有授权，不补槽或重置尝试。原三分类槽位无写入操作；进程总量技术防线随新增10次从35调整为45，不为原分类扩容。新配置单独保存在仓库外amap-place-search-runtime/.env.place-budget，密钥继续从原私密配置读入；不会因前端可见搜索方式就自动发出请求。POI搜索高级服务权限和真实效果仍需用户自行验收，不能保证此前GEOCODE的30001通过新增接口自动消除。

更新后仅恢复原未保存城市/区县/文字和查询范围，没有点击搜索、加载地图、确认位置或提交派单。实际新增搜索方式区域截图search-mode-current.png位于仓库外amap-place-search-runtime，无客户身份、Key或Token。安装脚本、构建日志和一次性配置回执亦在该目录；原manifest及安装启动日志在mock-manual-dispatch-runtime。本轮代理新增真实百度/高德请求0。仅本地分支codex/amap-place-search提交，未推送、创建PR或合并，不部署生产；此后由用户明确操作搜索和查看剩余次数。

## 2026-10-23：统一“输入地址／搜索位置”

用户确认取消搜索方式选择，输入栏直接显示“输入地址”。仅调整ParkingLocationPanel.vue：实际页面一律调用既有places服务，保留城市/区县、4—80字符限制、分页、明确候选选择与确认；不并行调用GEOCODE、不失败自动切换，不增加或重置POI_SEARCH及其他分类额度。原受保护后端接口保留兼容，Mock纯合成预览仍使用隔离adapter。输入/区域变化仍清理旧确认与推荐，确认及原派单边界不变。

仅执行生成前端版本所需的TypeScript编译及Vite构建，均成功；按用户此前要求不进行测试、lint、真实搜索、地图加载或派单联调。后端源码、JAR、数据库/迁移、私密配置及运行进程不变，本轮真实高德/百度请求0；由既有5210 Vite热更新提供新界面。实际搜索质量与权限由用户自行测试，未验证。更新前保存未提交输入，界面更新后恢复；不伪造恢复候选/位置确认。未推送、合并或生产部署，继续保留原独立分支。

## 2026-10-23：公开两轮电动自行车维修候选及本案临时门店

用户明确确认：仅两轮电动自行车；停车点周边10公里、每次最多20家公开结果；不以合作状态为推荐条件；客服可为本案登记临时门店；公开查询/翻页和手动门店地址搜索共享原POI_SEARCH剩余额度，总授权仍10次、不补次数，路线用原DRIVING剩余额度。用户自行测试，本轮不做功能联调或真实调用。新分支codex/ebike-public-workshops基于实际5210前端0054930，原工作区/主线保持；fetch确认origin/main仍1ebb58c、V27/V28在主线而当前运行业务基线V26，未擅自合入/升级这两份迁移。

实现：后端固定v5/place/around参数、广东返回行政复核、保守两轮维修名称/类型分类，明确汽车专修/纯销售/无关结果过滤、边界类型待核实。一次一页20条，不自动扩圈/翻页。公开候选、手动补充、选定集合路线计算独立于合作关系；按原车辆→门店策略32道路距离排序，未知留空，相同距离稳定次序，路线2分钟后过期。先预留完整选中集合额度，不够不截断；最多60秒执行窗口，未开始的槽位正常释放，已尝试不归还。持久外呼操作键防重复/进程重启后补发，候选只留短期内存，不存供应商原响应/坐标/完整轨迹。

客服在核实门店身份、两轮维修能力、愿意接本案及业务名称/地址后，才能登记并选择临时网点；本案绑定、事故区县及服务关系不可扩成任意案件。服务端复核加数据库触发器保护，旧版程序亦不能借该临时网点跨案派单；原人工网点分页排除其他案件的临时门店。正式派单仍用既有状态/地址/区域/重复/幂等/并发/审计服务，创建临时网点不派单、不建账号、不发外部通知；管理员仍负责网点账号邀请。原合作网点推荐折叠保留、H5无新增内部地图权限。

结构采用新增db/public-workshop/V1__case_scoped_workshops.sql及独立flyway_public_workshop_history，仅追加本功能关联、操作记录与范围保护，不改已执行SQL，不清库或repair，不升级主线V27/V28。默认PUBLIC_WORKSHOP_ENABLED=false，无测试门店/案件/账号/额度初始化。API、留存边界和后续管理员操作见PUBLIC_WORKSHOPS.md。

按用户要求未执行测试、lint、真实接口、浏览器搜索/排序/登记/正式派单验收；仅生成运行文件所需TypeScript编译、Vite构建和Maven package（-DskipTests）。前后端初次编译打包成功，最后范围保护追加后再打包，不引用旧测试结果。本轮真实高德/百度请求0。分类准确性、高德高级搜索权限、真实排序及临时门店派单/接单仍未验证，不能把编译通过当作验收通过。实际运行切换另记；不推送/合并或生产部署。

## 2026-10-24：公开维修门店任务进度核对与运行收尾

已核对本任务功能提交为55db5ed753ac6ee9bed4c62b3e0ca5ecd73e510c，分支codex/ebike-public-workshops；恢复任务时工作区干净，无需重写已完成代码。实现、配置和API见PUBLIC_WORKSHOPS.md。搜索仅限两轮电动自行车维修线索，不代表高德分类或实际能力已经人工验收；手动补充及本案临时门店登记已接入原派单选择，登记不等于正式派单或账号开通。

上次安装实际于2026-10-23 23:07完成：前端5210、后端18105均使用55db5ed，MAP_PROVIDER=AMAP_WEB_V1、PUBLIC_WORKSHOP_ENABLED=true，OCR为MOCK_V1且Worker关闭。目标仍为原ev_auth_phase2_test数据库、manual_case_20261022_204936业务schema。启动记录显示原业务V26、开发预算V2、地点搜索预算V1均无需迁移；仅独立flyway_public_workshop_history应用本功能V1。未修改任何旧SQL、清库或repair，旧JAR、工作区、manifest-before-public-workshops.json均保留；没有创建测试案件、网点或账号，没有执行派单。

核对上次生成物：2026-10-23最终Maven package（-DskipTests）成功，TypeScript编译及Vite生产构建成功。此为已有构建证据，不是2026-10-24重新执行的测试；按用户“不用检验”要求，本次不运行功能测试、lint、浏览器搜索/排序/登记/派单或真实接口，不引用历史测试数量宣称通过。分类质量、真实高德高级服务权限、道路排序及临时网点完整派单/接单链路仍未验证。

现有停车地址搜索、门店查询和手动门店地址搜索共用原POI_SEARCH总10次的剩余额度；道路计算沿用原DRIVING总20次的剩余额度。总授权不是当前剩余次数，使用量可能已被用户操作消耗，以现有持久台账和页面只读余额为准；本次不补槽、不换run、不重置日期或重启后的使用次数。进程45次技术上限不是额外授权。

用户自测路径：客服工单详情→派单到店→核实事故区县并独立确认、保存停车位置→查询附近维修门店（10公里、本页最多20家）→明确勾选比较集合并确认道路计算→选择并核实门店，或手动补充→登记并选择本案临时网点→点击原派单按钮。修改停车位置须重新确认；账号接单仍由管理员沿原受邀开通流程准备。没有道路结果不伪造距离，范围或分类不确定时由客服核实，不宣称全省最近。

本次核对发现原前后端及PostgreSQL均已停止，JAR哈希仍与安装清单一致。已使用原数据目录启动PostgreSQL，数据库因上次中断执行自身恢复，未初始化替代库或修改恢复参数；网页恢复结果另记。当前浏览器停在无法访问页面，读取该错误页面被浏览器协议安全策略拒绝，没有绕过该限制获取截图，也没有伪造页面或结果。没有真实百度或高德请求。本任务仍仅本地提交，未推送、创建PR、合并main或部署生产。

恢复结果：2026-10-24 12:53 PostgreSQL自行恢复完成；随后恢复原MinIO数据目录及55db5ed的前后端。前端http://localhost:5210，API目标http://localhost:18105/api/v1，均只监听本机地址。安装清单保留原配置、schema、JAR摘要及两个预算run，仅更新实际进程身份和恢复时间。后端12:54启动日志再次校验原业务28条、开发预算3条、地点预算2条及本功能独立历史2条（含baseline），各自均无需迁移；没有再次执行本功能V1。此为启动与迁移校验，不是业务功能测试。启动脚本和脱敏日志保存在仓库外ebike-public-workshops-runtime、mock-manual-dispatch-runtime，未写入或重置台账。上次浏览器会话已丢失，未伪造恢复原输入、候选或位置确认；需用户刷新并登录后重新核对、确认停车位置。按用户要求，正式功能与真实接口测试继续由用户操作。
