# 项目进度

## 2026-10-04 阶段 3 客服运营权限调整（D-023）

- 延续 feature/sherr-price-database，开始时工作区干净、HEAD=60e21bf。远程核对发现原阶段 3 PR #2 已由仓库协作者合并（c698d54），main 后续 README 更新为 71e5c87。本次不切换分支、不 reset/rebase/force push，保留共享提交及 README 的模块权限表达。
- 用户本次确认取代此前客服只读策略：客服与管理员均可维护品牌、车型、标准配件、别名及适配关系，查询价格、新增/追加版本、预览/确认 CSV 与 XLSX 导入、查看批次及错误报告。来源字典维护、用户/角色/组织/系统配置及审计管理继续限管理员；网点/车主仍被拒绝。
- 同步过滤链、Controller 授权及 PricingAccess 应用服务。来源字典写操作单独在请求、方法、服务三层限制，管理员与客服均受历史字段不可变、新版本追加、有效期不重叠、正金额和自然日规则保护；所有 Flyway 文件原样。
- 前端共享权限常量开放客服价格新增/版本/导入/批次路由和操作按钮；来源字典维护按钮单独限 ADMIN。H5 无新增入口，原用户管理路由和 Token/错误端处理不变。
- 导入预览新增 PRICE_IMPORT_PREVIEW 审计动作，UUID 存入对象标识；元数据、预览行及审计同事务。正式导入、资料维护、版本追加与自动截止沿用当前操作者及角色的既有审计，预览仍仅创建者可访问和确认。
- 新增客服资料 CRUD/关系操作审计测试，以及客服真实 CSV/XLSX 导入、版本追加、失败报告、历史保护和 H5 越权测试。更新前端按钮/路由测试，保留阶段 2 回归、管理员原能力、数据库约束及十万条查询测试。
- 已同步 DECISIONS、REQUIREMENTS、ARCHITECTURE、PRICE_IMPORT、LOCAL_DEVELOPMENT、README，完整门禁已通过。本次没有进入阶段 4。

| 本次实际命令/验证 | 结果 |
| --- | --- |
| backend .\mvnw.cmd -B -ntp clean verify -Ppostgres-it（通过仓库外 run-backend.ps1 注入专用测试环境） | 2026-10-04 18:38 BUILD SUCCESS；5 单元 + 28 集成（身份组织 11、目录 4、价格导入 13），0 失败/错误/跳过，JAR 构建成功 |
| 空普通/dev schema Flyway migrate/validate，既有迁移比对 | 通过；全部 V1/V2/V2.1/V3/V4 原样，不新增重复表或修改数据库结构 |
| 权限、历史保护与审计 | 客服资料 CRUD/关系操作、新版本及自动截止、CSV/XLSX 成功/失败导入、预览所有者、批次/错误查询、操作者角色和对象追踪通过；用户/角色/组织/来源字典写入/审计仍 403，历史 PUT/PATCH/DELETE 仍 409，H5 价格与导入请求仍 403 |
| 十万条价格与查询计划 | 非生产批量夹具、分页、编号/别名、品牌/车型、区域/网点、类型、日期筛选及 EXPLAIN 再次通过；产物在 backend/target，不提交数据 |
| admin-web npm run lint、npm run typecheck、npm test、npm run build | 全通过；14 测试、0 失败/跳过；保留 Vite 单包体积提示，无放宽门禁 |
| h5-web npm run lint、npm run typecheck、npm test、npm run build | 全通过；6 测试、0 失败/跳过，源码未改 |
| git diff --check；与 HEAD/origin/main 比较 db 迁移目录 | 通过 |

复跑过程中修正独立 PostgreSQL 实例端口配置、错误端前端测试设置，以及来源字典请求过滤链的授权顺序；不删除/跳过测试，不放宽历史保护。Docker/Compose、浏览器/手机手工验收与生产部署本次仍未执行，原因沿用前次环境记录。正式价格适用优先级仍待业务确认。

本次交付：实现提交 e83041c278296d6e8bb324fb99f30b9a7f928ade（feat(pricing): grant customer service daily operations with audit），正常合并 origin/main 的提交 41ef7456a76d7208cf543a0513b9feddcabf56bb。唯一 README 文案冲突采用用户确认的新权限，并保留 main 的“在价格数据库模块中”表达；合并后 backend/admin-web/h5-web/docs 与已验证实现树相同，未改变应用代码。git push origin feature/sherr-price-database 已成功，继续普通提交/推送本记录，不重写历史。

GitHub 集成读取确认此分支无开放 PR，尝试创建新的 base=main、draft=true 客服权限调整 PR 返回 403 Resource not accessible by integration；未创建、未绕过权限，未合并 PR。原 PR #2 的合并为已有远程状态。手动创建链接：[main ← feature/sherr-price-database](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-price-database?expand=1)，请选择 Draft，建议标题 feat(pricing): grant customer service daily operations with audit。

