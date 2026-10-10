# 第7阶段交付与验证

2026-10-10。指定第二阶段仓库，feature/phase7-repair-delivery从已推送阶段6的9619766续建；阶段1—6、Boot4.1.1、四角色及模块化单体保持。第一阶段计划未修改，所有新增文件在第二阶段。**第7阶段已完成；角色变更后的幂等回放字段过滤与完整回归通过；未提交/推送、未开PR、未部署、未进入阶段8。**

## 最终实现

- V8维修进度/完工，V9收车确认，V10撤回/评价/独立投诉；V1—V8已用迁移未改，通过新迁移演进。继续案件行锁/CAS/派单版本/幂等及不可变审计。
- 网点或客服代录进度/完工，必须合法开修；完工至少1张当前网点、本次派单实际上传的有效JPG/PNG图片。上传身份依据上传审计中的历史角色，客服上传带网点元数据的图片不冒充网点证据。提交后照片版本快照冻结，API和数据库均拒绝替换/作废。
- 绑定车主或客服确认收车后COMPLETED，评价/评分/资金都不是完成门槛。客服必须有原因撤回至WAITING_OWNER_CONFIRMATION，原完工证据保持冻结。收车确认/撤回均追加记录，保留操作者/来源/时间及状态历史。
- 车主可一次提交可选文字或1—5整数评分，至少一项，也可完全跳过。提交后不可改；客服有原因新增纠正版本，不覆盖原文/原评分，输出标识客服来源。撤回后原评价留历史且不计当前评分；再次收车不能重复评价，客服追加绑定当前收车的纠正版后恢复当前资格。
- 绑定车主开修后可提交独立投诉，完成后允许、取消后不新增；管理员顺序SUBMITTED→PROCESSING→RESOLVED→CLOSED，客服追加纠正说明。原正文/证据/处理记录保留，车主不可修改。投诉不改变维修或评分，网点仅当前派单公开内容；内部说明仅管理员/客服。
- 两端现有详情加入必要真实API面板，保留失败重试的幂等键；不展开完整UI重设计、资金/通知/导入、短信或真实外部服务。

## 验证结果

| 检查 | 最终结果 |
| --- | --- |
| 后端完整clean verify -Ppostgres-it | PASS；14单元＋54真实PostgreSQL集成，0失败/错误/跳过；JAR构建通过。保留阶段6的12单元/44集成，新增2照片规则单元与10集成 |
| Flyway | PASS；真实随机专用schema应用V1—V10及devV2.1，精确迁移序列/validate、普通/dev切换、重启不重复迁移；未改已用迁移 |
| 管理端 | lint/typecheck/test/build全部PASS；21测试，0失败/跳过 |
| H5 | lint/typecheck/test/build全部PASS；11测试，0失败/跳过 |
| 状态/权限/数据 | 无合法开修不能更新/完工，缺照/PDF/错误类别/跨案/旧派单/历史版拒绝；他店/未绑定车主拒绝；客服代录守门禁；管理员不能代收车或改车主评价；越权撤回/评价纠正/投诉处理拒绝 |
| 并发与幂等 | 进度/完工/收车/评价/投诉同键重放不重记；跨案件同键冲突409；完工和照片作废串行互斥；车主与客服同时收车一成功一409，仅一条确认/审计/状态历史 |
| 反馈与投诉 | 可选文字/可选评分、空值/小数/字符串/越界拒绝；原评价/纠正版/撤回/投诉原文及事件数据库不可覆盖；评价历史内部原因、投诉内部说明从外部字段中移除；合法角色变更后幂等回放按当前角色过滤内部原因；投诉不改变维修上下文或评分 |
| 真实JAR/HTTP/MinIO | PASS；7组验收，含阶段6价格/门禁回归、真实上传/下载、客服完工代录、收车/撤回/再次收车、原评价保留/纠正、完成后独立投诉、幂等与公开/内部隔离 |
| 重启 | PASS；同一JAR停止/重启后版本7、COMPLETED、原评分5/纠正4、原投诉/CLOSED/处理记录、照片快照及受控下载、报价/核损快照保留 |
| Compose/服务 | config --quiet PASS；PostgreSQL/MinIO healthy，MinIO ready200；专用DB25432、MinIO19000/19001；无工具重装、全局修改、清库或删卷 |
| Git检查 | git diff --check通过；分支保留，未提交/推送/PR |

HTTP合成案件a30771eb-80a1-42fb-9aa9-f5c5c70cf2a3，投诉2c7a2337-6308-4729-a7e9-03c692b31b55，schema为phase7_http_ab423c309a1f443fbcef15e2ccd5855b。测试只设置此随机schema中的合成车主手机号绑定，未扩展原有绑定/改派/取消流程。

完整证据在第二阶段local-validation：phase7-release-final-build内surefire/failsafe报告与JAR、phase7-complete-regression.log、phase7-baseline-results.json、两端8项日志、phase7-http-results.json、phase7-http-runtime.log/phase7-http-before-restart.log。凭据只从未提交本地环境文件读取，不写入文档。

## 失败及未执行

- 首轮维修部分的迁移预期、误用作废路径、TS幂等键类型与命令锁映射失败均保留，修复后通过；详见PHASE7_REPAIR_VERIFICATION，未删除/跳过/放宽测试。
- 本轮旧phase7-build目录被Windows锁定，clean失败保留phase7-delivery-clean-failed.log；使用新的隔离phase7-release-final-build执行完整clean verify成功，不删旧目录或修改ACL。
- 最终新增角色变更测试首轮误用了不存在的roles路径，1项404失败；日志phase7-role-replay-first-failed.log、JSON及phase7-release-build内failsafe报告保留。修正为既有assignment接口后完整14单元/54集成通过，未放宽断言。
- HTTP最初Docker调用受沙箱限制，失败JSON保留；获得执行授权后同一专用验证链路通过，无系统重装或配置修改。
- 未执行浏览器视觉验收：两端代码门禁与真实API链路通过，不将生产构建视为视觉QA。
- 未执行GitHub推送、PR、云部署、真实付款/短信/外部服务或第8阶段。
- 无第7阶段阻塞业务问题；归档保留与第8阶段资金/导入/通知规则仍留其阶段确认。

## 启动与复验

仓库根目录PowerShell：

```powershell
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase7-dev-build'
.\scripts\Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env' -BuildDirectory '..\local-validation\phase7-release-final-build'
.\scripts\Verify-Phase7-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

普通后端8080；分别进入admin-web、h5-web执行npm run dev，对应5173/5174，经Vite代理8080。本地dev账号密码只在.env.phase5查看，不写报告。HTTP脚本临时18080，自行启动/重启/停止测试JAR；本次测试进程已停止，专用PostgreSQL/MinIO及数据保留。若旧验证目录再次锁定，使用第二阶段local-validation内新的隔离构建路径，不手动删锁定文件。
