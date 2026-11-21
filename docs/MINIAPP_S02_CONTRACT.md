# S02：小程序接口与微信身份协议

2026-11-18同步修订：已拉取main至`d32120c`并恢复本地小程序工作。远程V33—V40已占用；本地仅在隔离库验证、未用于现有业务实例的微信绑定/历史索引迁移改为V41/V42，SQL内容保持不变。下文V33/V34为历史编号，当前源码与构建收据以V41/V42为准。未执行任何数据库升级；如恢复旧隔离库，须先核对该库Flyway历史，不可直接将旧编号记录套用到新迁移集合。

2026-11-02 v2最新变更（用户已确认）：主页面只有微信登录；`POST /auth/wechat/login`仍只接收code，但首次未知微信自动原子创建OWNER账号、车主资料及唯一绑定，未关联案件为空。没有手机号认领或旧账号自动合并；登录后在“我的”展示平台账号ID，管理员通过既有角色分配接口指定单一REPAIR_SHOP与具体网点。分配时撤销旧绑定并按新角色新增绑定快照，旧JWT失效，再次微信登录进入新端。已撤销微信/禁用账号不能自动重新建号；历史邀请/密码绑定与人工恢复接口保留兼容，登录主流程不再引导使用。下文v1中“无自动注册、先邀请/密码绑定、角色快照变化必须重新绑定”由本段覆盖；其余权限、事务、字段、限流、秘密和不自动认领案件规则继续适用。真实微信/HTTPS尚未联调。

版本：2026-11-02，v2。S02/S03/S04的v1本机范围曾获验收，本次用户明确更改为微信直接登录并确认新微信默认创建车主，授权更新身份主流程。S03已有8个接口/V33，本次增V34历史查询索引；S04登录页只保留微信登录，S05车主业务仍待验收。正式代码基线为`44b8b9036b687d7dbbb330c769100a0762eace56`及本机未提交修改。默认关闭微信入口；源码、隔离验证和真实微信/部署能力分别记录，不修改真实账号或现有业务库。

## 1. 已确认规则及当前缺口

- 2026-10-30确认：车主、网点使用各自微信；按服务端唯一身份进入，不提供角色切换。一个AppID下，一个微信至多绑定一个平台账号，一个平台账号至多有一个有效微信绑定。角色必须恰为`OWNER`或`REPAIR_SHOP`之一，不能带管理员/客服角色或两种移动角色。
- 2026-11-02最新确认：新微信直接登录，首次自动创建已启用/已激活的OWNER及唯一绑定，无密码输入、邀请或角色选择；无关联案件显示空列表。后台管理员按账号ID设REPAIR_SHOP并关联单一网点。旧邀请内设密码/激活流程仅保留兼容，不在登录页展示。不提供默认密码，不按手机号自动认领或合并。
- 同日确认：管理员人工核实后解除旧绑定，使该账号所有旧会话失效；新微信再凭原账号密码绑定。忘记密码沿用管理员重置；恢复顺序必须先核实并撤销旧微信，再重置/重新绑定。单独改密码并不等同于撤销微信身份。
- 现有代码：`AuthService`签JWT，`JwtAuthenticationFilter`逐请求查enabled/activated/bindingValid/auth_version；`AccountActivationService`管理24小时一次性邀请；`UserService`维护启停、重置和角色。S03新增`WechatIdentityService`、`WechatBindingStore`和`WechatCodeExchange`，微信通道逐请求核对绑定。现有多角色H5账号保留，不批量改造，但不得登录小程序。
- S04前端已补登录首屏、邀请页、已有账号绑定页及公共请求/会话/文件服务；默认未配置服务时失败，无假登录。S05车主首页/详情已接原业务契约，网点业务、后台微信绑定管理界面及真实HTTPS联调仍未实施。`auth/me`返回`CurrentUser`，不是`LoginResult`，已有独立重验解析，不得与登录结果混用。

## 2. 用户路径与状态转移

