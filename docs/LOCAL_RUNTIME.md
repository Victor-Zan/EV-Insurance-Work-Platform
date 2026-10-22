# 当前电脑的登录恢复与常驻启动

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
