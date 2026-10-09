# 第5阶段交付与本轮验证

2026-10-09（本地验收持续至次日凌晨）。工作路径严格为用户指定第二阶段，第一阶段搭建计划只读。基线origin/main 85fa6f0，初始工作区干净；本地分支feature/phase5-files-ocr-map，无推送、PR、部署。当前改动未提交，迁移单独暂存的提交因缺少Git作者身份失败；未改全局配置。

## 实现

先完成用户选择的独立Boot4.1.1迁移，阶段1—4的5单元+34集成及两端门禁和实际JAR登录回归通过，见BOOT4_MIGRATION.md。随后阶段5新增V6、document/ocr/integration模块和两端案件详情验证组件；既有V1—V5/devV2.1及锁文件保持原样。

MinIO真实私有存储、七类材料、10MiB/20有效件、无案件总量门槛、版本保留、客服补传/替换/作废、角色/当前派单/绑定车主权限、受控流式下载、类型签名及哈希/大小校验、并发和幂等、审计。数据库回滚尝试补偿尚未提交对象；补偿失败只记录泛化错误待人工对账，不自动清理历史。

持久化Mock OCR去重、SKIP LOCKED领取、有限重试、租约/令牌防止旧Worker覆盖；合成候选、人工修订不可变快照、客服确认和版本审计。MapProvider支持客服Mock搜索/选点/正逆编码、真实区域网点列表加模拟位置、直线距离。无真实OCR/收费地图接口。

完成/取消案件只读。除新增模块限制外，修复既有关键字段修改接口允许COMPLETED的直接冲突；不改其他阶段4流程。历史附件保留，不恢复旧D-026的正式建单或到店附件门槛；正式报价/开修门禁/维修/资金不在本阶段。

## 验证结果

| 检查 | 结果和证据（第二阶段local-validation） |
| --- | --- |
| Java/Node/npm | Java/javac21.0.10真实编译运行；Node24.14.0/npm11.9.0实际安装构建；未重装或改全局配置 |
| Docker/WSL | Docker Desktop4.94.0、Engine29.8.2、Compose5.5.1、docker-desktop WSL2 Running；Client/Server及Compose实际可用 |
| PostgreSQL | 隔离Compose PostgreSQL17.6，25432 ev_insurance_test/phase5_audit，实际SQL连接与数据库身份核验；宿主5432不作为测试库 |
| MinIO | live/ready200、认证对象写读；基础设施重启哈希一致；实际业务桶匿名对象读取403（phase5-anonymous-results.json） |
| 全量后端门禁 | BUILD SUCCESS，5单元+39集成，0失败/错误/跳过；阶段1—4原34集成全部保留，新增材料5测试；phase5-complete-regression.log |
| 追加权限边界 | MaterialsPostgresIT 5测试focused verify通过（phase5-boundaries.log）；最后完整门禁再次运行全部39集成通过，包括完成案件运行中Worker写回、关键字段修改与缺少请求头400。 |
| Flyway | 真实PG随机schema普通/dev迁移与validate；V6成功；既有迁移未改；每次集成包含迁移与校验 |
| 两端 | admin-web lint/typecheck/test(18)/build、h5-web lint/typecheck/test(8)/build全部通过；baseline-results.json及各日志；管理端大包提示非阻塞 |
| 实际JAR HTTP | 真上传/鉴权下载SHA256一致，车主通知单403，定时Worker首次失败、客服retry第二次成功，人工修正/确认；phase5-http-results.json |
| 重启持久性 | 停止并重启本任务JAR、重启隔离MinIO，原文件SHA256仍一致，复核version1/confirmed仍在，隔离schema见phase5-http-results.json；未删除卷 |
| 附件/权限集成 | ADMIN不可上传、网点不可上传通知单、错误派单版本、跨网点、非绑定车主、改派后旧网点、旧/作废版、删除草稿材料读取拒绝；内部对象键/哈希不输出、非内部文件名合成；文件内容与SHA落库一致 |
| 版本/限制/审计 | 重复上传同ID及响应、不同载荷幂等冲突、并发重复上传不重复对象、有效20上限且替换不占新名额、旧版保留/禁止DELETE、伪造类型/过大拒绝、审计记录存在、通知单补齐保留原缺失原因 |
| OCR | 文件任务去重、并发领取不重复、过期旧租约不可覆盖、失败重试、版本冲突、管理员不可确认、网点不可查看、人工快照不可覆盖、确认不更改工单字段、完成案件不再领取任务；定时Worker实际链路另验 |
| git diff --check | 通过，只有Windows换行提示 |

失败记录保留：阶段5首次编译因MinIO9的OkHttp JVM依赖缺失失败，按SDK版本添加okhttp-jvm5.3.2后通过；首次阶段5全量有1失败（首次与幂等重放时间精度不同），统一返回落库元数据后全量通过。另有最初命令引号/工作目录及网络错误，未通过跳过测试、删除测试、降依赖或放宽断言解决。

未执行：浏览器逐项点击/视觉验收、生产部署/真实OCR/地图/短信、灾难恢复及强制断电下存储补偿演练；没有将这些写为通过。Spring维护窗口API因TLS/超时未实读，计划记载的4.1.x OSS截止日期仍未官方复核；依赖兼容与系统要求已查官方来源并实测。

## 启动与停止

从仓库根目录执行 `scripts/Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5'`，JDK默认指向本机Android Studio JBR21；后端8080，实际测试schema独立于新phase5_local本地体验schema。两个终端分别在admin-web/h5-web执行npm run dev（5173/5174）。开发密码见本地.env.phase5各DEV_*变量，禁止提交。详细配置、完整门禁与HTTP复验命令见LOCAL_DEVELOPMENT.md。

本轮HTTP烟测后端已停止；隔离PG/MinIO仍运行，数据保留。没有修改宿主数据库或清理Docker卷。首次体验时需准备一条正式案件并按阶段4派单/绑定，才有对应H5附件权限。

## 剩余事项与阶段门禁

本阶段业务问题已全部确认。生产保留/归档策略、应用桶最小权限和真实外部供应商留上线加固；Boot维护窗口精确日期待官方接口可访问后补验。本地提交前须提供Git作者信息；这不影响已验证代码，未自动推送。

停在阶段5，等待用户确认后才进入阶段6。阶段6需要确认加价基数/比例单位范围/零加价/精度舍入、权重/单价精度/尾差、报价核损修订确认失效及已开修处理，不采用默认算法。
