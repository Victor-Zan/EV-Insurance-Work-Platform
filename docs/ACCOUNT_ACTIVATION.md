# 受邀开通与统一登录（D-062，2026-10-19）

不开放注册、角色自选、短信/邮件发送、找回密码、陌生网点入驻。ADMIN准备客服/管理员/已建有效合作网点账号；OWNER从明确案件办理，不用未核实手机号自动认领或复用身份。客服只可操作其可见非草稿、非取消/完成案件对应的OWNER。

新账号 activated=false、enabled=true；已有账号迁移默认 activated=true，原密码/启停/绑定不改。停用独立于激活；待激活账号重置密码不能绕过邀请。新管理页面只创建待激活账号，受保护旧管理员直接设密建号API为兼容保留，不扩展权限。初始管理员仅现有dev环境变量安全哈希种子；非dev没有公开引导/默认管理员，本次不增加初始化后门。生产首管仍需受控运维准备账户，部署未纳入本轮。

OWNER：已明确绑定账号则复用。未绑定时，工作人员必须勾选已通过已核实联系方式核验本人/案件归属并提供核实说明；不存在同手机号车主时，由固定OWNER服务生成待激活账号、资料和当前案件绑定（版本锁/审计）；存在一个或多个潜在身份时，不自动复用，由管理员人工核实后明确指定已有OWNER ID，同样须说明/版本/联系资料一致。客服无通用角色分配/任意OWNER绑定入口；已有绑定不转移。本次邀请创建的车主标记invitation_managed=true，无论激活前后均不参与旧手机号自动匹配；后续其他案件须人工核实并明确绑定，既有历史绑定和旧账号匹配机制保留。已激活账号复用只返回H5登录地址，不签发激活邀请、不改密码；停用账号拒绝复用。新pending账号不参与旧手机号自动匹配，确认邀请不修改案件其他字段。

邀请有效24小时、256位随机令牌、SHA256摘要入库，明文仅签发当次响应，工作人员手工复制发送；不提供历史明文取回。重发撤销旧邀请，单账号行锁串行签发/激活；停用/角色归属变动(auth_version)使旧邀请无效。激活与消费同事务，已激活/停用/失效/已用邀请不能改密码或恢复账户。GET只加载页面，POST预览/激活执行服务端验证，错误不返回账号是否存在。密码沿用非空且不超过72个UTF8字节的BCrypt规则，无新增复杂度。

统一登录保留两端架构与旧指定端API；新sign-in不接受角色，后端验证凭据/激活/启用/角色/业务绑定后确定目标端。签发60秒、一次性随机交接码（库中摘要），跳转到配置的对应端/login片段；目标端POST兑换自身portal的JWT，立即清除片段，不把JWT放URL。激活URL也用片段，只在页面内存保留令牌，Referrer-Policy=no-referrer，不持久化、不分析。没有角色切换或任意redirect参数。现有JWT即时启停/认证版本检查及到期重登保持。

公开地址配置 ACCOUNT_ADMIN_PUBLIC_URL、ACCOUNT_H5_PUBLIC_URL，HTTP仅允许localhost/127.0.0.1/[::1]开发，其他须HTTPS，无用户名、query、fragment；支持部署路径前缀。默认本地5173/5174，正式域名由部署环境注入。不得将邀请/交接码/密码写日志、审计或仓库。

限流：数据库原子固定时间窗，匿名预览/激活20次/IP/10分钟、统一登录20次/IP/10分钟、交接兑换30次/IP/10分钟；不信任客户端转发头。签发/重发20次/授权账号/10分钟，单目标3次/10分钟；跨进程重启不重置。429提示稍后重试。原有登录API同样纳入限流。审计使用独立事务记录失败，只有受控ID/动作/结果，无凭据/原始请求；工作人员核实说明仅私有业务记录保存，审计不放正文。

## API

- POST /api/v1/auth/activation/inspect {token}；返回脱敏账号、角色、必要网点名，不返回完整手机号。
- POST /api/v1/auth/activation/complete {token,password,confirmPassword}；成功返回本人登录账号及登录地址（开通前仅脱敏），手工登录，无自动JWT。
- POST /api/v1/auth/sign-in {username,password} → {portal,loginUrl,code}。
- POST /api/v1/auth/handoff {code,portal} → 原LoginResult。
- POST /api/v1/admin/account-invitations/accounts {username,displayName,role,shopId?}：仅ADMIN创建待激活非OWNER账号。
- POST /api/v1/admin/account-invitations/users/{id}：仅ADMIN签发/重发待激活账号邀请。
- POST /api/v1/work-orders/{caseId}/owner-invitation {expectedVersion,verified,reason,existingOwnerId?}：客服/管理员；客服不能指定已有身份ID。绑定/创建与签发同事务，重复不会创建第二个账号。
- GET /api/v1/admin/account-invitations/users/{id}：仅ADMIN读取激活、启停与邀请状态，无令牌。

工作人员须通过已核实联系方式手动发给本人；页面不宣称已发送短信或完成系统身份验证。数据库仅追加V23，V1–V22不改。本轮独立分支，不依赖未合并地图，不部署或合并。

激活成功审计以邀请绑定账号作为操作者，失败只记匿名结果；不将邀请凭据或密码写入审计。跨端交接优先于旧登录态路由守卫，兑换前清理目标端旧会话，避免复用旧账号身份。
