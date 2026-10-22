# 2026-10-22 高德整合迁移与隔离运行验证

本次完成用户批准的迁移冲突处理与离线整合。真实高德预算依最新要求继续关闭，尚未完成“三个真实地理位置、虚构合作网点的驾车距离排序与合成案件派单验收”，不满足普通合并条件。

## 迁移与可恢复检查点

- 操作前两次明确fetch确认远程main为`94173d2`，V25未占用。
- 共享`V23__account_activation_login.sql`和`V24__amap_shop_recommendations.sql`与main Git blob完全一致。
- 旧本地`V23__amap_dispatch_integration.sql`仅从最终目录撤下；可从`a6aa673ad9caaaf80d2d6561935ee19dc336db68`或`9701373`恢复。没有改写这两个提交。
- 最终只新增`V25__amap_dispatch_safety.sql`：停车列、真实查询版本（历史未知=-1）、短期测试位置来源约束和预算表/不可重置触发器；复用V24地图基础表。无repair、清库、旧schema升级或历史修改。
- 最终代码提交`7cb469ed24529770157f585075a5db273ab6f1cd`，普通合并main后保留账号/统一登录/H5、OwnerInvitation、网点维护及原权限。管理员不获得客服定位接口权限，原人工派单仍可用。

## 数据库与程序对应

均位于本机开发PostgreSQL 55432的`ev_auth_phase2_test`中不同schema；未输出密码。

| schema | 迁移/程序 | 当前处理 |
| --- | --- | --- |
| `amap_browser_20261015` | 旧地图V17、原地图分支 | 原样保留；旧5187/18088预览未停止，不能接最终JAR |
| `amap_main_validation_20261021` | 本地旧V23、a6aa673/9701373检查点 | 数据/历史/旧JAR/manifest保留；其5194/18089两个旧进程经PID＋启动时间＋可执行路径核实后停止，不能接最终JAR |
| `amap_main_final_20261022` | main V1—V24＋新V25、7cb469e | 创建前核实不存在；最终JAR只连接此新schema，未升级任何旧库 |
| `map_migration_it_*`及其他新随机测试schema | 迁移/业务自动化夹具 | 仅合成数据；新增迁移用例从共享V24演进至V25，未利用任何已有旧业务库 |

未来升级旧业务库必须另行核实实际执行历史、位置来源、历史推荐及统一预算；本次测试不代表任意旧V17或本地V23可直接升级。尤其不能把最终JAR指向旧V23 schema。

## 验证证据

| 检查 | 结果 |
| --- | --- |
| 后端 `mvnw.cmd -B -ntp clean verify -Ppostgres-it` | 77单元＋132 PostgreSQL/MinIO集成；失败/错误/跳过0；Maven成功，2026-10-22 00:50:06，4分51秒 |
| V24→V25增量用例 | 保留原位置/账号；旧推荐版本-1；新库无预算；来源互斥；再次migrate执行0条；通过 |
| 完整空库迁移及Flyway校验 | 全部迁移成功，V23/V24/V25 success=true；每个完整应用集成schema独立验证 |
| 最终隔离运行第一次启动 | 新schema完成全部迁移，后端UP，7cb469e/AMAP_WEB_V1/mock=false/callsEnabled=false |
| 同一新schema第二次启动 | 2026-10-22 00:51:39验证27个迁移资源；明确“up to date / No migration necessary”，随后UP；未重新分配预算 |
| 管理端 lint/typecheck/test/build | 124测试，全部通过，失败/跳过0；生产包API目标127.0.0.1:18089/api/v1 |
| H5 lint/typecheck/test/build | 32测试，全部通过，失败/跳过0；保留main账号/车主/网点流程 |
| 浏览器 | 5194统一登录以合成客服进入现有派单页面；事故区县单独显示和核对，保存原因、版本1→2与审计；事故地址未变、停车字段仍空；真实解析/路线按钮禁用，原人工候选三店均距离未计算 |
| 秘密与共享迁移检查 | 暂存实际私密值扫描无匹配；main V23/V24 blob一致；无真实图片/客户资料/凭据提交 |
| Compose | 未验证：本机无Docker CLI；真实PostgreSQL/MinIO集成已执行，不冒充Compose通过 |

失败过程保留：沙箱连接限制中断、共享PG连接池耗尽、首次合并回归的权限/夹具问题。测试进程连接池限制为3后完整运行，未删除/跳过测试。首次合并回归11失败/1错误分别为管理员配置读取权限（已恢复main拒绝边界）、地图测试遗漏main原有每例限流隔离（恢复）、新迁移夹具缺随机BCrypt占位（补齐）；之后完整通过。此前一条口头误把完整应用空库迁移当作增量用例通过，已明确纠正；本表取最终真实报告。

## 实际运行与预算

- 前端：`http://127.0.0.1:5194`；后端：`http://127.0.0.1:18089`。这是功能分支隔离预览，未更新main或生产。
- 运行JAR/新schema/源码7cb469e/哈希及PID启动路径记录于仓库外`amap-main-runtime/final-manifest.json`；两次启动日志分别保存。后续纯文档提交不改变运行代码版本。
- Key复用本机后端私密`.env.amap`，不在VITE或响应中；最终进程`AMAP_MAX_CALLS_PER_PROCESS=0`。新schema预算run/slot均0，不能通过重启/新库解锁。
- 权威旧run `amap-three-gates-20261021`仍在旧schema，enabled=false、attempts=0。历史单次联通与本轮分开；本轮四个入口地理编码各0，逆编码0，三条指定路线各0，总0/8。没有真实距离、预计时间、排名、推荐或真实改选派单结果，不用Mock补造。
- 后续真实验收需用户明确重新启用授权，并先设计/核对唯一持久台账承接方案；不能另起run或复制空预算恢复额度。四个入口与原分类配额沿用AMAP_VALIDATION，不因跨日重置。

最终合成案件`a2a6e74d-ffdf-4301-b943-730de307c5bd`（EVR-20261022-000001）仍PENDING_DISPATCH、assignment数0。三个测试网点仅配合成车型/南山区服务关系，无学校联系人/电话/坐标，学校不是维修合作方。正式运营前管理员停用测试网点，按正常入口新建真实合作方与来源明确位置；保留被引用历史，不物理删除。没有通知学校、真实客户或外部业务系统；无付费、生产部署或JS地图。

代码审查已检查迁移差异、原角色边界、原派单服务复核、共享预算不重置、历史数据保留、坐标短期内存、过期/并发校验和暂存秘密。PR仍需仓库要求检查及人工审批；真实验收未完成，禁止自行合并。

GitHub交付：功能分支已正常推送，代码7cb469e、首份验证记录4066783。推送前main仍94173d2。创建Draft PR时集成返回`403 Resource not accessible by integration`，PR未创建，未换通道或绕过权限；[手动创建Draft PR](https://github.com/Victor-Zan/EV-Insurance-Work-Platform/compare/main...codex/amap-main-integration?expand=1)。没有修改远程main或自动合并。后续文档交付补记不改变当前运行代码7cb469e。
