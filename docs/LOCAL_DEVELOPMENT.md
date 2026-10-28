## 2026-10-21 高德整合分支与隔离验收

2026-10-22更新：最终合并版迁移为main原样V23账号开通/V24地图＋新增V25增量；只连接新schema `amap_main_final_20261022`。下述`amap_main_validation_20261021`/本地V23为已保留历史检查点，严禁新JAR连接。旧V17预览同样保留。数据库对应、恢复提交及后续升级门禁见[AMAP_MIGRATION_PLAN](AMAP_MIGRATION_PLAN.md)。本轮最终预览真实外呼上限0、不创建预算；Key仍仅后端私密文件，重启不解锁，页面明确关闭状态。

从最新main增量整合的代码位于codex/amap-main-integration；不要将旧地图JAR连接main库。完整迁移含新V23，先在新隔离schema验收，旧地图V17及其他数据库不改历史、不repair。提交/合并审批未完成时，不替换现用main进程。

后端私密配置使用MAP_PROVIDER=AMAP_WEB_V1、AMAP_WEB_SERVICE_KEY、AMAP_BUDGET_RUN_ID及进程防护上限；默认均不外呼。共享预算run/slot还须预登记已授权请求摘要；仅填写Key或重启不会取得额度。已有Load-AmapEnvironment.ps1白名单加载器支持新增预算ID和显式dev测试会话开关；Key只留被忽略的后端配置，不放VITE变量。APP_BUILD_COMMIT用于核对运行包来源，需同时核对JAR哈希和实际启动路径。

本机本轮独立验收预定前端127.0.0.1:5194、后端127.0.0.1:18089，新schema amap_main_validation_20261021；旧5187/18088为历史预览，不能称当前main网站。启动/验证脚本及私密文件保留在仓库外amap-main-runtime，不复制别人的绝对路径；实际运行状态以PROGRESS最终证据为准。

完整场景与停止/替换方法见[AMAP_VALIDATION](AMAP_VALIDATION.md)。验收后关闭共享run；页面必须提示不能继续查询，不宣称可持续正常使用。真实网点运营、生产和JS地图不在本轮范围。

# 本地开发与启动

当前 `C:\Users\zd070\Desktop\电动车保险工作平台` 的常驻入口与登录故障处理见 [当前电脑运行说明](LOCAL_RUNTIME.md)：根目录 `.env`、现有15432数据库／9000对象存储、Java21已验证运行包、5173管理端以及普通用户自启动／恢复任务。根目录提供启动、检查、停止三个 `.cmd`。下方 `local-validation/.env.phase5` 和 D 盘相关说明属于原环境，不能用于当前电脑。

照片上传返回`STORAGE_UNAVAILABLE`时，先核对后端进程是否加载`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`和`MINIO_BUCKET`。`MINIO_ROOT_USER/PASSWORD`仅供容器启动，不会自动映射到应用；已有`.env`不会随`.env.example`更新而自动补齐。仅本地开发可将应用访问凭据设为已创建的本地MinIO账户，禁止输出或提交真实值。修改后重启实际提供API的后端；容器healthy或`/actuator/health`为UP不能代替照片上传验证。上传失败时保留当前草稿和所选照片，配置恢复后在原页面重试，避免重复建单。

网点报价表单OCR见[SHOP_QUOTE_OCR.md](SHOP_QUOTE_OCR.md)：启动配置与客服OCR共用，未新增供应商或前端Key变量。使用当前功能分支在新隔离schema迁移至V18；不得将本分支直接连接仍记录旧OCR V15的历史库或执行repair。此段为网点 OCR 功能分支的历史说明；当前集成版本已将地图迁移排为 V24，账号激活迁移为 V23。本轮验收使用MOCK_V1/模拟响应，不读取真实凭据、不增加此前真实调用预算；未来真实联调需另行确认。

## 高德私密配置（2026-10-15）

在当前代码仓库根目录创建 `.env.amap`（已由 `.gitignore` 的 `.env.*` 排除）。其他电脑应使用自己的仓库路径。文件初始内容：