| 起点 | 用户操作 | 服务端结果 | 页面结果 |
| --- | --- | --- | --- |
| 已激活、已有有效绑定 | 点击微信登录 | code交换并核验唯一绑定/账号/角色，签平台JWT | 自动进入对应首页 |
| 从无绑定历史的新微信 | 点击微信登录 | 同身份事务锁内创建账号/OWNER资料/唯一绑定/审计并签JWT，不认领案件 | 直接进入车主端，案件为空；我的显示平台账号ID |
| 管理员指定网点身份 | 后台按ID选择唯一REPAIR_SHOP及具体网点，用户重新微信登录 | 角色/归属、auth_version和绑定快照更新在同一事务，旧JWT失效 | 新登录自动进入网点端，仍是同一平台账号ID |
| 已有H5账号尚未绑定（兼容） | 使用既有受控绑定流程并证明账号密码 | 新code＋账号凭据验证，建立唯一绑定后签JWT；不自动合并首次建号账号 | 旧接口保留，主登录页不再引导 |
| 待激活、有效旧邀请（兼容） | 从已签发链接查看脱敏身份并明确激活绑定 | 激活、BCrypt密码、绑定、邀请消费、auth_version增加及成功审计同一事务 | 旧邀请路径保留，不在登录主页面展示 |
| 仅有已撤销历史的微信 | 点击微信登录 | `WECHAT_IDENTITY_REVOKED`，不自动创建另一个账号 | 联系工作人员核实恢复 |
| 邀请已用/过期/撤销 | 打开或提交邀请 | 统一`INVITATION_INVALID` | 联系原邀请人员；不泄露目标是否存在 |
| 激活/绑定提交后响应丢失 | 用户再次点击微信登录 | 使用新code；若已提交则直接登录，未提交则仍提示未绑定 | 保留输入供用户恢复，不自动重复消费邀请 |
| 微信丢失/更换 | 管理员核实并撤销旧绑定；新微信验证原账号密码 | 撤销增加auth_version；新绑定产生新bindingId | 旧微信及旧JWT均不能继续使用 |
| 停用/归属无效/角色冲突 | 登录或携JWT访问 | 拒绝，客户端清空会话 | 返回登录页联系工作人员 |

页面只在点击确认后写入。预览邀请不消费、不激活；不先消费邀请再调用微信。没有Refresh Token或后台静默自动重登；过期后重新点击微信登录，避免未知绑定在后台悄悄恢复权限。

## 3. 通用协议

现有JSON响应为`{code,message,data,traceId}`，成功code为`SUCCESS`。分页为`{page,size,total,records}`；page从1开始、最大100000，size为1—100，默认20。UUID按字符串；用户/通知ID沿现有整数协议，前端拒绝不安全整数。所有业务金额用CNY元十进制字符串，服务端BigDecimal为准，空金额不能变成0。

沿现有NON_NULL序列化，错误/空成功响应的null data、null traceId可能省略；客户端及机器契约应接受，不为小程序修改旧ApiResponse。正常登录等非空成功响应仍必须有data。微信User.shopId、Invitation空token/到期、Binding.revokedAt用专用投影明确保留null，避免字段含义歧义。

除下表公开认证入口外均需`Authorization: Bearer <平台JWT>`。token、密码、微信code、邀请明文、OpenID/session_key不得进入日志/追踪属性/异常正文或持久客户端缓存。认证响应和邀请响应`Cache-Control: no-store`。AppID来自服务端配置，不接受客户端传AppID、OpenID、userId、role或shopId决定授权；拒绝额外身份字段。

安全配置只精确放行四个公开POST：wechat/login、wechat/bindings、wechat/activation/inspect、wechat/activation/complete；JWT过滤器的匿名例外采用同样精确路径，不使用整个auth命名空间的通配放行。公开绑定入口以账号凭据证明平台身份，公开激活入口以有效邀请证明目标，均还必须完成服务端code交换；不能凭任意JWT或URL角色绕过。

小程序内存保存会话，退出清空会话、图片临时状态和私密页面缓存；前后台切换/进入业务页通过现有`GET /api/v1/auth/me`重验。401清空并回登录；403停止操作、保留非敏感输入并提示；409刷新对应资源供用户复核；429遵从重试时间，不自动循环。旧账号在途请求不能写入新会话缓存。普通“退出登录”是本机退出，不声称撤销已复制JWT；管理员恢复撤销才使所有端旧JWT失效。

