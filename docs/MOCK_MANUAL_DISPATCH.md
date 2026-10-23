# Mock模式人工派单核查与修复（2026-10-23）

## 复现结论

5210实际为前端29b792d、后端9900f7b、18105、schema `manual_case_20261022_204936`、MOCK_V1，不是另一台电脑或另一套main实例。合成案件 `b0348704-c381-450f-9b52-90f9e1240290`（报案号SYNTH-MANUAL-FORMAL-20261022-B）初始缺事故区县、未确认停车地址、无网点选择，因此原按钮禁用包含正常业务校验。

原版实际操作中，填写城市/地址并确认Mock定位成功后，停车信息已保存，但事故区县仍为空，候选为0；页面却提示“该城市暂无…请修改城市”，没有指向真正缺失的事故服务区县。随后通过既有独立入口补全合成事故区县，原版即可显示DEV-SHOP并启用派单按钮。因此不能宣称“Mock服务全面失效”或“后端禁止所有Mock派单”。原版当时仅验证到按钮可用，没有提前提交派单。

另外确认的实现问题：Mock候选界面硬依赖定位成功，已有不调用地图的人工入口仅在非Mock模式显示；已保存位置的恢复依赖第一页城市及从事故区域/地址推断城市；原eligible-shops接口在Mock模式也会调用Mock正编码计算模拟距离。定位失败、列表加载失败及重新打开时存在不必要的阻塞和恢复风险。

## 修复与接口边界

- Mock模式直接复用ManualDispatchChoice，默认展开停车确认和资格候选。页面明确显示“Mock模拟地图，位置和距离仅用于测试，不代表真实道路”。不新增全局供应商开关。
- 事故区县仍由独立AccidentDistrictReview及原服务核实；停车区域/地址通过原dispatch-location保存，使用当前案件版本、人工确认、权限与审计，不修改事故字段。
- 仅按原人工规则：事故区域精确服务关系＋网点启用。未声明车型能力已验证，也没有将服务城市自动展开到全部区县。真实高德推荐的能力、位置、预算及推荐会话规则原样保留。
- `GET /api/v1/work-orders/{id}/eligible-shops?includeMockDistances=false&page=1&size=20` 新增可选查询参数；false仅关闭模拟距离补充，直接按现有资格SQL读取、名称/ID稳定分页，金额和资格规则不变。默认true兼容旧客户端、旧Mock距离排序及地图接口；新旧重载均保留原只读REPEATABLE_READ事务，候选总数与分页来自同一快照。
- false分支不调用MapProvider的任何方法，距离为空；不用Key、真实预算、geocode或recommendationId。接口依旧仅ADMIN/客服，保留案件可见性、分页上限及后端提交时的资格复核。
- 必须人工选网点，不自动采用首条或模拟最近者。Mock示意图改为可选，不参与派单阻塞；仅展示第一页模拟点，不冒充全体网点道路排序，失败不能清除已确认地址或选择。
- 缺区县、未确认停车地址、列表失败、无资格网点和未选择网点分别说明原因。区域/地址修改、取消确认、刷新候选及资格变化清除旧选择；迟到请求不替换新服务区候选。已确认地址重开不要求定位，不因区域不在第一页而丢失。
- 原“派单/改派”按钮、状态机、重复判断、命令幂等、审计及站内通知保留。没有伪造确认、推荐ID、坐标或道路距离。

## 实际操作与读取证据

本轮沿用上述合成案件，未新建案件，也未操作昨天高德验收schema中的学校案件。同名展示工单号不表示同一数据库对象。

1. 上方事故区县入口核实“模拟覆盖省 / 模拟覆盖市 / 模拟覆盖区”，填写合成测试原因并记录审计。
2. 在默认展开的人工入口选择停车区域，填写“模拟覆盖市模拟覆盖区合成停车入口2号（非真实地址）”，勾选核实并保存。
3. 实际刷新页面：停车信息保持已确认；未选网点仍禁用派单，页面指明需选择。
4. 人工选择“开发模拟维修网点”（DEV-SHOP，ID1），点击原“派单”。本店已有启用状态及该测试区服务关系，无新增网点配置、坐标或服务区域。
5. 页面进入“待接单”，显示当前派单网点；不继续接单、到店、报价或其他业务。

| 后端及SQL只读核对 | 结果 |
| --- | --- |
| 案件/状态 | EVR-20261022-000001，PENDING_ACCEPTANCE，案件版本5 |
| 人工选择/实际归属 | 都是DEV-SHOP / ID1 / 开发模拟维修网点 |
| 派单版本/状态 | assignmentVersion=1，PENDING |
| 记录唯一性 | work_order_assignment1条，WORK_ORDER_DISPATCH审计1条 |
| 同命令键重放 | HTTP200，案件、网点、派单版本一致，记录与审计仍各1条 |
| 字段隔离 | accident_region_id=3；accident_address仍为空；parking_region_id=3及独立停车地址，停车保存未填造事故地址 |
| 供应商及用量 | MOCK_V1；callsEnabled=false、callsRemaining=0、真实预算尝试0；未调用百度或高德 |
| 距离/推荐 | 人工候选distanceMetres=null；本轮不需要真实/模拟道路结果或推荐ID |