本次全部自动化门禁和分支交付完成，独立测试 PostgreSQL 已停止，数据保留；停止于阶段 3，仅余权限阻塞的 Draft PR 创建及已标注手工验收。

## 2026-10-04 阶段 3 完成与交付验证（权限调整前的历史记录）

- 当前分支 feature/sherr-price-database，基线为阶段 2 已合并的 5f6f1e0。中断恢复时保留 d10ad9f 基础资料提交，并先以 ab17835 提交已有价格版本/导入工作，再继续剩余实现；未切换分支、reset、rebase、force push 或覆盖队友工作。
- 复用账号、RBAC、区域、维修网点、服务区域关系与审计。新增 V3 价格目录和 V4 逻辑价格、版本、预览、批次及错误表，既有共享迁移未改；恢复后的 V3/V4 亦未改。品牌、车型、配件、全局唯一内部编号、独立别名、多对多适配与来源管理完整。
- 价格候选分页查询支持内部编号/别名、品牌、车型、四种价格类型、全国/区域/网点范围和适用日期；显示范围、金额、来源、有效期与版本，稳定排序且单页最多 100。组织投影使用既有服务区域关系，不返回联系方式。正式价格适用优先级尚待业务确认，只返回候选，不自动取价、回退或加价。
- 所有价格为正数 CNY NUMERIC(18,2)，API 金额字符串，拒绝多余小数位。有效期使用中国自然日 DATE，首尾包含。新版本开始日严格晚于最新版本；无期限旧版本在同一事务补齐新开始日前一天，并写既有审计与 previous_version_id / closed_by_version_id 关联。历史金额、来源快照、创建信息及有限截止日受 API 和数据库保护，拒绝更新、删除和重叠。
- 手工与导入复用 PriceRules 及批量版本应用 SQL。真实 UTF-8 CSV / XLSX 有界流式解析、字段/主数据/重复/版本校验、分页预览与显式确认；预览不写正式价格，确认重新校验，任意错误或数据库冲突整批价格/截止日/对应审计回滚，失败批次与逐行错误保留。重复确认返回同一批次。不保存文件或接入对象存储。
- 管理端完成候选筛选列表、品牌/车型/配件/别名/来源维护、适配关系、新增与追加版本、历史、预览确认、批次列表/详情/分页错误报告。ADMIN 读写，客服只读基础资料与价格/历史，导入与批次仅 ADMIN；网点、车主无任何价格权限，H5 无入口。沿用阶段 2 登录守卫、401 清理、403/网络错误处理。
- OpenAPI 描述与统一 410/413 响应已更新；导入模板、字段及重复规则见 PRICE_IMPORT.md，数据模型/版本/锁与索引见 ARCHITECTURE.md，确认规则见 D-021/D-022。

### 实际运行与结果

环境为 Temurin JDK 21.0.12.1、Maven Wrapper、Node 24/npm 11、独立 PostgreSQL 17.6（127.0.0.1:55432）；使用仓库外的测试启动脚本注入 TEST_DB_*，随机 schema 隔离，凭据不进入仓库或输出。

| 实际命令/验证 | 最后结果 |
| --- | --- |
| backend: .\mvnw.cmd -B -ntp clean verify -Ppostgres-it（通过仓库外 run-backend.ps1 注入环境） | 2026-10-04 15:56 完成，BUILD SUCCESS；5 单元 + 26 集成（身份组织 11、目录 3、价格导入 12），0 失败/错误/跳过，JAR 构建成功 |
| Flyway 空 schema migrate / validate，含普通配置与 dev 配置 | 通过；普通 V1/V2/V3/V4 不初始化账号，dev 另含 V2.1；旧 checksum 验证通过 |
| 版本/数据正确性 | 自动截止及双向链接、历史字段不可变、直接 SQL 正金额/日期/排他约束、最大金额精度、首尾包含、有限重叠/倒序/非法日期、并发同日追加 200/409 均通过 |
| 导入及权限 | CSV/BOM/真实 XLSX、文件内重复、既有有限区间冲突、过时预览重新校验、显式确认、预览所有者、批次幂等、失败错误报告、故障注入后的整批与截止审计回滚均通过；管理员/客服/网点/车主边界及阶段 2 回归通过 |
| 十万条真实 PostgreSQL 数据 | PriceDataFixture.generate 在 src/test 中按 20 × 5,000 条批量生成；分页、编号/别名、品牌/车型、区域、网点、类型、日期筛选全部通过，不进入 Flyway 或默认初始化 |
| EXPLAIN (ANALYZE, BUFFERS) | 通过；编号计划使用 price_part_internal_code_key、idx_price_record_part_type、idx_price_version_record；别名使用 idx_price_alias_name、idx_price_record_part_type。计划产物在 backend/target/price-query-plans.txt，不提交测试数据/构建产物 |
| admin-web: npm run lint、npm run typecheck、npm test、npm run build | 全部通过；14 测试，0 失败/跳过；Vite 有单包超过 500 kB 的提示，构建成功 |
| h5-web: npm run lint、npm run typecheck、npm test、npm run build | 全部通过；6 测试，0 失败/跳过；未改 H5 源码 |
| git fetch origin；git rev-list --left-right --count origin/main...HEAD | 通过；推送前 origin/main 无新增未包含提交，保持功能分支，不重写历史 |
| git diff --check；与 origin/main 比较 V1/V2/V2.1，与 ab17835 比较 db 迁移目录 | 通过；旧迁移及已保存检查点迁移原样 |

