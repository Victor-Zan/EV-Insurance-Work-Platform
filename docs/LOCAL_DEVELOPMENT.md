# 本地开发与启动

照片上传返回`STORAGE_UNAVAILABLE`时，先核对后端进程是否加载`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`和`MINIO_BUCKET`。`MINIO_ROOT_USER/PASSWORD`仅供容器启动，不会自动映射到应用；已有`.env`不会随`.env.example`更新而自动补齐。仅本地开发可将应用访问凭据设为已创建的本地MinIO账户，禁止输出或提交真实值。修改后重启实际提供API的后端；容器healthy或`/actuator/health`为UP不能代替照片上传验证。上传失败时保留当前草稿和所选照片，配置恢复后在原页面重试，避免重复建单。

网点报价表单OCR见[SHOP_QUOTE_OCR.md](SHOP_QUOTE_OCR.md)：启动配置与客服OCR共用，未新增供应商或前端Key变量。使用当前功能分支在新隔离schema迁移至V18；不得将本分支直接连接仍记录旧OCR V15的历史库或执行repair。V17为独立地图分支迁移，本分支不假设地图已合并。本轮验收使用MOCK_V1/模拟响应，不读取真实凭据、不增加此前真实调用预算；未来真实联调需另行确认。

百度 OCR 与建单前表单配置/验收见 BAIDU_OCR.md。默认 OCR_PROVIDER=MOCK_V1，不发送真实请求；改为 BAIDU_HANDWRITING_V1 前注入重置后的后端环境变量，不能配置前端 VITE 密钥。scripts/Start-Local.ps1 的 EnvironmentFile 加载所有大写变量，不回显值，因此可读取 BAIDU_OCR_API_KEY / BAIDU_OCR_SECRET_KEY；私密文件须留在仓库外或被忽略的 .env。启动须提供本机真实 JDK 路径，不照搬他人绝对路径。真实任务建议 OCR_LEASE_SECONDS=180；缺凭据显式启动失败，不回退 Mock。不要把已有待执行任务改供应商；任务来源与供应商已不可变。

## 当前 Windows 工作区的便捷入口

对于已配置第二阶段 `local-validation/.env` 与 `.env.phase5` 的本机，从第二阶段目录执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\启动本地网站.ps1`。启动器调用本仓库 `scripts/Manage-Website.ps1`，复用既有 `ev-insurance-phase5-audit` PostgreSQL/MinIO，以 `phase5_local` 启动网站。管理端5173、H5 5174、后端8080。

后端与两端前端分别在独立隐藏控制台后台运行，启动完成可关闭终端；重复启动复用健康服务、补启动缺失服务。`检查本地网站.ps1` 检查健康，`停止本地网站.ps1` 只停止登记且核验路径/启动时间的网站进程树，保留 Docker 和数据。各服务自行将日志写到仓库外 `local-validation`，不继承启动器的输出捕获管道；进程登记也保存在该目录。没有开机自启或自动重启，故障恢复时重新运行启动器；未知进程占用端口时不自动杀进程。

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

默认地址：`http://localhost:5173`。

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

默认地址：`http://localhost:5174`。

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

管理端默认 http://localhost:5173，H5 默认 http://localhost:5174；本地端口占用时，当前开发环境也允许 H5 使用 http://localhost:5175。两端通过 Vite /api 代理后端。部署时配置同源反向代理或明确配置 VITE_API_BASE_URL 与 CORS_ALLOWED_ORIGINS，不能用通配 CORS 替代授权。

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