站内测试待办/通知沿用原流程，不发送短信、运输或其他外部任务。原图/材料、账号、旧运行包、昨天高德库与台账保留。

## 两套代码与迁移安全

开发分支 `codex/mock-manual-dispatch-fix` 基于最新远程main `1ebb58c849703d171438bf8ed0de90f191138e79`，保留队友新H5、界面、业务及V27/V28。此前独立手工建单修复尚不在该main中，本轮没有擅自合并该分支或把无关修复混入PR。

5210原版含手工建单修复、使用V26隔离schema。为保留它且不升级旧库，专用运行副本以29b792d为基线，仅回移本轮补丁。补丁校验可干净应用，两套源码分别测试。原工作区未覆盖；运行副本在仓库外mock-manual-dispatch-runtime/source，属于可重建开发运行材料，不冒充完整最新main。

替换前核对PID、命令行、创建时间、真实schema和JAR哈希；保存schema备份及旧进程清单。新旧JAR内27个SQL资源逐个哈希一致；仅替换5210/18105两个已登记进程。启动再次Flyway校验28资源、当前V26、无待执行迁移。没有清库、repair、改历史或将最新main JAR直接连接旧schema。

运行地址：`http://localhost:5210` → `http://localhost:18105/api/v1`，数据库为本机127.0.0.1:55432的ev_auth_phase2_test、schema见上文。后端实际buildCommit为 `29b792d+mock-dispatch-622e929b7435`，JAR SHA256 `4CDBF899B3E50E97DEFD54F4B31F7132F1C8885B3CA4199DDD83DC4C7FF924F5`。最终前后端代码补丁SHA256为 `622E929B74354273A131D788EC7A86BBAA9DAD356FFD98724A288EE0CB7F4F3C`，各前端文件摘要另记于仓库外manifest，不将受控回移误写成完整功能分支运行提交。

派单成功后的最终审查补齐四参数候选查询的原只读、可重复读事务注解，两套后端重新完整运行上述相关门禁后，只更新本轮后端到最终包。没有回退或重派，最终读回和同键重放仍为同一网点、待接单/v5/派单v1，记录及审计各1。

私密配置沿用原后端文件，本轮只读，未覆盖或输出值；不把密钥注入前端。运行脚本、备份、原始日志、截图、脱敏读取结果和可查看acceptance.html均保存在仓库外mock-manual-dispatch-runtime，不进入公开Git。

## 验证及限制

| 本轮实际执行 | 结果 |
| --- | --- |
| main基线后端 `mvnw.cmd -B -ntp '-Dvalidation.build.directory=C:/Users/Sherr/Documents/Codex/mock-manual-dispatch-runtime/main-backend-reviewed-build' '-Dit.test=WorkOrderPostgresIT,MapRecommendationPostgresIT,MapMigrationPostgresIT,IdentityPostgresIT' '-Dspring.test.context.cache.maxSize=1' verify -Ppostgres-it` | 80单元＋45相关PG/MinIO集成测试、Maven构建通过；新schema完整V28迁移及验证 |
| 运行副本相同命令，构建目录为C:/Users/Sherr/Documents/Codex/mock-manual-dispatch-runtime/runtime-backend-reviewed-build | 80单元＋48相关集成测试、Maven构建通过；保留此前手工建单测试；完整V26迁移及验证 |
| 两套管理端分别 `npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 全通过；最终main151、运行副本165测试，失败/跳过0 |
| 最新main H5相同四条命令 | 全通过，42测试，失败/跳过0；本轮未改H5 |
| 实际浏览器＋真实隔离后端 | 缺项/地址编辑失效、保存、重开恢复、明确人工选择、原按钮提交、待接单和网点归属通过；实际接口重放及SQL唯一性通过 |

测试覆盖无Key/无预算、MapProvider零交互、字段隔离、未确认/未选择、无候选、停用/区域不匹配、版本冲突、失败重试、请求错序、地址变更、重复操作及四角色/跨店数据边界；原默认Mock排序和真实推荐离线回归仍执行。

首轮Maven因PowerShell未引用带点参数而在测试前失败，修正引号完整重跑；原前端“保存后最后请求必须为PUT”断言随新增刷新改为检查准确PUT内容及后续资格GET，未删测试或弱化校验。原“Mock定位失败就不能派单”测试按本次明确要求改为失败不阻塞人工路径，原其他正向及恢复场景保留并扩展。

未验证：真实高德/百度识别和道路效果（本轮禁止调用）；真实客户/合作网点运营；实体手机及其他浏览器；真实浏览器网络故障注入（失败、错序由组件模拟和后端测试覆盖）；全部其他领域集成测试及Compose本轮未重跑。未合并、未部署生产。Git提交/推送/PR的最终状态见PROGRESS最新补记。