本次未执行 Docker/Compose（本机 CLI/Desktop 不可用，配置未改）、浏览器/手机手工验收或生产部署；需在可用环境补验。十万条验证仅证明测试环境下分页、筛选与索引方案可用，不作生产容量或固定时延承诺。未实现工单、案件、OCR、派单、自动核价、支付或结算，停止于阶段 3。

### 交付记录

- 基础资料提交 d10ad9fc36f32e05d92bcd98450c1007ce4fad2e；恢复保全提交 ab17835；完整实现与验证提交 cc5b7617710fa7e63509ced65a796104a65ade24，标题 feat(pricing): complete price database imports and verification。
- git push -u origin feature/sherr-price-database 成功，远程分支已创建并设置跟踪。随后普通提交/推送本交付记录，不重写历史。
- 使用 GitHub 集成尝试创建 base=main、head=feature/sherr-price-database、draft=true 的 PR，返回 HTTP 403：Resource not accessible by integration。没有创建 PR，没有尝试其他身份或绕过集成权限。
- 手动创建链接：[main ← feature/sherr-price-database](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...feature/sherr-price-database?expand=1)。创建时选择 Draft；建议标题 feat(pricing): add versioned price database and atomic imports，变更和验证摘要见本节。不得合并。
- 自动化实现与交付已停止于阶段 3；仅剩集成权限之外的 Draft PR 创建及已标注的手工验收。停止本任务独立 PostgreSQL 实例，测试数据保留，不操作共享库。

## 2026-10-04 中断恢复检查点（历史记录）

- 保持 feature/sherr-price-database；原基础资料提交 d10ad9f 保留，未切换分支、reset、rebase 或覆盖已有工作。
- 新规则已写入 REQUIREMENTS / D-021：正金额、中国自然日、首尾包含、新版本追加、无期限旧版本同事务补齐截止日并审计及关联。
- 已新增 V4 价格维度/版本、预览暂存、批次和错误模型；已有 V1/V2/V2.1/V3 未改。价格候选查询、手工版本应用、CSV/XLSX 流式解析、批量解析映射与版本校验、预览和显式确认接口已加入，仍需专项测试验证。
- 中断前后台命令 .\\mvnw.cmd -B -ntp clean verify -Ppostgres-it 已正常结束：5 单元 + 14 既有集成测试通过，V4 空 schema 迁移及 validate 通过，JAR 构建成功。这不能代替价格/导入专项测试。
- 恢复后先提交现有阶段 3 工作，再补齐价格与导入专项测试、十万条夹具/查询计划、剩余管理页面和文档，完成全部门禁后推送和尝试 Draft PR；不进入阶段 4。

## 2026-10-04 阶段 3：基础资料检查点（历史记录，已由上方完成记录取代）

- 执行 git fetch origin、git switch main、git pull --ff-only origin main、git switch -c feature/sherr-price-database。基线为阶段 2 合并提交 5f6f1e0；操作前工作区干净，origin 为 Victor-Zan/EV-Insurance-Work-Platform。没有改动队友代码或既有 Flyway 文件。
- 已固化本次价格类型、CNY 精度、三种范围、客服只读与候选返回要求。正式价格适用优先级尚待业务确认，不实现自动取价或回退。
- 新增 V3__price_catalogue.sql：品牌、品牌下车型、全局唯一编号的标准配件、独立别名、多对多车型适配、四类来源字典；复用阶段 2 区域、网点、服务区域、用户、角色及审计。
- 品牌/车型/配件/别名/来源 CRUD、适配关系增删查、组织只读投影已实现。新增独立 pricing 权限入口，ADMIN 维护，客服只读，网点/车主拒绝。组织管理权限仍仅 ADMIN。维护与审计同事务，外键保护有引用的主数据；所有列表分页、稳定排序、单页至多 100。
- 管理端五类基础资料与适配关系页面接入真实 API，支持筛选、分页、维护、错误/加载/空态；客服隐藏维护入口。H5 无价格入口。页面通过现有登录守卫、Token 清理和统一错误客户端。
- 依用户“关键业务规则记录待确认，不自行猜测”及 AGENTS.md 第 1 节，价格事实、版本与正式导入暂不落地：无失效时间旧版本的后续处理、金额零/负边界、生效区间口径已提出确认（见 REQUIREMENTS 7.4）。这些会影响历史不变性、重叠判断、金额检查和批次原子校验。
- 尚未完成：价格记录及历史版本、组合价格筛选、XLSX/CSV 预览与原子导入、批次/错误报告、相关价格页面、十万条价格生成与查询计划。不能将当前目录测试视为这些能力的验收，不进入阶段 4。

