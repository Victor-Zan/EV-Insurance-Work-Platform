# 可公开验证证据

2026-10-10，整理已有阶段交付结果，非本次重新运行业务测试。完整原日志、Maven XML、隔离数据库及MinIO对象仍保留在用户指定第二阶段local-validation。公共仓库只保存结果和无密钥的摘要，不复制完整XML的系统属性、运行日志、环境文件或二进制附件。

- boot4-smoke-results.json：迁移后实际JAR健康、OpenAPI与四角色登录检查。
- phase5-http-results.json：真实上传下载/哈希、越权拒绝、定时Mock OCR失败重试/人工复核及JAR/MinIO重启持久性。UUID/schema是随机隔离测试标识。
- phase5-anonymous-results.json：实际业务对象匿名下载403。
- phase6-checks.json：完整后端和两端lint/typecheck/test/build结果，日志只保留文件名。
- phase6-test-suites.json：从最终JUnit报告提取名称和计数，不含环境属性/密钥；12单元、44 PostgreSQL集成，失败/错误/跳过均0。
- phase6-http-final-results.json：最终JAR报价/字段隔离/门禁/授权失效与重启持久性复验。

历史失败、未执行项目、命令与启动说明见BOOT4_MIGRATION.md、PHASE5_VERIFICATION.md、PHASE6_VERIFICATION.md和LOCAL_DEVELOPMENT.md。浏览器视觉QA未执行；本记录不升级其验证状态。凭据、数据库和Docker卷、node_modules、缓存、JAR/dist均不上传。