```dotenv
MAP_PROVIDER=MOCK_V1
AMAP_WEB_SERVICE_KEY=
AMAP_MAX_CALLS_PER_PROCESS=0
AMAP_REQUESTS_PER_SECOND=2
AMAP_BUDGET_RUN_ID=
MAP_TEST_SESSION_ENABLED=false
AMAP_JS_ENABLED=false
AMAP_JS_API_KEY=
AMAP_JS_SECURITY_CODE=
```

用户只在本机文件填写凭据，不发聊天、不放VITE。`AMAP_WEB_SERVICE_KEY` 是后端地址解析、逆地理与路线查询使用的 **Web服务 Key**；`AMAP_JS_API_KEY` 是控制台选择 **Web端（JS API）** 创建的另一个 Key，配套安全密钥填入 `AMAP_JS_SECURITY_CODE`，两种 Key 不能互相替代。真实底图需同时设置 `MAP_PROVIDER=AMAP_WEB_V1`、`AMAP_JS_ENABLED=true` 并填写完整 JS 配置；JS 安全密钥仅由后端资源代理使用，不注入前端环境。底图加载不会授予 Web 服务查询预算；具体入口与资源请求边界见 [高德 JS 地图说明](AMAP_JS_MAP.md)。

`scripts/Start-Local.ps1` 先读取既有 `-EnvironmentFile`，再自动读取仓库 `.env.amap`；也可用 `-MapEnvironmentFile` 指定私密路径。`scripts/Load-AmapEnvironment.ps1 -EnvironmentFile <本机私密文件>` 可用于手动启动前加载，仅接受上述白名单内的地图变量，不输出值。配置文件不会被 Spring 自动读取，直接启动Java/Maven时必须先加载。修改配置后须重启实际提供 API 的后端；仅刷新浏览器、重跑会复用健康进程的启动器或改文件本身，都不会使既有进程重新读取配置。前端不需要因此重新注入凭据。

真实供应商必须明确设 `MAP_PROVIDER=AMAP_WEB_V1`，缺Key即启动失败；调用上限0表示关闭外呼，正整数为本进程批准预算，所有地址、逆编码、路线请求共用并发安全计数，无自动重试。计数重启会归零，不能通过重启延长已批准预算。只有另行确认真实联调预算与地址之后才设置正数。本轮仅完成实现和离线验收，不启用真实联调。客服入口为工单详情→派单与异常；管理员入口为维修网点→车型与位置。dev Mock可显式维护合成位置，真实模式禁止拿合成坐标计算道路距离。详情见 AMAP_INTEGRATION.md。

百度 OCR 与建单前表单配置/验收见 BAIDU_OCR.md。默认 OCR_PROVIDER=MOCK_V1，不发送真实请求；改为 BAIDU_HANDWRITING_V1 前注入重置后的后端环境变量，不能配置前端 VITE 密钥。scripts/Start-Local.ps1 的 EnvironmentFile 加载所有大写变量，不回显值，因此可读取 BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY；私密文件须留在仓库外或被忽略的 .env。启动须提供本机真实 JDK 路径，不照搬他人绝对路径。真实任务建议 OCR_LEASE_SECONDS=180；缺凭据显式启动失败，不回退 Mock。不要把已有待执行任务改供应商；任务来源与供应商已不可变。

## 当前 Windows 工作区的便捷入口