### 当前实际验证（仅覆盖已实现的检查点）

环境沿用本任务目录的 Temurin JDK 21.0.12.1、Maven Wrapper、Node 24/npm 11、独立 PostgreSQL 17.6（127.0.0.1:55432），每个集成测试使用随机隔离 schema，不连接共享或生产数据库；凭据仅在仓库外与环境变量。

| 实际命令/方式 | 结果 |
| --- | --- |
| backend: .\\mvnw.cmd -B -ntp clean verify -Ppostgres-it（注入 TEST_DB_*） | 通过；5 单元测试 + 11 既有认证/组织集成测试 + 3 新增目录集成测试，0 失败/错误/跳过，JAR 构建成功 |
| 最后改动复核：backend .\\mvnw.cmd -B -ntp verify -Ppostgres-it；管理端重新运行 lint/typecheck/test/build | 通过，后端仍为 5 + 14 个测试、管理端仍为 9 个测试，无失败/跳过 |
| Flyway migrate/validate，空 dev 与普通 schema；重新初始化开发哈希再验证 | 通过；dev V1/V2/V2.1/V3，普通 V1/V2/V3 且无用户；旧迁移 checksum 正常 |
| 新增目录测试 | ADMIN CRUD、客服只读、H5 拒绝、匿名/非法 Token、内部编号唯一、别名前缀及通配符转义、品牌筛选、关系维护、约束失败无审计残留、分页稳定性/上限、组织投影、OpenAPI 通过 |
| admin-web: npm run lint、npm run typecheck、npm test、npm run build | 通过；9 测试，0 失败/跳过 |
| h5-web: npm run lint、npm run typecheck、npm test、npm run build | 通过；6 测试，0 失败/跳过；未修改 H5 源码 |
| git diff --exit-code origin/main -- 既有 V1/V2/V2.1；git diff --check | 通过；旧迁移原样 |

Docker CLI/Desktop 不可用，Compose 本次未验证且文件未改；未执行浏览器/手机手工验收、生产部署或十万条价格验证。价格库完整测试需待剩余能力实现后补齐。

当前分支 feature/sherr-price-database；本地基础资料检查点提交标题为 feat(pricing): add catalogue foundation and read-only access。此检查点仍未完成阶段 3，不进行完整阶段交付、推送或 Draft PR 创建。确认关键边界后继续当前分支，不改历史、不 force push，不合并 PR。

验证后停止本任务独立 PostgreSQL 实例，测试数据保留；只操作本任务 work/postgres-test-data，不操作共享数据库。

## 当前状态

- 当前阶段：阶段 3 — 价格数据库
- 状态：原阶段 3 PR #2 已合并；客服运营权限调整、全部自动化验证及推送已完成，新 Draft PR 创建因集成 403 阻塞；详情见顶部 D-023 记录，未进入阶段 4
- 状态日期：2026-10-04；阶段 2 已合并到 main（5f6f1e0）
- 阶段边界：只建设价格参考库，禁止工单、案件、OCR、派单、自动核价、支付或结算流程；不进入阶段 4。

## 阶段 1 完成内容

- 初始化 Git 仓库，默认分支为 `main`；未创建提交和远程仓库。
- 建立 `backend`、`admin-web`、`h5-web`、`infra`、`docs` 的 Monorepo 结构。
- 后端建立 Java 21 / Spring Boot 3.5.16 / Maven Wrapper 工程，接入 PostgreSQL、Flyway、MyBatis-Plus、Spring Security、OpenAPI 和 Actuator。
- 后端建立统一响应、统一异常、请求追踪、安全默认拒绝策略和健康检查；未实现登录、JWT、用户、案件或其他业务功能。
- 管理端建立 Vue 3 + TypeScript + Vite + Element Plus + Router + Pinia + Axios 骨架。
- H5 建立 Vue 3 + TypeScript + Vite + Vant + Router + Pinia + Axios 骨架。
- 建立 PostgreSQL 17.6 与 MinIO 的 Docker Compose；默认宿主机 PostgreSQL 端口为 `15432`，避免与本机已有 PostgreSQL 冲突。
- 建立 `.gitignore`、`.editorconfig`、`.env.example`、README 和本地启动文档；真实 `.env` 已忽略。
- Flyway `V1__baseline.sql` 只建立迁移基线，不创建业务表。
- 两个前端均提交依赖锁文件，依赖版本固定；未使用 `--force` 或 `--legacy-peer-deps` 绕过依赖检查。

