# 第7阶段维修部分验证记录

2026-10-10。当前分支feature/phase7-repair-delivery，基线9619766。第7阶段**尚未整体完成**：只实现用户确认D-035的维修进度/完工，收车撤回、评价、投诉等待最终规则。所有代码/文档/验证文件位于指定第二阶段目录，第一阶段计划未改，未推送/PR/部署。

## 已实现与通过

V8与repair领域、维修独立CAS版本、追加进度、完工有效照片检查及不可变快照、材料API和数据库冻结、角色/案件/派单隔离、状态历史/审计与幂等。两端详情新增必要真实API面板，保留网络失败的重试键。完工进入WAITING_OWNER_CONFIRMATION，不自动收车、不引入资金门槛或新状态。

| 验证 | 最终结果与证据 |
| --- | --- |
| 后端clean verify -Ppostgres-it | PASS；14单元、48真实PG集成，0失败/错误/跳过；JAR构建成功。原阶段1—6测试保留，新增2照片政策单元及4维修集成 |
| Flyway | PASS；新随机专用schema实际应用V1—V8与devV2.1，精确迁移序列/validate、重启无重复迁移；V1—V7未修改 |
| 管理端 | lint/typecheck/test/build全部PASS；21测试，0失败/跳过 |
| H5 | lint/typecheck/test/build全部PASS；11测试，0失败/跳过 |
| 维修后端链路 | 未开修或伪造REPAIRING但缺合法授权事件拒绝；未绑定车主/跨网点拒绝；当前网点追加记录，进度不重置状态时间；无照/PDF/错类别/跨案/旧派单/历史版等拒绝 |
| 上传身份 | 照片shop_id/assignment_version不足以证明上传角色；客服上传同作用域照片被拒绝作为网点完工证据；按不可变上传审计中的历史角色判断 |
| 并发/幂等 | 同键同载荷只写一次；跨案件同操作者同键并发一成功一冲突；完工与照片作废竞争一成功一409，已提交证据必然ACTIVE；进度/完工及证据关联数据库禁止覆盖/删除 |
| 真实HTTP/MinIO | PASS；构建JAR真实登录/案件/派单/四项授权/开修/进度/照片上传/完工；重复进度仅1条，缺照400，提交后作废409，绑定车主真实下载不含价格/对象键 |
| 重启 | PASS；停止/重启同一测试JAR，版本2、完工照片快照ID和待收车状态持久保留；照片仍可从MinIO受控下载，报价历史与金额同步保留 |

完整日志和结果在第二阶段`local-validation`：`backend-verify.log`、`baseline-results.json`、各端8项日志、`phase7-build/*-reports`、`phase7-http-results.json`、`phase7-http-runtime.log`与`phase7-http-before-restart.log`。HTTP合成案件c63a9712-2550-46de-9186-f40eb4f87932，schema为phase7_http_beaf59516ff24c26affb6ba1ee6a2532；只修改此随机测试schema中的合成owner_profile绑定，无清库/删卷/改全局配置。凭据来自未提交的本地环境文件，不写入报告。

## 失败修复与未执行

- 首轮失败保留phase7-first-run：迁移序列预期止于V7，新增V8后精确加入V8；新增测试误用DELETE附件路径，改为既有POST /void；前端幂等键默认值被TS推断为UUID模板类型，显式允许字符串键。未删测试/放宽断言。
- 第二轮失败保留phase7-second-run：新增PostgreSQL命令锁返回void无法映射，改为执行锁函数并返回整数；新增跨案件同键并发测试确认冲突按409处理。最终完整重跑通过。
- HTTP最初三次在Docker调用层失败，记录phase7-http-first/second/third-failed.json。改用参数列表及显式输出重定向，获准在沙箱外运行同一隔离验收脚本后完成。没有安装工具、修改Docker配置或删除卷。
- **未执行**收车/撤回/评价/投诉链路：剩余业务规则尚未确认，接口未开放。
- **未执行**浏览器视觉验收：当前面板通过lint/类型/测试/构建和真实API验证，不将构建视为视觉QA。
- **未执行**GitHub推送/PR/云部署/阶段8。

## 复现与启动

仓库根目录PowerShell运行：

```powershell
.\scripts\Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env' -BuildDirectory '..\local-validation\phase7-build'
.\scripts\Verify-Phase7-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase7-dev-build'
```

HTTP脚本独立启动/重启/停止自身测试JAR（18080），本次测试进程已停止，PG/MinIO仍保留。前端分别进入admin-web、h5-web运行`npm run dev`；普通启动与环境变量说明见LOCAL_DEVELOPMENT。不要把验证脚本指向共享或生产数据库。