对于已配置第二阶段 `local-validation/.env` 与 `.env.phase5` 的本机，从第二阶段目录执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\启动本地网站.ps1`。启动器调用本仓库 `scripts/Manage-Website.ps1`，复用既有 `ev-insurance-phase5-audit` PostgreSQL/MinIO，以 `phase5_local` 启动网站。管理端5173、H5 5174、后端8080。

后端与两端前端分别由当前用户的无触发器按需任务在独立后台运行，启动完成可关闭终端；重复启动复用健康服务、补启动缺失服务。任务通过 `wscript.exe` 与 `Run-WebsiteService.vbs` 创建无窗口的系统 Windows PowerShell，不委托启动终端承载控制台，不保存密码，也不会自行开机启动。工作器退出由无窗口宿主间隔1分钟、最多重启3次（登记宿主PID，因此恢复不会令进程登记失效）；显式 `停止本地网站.ps1` 先结束通过路径/启动时间核验的登记宿主及其子进程树，再取消本仓库任务实例，避免停后自动重启，保留 Docker 和数据。各服务日志及进程登记保存在仓库外 `local-validation`；恢复耗尽时重新运行启动器，未知进程占用端口时不自动杀进程。

完整恢复说明在第二阶段 `本地网站使用说明.md`，验证见 [WORKFLOW_FIXES.md](WORKFLOW_FIXES.md)。以下是新环境或手动分别启动时的基础步骤。

## 1. 环境要求

- Git 2.40+
- Java 21（构建目标固定为 Java 21）
- Node.js 24 与 npm 11
- Docker Desktop，支持 Docker Compose

后端包含 Maven Wrapper，不要求全局安装 Maven。

## 2. 准备环境变量

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

`.env` 已被 Git 忽略。填写本地数据库和 MinIO 凭据，并注入 JWT_SECRET_BASE64（至少 32 字节随机数据的 Base64）及 JWT_TTL_SECONDS=1800。JWT 配置没有源码回退密钥；不得提交真实 .env。

## 3. 启动 PostgreSQL 和 MinIO

在仓库根目录执行：

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml ps
```

服务地址：

- PostgreSQL：`localhost:15432`（容器内仍为 `5432`，避免与本机 PostgreSQL 冲突）
- MinIO API：`http://localhost:9000`
- MinIO Console：`http://localhost:9001`

验证 MinIO HTTP 健康端点：

```powershell
Invoke-WebRequest http://localhost:9000/minio/health/live -UseBasicParsing
```

停止本地依赖但保留数据卷：

```powershell
docker compose --env-file .env -f infra/docker-compose.yml down
```

删除数据卷会清空本地数据，因此不作为常规停止命令。

## 4. 启动后端

后端从进程环境变量读取数据库连接。以下 PowerShell 片段把根目录 `.env` 加载到当前进程，然后启动应用：

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
Set-Location backend
.\mvnw.cmd spring-boot:run
```

健康与接口文档：

- 应用健康接口：`http://localhost:8080/api/v1/health`
- Actuator 健康接口：`http://localhost:8080/actuator/health`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

后端验证命令：

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd clean package
```

## 5. 启动管理端

```powershell
Set-Location admin-web
npm ci
npm run dev
```

默认地址：`http://127.0.0.1:5173`。

质量检查：

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

## 6. 启动移动 H5

```powershell
Set-Location h5-web
npm ci
npm run dev
```

默认地址：`http://127.0.0.1:5174`。

质量检查：

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

## 7. 说明

- 两个前端从仓库根目录读取 `.env`，只有 `VITE_` 前缀变量会进入浏览器构建。
- 后端的数据库密码没有源码默认值；未设置 `DB_PASSWORD` 时不会带着隐式密码启动。
- 当前公开健康与 OpenAPI、账号密码登录。身份入口按端限制，管理 API 仅 ADMIN；未知 API 默认拒绝。JWT 无刷新功能，过期重新登录，每次请求读取账号状态。
- Compose 中的固定 Bitnami Legacy MinIO 镜像只用于本地开发；生产镜像与对象存储部署方案需在部署加固阶段重新评估，见 `docs/DECISIONS.md` D-017。
- `infra/docker-compose.yml` 仅用于本地开发，不是生产高可用部署方案。

## 8. 阶段 2 本地账号与基础数据

只在独立本地数据库设置 SPRING_PROFILES_ACTIVE=dev，并通过本地环境变量或已忽略的 .env 注入 DEV_ADMIN_PASSWORD、DEV_CUSTOMER_SERVICE_PASSWORD、DEV_REPAIR_SHOP_PASSWORD、DEV_OWNER_PASSWORD。值不在此文档提供。非空密码须满足 BCrypt 的 72 字节 UTF-8 输入上限；本阶段不增加复杂度策略。