## 阶段 1 验证记录

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| Compose 配置 | `docker compose --env-file .env -f infra/docker-compose.yml config --quiet` | 通过 |
| PostgreSQL | Compose healthcheck、`pg_isready`、宿主机端口检查 | PostgreSQL 17.6，`healthy`，`localhost:15432` 已发布 |
| MinIO | Compose healthcheck、`GET /minio/health/live` | `healthy`，HTTP 200，API/Console 为 9000/9001 |
| 后端测试与构建 | Java 21 容器内 `./mvnw -B clean verify` | 通过；3 个测试，0 失败、0 错误；JAR 构建成功 |
| 后端实际启动 | Java 21.0.12.1 容器连接 Compose PostgreSQL | 通过；Flyway V1 成功执行，应用启动成功 |
| 后端接口 | `/api/v1/health`、`/actuator/health`、`/v3/api-docs` | 均通过；状态为 `UP`，OpenAPI 可读取 |
| 管理端 | `npm run lint`、`npm run typecheck`、`npm run build` | 全部通过 |
| H5 | `npm run lint`、`npm run typecheck`、`npm run build` | 全部通过 |
| Git 与秘密 | `git status`、忽略规则及配置复核 | `.env`、构建产物、依赖目录均不进入版本控制；未发现真实密码或 Token |

## 阶段 1 环境说明

- 主机只有 Java 24.0.2，正式 Java 21 验证通过 Eclipse Temurin 21.0.12.1 容器完成；日常本机开发仍应安装 Java 21。
- Docker Desktop 4.79.0 在本机受无效 Unix 套接字影响；已备份原设置和运行时目录，关闭无关的 Docker AI，并通过 WSL 重启恢复引擎。镜像和项目数据卷未被重置。
- 原有 Docker 镜像代理返回 403/400；已备份 `daemon.json` 并清空失效代理配置，Docker Hub 直连已验证可用。
- `minio/minio` 仓库当前拒绝拉取，Compose 改用固定的 Bitnami Legacy 社区版 MinIO 镜像；它只用于本地开发，生产部署前必须重新评估维护来源，详见 D-017。
- Compose 当前保持运行，PostgreSQL 与 MinIO 均健康；可按 `docs/LOCAL_DEVELOPMENT.md` 停止且保留数据卷。

## 阶段 0 完成内容

- 检查当前目录：检查前为空目录。
- 检查 Git：当前目录不是 Git 仓库，未执行 `git init`。
- 检查本地 Java、Maven、Node.js、npm、Docker 和 Docker Compose。
- 建立根目录 `AGENTS.md`。
- 建立需求、架构、决策和进度文档。
- 固化角色、正常主流程、报价与授权硬规则、价格库要求、技术栈、工程约束和 MVP 边界。
- 给出完整分阶段计划与不阻塞初始化的待确认事项。
- 未创建任何业务代码、页面、数据库迁移或运行配置。

## 阶段 0 本地环境实测（历史记录）

| 项目 | 实测结果 | 结论 |
| --- | --- | --- |
| 操作目录 | `C:\Users\zd070\Desktop\电动车保险工作平台` | 正确 |
| Git 工作区 | 不是 Git 仓库 | 阶段 1 需初始化（执行前确认） |
| Git | 2.54.0.windows.1 | 可用 |
| Java | 24.0.2，路径 `D:\Program Files\Java\jdk-24\bin\java.exe` | 可调用，但不符合目标 Java 21；未检测到 `JAVA_HOME` |
| Maven | `mvn` 命令不存在 | 未安装；阶段 1 可引入 Maven Wrapper，但正式构建仍需 JDK 21 |
| Node.js | 24.15.0 | 可用；具体项目支持版本与锁定策略在阶段 1 固化 |
| npm | 11.12.1 | 可用 |
| Docker CLI | 29.5.3 | 已安装 |
| Docker Compose | v5.1.4 | 已安装 |
| Docker daemon | 无法连接 `docker_engine` | 当前未运行（通常需启动 Docker Desktop） |
| Docker 用户配置 | 读取 `%USERPROFILE%\.docker\config.json` 出现权限警告 | 需在阶段 1 启动验证时复查，可能与当前受限执行环境有关 |

## 阶段 0 验证记录

- 已执行目录文件检查；初始目录无文件。
- 已执行 `git status --short --branch`；结果为“not a git repository”。
- 已执行 `git --version`、`java -version`、`mvn -version`、`node --version`、`npm --version`、`docker --version`、`docker compose version`。
- 已执行 Docker 服务端探测；CLI 存在但 daemon 当前不可连接。
- 已复核文档内容和文件清单（见本阶段结束前的最终检查）。
- 本阶段没有代码，因此无单元测试、lint 或应用 build 可运行；未将其标记为通过。

## 完整分阶段开发计划

所有阶段都遵循：先确认该阶段未决业务规则；只实现本阶段；运行测试/lint/typecheck/build；更新本文件；汇报并停止。

### 阶段 0：需求、架构和规则（已完成）

- 交付：需求基线、架构、决策、规则、环境盘点、路线图。
- 验收：文档覆盖已确认范围，未写业务代码。

### 阶段 1：工程骨架与本地基础设施（已完成）