新接口的机器契约见[miniapp-auth.openapi.json](contracts/miniapp-auth.openapi.json)，S03操作标为源码`implemented`；这是维护的契约文件，不是现有业务运行实例的Swagger导出，不能据此认定已部署。

## 4. 新增认证与管理接口（S03源码已实现）

以下路径均含`/api/v1`。公开请求的字符串必须有长度限制并禁止记录正文。密码非空且UTF-8编码不超过72字节，两次一致；不能只用字符数代替BCrypt字节限制。`confirmed`必须为true。

| 方法/路径 | 权限、请求 | 成功data及语义 |
| --- | --- | --- |
| POST `/auth/wechat/login` | 公开；`{code}` | `WechatLoginResult`；识别有效唯一绑定，或为从无绑定历史的新微信原子创建OWNER及绑定；撤销历史不可借此重新注册 |
| POST `/auth/wechat/bindings` | 公开，账号密码作为本人凭据；`{code,username,password,confirmed:true}` | `WechatLoginResult`；仅已激活有效单角色账号；不能覆盖旧绑定 |
| POST `/auth/wechat/activation/inspect` | 公开；`{token}` | `{displayNameMasked,usernameMasked,role,shopNameMasked?,expiresAt}`；仅待激活单角色目标；不返完整联系方式/案件/账号ID，不消费 |
| POST `/auth/wechat/activation/complete` | 公开；`{token,code,password,confirmPassword,confirmed:true}` | `WechatLoginResult`；一次原子激活并绑定 |
| POST `/admin/users/{id}/wechat-invitation` | 仅ADMIN；无正文 | `WechatInvitation`；仅既有待激活移动账号，复用原签发/限流/重发撤销规则，不从此入口创建账号 |
| POST `/work-orders/{id}/owner-wechat-invitation` | 原ADMIN/CUSTOMER_SERVICE权限；`{expectedVersion,verified:true,reason,existingOwnerId?}` | `WechatInvitation`；沿原owner-invitation的案件范围、人工核实及既有账号显式关联规则；客服不得传existingOwnerId |
| GET `/admin/users/{id}/wechat-bindings` | 仅ADMIN；page/size | 分页`{id,version,role,shopId,status,createdAt,revokedAt}`，包括历史；不返OpenID/UnionID/秘密 |
| POST `/admin/users/{id}/wechat-bindings/{bindingId}/revoke` | 仅ADMIN；`{expectedVersion,verified:true,reason}` | `null`；锁定并撤销指定有效绑定、增加auth_version、写审计；旧版本/已撤销返回409，不再次递增 |

`WechatLoginResult={accessToken,expiresAt,user:{id,username,displayName,enabled:true,activated:true,roles:[唯一角色],shopId}}`，shopId网点为有效整数，车主为null；沿用现有登录形状，但微信专用投影不返回ownerPhone。有效期沿`JWT_TTL_SECONDS`配置（现代码限制1—86400秒），不新增刷新令牌或第二套业务用户。

`WechatInvitation={userId,username,alreadyActive,expiresAt,entryPath,invitationToken}`：待激活时返回一次性明文token、`pages/activate/index`及到期时间；已激活车主复用现账号时`alreadyActive=true`，后三项中token/到期为null、entryPath为`pages/login/index`，不改密码/签发无效邀请。新邀请仍使用32字节安全随机数的Base64URL（43字符），数据库仅存摘要，24小时有效，重发使同账号旧邀请立即失效。

邀请发行沿用`account_invitation`及`ACTIVATE_ACCOUNT`用途，不能调用原H5激活complete后另起事务绑定。新增服务端适配入口复用同一签发核心，输出小程序入口描述；旧H5入口、邀请URL、请求与响应不变。同一邀请可通过原H5激活或新小程序激活消费，只有一条路径成功；若H5先成功，用户走“已有账号绑定”。不承诺测试AppID可生成正式小程序码；二维码/链接投递和scene承载限制须有平台当前证据后实现，不能把43字符token直接假设塞入任意scene。

