# 第8阶段交付与验证

2026-10-10。第8阶段完成，D-038—D-040全部已确认。指定第二阶段仓库feature/phase8-funds-reminders，入口HEAD9619766，第7阶段未提交成果保留；四角色、Boot4.1.1、模块化单体及阶段1—7机制保持。第一阶段计划未修改。未提交/推送、未开PR/部署、未进入阶段9或完整UI设计。

## 最终成果

- V11 funds：不可变人工应收/应付版本、分笔实收/实付、整笔冲销、独立结清、锁/CAS/命令幂等/方向＋流水号全局去重和审计。金额字符串CNY元/2位，NUMERIC(38,2)；应付可空，实际每笔>0，目标0允许，不从核损推导。
- 实付必须实际收车完成、当前应付已定、保险应收结清；超额拒绝。取消仅历史冲销；收车撤回保留资金但拒绝新实付。金额目标不低于净流水，不覆盖旧目标/流水，不改变维修状态。
- XLSX/UTF-8 CSV预览、2000行/10MiB、双编号交叉核对、人工有原因匹配、行级事务确认/错误结果、重复导入去重；真实私有MinIO源文件及哈希校验、创建客服专属下载。预览批量匹配/500行分块写入。
- V12 notification：审计同事务持久化outbox，Worker并发有界领取与幂等个人通知；角色/绑定/当前派单实时复核、每人已读、历史保留。待办基于业务状态，处理/改派后失效。
- 客服可选单待办deadline，+08:00或空，原因/CAS/不可变历史；逾期只标签。自动超时、重复、升级关闭，SmsProvider仅Disabled实现不发送。
- 两端必要真实API面板、受控CSV导出和分页、字段脱敏；车主无资金接口，网点仅当前派单PAY。API/数据字典/需求/决策/流程/架构/进度/启动说明已同步。

## 最终门禁

| 检查 | 结果与证据 |
| --- | --- |
| 后端完整clean verify -Ppostgres-it | PASS：19单元＋62真实PG集成，0失败/错误/跳过；JAR构建成功 |
| 原有阶段回归 | 原54 PG集成保留，新增5资金＋3通知集成；原14单元保留，新增2资金金额＋3解析单元 |
| Flyway | V1—V12及dev V2.1迁移，隔离PG随机schema迁移/validate PASS；既用迁移未修改 |
| 管理端 | lint/typecheck/test/build全部PASS，21测试0失败/跳过 |
| H5 | lint/typecheck/test/build全部PASS，11测试0失败/跳过 |
| 真实HTTP/MinIO与JAR重启 | 14组全部PASS，含前阶段维修/报价链路及本阶段资金、通知、deadline、私有导入源文件保留 |
| Compose/服务 | 配置校验、PG/MinIO healthy、PG真实SQL、MinIO ready200 PASS；没有重装/清库/删卷 |
| 静态检查 | PowerShell HTTP脚本语法解析无错误；git diff --check无错误（仅已有换行转换提示） |

后端XML报告位于第二阶段local-validation/phase8-delivery-build/surefire-reports及failsafe-reports。最终构建日志phase8-delivery-passed.log、门禁phase8-delivery-results.json；各前端日志同目录。真实HTTP结果phase8-http-results.json及runtime日志。它们位于指定第二阶段，不含公开凭据输出，原环境文件未提交。

## 实际链路证据

资金目标120元应收/80元应付：先40元实收，同键回放不重记，保险未结清时付款拒绝；CSV再导入60元、同流水重复和编号冲突20元。首次确认逐行RECORDED/DUPLICATE/NEEDS_REVIEW，客服有原因选案件再确认，净实收120.00/SETTLED。私有源文件真实下载与上传字节数一致，管理员/车主/网点下载403。

80元付款→整笔冲销→新流水重录80元，净实付80.00/SETTLED，旧付款仍在历史；全部资金共5笔原流水。车主资金/导出403，网点响应与CSV无应收/内部备注/报价加价字段。PG集成另验证零目标、非正实收、精度拒绝、超额/目标低于净额、取消仅冲销、收车撤回、并发两笔30元争抢50元目标只有一笔成功、数据库历史不可修改。

单待办deadline可设过去+08:00时间并显示逾期，清除后保留2条历史；资金办结后旧taskKey写入409。自动提醒/短信/升级均false。车主通知无金额/内部说明/资金事件，个人重复已读仅一条；他人操作403。PG集成另验证角色/绑定变化后访问拒绝、改派旧网点失去通知与待办权限、并发投影不重复。

JAR停止并重启、重新登录后：净收120.00、净付80.00、5笔流水、私有导入源文件、2条deadline历史及个人已读状态均保留；维修证据/收车/评价/投诉同时回归通过。HTTP脚本结束已停止自己创建的后端，PG/MinIO保留。

## 失败与修复记录（保留）

1. 资金首次完整回归：59 PG集成中1失败，导入预览500。MyBatis同名batch查询/写入方法映射冲突，拆成insertBatch/findBatch修复；不删除/放宽断言。失败日志phase8-funds-first-failed.log/results.json与phase8-funds-first-build保留。修复后19单元/59PG及前端门禁通过，phase8-funds-passed.log/results.json。
2. 通知首次完整回归在testCompile失败：新测试引用不存在的line helper。改为显式报价行Map；失败日志phase8-notification-first-failed.log/results.json与phase8-complete-first-build保留。随后19单元/62PG及前端门禁通过，phase8-notification-passed.log/results.json。
3. 导入批量匹配优化后重新使用新目录完整回归，最终phase8-delivery-build结果如上；避免Windows已锁定目录清理，不修改ACL或删除旧报告。

## 未执行与剩余边界

浏览器视觉QA、2000行导入性能基准、生产备份/恢复及云部署未执行；不据此宣称验证通过。XLSX解析有单元验证，真实HTTP链路使用CSV。前端现有21/11测试是既有测试集，未声称新增业务交互全覆盖；新增金额/权限/通知业务由PG集成和实际HTTP验证。真实OCR/地图/短信/付款不接入。自动超时启用和归档策略为未来确认范围，本阶段无阻塞业务未决问题。

复验命令、环境及启动方式见LOCAL_DEVELOPMENT.md；接口见PHASE8_API.md。完成后停止等待下一步指令。

## 视觉验收补充

2026-10-10后续用户授权后，四角色浏览器基础布局验收已执行并修复H5进度时间重叠；范围、截图、相关H5检查和剩余可用性问题见PHASE8_VISUAL_VERIFICATION.md。上文“视觉QA未执行”是此补充之前的历史状态，未扩展为物理手机/全部状态通过。