- 初始化 Git（经确认）、`.gitignore`、编辑器与格式化基础约定。
- 建立后端、管理端、H5 的最小可构建工程，不含业务页面与业务接口。
- 固化 Java 21、Maven Wrapper、Node/npm 版本策略和锁文件。
- 建立 PostgreSQL、MinIO 的 Docker Compose、环境变量加载和 `.env.example`。
- 建立统一响应/异常的基础契约、OpenAPI、健康检查和测试框架，但不实现具体业务。
- 验收：三个应用可构建；基础测试通过；Compose 配置有效且依赖健康；仓库无真实秘密。

### 阶段 2：身份权限、组织基础数据与审计底座

- 确认登录方式、账号生命周期、权限粒度和区域模型。
- 实现用户、四类角色、权限、网点、区域和必要系统配置。
- 实现 JWT 认证、RBAC、数据范围基础机制、审计写入框架。
- 提供明确标识的本地测试账号和模拟基础数据。
- 验收：认证、功能权限、跨网点/跨用户拒绝、权限变更审计测试通过。

### 阶段 3：正式价格数据库与导入

- 确认币种/精度、导入模板、去重键、匹配优先级、版本冲突和失效策略。
- 实现品牌、车型、标准配件、内部编号、别名、适配车型、区域/网点维度。
- 实现原始/对外/核损/结算价格事实、来源、有效期、历史版本和导入批次。
- 实现校验、批量导入、错误明细、分页检索和历史查询。
- 验收：历史不被覆盖；十万级数据导入与关键查询达到约定性能；错误批次可追踪。

### 阶段 4：工单、派单与材料管理

- 确认工单字段、编号、异常流转、车主绑定与文件限制。
- 实现客服建单、选择网点派单、网点接单和确认车辆到店。
- 实现基于 `ObjectStorageService` 的 MinIO 文件上传、授权读取和材料关联。
- 定义 `MapProvider` 及 Mock，但只覆盖确认过的地图用例。
- 验收：正常主流程到“车辆到店”可走通；跨网点和车主越权被拒绝；材料访问受控。

### 阶段 5：OCR、维修项目与网点原始报价

- 确认报价修订、OCR 字段、人工确认责任和材料组合。
- 定义 `OcrProvider` 并实现 `MockOcrProvider`。
- 实现报价单/清单/照片上传、异步 OCR 任务、人工确认修正、维修项目与原始报价版本。
- 使用 PostgreSQL 任务表和 Worker，验证幂等、重试和并发领取。
- 验收：Mock OCR 到人工确认再到原始报价完整可追踪；失败任务可诊断和重试。

### 阶段 6：报价审核、统一加价、保险核损与维修授权

- 确认加价精度/舍入/边界、正式报价展示和保险材料规则。
- 实现客服审核项目、固定金额或百分比二选一的总额加价、正式报价。
- 实现保险最终核损总额录入、定损/核损单、保险确认和客服确认。
- 实现三项条件全部满足才开放维修的服务端状态门禁。
- 验收：金额精度正确；敏感字段按角色隔离；任一条件缺失均不能开始维修；关键变化有审计。

### 阶段 7：维修、完工、收车与评价

- 确认维修进度枚举、完工照片类别/数量、车主确认安全方式和评价维度。
- 实现授权后开工、维修进度、必传完工照片、完工提交。
- 实现车主在线确认收车和可选评价。
- 自动确认只保留设计扩展点，不设置倒计时或自动任务。
- 验收：未授权不可开工、照片不足不可完工、非本人不可确认收车，正常交付路径可走通。

### 阶段 8：投诉与站内通知

- 确认投诉分类、证据、状态、处理权限/SLA 和通知事件。
- 实现车主发起独立投诉工单、管理员处理与审计。
- 实现站内通知、已读状态和权限；短信仅定义端口/测试实现，不真实发送。
- 验收：投诉独立可追踪；通知接收人正确；敏感信息不越权泄露。

### 阶段 9：回款、网点结算记录与工单完成

- 确认分笔/部分回款、冲正、结算字段和工单完成门槛。
- 实现保险公司回款记录、状态和凭证（如确认需要）。
- 实现可空、人工录入的最终网点结算金额及结算状态。
- 不实现真实支付、自动分账或自动结算计算。
- 验收：金额与状态可追溯、按角色隔离、修改有审计；满足确认条件后工单可完成。

### 阶段 10：全链路验收、性能、安全与部署加固

- 运行四角色端到端主流程和关键异常/越权用例。
- 完成价格库性能、分页、并发任务、文件访问和审计完整性验证。
- 校验 OpenAPI、数据库迁移、测试数据、环境变量、备份恢复和部署文档。
- 明确云端部署、HTTPS、日志监控、保留策略和恢复目标。
- 验收：所有阶段门禁通过，无真实秘密，MVP 验收口径逐项有证据。

## 阶段 1 时的待确认事项（历史记录；阶段 2 已确认项见文末）

完整清单及最晚确认阶段见 `docs/REQUIREMENTS.md` 第 10 节。当前优先级较高的包括：

- 四类角色的具体登录和账号开通方式。
- 区域层级与网点关系。
- 金额/百分比精度、币种和舍入规则。
- 工单及报价的异常流转和版本规则。
- 文件限制与完工照片具体要求。
- 回款/结算是否允许分笔及工单完成门槛。