## 5. 微信外呼、事务与竞争

1. 新入口默认关闭。显式启用后，AppID/AppSecret仅安全注入后端；缺配置启动校验失败。真实模式绝不降级成Mock成功，测试适配仅隔离测试使用。
2. 服务端以固定微信官方HTTPS code2Session入口交换；禁客户端自定义地址、重定向，连接超时3秒、总读取上限10秒、响应上限64KiB，无网络自动重试。只有可验证成功且含有效OpenID的响应可继续；session_key不下发、不持久化、不记录。UnionID非必要，不保存也不用于合并。具体供应商错误映射在S03按可取得的官方文档核实。
3. 外呼在数据库事务之外。开始外呼前做格式/频控和非权威快速检查；外呼后进入事务，重新读取账号、邀请、绑定和auth_version，不能复用外呼前的授权结果。
4. 涉及既有账号时锁序：`app_user`→该用户邀请（若有）→绑定；案件开通沿原服务的案件/车主匹配锁序，不在其中外呼微信。不同用户争同OpenID由唯一索引兜底；数据库唯一冲突转409，不覆盖或自动重试。
5. 激活complete在同一事务重新核实邀请用途/时效/未用未撤销/auth_version、账号启用/未激活/资料有效/唯一角色；检查微信和账号均无其他有效绑定，写密码、激活、绑定、消费邀请、auth_version+1、成功审计并准备JWT。事务回滚时上述变更全部回滚，不泄露JWT；响应只能在提交成功后发送。
6. 已激活账号绑定先验证原凭据（失败统一INVALID_CREDENTIALS），锁后重新验证当前密码摘要及授权状态，防止校验后密码重置竞态；相同微信＋相同账号已绑定时可返回新会话但不再插入/递增，其他组合一律冲突。首次建立绑定增加auth_version，旧凭据会话失效。
7. 微信login在code交换后对(AppID,OpenID)持事务级锁。已绑定时锁账号并重新读取绑定，角色/网点必须与快照相符；未知时检查完整历史，仅从无历史的新微信可建号，账号/资料/绑定/审计同事务。随机不可知口令摘要满足旧结构，不向用户颁发口令；OWNER资料沿现有invitation_managed保护，不参与手机号自动认领。并发首次登录只创建一个账号。解绑与登录按账号锁串行，即使旧响应稍后抵达，JWT也因授权版本或绑定状态无效被拒绝。
8. 管理员沿原角色/网点分配入口，在账号锁内变更归属、递增auth_version；活动微信快照不匹配时只撤销原记录，再按新单一移动角色新增记录。更改为非移动/多角色时不签发新移动绑定；未授权绕过该操作的原地角色变化仍被快照拒绝。ROLE_CHANGE审计保留角色前后与重建/撤销条数，SHOP_ACCOUNT_CHANGE保留网点前后。新登录读取新快照，不在原页面静默变换身份。
9. 兼容激活和绑定不持久化密码、code或可重放JWT结果；不使用旧code自动重试。并发消费邀请最多成功一次；原流程身份与自动创建的新账号不自动合并。

限流复用`AccountRateLimit`十分钟窗口：公开认证按可信客户端IP20次，绑定按账号摘要10次，交换成功后按(AppID,OpenID)摘要10次；邀请inspect按IP20次，签发沿原每操作人20次/每目标3次。代理IP只有配置可信代理后才能使用，不信任任意X-Forwarded-For。超限`ACCOUNT_RATE_LIMIT`，返回Retry-After（窗口剩余秒数；无法取得时600），无额外外呼。限流键/失败记录不保存明文秘密。

## 6. 会话失效与增量迁移设计

新增`wechat_identity_binding`（V33）：UUID主键、app_id、openid、user_id外键、固定role、shop_id快照、version、created_at、revoked_at、revoked_by、脱敏revoke_reason。OWNER的shop_id必须空，REPAIR_SHOP必须非空；version初始0，撤销加1，角色/归属快照不可原地改写；撤销元数据必须一致。数据库触发器只允许一次记录撤销，禁止修改身份快照和删除历史。