dev 配置在 Flyway 执行前将注入值转换为 BCrypt 哈希，V2.1 开发迁移只接收哈希。账号为 dev_admin、dev_customer_service（管理端）及 dev_repair_shop、dev_owner（H5），显示名明确标识开发测试；同时创建无真实个人信息的模拟省、市、区和网点服务区域。dev 数据库不可作为生产数据库复用。初始化完成后，修改环境密码不会覆盖现有账号，应调用管理员重置 API；其他环境执行普通迁移（当前 V1、V2、V3、V4、V5），不创建测试账号。

若本地数据库此前已在非 dev 模式完成较新迁移，首次启用 dev 时允许仅位于 `db/dev` 的 V2.1 开发数据迁移乱序补执行；`out-of-order` 只在 `application-dev.yml` 生效，普通及生产配置仍保持严格迁移顺序。

第一位生产管理员的安全初始化属于上线加固阶段，本阶段不提供公开注册或无认证初始化 API。

阶段目录一键入口的管理端默认 http://127.0.0.1:5173，H5 默认 http://127.0.0.1:5174；其他历史本机运行方案可能使用不同端口。两端通过 Vite /api 代理后端。部署时配置同源反向代理或明确配置 VITE_API_BASE_URL 与 CORS_ALLOWED_ORIGINS，不能用通配 CORS 替代授权。

API 契约详见 Swagger：POST /api/v1/auth/login 输入 username、password、portal（ADMIN 或 H5）；GET /api/v1/auth/me 读取实时身份。Token 过期、账号停用或认证版本变化返回 401。管理员用户/角色、区域、网点及服务区域、审计 API 均在 /api/v1/admin 下。密码重置只返回成功状态；退出登录清理浏览器会话状态，已签发 Token 在到期或认证版本变化前仍遵守服务端校验。

## 9. PostgreSQL 集成测试

在专用测试 PostgreSQL 创建空测试数据库，通过环境变量设置 TEST_DB_URL、TEST_DB_USERNAME、TEST_DB_PASSWORD（账号应能创建该测试库的 schema）。不得指向生产或共享业务库。测试随机生成 schema 和凭据，迁移、权限与审计均使用真实 PostgreSQL，不替换为 H2，不跳过集成测试。测试 schema 保留用于复核，可由测试库维护者清理。

在 backend 运行：

~~~powershell
.\mvnw.cmd -B clean verify -Ppostgres-it
~~~

该命令包括所有单元测试、真实数据库集成测试、空 schema Flyway migrate/validate 和 JAR 构建；缺少 TEST_DB_URL 时明确失败。日常无数据库单元测试可用 .\mvnw.cmd test，但它不代表本阶段完整门禁。

## 10. 阶段 3 价格库验证

管理端登录后进入“价格数据库”。先配置品牌、车型、配件、别名、配件车型关系及来源，再新增价格或按 [导入说明](PRICE_IMPORT.md) 预览和确认导入。来源字典的 MANUAL / CSV / EXCEL / HISTORICAL_CASE 只是来源类别，不创建或导入案件业务。管理员和客服均可进行日常资料维护、版本追加、导入及批次查询；来源字典与系统配置维护仅管理员，客服可选用已有来源（D-023）。

上述 postgres-it 命令包含 CataloguePostgresIT 与 PricePostgresIT；后者在独立随机 schema 中调用仅位于 src/test 的 PriceDataFixture.generate，分 20 批各 5,000 条生成十万条模拟价格及配套编号/别名，使用批量 INSERT SELECT，不通过默认迁移或启动初始化产生数据。生成器带 DEV-PERF 前缀，日期和金额均为合成测试值，严禁对生产或共享业务库运行。