这些事项未阻塞阶段 1 工程骨架，但不得在对应业务阶段被实现者自行默认。阶段 2 开始前至少需要确认登录方式、账号开通/停用、权限粒度、区域层级与网点关系。

## 进入阶段 2 前的检查清单（历史记录；本次已确认业务规则）

- 用户明确同意开始阶段 2。
- 确认四类角色的登录标识、初始密码/重置方式、账号创建者、停用规则和 JWT 生命周期。
- 确认 RBAC 权限粒度，以及管理员和客服的数据范围差异。
- 确认区域层级、网点与区域的关系及网点账号归属。
- 宿主机开发建议安装 Java 21；在此之前可继续用已验证的 Java 21 容器执行正式构建。

## 2026-10-02 GitHub 仓库发布

### 已完成

- 移除误建在项目根目录下的嵌套 Git 仓库 `EV-Insurance-Work-Platform/`；该目录仅包含 Git 元数据和 `.gitattributes`，不包含项目源码。
- 将项目根目录作为唯一 Git 仓库，并连接远程仓库 `https://github.com/Victor-Zan/EV-Insurance-Work-Platform.git`。
- 保留远程 `Initial commit` 历史，将当前阶段源码提交并推送至 `main` 分支。
- 本次仅整理和发布仓库，未进入阶段 2，未修改业务实现。

### 验证结果

- `git check-ignore -v .env`：通过，`.env` 仍由 `.gitignore` 排除，未进入提交。
- 暂存文件安全检查：通过，共 57 个项目文件，无疑似密钥文件，无超过 10 MB 的文件。
- `git push -u origin main`：通过，提交 `3e533ae` 已推送至 `origin/main`。

## 2026-10-03 阶段 2 开始

- 已获明确授权，已确认账号密码、管理员账号管理、四角色边界、JWT 生命周期、区域树与服务区域关系，见 REQUIREMENTS 第 11 节和 D-018～D-020；其他待确认项保留。
- 当前聊天目录初始不是 Git 仓库；在 work/EV-Insurance-Work-Platform 克隆指定仓库。工作区干净，origin 指向 Victor-Zan/EV-Insurance-Work-Platform。
- 已 fetch origin main，从最新 origin/main（470eb74）创建并切换 feature/sherr-auth-rbac；保留 V1 原样。
- 实施计划：先固化规则，再新增迁移和领域服务，接入真实认证与角色首页，最后验证权限、审计、迁移及三个应用构建并提交 Draft PR。
- 验收重点：停用立即拒绝旧 JWT、管理 API 仅 ADMIN、错误端登录被拒绝、敏感信息不进入审计，全部列表分页且稳定排序。

## 2026-10-03 阶段 2 完成与验证

### 完成内容

- 新增 V2 业务迁移：账号、固定角色、用户角色、车主资料、区域树、维修网点、多对多服务区域、单网点账号关联、只追加审计日志；阶段 1 的 V1 与 origin/main 完全一致。
- 新增显式 dev 配置和 V2.1 开发数据迁移，创建四类测试账号及模拟组织数据；密码由环境变量注入后转为 BCrypt 哈希，不在代码、迁移或文档提供明文。普通配置不创建开发账号，开发数据库不得用于生产。
- JWT 登录、当前身份及对应端入口，Spring Security 和 RBAC；密钥与有效期均由环境变量提供，示例为 1800 秒，无刷新 Token。每次请求读取数据库状态；启停、密码重置、角色及网点归属变化递增认证版本，旧 Token 失效。
- 仅 ADMIN 可管理用户、角色分配、区域、网点、服务区域和审计；客服仅管理端身份入口，网点/车主仅 H5 身份入口。跨端角色组合被拒绝，避免 H5 角色获得管理端入口。
- 服务端本店/本人校验方法仅提供身份范围基础，不开放案件数据接口。Controller 不承载业务规则；按 identity、organization、audit 领域组织，MyBatis-Plus 单表 CRUD 和 XML 关联查询，事务与审计同边界。
- 登录及失败、用户创建/启停/密码重置、角色/网点归属、区域/网点和服务区域变更均审计；摘要由受控 ID、角色和状态构造，不保存密码、哈希、JWT、完整联系方式或密钥；无审计修改/删除 API，数据库阻止更新/删除。
- 管理端/H5 真实 API 登录、端类型提示、角色首页、路由守卫、认证失败清理、过期计时与退出。管理员最小用户管理验证页可创建、启停、重置账号。无空业务页面或静态假案件数据。
- 统一参数/认证/权限/不存在/冲突响应、错误追踪与 OpenAPI Bearer/错误契约；更新需求、决策、架构和启动说明。

### 实际执行的验证