V34仅追加(app_id,openid)全历史索引，供首次登录辨别新身份与已撤销身份；V1—V33不改写。此迁移只在隔离数据库验证，未用于现有业务实例。

两个部分唯一索引：有效记录的`(app_id,openid)`及`(app_id,user_id)`分别唯一（`WHERE revoked_at IS NULL`）；查询历史用`(user_id,created_at DESC,id)`稳定分页。OpenID仅后端受限身份表可读，不进业务响应/URL/审计摘要；备份继承身份数据访问控制。S03开工核实最高V32后新增V33，旧迁移不变；现有业务库未执行本次迁移。

微信JWT沿原subject/issuer/到期/av，额外携`channel=WECHAT`和`wechatBindingId`。过滤器对该通道每次检查绑定存在、未撤销、user_id/AppID匹配、账号唯一移动角色及绑定快照仍一致；带channel却无绑定声明或声明形状错误均401。新token仍是原JWT服务签发，不能新增前端自签身份。旧H5/管理端无此通道字段，继续走原校验，不要求存在微信绑定。

撤销事务更新绑定、用户auth_version及审计。因原过滤器核验av，该用户所有旧H5和微信JWT/旧登录交接凭据均失效；再次密码登录H5可用，旧微信不能换新JWT。停用、密码重置、角色/网点变化继续沿原touch规则。归属改变不把旧微信自动迁到新身份；登录发现快照变化拒绝，管理员核实撤销后重绑。审计至少记录行为、操作者/目标账号、bindingId、原因摘要、时间、traceId；不含密码/code/token/OpenID/session_key。失败审计独立提交并有界限流，不能因主事务回滚全部丢失。

回滚先关闭微信入口，前端保留明确不可用提示；保留表、历史和新增迁移，不回退旧库。曾发行微信通道JWT后，过滤器绑定校验不得随意回退为忽略channel的旧程序；需要服务端回滚时必须先用保留绑定校验的兼容版本，或先批量撤销受影响会话并核实兼容，再切服务。H5无需迁移账号/案件。

## 7. 现有业务API及写入字段（已核对源码，非本轮实连）

所有路径前缀`/api/v1`。表内`K`表示`Idempotency-Key`必传，1—128字符，超时保持相同业务命令/正文/键；409后刷新并经用户重新核对，不能换键自动提交旧业务意图。下列仅是移动端白名单，不能因Controller存在就开放全部方法。

