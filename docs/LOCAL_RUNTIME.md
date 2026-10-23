# 当前电脑的登录恢复与常驻启动

## 2026-10-23 当前运行版本：拉取与本机成果整合

本节覆盖下方旧运行包、target=24/25、PID和禁止直接升级的历史说明。用户已确认本次整合并同步本机：当前运行包来自 `tmp/pull-integration/backend-final-build/backend-0.0.1-SNAPSHOT.jar`，SHA256 `0CB7E0295144C49CF339EA1D6619A6B8B7442C5D26F9E5B724FF2F5AFB8F1810`。旧包为 `tmp/local-website/backend.before-pull-integration-20261023-093311.jar`，数据库安装前备份为 `tmp/pull-integration/db-public-at-install.dump`。后端46932、守护35244，原管理端4264保留；PID只代表本次启动，应以检查脚本为准。

public 已从本机旧 V25 追加迁移至 V28，保留原 V25 名称及 checksum=-1573259309。通过 `tmp/local-website/legacy-v25.enabled` 显式启用专用目录 `tmp/local-website/flyway-legacy-v25/`，其中 V25.1 为主线地图 V25 的原文。启动器 target=28；不可删除标记后直接切回主线 classpath，也不可只换新 JAR 而不更新配套迁移目录。维护步骤和两条迁移路径见 [LOCAL_V25_COMPATIBILITY.md](LOCAL_V25_COMPATIBILITY.md)。

当前重复报案号允许规则、保险公司下拉框、事故/停车位置分离、无外呼人工选店和网点隔离均保留。DEV-SHOP 及账号绑定未修改，人工可派候选实际可见。AMAP_WEB_V1、BAIDU_HANDWRITING_V1 及原私密配置继续沿用；真实地图持久预算仍关闭、剩余0，没有默认额度或新增外呼。计划任务已恢复 wscript 隐藏启动，常驻入口不变。

管理端 <http://127.0.0.1:5173/login>，网点/车主 H5 <http://localhost:5175/>。后台、管理端、登录、守护、H5及API代理健康；独立5191预览未启动。验收与实际命令见 PROGRESS.md 最新整合记录；所有旧记录仅供历史追踪。

## 2026-10-23 当前电脑的模拟网点测试配置

本轮用户改用直接派给模拟网点测试，DEV-SHOP/ID1现名“开发者模拟网点（仅测试）”，原dev_repair_shop绑定不变；服务区域保留模拟城市/区县2、3，仅增加深圳5。派单选择它后，原网点账号按正常本店权限查看、接单和继续办理。跨网点测试代码及本机开关已撤回，其他网点工单仍隔离。仅修改本机基础资料并保留审计，不作为生产数据迁移。

当前常驻包恢复为本轮前已验证的FDFC25FD72BE206972DCF283FD17CB938E70C74EA657B2041B07E159A6ED2EAE，数据库保留该实例已有的V25，未安装工作区合入的新迁移包。跨网点临时包备份tmp/local-website/backend.before-simulation-shop-20261023-090249.jar；后台51452、守护36036、管理端4264。网点实际运行入口为http://localhost:5175/work-orders，原账号密码不变。当前工作区与该旧实例的迁移历史不同，不能直接替换最新构建或用本轮说明推定其他分支已部署。

## PR #6 整合后的运行边界（2026-10-22）

下文是队友电脑的既有 V24 常驻运行说明，不是高德整合分支的部署步骤。该启动器仍固定 `spring.flyway.target=24`，不能直接用含 V25/V26 的整合 JAR 替换它的运行包，也不能为了启动本分支而自行提高 target 或升级旧库。后续更新此实例须另行确认实际执行历史、数据库、迁移方案和授权。

高德验收使用独立的 `amap_main_final_20261022` schema（V26），旧 `amap_main_validation_20261021`（本地旧 V23）及 `amap_browser_20261015`（旧地图 V17）原样保留，不能接整合 JAR。本次 PR 冲突处理只在新随机测试 schema 验证，不替换任何运行包、启动或停止服务；构建输出在仓库外独立目录，不替换现有页面产物。真实预算仍关闭，历史验收不代表日常外呼已开放。最终验收事实见 [AMAP_LIVE_ACCEPTANCE_20261022](AMAP_LIVE_ACCEPTANCE_20261022.md)。

本说明适用于 `C:\Users\zd070\Desktop\电动车保险工作平台`，覆盖旧文档中另一台电脑的 `D:`、`local-validation/.env.phase5` 和 Android Studio JDK 启动方式。旧脚本仍保留供原环境使用，本次不修改那些环境或 H5。

## 平时使用