测试验证内部编号、别名、品牌/车型、区域、网点、类型、适用日期、稳定分页，以及正金额、日期冲突、自动截止、历史保护、并发追加、XLSX/CSV 与导入整批回滚。执行 EXPLAIN (ANALYZE, BUFFERS) 后把两条选择性查询计划保存至 backend/target/price-query-plans.txt（不提交构建产物）。十万条测试是查询与索引合理性验证，不等同生产容量承诺或十万行单文件导入；单文件限制仍为 20,000 行。

## 11. 提示词 4 工单与前段状态机验证

管理端登录后进入“保险案件”，可创建/编辑/删除本人草稿、提交正式案件、处理疑似重复、派单、取消派单、改派、标记到店异常、继续等待、修改关键字段和取消整个工单。网点在 H5 可查看本店案件、接单/拒单、标记到店异常、继续等待及确认到店；车主 H5 只看到手机号唯一绑定到本人的案件。

本阶段不上传材料，不读取 MinIO，也不使用 OCR 或地图。根目录 Compose 仍保留阶段 1 的 MinIO 基础设施，但它不是提示词 4 的运行前置条件。案件通知单、到店照片及其他材料从提示词 5 开始实现。

`postgres-it` 命令同时运行 `WorkOrderPostgresIT`，在随机 schema 中验证 V5 迁移、草稿范围与删除、正式必填、强/疑似重复、可配置时间窗口、并发编号、车主绑定、派单版本、接拒单、取消/改派、到店异常、重复请求、旧派单、跨网点越权、字段脱敏和审计。测试数据库必须专用，不得指向生产或共享业务库。

## 12. VS Code 快捷启动

先按第 2 节准备好根目录 `.env`，并确保 Docker Desktop 已启动。使用 VS Code 打开仓库根目录后，按 `Ctrl+Shift+B` 即可运行默认任务“开发：启动全部服务”。该任务会：

- 启动并等待 PostgreSQL 健康；提示词 4 不需要启动 MinIO。
- 自动读取根目录 `.env` 并启动后端；若当前默认 Java 不是 21，会优先查找当前用户 `.jdks` 目录中已安装的 Java 21，仍未找到时给出明确错误。
- 管理端和 H5 会先等待后端健康接口返回 `UP`，再启动前端开发服务器，避免后端尚未就绪时登录出现 Vite 代理连接错误；等待超过 120 秒会提示查看后端终端。
- 在缺少 `node_modules` 时为管理端和 H5 执行 `npm ci`，随后启动两个前端。
- 将后端、管理端和 H5 放在 VS Code 终端面板的独立分屏中。

也可按 `Ctrl+Shift+P`，运行“任务: 运行任务”，单独选择某个“开发：”任务。停止时先运行“任务: 终止任务”并选择终止全部任务，再运行“开发：停止 Docker 基础设施”。停止基础设施会保留数据库卷，不会清空本地数据。

如果快捷任务提示缺少 `.env`，先完成第 2 节；如果提示无法连接 Docker Engine，启动 Docker Desktop 后重试。若端口 `15432`、`8080`、`5173` 或 `5174` 已被占用，应先停止占用对应端口的旧进程。

若 H5 终端出现 `[vite] http proxy error` 或 `AggregateError`，表示前端当时无法连接 `localhost:8080` 的后端，并非账号密码错误。默认快捷任务现已在开放前端端口前等待后端就绪；如单独手工运行 `npm run dev`，应先确认 `http://localhost:8080/api/v1/health` 可访问。

## 本轮阶段5隔离环境与快速启动

已核验Java21.0.10、Node24.14.0、npm11.9.0（项目声明npm11.12.1，未全局改装）、Docker29.8.2/Compose5.5.1、WSL2、PostgreSQL17.6和MinIO。宿主5432不是本轮测试数据库。本任务依赖project `ev-insurance-phase5-audit`，PG25432，MinIO19000/19001。凭据在第二阶段local-validation，禁止提交。

从仓库根目录启动后端（新phase5_local schema，保留现有测试schema与数据）：

```powershell
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5'
```

