# 第6阶段交付与验证

2026-10-10。用户已确认D-032的金额、占比分摊与版本/确认失效规则。续建指定第二阶段仓库，origin正确，feature/phase5-files-ocr-map、HEAD85fa6f0；既有阶段5与Boot4迁移的暂存/未暂存内容保留，未自动提交、推送、PR或部署。

## 实现与证据

新增V7及quotation模块，保留阶段4状态机/派单版本/重复判断/CAS/幂等/审计、阶段5私有MinIO和Mock接口。网点不可变原始报价，客服固定额/百分比二选一审核，原始单价不改；BigDecimal/NUMERIC金额和API字符串，最大余数法按分精确分摊，数量>1使用6位展示单价、行金额为准。原始/正式/核损/确认/价格来源及金额快照永久保留，数据库触发器拒绝覆盖/删除。

客服代录核损、保司和客服确认；报价/核损金额/核损附件变化使两确认失效并撤销授权。开修事务锁案件重读四条件，无二次网点确认/回款门槛；开修后普通报价/核损修订拒绝。管理员只读内部审计，网点仅当前派单自己的原始报价及不带金额的门禁，OWNER报价接口403；受控历史CSV只导出对外金额，描述转义及公式防护（包括多行）。两端仅新增案件详情验证面板，不扩展完整设计/正式模板/后续阶段。

| 检查 | 通过证据 |
| --- | --- |
| 后端完整clean verify -Ppostgres-it | 12单元 + 44 PostgreSQL集成，0失败/错误/跳过；JAR构建成功；原39集成保留，新报价5集成 |
| Flyway真实PG | 随机专用schema V1—V7及devV2.1迁移与validate通过；V1—V6未改 |
| 两前端 | 管理端21、H5 11测试；两端lint/typecheck/test/build全部PASS，baseline-results.json及8份前端日志 |
| 金额测试 | 固定/比例/零加价、HALF_UP、数量>1、零金额行、同余序号尾差、全部零拒绝正加价、超精度/数值JSON/非整数数量/比例越界拒绝；200组合分摊守恒 |
| PostgreSQL业务/权限 | 不可变触发器、旧版本、两确认失效、每项缺失拒绝开修、四项全部满足、无需付款/正式报价第五门槛、开修后冻结、跨网点/旧派单、管理员不可写、OWNER403、网点/导出无内部字段 |
| 并发与幂等 | 并发同键报价仅一版本；开修与核损附件作废互斥正确；重放开修不重复事件或审计 |
| 真实HTTP JAR/MinIO | 固定100+10=110、比例100+1.23=101.23、原价及历史保留、字段隔离、缺客服确认409、核损替换撤销授权、重新确认后REPAIRING、开修后修订409；phase6-http-results.json |
| 重启 | 停止/重启同一测试JAR，原正式101.23/核损105/REPAIRING及授权事件持久保留；before-restart与runtime日志，使用同一随机schema；未删除数据卷 |
| 环境 | PostgreSQL17.6隔离25432真实SQL；MinIO ready200、真实写入；Java21/Node24/npm11使用既有工具，Docker健康，未安装系统工具或修改全局配置 |

完整日志backend-verify.log、JAR/测试XML位于第二阶段local-validation/phase6-build；脚本Verify-Baseline.ps1/Verify-Phase6-Http.ps1，启动方式见LOCAL_DEVELOPMENT.md。最终phase6日志副本便于与后续门禁区分。

## 失败与未执行

默认backend/target第一次完整门禁及一次clean重试因Windows无法删除classes/audit/api而失败，测试尚未执行；phase6-first-clean-failed.log、phase6-clean-retry.log保留。CIM未发现Java进程；没有手工递归删除、修改ACL或跳过clean。改用受路径限制的独立构建目录后完整验证通过。脚本生成时的PowerShell解析/前缀错误在运行前修正；没有制造通过或放宽测试。

浏览器逐项点击及视觉QA未执行；生产容量、生产备份恢复、正式PDF/Excel模板、异常开修后修订、价格库自动候选优先级、真实保司/OCR/地图/支付/短信均未实施。价格来源当前明确SHOP_MANUAL，OCR候选不自动写正式结果。Git作者身份此前未配置，本轮保留本地改动且未擅自改全局身份。

阶段6无待确认财务规则；第7阶段维修状态、完工照片最低数量、收车/评价/投诉规则待用户确认。本阶段交付后停止，不自动进入7。