1. 双击仓库根目录的 `启动本地网站.cmd`。它读取当前根目录 `.env`，等待已有数据库／MinIO 和后端健康，然后启动管理端并打开页面。
2. 管理端固定使用 <http://127.0.0.1:5173/login>。5191 是本轮界面预览，也代理到同一后端，但不属于正式启动器托管的页面进程。
3. 双击 `检查本地网站.cmd` 查看后端、页面、API 代理、统一登录接口和守护状态；正常情况下 Backend、Admin、AdminApi、LoginApi、WatchRunning、AutoRecoveryEnabled 均为 True。LoginApi为False时先核对后端运行包，不要重置密码。
4. 不用网站时双击 `停止本地网站.cmd`。它关闭自动恢复，只结束本启动器登记、校验过路径及创建时间的进程；保留 Docker、数据库、数据卷和图片。下次双击启动会重新启用恢复。

关闭浏览器或启动终端不会停止后台。Windows 当前用户登录后，任务 `EVInsurance-LocalWebsite` 会启动守护；每分钟另有一次兜底触发，已有运行实例时忽略。正常情况下每10秒检查一次，恢复退出的本项目后端或管理端；后端仍在启动时保留120秒宽限。Docker不可用时等待依赖，最多每5分钟请求启动一次已安装的 Docker Desktop。不会清库、重建容器、强占端口或结束未知 Java／Node 进程。

## 当前运行配置

- `scripts/Manage-LocalWebsite.ps1` 是当前工作区入口，支持 Start、Status、Stop、Watch、InstallAutoStart。
- 根目录 `.env` 提供凭据，启动器不输出或写入状态文件。进程状态、启用标记和日志在被 Git 忽略的 `tmp/local-website/`。
- JDK 为当前电脑已安装的 `%USERPROFILE%\.jdks\corretto-21.0.11`。后端为已完成构建／验证的 JAR 副本 `tmp/local-website/backend.jar`；2026-10-22同步统一登录后，来源为 `tmp/login-runtime-sync/backend-build/backend-0.0.1-SNAPSHOT.jar`，SHA256 `15E9FAAE5842A1E0424E3A37F5410BE6D977108B5DB0382B4726EF0AC17BBEB9`。前一运行包保存在 `tmp/local-website/backend.before-unified-login-20261022-113338.jar`。
- 运行包与守护固定 Flyway target=24，启动实际校验25条迁移、public为V24，无需新迁移（修复前数据库已是V24）。本地数据库备份为Git忽略的 `tmp/login-runtime-sync/db-before-runtime-sync.dump`。更新运行包或迁移时需要重新构建、验证、停止旧进程后替换；拉取源码不会自动替换常驻JAR。
- 后端只监听127.0.0.1:8080，使用已有 `ev-insurance-platform` PostgreSQL 15432／MinIO 9000；管理端只监听127.0.0.1:5173。检查数据库就绪使用 `/actuator/health`，不是只返回静态 UP 的普通 `/api/v1/health`。
- 浏览器地址会作为Origin发送，`localhost`与`127.0.0.1`属于不同来源。根目录 `.env` 的 `CORS_ALLOWED_ORIGINS` 必须明确包含 `http://localhost:5173` 和 `http://127.0.0.1:5173`；当前5191预览另在私密本机配置中添加 `http://127.0.0.1:5191`。不得用 `*` 替代。修改后由守护重新启动已登记后端加载配置。只做不带Origin的HTTP登录测试，不能证明浏览器可登录。

拉取包含后端变更的更新后，先构建并验证对应后端，在停止已登记后端及守护后保留旧JAR、替换新包，再重新启动守护；迁移目标应匹配已经确认的版本。不要只更新Vite页面而保留旧运行包。启动器和守护现在额外向统一登录接口发送空对象：新版应在进入登录逻辑前返回参数错误400，旧后端会拒绝为401；不提交账号密码、不消耗登录限流额度。检查失败会明确提示更新后端，而非把普通health正常当作可登录。该探针检测接口契约，不代替全部业务功能的版本验证。