此命令在当前终端运行，Ctrl+C停止后端。另开终端分别进入admin-web/h5-web，执行 `npm run dev`；Vite默认代理8080，管理端5173、H5 5174。dev_admin/dev_customer_service/dev_repair_shop/dev_owner的本地密码从.env.phase5对应变量查看，不写入报告。已有node_modules，无需重复安装。

Docker不在当前PATH时，用已安装的 `C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe` 执行原Compose命令，并指定 `--project-name ev-insurance-phase5-audit --env-file ..\local-validation\.env`，不加-v。

普通本地.env须新增MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BUCKET；这些是后端配置，Compose的MINIO_ROOT_*不自动映射。私有桶不存在时创建；已有桶存在公开策略则拒绝存取，不擅自修改策略。真实上线应用账号应限定桶权限，本轮隔离验收使用本地测试存储凭据。

完整门禁：`scripts/Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env'`。真实HTTP及重启链路：`scripts/Verify-Phase5-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'`，临时占用18080，结束后停止该后端，数据保留。
HTTP复验脚本需要PowerShell7（使用HttpClient和SkipHttpErrorCheck）；Start-Local/Verify-Baseline无需该HTTP开关。

## 第6阶段启动和复验（2026-10-10）

沿用上述隔离 PostgreSQL/MinIO。本轮backend/target清理两次被Windows拒绝，未发现Java进程，也未改权限或删除目录；通过可选Maven profile将完整构建放到第二阶段local-validation/phase6-build。原默认构建方式保留。

从仓库根目录启动（凭据仍由本地环境文件注入，不写文档）：

```powershell
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase6-dev-build'
```

仍使用8080，两前端分别npm run dev。客服进入已到店案件可审核加价、录入核损并两项确认；网点在H5提交原始报价、查看四项门禁并开修；管理员只读，车主无报价入口。材料上传沿用阶段5真实MinIO，完成/取消只读。停止用Ctrl+C，不删数据。

完整门禁与真实业务复验（PowerShell7，真实HTTP占用18080且结束停止自己的JAR；不停止其他服务）：

```powershell
.\scripts\Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env' -BuildDirectory '..\local-validation\phase6-build'
.\scripts\Verify-Phase6-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

HTTP脚本默认读取phase6-build/backend-0.0.1-SNAPSHOT.jar，临时随机schema与测试JWT/密码，仅测试专用数据库；保存脱敏结果，不清空现有schema/卷。日志及JSON在第二阶段local-validation；API见PHASE6_API.md。

## 第7阶段本地启动与验证

使用已有阶段5专用开发环境，保持数据与私有MinIO；无需重新安装工具或删除数据库/卷。

```powershell
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase7-dev-build'
.\scripts\Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env' -BuildDirectory '..\local-validation\phase7-release-final-build'
.\scripts\Verify-Phase7-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

后端8080，两端分别进入admin-web、h5-web执行npm run dev，管理端5173、H5 5174。在现有案件详情操作维修、收车、评价和投诉；客服代操作仍遵守开修门禁及网点照片证据，管理员仅处理投诉。开发账号密码从未提交的.env.phase5读取，不复制到文档。

HTTP复验使用专用数据库的随机schema及合成材料、临时18080端口，结束停止自己的JAR，保留数据和日志。验证构建目录被Windows锁定时，改用第二阶段local-validation中的新目录，不手动删除或更改ACL；同时用-JarPath指定新JAR。业务边界见PHASE7_API.md，结果见PHASE7_VERIFICATION.md。

## 第8阶段启动与复验

在指定第二阶段仓库根目录运行，继续复用开发PG/私有MinIO；环境文件不提交或打印凭据：