| 页面/功能 | HTTP路径 | 请求/返回关键字段、限制 |
| --- | --- | --- |
| 案件列表/搜索 | GET `/work-orders` | page,size,status?,query?,excludeCompleted?；分页WorkOrder，服务端本人/本店过滤 |
| 案件详情/历史 | GET `/work-orders/{id}`、`/{id}/history` | WorkOrder含id/businessNo/claimNo/车辆/status/version/workflowNode/workflowActor；历史分页，不向用户直接展示技术action/actor/trace。currentAssignment只在非OWNER详情返回，列表不得假设包含 |
| 接单/到店 | POST `/work-orders/{id}/accept`、`/arrive`，K | `{assignmentVersion}`，不是案件expectedVersion；返回WorkOrder |
| 拒单/到店异常/继续等待 | POST同前缀`/reject`、`/arrival-exceptions`、`/continue-waiting`，K | `{assignmentVersion,reason}`；拒单reason1—500，到店异常1—1000，继续等待reason可空、最多500 |
| 材料列表/下载 | GET `/materials/cases/{id}`、`/materials/{fileId}/download` | 列表分页`{id,groupId,category,version,state,name,contentType,size,createdAt}`；下载是二进制，认证头下载到临时文件再预览；不公开MinIO |
| 网点材料上传 | POST `/materials/cases/{id}`，K，multipart | 字段file/category/assignmentVersion；网点不传replace。文件1字节—10MiB；仍执行阶段、格式、类别有效数量与归属校验。普通维修图片JPG/PNG；OCR另遵供应商限制 |
| 报价上下文 | GET `/quotations/cases/{id}` | caseId,status,version,basisVersion,assignmentVersion,arrivalPhotoUploaded,rawQuote,gate,authorized；网点没有formalQuote/assessment内部金额 |
| 网点手工报价/开修 | POST同报价前缀`/raw-quotes`、`/start-repair`，K | 报价`{expectedVersion,assignmentVersion,lines:[{description,quantity,unitPrice,ocr?}]}`；quantity为正32位JSON整数，unitPrice十进制字符串；开修`{expectedVersion,assignmentVersion}`。服务端总额权威 |
| 报价版本/识别关联 | GET同报价前缀`/history?type=RAW`、`/sources/{business}` | 网点历史必须显式type=RAW；省略会默认EVENT并被拒绝；分页只读本次本店原报价。来源关联只读对应不可变提交版本，不拿最新OCR替换历史原件 |
| 网点OCR配置/任务 | GET `/quotations/cases/{id}/ocr/configuration`；GET/POST `/quotations/cases/{id}/ocr` | 列表assignmentVersion/page/size；上传multipart file/assignmentVersion/category（SHOP_QUOTE或ASSESSMENT）/pageNo（1—20）＋K，不自动提交报价 |
| OCR详情与原件 | GET OCR前缀`/{job}`、`/{job}/program`、`/{job}/raw`、`/{job}/history`、`/{job}/image` | Job含id/fileId/provider/state/attempts/errorCode/candidate/reviewVersion/confirmed/retryable/pageNo/rerunsUsed；history分页，image认证二进制；raw为授权识别结果，不等于能读内部通用OCR入口 |
| OCR重识别/采用 | POST OCR前缀`/{job}/retry`、`/{job}/apply`，K | retry`{expectedVersion}`；apply`{expectedVersion,items:[{index,description,quantity,unitPrice,unit,lineAmount}]}`，expectedVersion来自reviewVersion；items为1—200行，index为0—199且不重复，其余为字符串、各最多500字符，人工核对后确认；首次之外最多3次，失败可继续手填 |
| 识别报价提交 | POST `/quotations/cases/{id}/recognized-quote`，K | `{jobId,revision,quote:{expectedVersion,assignmentVersion,lines}}`；revision为已确认人工快照，服务端校验原件/采用值一致；区别于不带识别来源的raw-quotes |
| 维修详情/历史 | GET `/repairs/cases/{id}`、`/progress` | caseId/status/version/assignmentVersion/completion/receipt/review；进度历史分页 |
| 更新进度/完工 | POST维修前缀`/progress`、`/completion`，K | `{expectedVersion,assignmentVersion,photoIds}`；进度另含note。完工至少一张本次派单有效JPG/PNG照片，快照冻结 |
| 车主收车/评价 | POST维修前缀`/receipt`、`/review`，K | 收车`{expectedVersion}`；评价`{expectedVersion,text,score}`，文本/评分可选且一次性；expectedVersion为维修版本，不是WorkOrder版本 |
| 通知/待办 | GET `/notifications`、`/unread-count`、`/todos`；POST `/{notificationId}/read` | 列表/待办分页，todos可caseId；未读返回`{count}`；通知id/caseId/kind/title/createdAt/read；待办taskKey/caseId/kind/title/version/deadline/overdue。不开放修改deadline/settings写权限 |
| 投诉 | GET `/complaints/cases/{id}`、`/complaints/{complaintId}`、`/{complaintId}/history`；POST `/complaints/cases/{id}`，K | 列表/历史分页；提交`{description,photoIds}`，描述1—2000字符，0—20个不重复且本人有权读的既有案件照片。开修后可提交，完成后仍可；取消/关闭拒绝。不增加车主上传 |
| 网点结算摘要 | GET `/funds/cases/{id}` | `{caseId,currency,unit,version,payable:{target,net,status}}`；target可空，net为本次派单净支付额，均为金额字符串；余额只作精确展示推算，不冒称接口返回。无receivable、利润或内部台账，无资金写入口 |