重新安装本机自启动（不需要管理员权限或保存账户密码）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Manage-LocalWebsite.ps1 -Action InstallAutoStart
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Manage-LocalWebsite.ps1 -Action Start
```

停止入口的启用标记会使后续定时任务直接退出；如需彻底移除任务，可在 Windows 任务计划程序中删除 `EVInsurance-LocalWebsite`，不影响数据库。

## 本次故障根因与处理边界

实际复现：8080没有监听，5191的 `/api/v1/health` 返回502，Docker Linux engine管道不存在。Docker日志显示 `Docker/run/dockerInference`、继而 `docker-secrets-engine/engine.sock` 无法访问，错误1920；它们是零字节失效通信端点。单文件移动／删除和重解析标记操作均失败；关闭Docker后，原地改名保留这两个临时目录，让Docker自行创建新目录，随后原数据库／MinIO容器健康恢复。没有factory reset、prune、数据卷删除、WSL注销或账号重置。Docker官方问题跟踪中有相同类别的报告：[desktop-feedback #531](https://github.com/docker/desktop-feedback/issues/531)。

反复出现的另一个原因是原来仅启动了页面预览，后端依靠临时进程、没有自启动／守护；旧快捷脚本依赖本机不存在的另一台机器目录。当前入口及守护补上这部分，502／503／504也改为明确的后台暂不可用提示。没有自动重试登录或其他写请求，避免重复业务操作；401以外的服务故障不清除会话。

守护不能保证Windows、Docker或硬件永不故障。如果再次出现Docker错误1920，应保留日志并处理具体系统错误；本守护不会循环删除系统文件。实际重启电脑、断电、休眠恢复和Docker版本升级后的行为仍需对应场景验证。本次运行结果与受控恢复记录见 `PROGRESS.md`。


<!-- 以下为本轮恢复的拉取前本机记录；当前整合状态以前文/后续整合验收为准。 -->
## 2026-10-23 测试报案号可重复运行包

当前本机后台已按用户测试要求取消保险公司＋报案号强重复拦截，报案号仍必填，系统工单编号仍唯一，疑似重复确认保留。启动器Flyway target更新为25，public已从V24成功执行V25移除报案号唯一索引；不更改已有案件、材料或私密.env。运行包来源`tmp/repeated-claims/backend-build/backend-0.0.1-SNAPSHOT.jar`，SHA256 `FDFC25FD72BE206972DCF283FD17CB938E70C74EA657B2041B07E159A6ED2EAE`，前一包备份`tmp/local-website/backend.before-repeated-claims-20261023-083947.jar`。后台45616、守护31580、前端4264；主服务/代理/登录/守护健康均通过。下方真实API接入时的包、PID和数据库版本为历史记录；本轮未增加真实供应商调用。

## 2026-10-23 真实高德与百度OCR

本机`.env`已启用`MAP_PROVIDER=AMAP_WEB_V1`与`OCR_PROVIDER=BAIDU_HANDWRITING_V1`，三项凭据来自用户指定的本机私密文档，只保存在忽略配置中。本轮不声明凭据已轮换，不将其写入前端、命令参数、日志或Git。OCR Worker已启用，任务最多1次、Token失败不重发。

首轮预算共高德20次、百度2次：已用公开地标完成4次高德验证（3次地理编码、1次v5驾车路线）；安装后`AMAP_MAX_CALLS_PER_PROCESS=16`，保留剩余预算。`BAIDU_OCR_MAX_CALLS_PER_PROCESS=2`，当前实际识别1次、鉴权1次，剩余1次识别。以上为本机进程上限，重启会重置，不是供应商免费额度或日/月配额；本轮不靠重启追加外呼。后续业务量需另行明确并调整本地限额。

后端已替换为本轮通过回归的运行包，SHA256 `F4A204D99170855063E84D4437A7D6CF0FD730FC20A9AE478865940ABAD4C4CD`，来源`tmp/live-services/backend-build/backend-0.0.1-SNAPSHOT.jar`。旧包`tmp/local-website/backend.before-real-services-20261023-003053.jar`及原环境备份`tmp/live-services/env.before-real-services`保留。当前守护41464、后端4040，管理端4264保留；下方2026-10-22运行包信息作为历史记录。启动校验25条迁移、public仍V24，无新迁移。主服务、带Origin代理、统一登录及守护健康通过；未运行的5191预览不属于本次接入入口。

真实OCR验证任务使用合成单据，未创建工单：在管理端`/ocr/intake?job=c196a576-8677-4274-8dee-9edac3f6e199`可查看`SUCCEEDED`、7行原文及候选，仍须人工核对。聊天上传图片仍不自动OCR。高德定位和驾车API已真实成功，但当前业务库只有模拟/不完整的区域目录，12个启用网点缺车型适配及真实GCJ-02位置；用户已明确“暂时没有，资料以后再补”。本轮不改造成真实资料，不完成真实网点距离推荐验收；原人工地址核对与选店可用。

- 运行包与守护固定 Flyway target=25，2026-10-23启动实际校验26条迁移、public从V24执行V25成功。本地历史数据库备份为Git忽略的 `tmp/login-runtime-sync/db-before-runtime-sync.dump`。更新运行包或迁移时需要重新构建、验证、停止旧进程后替换；拉取源码不会自动替换常驻JAR。