```powershell
.\scripts\Start-Local.ps1 -EnvironmentFile '..\local-validation\.env.phase5' -BuildDirectory '..\local-validation\phase8-dev-build'
.\scripts\Verify-Baseline.ps1 -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -TestEnvironmentFile '..\local-validation\.env' -BuildDirectory '..\local-validation\phase8-delivery-build'
.\scripts\Verify-Phase8-Http.ps1 -TestEnvironmentFile '..\local-validation\.env' -DockerExe 'C:\Users\YUFEI\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

后端8080；admin-web/h5-web各执行npm run dev，5173/5174。客服在案件详情设置目标、记账/冲销和导入；网点只读本次派单应付/实付；车主无资金入口。登录后站内通知折叠面板查看本人通知和待办，客服可填原因设置/清除期限，自动提醒保持关闭。

HTTP脚本默认phase8-delivery-build JAR，专用验证数据库随机schema、私有合成CSV和图片、18080临时端口；结束停止自己的JAR，保留数据库/对象/报告，不删卷。复验目录若有Windows锁，用第二阶段local-validation新BuildDirectory并传-JarPath，不改ACL或手动清理。资金导出按页最多100行，全部需遍历页码。接口/结果见PHASE8_API.md与PHASE8_VERIFICATION.md。


## 2026-10-17 合并方案（用户确认）

网点端按远端 main 的网点报价OCR实现：可手填提交，识别结果人工确认后从原报价表单提交，保留多图与逐行来源；不再强制经过本地 DocumentRecognition 原件核对入口。客服、管理员端按本地 main 保留，报价审核、核损确认、维修授权及顺序资金确认规则不回退。共享后端保留本地原件快照和核损失效规则，同时接入远端OCR作用域及逐行来源。网点 ASSESSMENT 是允许报价阶段的网点清单，上传不要求先生成客服正式报价；客服核损材料仍执行本地阶段门禁。

用户确认网点OCR V18只在个人本地验证，未进入共享环境，因此将该迁移改编号为 V22__shop_quote_ocr.sql；本地 V1—V21 不修改。旧文档中的OCR V18及未合并地图说明为历史分支记录，当前合并版本以完整 V1—V22 为准。已有个人OCR V18库不得直接连合并版本或执行repair，验证使用隔离空schema；原个人库另行保留。

## 受邀账号配置与入口（2026-10-19）

配置`ACCOUNT_ADMIN_PUBLIC_URL`、`ACCOUNT_H5_PUBLIC_URL`为浏览器实际可访问的两端根地址（默认5173/5174；若使用其他端口须同步修改）。邀请和统一登录跳转由后端生成；本地可用HTTP回环地址，正式地址必须HTTPS。前后端部署路径前缀需与Vite BASE_URL及服务路由一致，CORS_ALLOWED_ORIGINS按实际源配置。变量只在后端读取，不配置前端密钥。`scripts/Start-Local.ps1 -EnvironmentFile <本机私密配置路径> -JavaHome <本机JDK21路径>`现有加载器会读取这两个大写变量；配置文件必须在仓库父目录范围或被忽略的.env，不能照搬队友路径。启动脚本不会自动读取未指定的.env，修改后需重启实际后端。

首个管理员沿用现有dev初始化：显式设置SPRING_PROFILES_ACTIVE=dev和DEV_ADMIN_PASSWORD（其他开发角色种子也需各自环境变量），由既有Flyway开发配置生成BCrypt；不能把真实密码写文档/SQL。非dev不自动创建任何账号，本轮未新增生产初始化后门，生产首管的受控运维初始化及部署未验证。

入口：管理员→用户与角色→准备账号并复制邀请；网点先建有效合作网点并绑定ID。客服→有权限的正式案件详情→概览→邀请本案车主开通账号，先线下核实本人/案件归属。受邀人打开对应端/activate，核对脱敏身份、设置密码；成功页显示其登录账号，再到/login登录。两端均可输入账号，由服务端按授权跳转管理端或H5。工作人员手动通过已核实渠道发送，无短信/邮件发送。已有已激活账号使用原密码；不批量迁移成待激活。

迁移仅追加V23。详细权限、限流、邀请失效与API见[ACCOUNT_ACTIVATION.md](ACCOUNT_ACTIVATION.md)。保留受保护的旧管理员直接设密API用于兼容，新的管理页面只走邀请。开通不是身份验证或合作网点入驻审核。