原始详细DTO/字段实现为依据：`workorder/api/{WorkOrderRequests,WorkOrderViews,WorkOrderController}.java`、`quotation/api/{QuotationRequests,QuotationController,RecognizedQuotationController}.java`、`ocr/api/ShopQuoteOcrController.java`、`ocr/application/ShopQuoteOcrService.java`、`repair/api/{RepairRequests,RepairController}.java`、`document/application/{FileService,FileAccess}.java`、`notification/application/NotificationService.java`、`complaint/application/ComplaintService.java`、`funds/application/FundsService.java`。现有`h5-web/src/features/`的适配可用于对照，不能直接复制DOM/Vant依赖。

现有H5未完成的兼容细节不自动扩大本轮范围：小程序采用当前正式后端基线，`arrivalPhotoUploaded`必须返回布尔；明确false不可被本地成功提示覆盖。若未来支持旧后端，须另定兼容边界和有界分页材料查询，不能无限扫描。案件列表缺派单版本时先取详情再操作。

## 8. 版本与权限矩阵

| 字段/版本 | 来源 | 只用于 |
| --- | --- | --- |
| WorkOrder.version | 案件详情 | 明确要求该版本的案件/开通命令 |
| assignmentVersion | 案件详情currentAssignment.assignmentVersion或已授权上下文 | 接单/拒单/到店/上传及本次网点操作；不是案件版本 |
| quotation.version | GET报价上下文 | raw-quotes、recognized-quote.quote、start-repair.expectedVersion |
| rawQuote.version / basisVersion | 不可变报价版本/授权依据 | 展示和依据核对，不能替代quotation.version |
| repair.version | GET维修上下文 | progress/completion/receipt/review.expectedVersion |
| OCR.reviewVersion | GET对应OCR Job | retry/apply.expectedVersion；确认后的revision用于识别报价 |
| complaint.version / funds.version / todo.version | 各自上下文 | 各自领域命令；移动端无投诉处理/资金/截止时间写权限 |
| auth_version / binding.version | 用户记录/绑定管理视图 | 服务端会话撤销 / 管理员指定绑定撤销；不能互换 |

| 能力 | OWNER | REPAIR_SHOP | 管理端既有职责 |
| --- | --- | --- | --- |
| 案件读取 | 仅已绑定本人且非草稿 | 本店有效范围；操作重验派单 | 沿原规则 |
| 维修图片读取 | 授权到店/进度/完工图，不含现场及内部文档 | 本次本店授权材料/原报价 | 沿原规则 |
| 上传/报价/OCR | 全部拒绝 | 本次派单、阶段允许的材料和原始报价 | 不扩大权限 |
| 开修/进度/完工 | 拒绝 | 授权门禁齐备且本次已接单 | 不改原流程 |
| 确认收车/原评价 | 本人、当前维修版本 | 拒绝代替车主评价 | 客服代确认/纠正沿原规则 |
| 投诉提交/处理 | 本人提交、读公开处理 | 只读本店本次投诉及公开处理 | 管理员处理、客服补充；内部说明不外泄 |
| 结算 | 无入口且API拒绝 | 只读当前派单摘要 | 资金录入仍由原授权端执行，不真实划款 |
| 微信恢复/撤销 | 无直接管理权 | 无直接管理权 | ADMIN人工核实撤销；客服不能解除绑定 |

车主“已收车”沿H5把WAITING_INSURER_PAYMENT/WAITING_SHOP_SETTLEMENT/COMPLETED投影为“已收车”，不改后端事实；网点相应显示待结算。禁止把现有`/work-orders/{id}/workflow`管理端流程快照、通用内部OCR、核损/正式加价、内部审计/资金台账开放给两种移动角色。新微信适配不按手机号调用旧owner-binding或修改案件归属。

## 9. 错误码与恢复

