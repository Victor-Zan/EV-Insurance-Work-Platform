# 本机旧 V25 的兼容升级

适用范围仅为本机 public 已执行 `V25__allow_repeated_formal_claims.sql`、checksum=-1573259309 的实例。普通新库与已执行主线地图 V25 的环境继续使用 `classpath:db/migration`，不要开启下面的本机标记。

主线 V1—V27 原文保持不变，新增 V28 允许测试阶段重复保险报案号。对旧本机实例，保留原 V25，以 V25.1 执行主线地图 V25 的原文，再执行 V26、V27、V28。原 V25 不删除、不改 checksum、不 repair。地图预算表不写入授权种子，不启用真实地图请求。

`scripts/Prepare-LocalLegacyMigrations.ps1` 从已验证 JAR 提取主线、dev 和旧本机 V25 资源，生成独立目录；只有地图 V25 的副本命名为 V25.1。生成器不连接数据库，拒绝覆盖现有目录。首次升级先备份数据库及旧运行包，核对上述迁移脚本和校验值，再生成 `tmp/local-website/flyway-legacy-v25/`，创建 `tmp/local-website/legacy-v25.enabled`。启动器检测该显式标记后，将 Flyway locations 指向该目录，target=28。标记存在而兼容资源缺失时拒绝启动，不能退回不兼容的主线目录。

后续升级此旧实例时，要从新验证 JAR 重新生成并检查配套目录，不能只替换 JAR；迁移目标同步下一次已确认版本。已执行迁移文件仍须保留原文。主线实例无需这套目录或标记。

验收覆盖两条升级路径：主线 V24→V28，以及本机旧 V25→V25.1→V28。旧 V25 校验值和脚本名保持不变，重复报案号案件与原事故地址保留，停车地址不自动填造，预算表为空；重启迁移及 validate 应通过。本机实际安装、备份位置和门禁结果见 PROGRESS.md 与 LOCAL_RUNTIME.md 的最新整合记录。