环境：Temurin JDK 21.0.12.1（下载到本任务 work，SHA256 校验通过）、Maven Wrapper 3.9.16、Node 24.18.0、npm 11.16.0、独立 PostgreSQL 17.6。数据库仅监听 127.0.0.1:55432，专用测试库，每次集成测试使用随机独立 schema；未连接共享或生产数据库。所有测试凭据在运行时随机生成，文件在仓库之外。

| 检查 | 实际命令/方式 | 最终结果 |
| --- | --- | --- |
| Git 基线 | git clone 指定仓库；git status --short --branch；git remote -v；git branch --show-current；git fetch origin main；git switch -c feature/sherr-auth-rbac origin/main | 通过；基线 470eb742a0598982ad666896c978fae5b04ba395，提交前再次 fetch 无变化 |
| 后端完整门禁 | 在 JDK 21 下 .\mvnw.cmd -B -ntp clean verify -Ppostgres-it，注入 TEST_DB_URL/USERNAME/PASSWORD | 通过；5 单元 + 11 PostgreSQL 集成测试，0 失败、0 错误、0 跳过；可执行 JAR 构建成功 |
| Flyway 空库与重启 | 集成测试内 Flyway migrate/validate；分别使用空 dev/普通 schema；新 BCrypt 占位值重新 validate/migrate | 通过；dev 执行 V1/V2/V2.1，普通配置执行 V1/V2 且无用户；重启无校验漂移或重复迁移 |
| 认证生命周期 | 四角色正确登录、错误/不存在账号同错误、停用拒绝登录与旧 JWT、重启用仍拒绝旧 JWT、重置密码/归属变更使旧 JWT 失效、无效/过期/缺少声明 Token | 全部通过 |
| 权限与审计 | 非 ADMIN 对用户/角色/区域/网点/服务区域/审计 GET 和用户创建返回 403，错误端入口拒绝；本店/本人校验、账号重置、组织更新、关联失败回滚、审计防修改/秘密检查 | 全部通过 |
| OpenAPI/错误 | 实际 MockMvc 调用 /v3/api-docs，检查登录/用户路径、Bearer 和 401 契约；参数 400、资源 404、唯一冲突 409 | 通过 |
| 管理端 | npm ci；npm run lint；npm run typecheck；npm test；npm run build | 全部通过；6 测试，0 失败/跳过 |
| H5 | npm ci；npm run lint；npm run typecheck；npm test；npm run build | 全部通过；6 测试，0 失败/跳过 |
| 迁移/工作区复核 | git diff --exit-code origin/main -- backend/src/main/resources/db/migration/V1__baseline.sql；git diff --check；暂存文件与秘密扫描 | V1 未修改；变更限本阶段；无真实个人/案件/生产数据或凭据入库 |

首轮健康测试因新增认证依赖缺少测试替身而失败，保留原断言并补齐依赖；管理端表格行类型已修正；Flyway 断言已明确排除自动 schema 记录并校验确切 SQL 版本。未删除测试、跳过测试、降低类型严格性或放宽角色权限。

### 未验证项和边界

- 本机器没有 Docker CLI/Desktop，Compose 配置/容器启动与健康检查本次未验证；Compose 文件未修改。数据库门禁已用真正 PostgreSQL 17.6 完成，不能把它记成 Docker 验证。补救：在有 Docker 的环境按 LOCAL_DEVELOPMENT 第 3 节运行配置校验和健康检查。
- 未做真实浏览器/手机手工视觉验收；前端认证客户端、路由策略通过源码测试，两个应用生产构建通过。未验证生产部署与管理员生产初始化（属于后续上线加固阶段）。
- 无案件、派单、价格库、报价、核损、维修、上传、OCR、地图、结算实现；本店/本人校验仅为后续服务端数据范围基础。

### Git 交付

- 分支：feature/sherr-auth-rbac；目标：main；不直接在 main 开发、不 force push、不 reset --hard、不改共享迁移。
- 实现提交标题：feat(auth): add role based authentication and organization foundation。
- 实现提交：20eb5f2eb824a49548765943779f4cb397a28a45，标题为 feat(auth): add role based authentication and organization foundation。
- git commit 与 git push -u origin feature/sherr-auth-rbac：成功；已设置跟踪 origin/feature/sherr-auth-rbac，工作区提交后干净。
- GitHub 插件 github_create_pull_request（base=main、head=feature/sherr-auth-rbac、draft=true）：失败，HTTP 403，Resource not accessible by integration。当前连接的集成权限无法创建 PR；未创建 Draft PR，未尝试其他渠道绕过权限，未合并。
- 可供人工创建 Draft PR 的分支：https://github.com/Victor-Zan/EV-Insurance-Work-Platform/tree/feature/sherr-auth-rbac 。需为 GitHub 集成配置 Pull requests 写权限，或由具有权限的协作者人工建立指向 main 的 Draft PR。
- 本任务创建的独立 PostgreSQL 测试实例已通过 pg_ctl -D work/postgres-test-data -m fast -w stop 停止，测试库数据保留；未删除或修改共享数据库。
- 本文件的收尾提交仅记录验证与 Git 交付结果；阶段 2 在此停止，不进入后续阶段。