| HTTP/code | 性质 | 前端动作 |
| --- | --- | --- |
| 400 VALIDATION_ERROR | 已有 | 校正字段；不重试或丢失表单 |
| 401 INVALID_CREDENTIALS | 已有 | 凭据统一失败提示，不枚举账号状态 |
| 401 TOKEN_INVALID / SESSION_INVALID | 已有 | 清会话、旧请求失效，回登录；无自动换身份 |
| 403 FORBIDDEN / PORTAL_MISMATCH | 已有 | 停止当前操作，不切成另一角色 |
| 404 NOT_FOUND | 已有 | 后台确认目标已不存在，刷新列表；公开邀请校验仍统一INVITATION_INVALID，不枚举账号 |
| 409 INVITATION_INVALID | 已有名称，新入口复用 | 联系邀请人员；不区分未知/已用/过期 |
| 409 WECHAT_BINDING_REQUIRED | 新增 | 提供绑定已有账号/邀请说明，不授予默认车主 |
| 409 WECHAT_BINDING_CONFLICT | 新增 | 微信或账号已被其他有效绑定占用；人工处理 |
| 409 WECHAT_IDENTITY_CONFLICT | 新增 | 多角色或角色/网点快照变化，拒绝进入 |
| 409 WECHAT_BINDING_VERSION_CONFLICT | 新增 | 后台刷新，不重复撤销或递增版本 |
| 401 WECHAT_CODE_INVALID | 新增 | 获取新code后由用户重试，不复用旧code |
| 502 WECHAT_PROVIDER_ERROR / 503 WECHAT_UNAVAILABLE | 新增 | 明确暂不可用；不输出供应商原文或伪造成功 |
| 429 ACCOUNT_RATE_LIMIT | 已有名称，新入口复用 | 按Retry-After提示稍后操作，不自动循环 |
| 409 *_VERSION_CONFLICT / IDEMPOTENCY_CONFLICT / READ_ONLY等 | 现有业务码 | 保留输入、刷新相关资源并重新核对；不自动换键 |

账号停用或资料无效：微信登录返回401 SESSION_INVALID，已有账号密码绑定统一INVALID_CREDENTIALS；邀请相关目标异常统一INVITATION_INVALID。隐藏详细状态不妨碍后台脱敏审计定位。绑定竞争失败不改变密码/激活/案件或已有绑定。

## 10. 后续验收和交接边界

S03必须在隔离数据验证：有效单角色登录；错误code/供应商超时/缺配置无登录；邀请并发仅一次成功；绑定冲突回滚；失败后密码与邀请保持原状态；响应丢失后新code可恢复；无手机号自动认领；跨角色/跨店拒绝；停用、角色/网点变化及管理员撤销使旧JWT失效；原H5/管理端密码登录和激活回归；新增迁移从既有库升级且保留旧数据。最小编译先行，再执行仓库要求的相关认证/迁移测试，不借真实业务账号造故障。

S04源码已实现邀请页、已有账号绑定页、会话生命周期与上述错误路径；取消旧“仅联系工作人员”的单一路径。UI沿现H5品牌和共享56px品牌栏；默认无配置不认证，H5不伪装微信code。公共服务10项合成传输检查通过，实际微信工具画面/负向路径另有记录；真实邀请预览后的密码区域、真实微信交换/绑定和鉴权文件实连未验。S03/S04不能把DEMO案件当成真实接口数据。

S02只做源码/契约静态核对；S03另有后端构建、供应商离线单元和独立PostgreSQL认证/迁移验证，结果见PROGRESS，不重复无变化的前端构建。真实微信外呼、部署、真机或业务API联调仍未验证。2026-11-02再次尝试读取[官方登录说明](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)及[code2Session文档](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)，工具均返回不可读取；上述链接仅是待核查入口，不作为已获取的官方证据，不据此承诺主体资格/平台配额/小程序码能力。真实适配已实现固定地址、有界超时和失败封闭；真实启用前仍须核对官方当前细节及应用条件，S08另验发布资格。

S02因用户“开始s03”验收，S03本机范围因用户“s04”验收，S04因用户“开始s05”验收；2026-11-03用户“继续s06”接受身份v2及S05本机范围，随后“s07”接受S06本机范围。S06复用既有接单、到店、材料、原始报价及网点专属OCR接口；S07复用维修进度/完工、照片冻结、公开收车评价及本网点只读结算，没有新增业务协议或迁移，当前S07本机实现待验收，完整表单点击及真实联调另列未验证。状态以阶段进度表/PROGRESS为准，不将公共基础、合成界面或隔离API实现当成真实微信绑定或发布完成。
