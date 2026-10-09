# Boot4.1.1独立迁移记录

2026-10-09，基线85fa6f0。用户明确选择先迁移再开发阶段5。本记录只描述阶段1—4技术迁移，不包含附件/OCR/地图业务交付。

Java21保留；Boot3.5.16→4.1.1，MP3.5.17改用boot4 starter，springdoc2.8.17→3.1.1，WebMVC/Flyway/WebMVC-test/Security-test采用Boot4模块。Jackson迁移至tools.jackson，annotations保留原包；启用官方Jackson2默认行为兼容开关，保持API与幂等响应快照的已有约定。Flyway配置、安全自动配置及WebMVC测试导入改用Boot4位置。没有改变角色、业务状态、金额、SQL迁移、派单或审计规则。

依据：[官方迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Java/Servlet要求](https://docs.spring.io/spring-boot/system-requirements.html)、[MP安装](https://baomidou.com/en/getting-started/install/)、[springdoc兼容说明](https://springdoc.org/)。官方支持窗口API本轮因TLS校验失败和curl超时未实读，不根据第三方摘要确认维护截止日期；计划所写4.1.x OSS至2027-07-31仍需补验。

| 检查 | 本轮结果 |
| --- | --- |
| 原Boot3基线单元 | 5通过，无失败/跳过 |
| Boot4 clean verify -Ppostgres-it | BUILD SUCCESS；5单元+34集成（身份11、目录4、价格13、工单6），无失败/错误/跳过；JAR成功 |
| PostgreSQL17.6与Flyway | 本任务25432专用测试库、随机schema；普通/dev迁移、validate、历史保护、十万条价格及查询计划通过 |
| 管理端lint/typecheck/test/build | 全通过，18测试；保留非阻塞大包提示 |
| H5 lint/typecheck/test/build | 全通过，8测试 |
| 实际JAR HTTP | 18080独立smoke schema，health/actuator/OpenAPI均200，四角色真实登录成功 |
| 既有迁移与锁文件 | 相对origin/main原样；git diff --check通过 |

证据位于第二阶段local-validation/backend-verify.log、baseline-results.json、boot4-smoke-results.json及两个前端日志；测试schema与数据卷保留。首次实际JAR启动因烟测命令遗漏JWT_TTL_SECONDS失败，补齐进程变量后复验通过，未加入源码默认密钥或放宽校验。首次依赖安装网络重置，按原锁文件重试成功；没有删除或跳过测试。

MinIO基础设施另验：私有测试桶写入、重启后SHA256一致、匿名读取403。这不能代替阶段5附件API授权验收。迁移验证JAR进程已停止；隔离PostgreSQL/MinIO仍运行，数据未删除。未推送、创建PR或部署。
