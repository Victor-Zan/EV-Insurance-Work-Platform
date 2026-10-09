# 第5阶段续建计划与决策门禁

日期：2026-10-09。状态：实现与自动化/真实HTTP验收完成；停止在阶段5。边界和结果见PHASE5_VERIFICATION.md。

## 仓库与执行范围

本次在用户指定的第二阶段目录克隆 Victor-Zan/EV-Insurance-Work-Platform。origin 正确；克隆时 main 与 origin/main 同步，HEAD 为 85fa6f0，工作区干净。从该基线创建本地 feature/phase5-files-ocr-map；不推送、不创建 PR、不部署。第一阶段计划只读，不采用其中旧 TIDE 目录和“仅阶段0完成”的历史状态。

仓库中身份、组织、价格库、工单服务、V1—V5（含 dev V2.1）、四角色前端和 PostgreSQL 集成测试确实存在。阶段4既有实现保留；历史 PROGRESS 的通过结果不是本机本轮的通过结果。

## 技术路线：用户已选择B

用户已确认先独立迁移Boot4.1.1并完整回归阶段1—4，再开发阶段5。以下比较保留供评审，A不再作为实施路线。

| 路线 | 依赖与代码影响 | 验证与实施范围 |
| --- | --- | --- |
| A：保留 Boot 3.5.16 开发阶段5 | 保留 MP 3.5.17 的 boot3 starter、springdoc 2.8.17、Jackson 2、现有 Security 和测试包 | 先验证当前基线，再做阶段5；主版本迁移另设任务，需说明维护窗口 |
| B：先独立迁移 Boot 4.1.1 | 按官方指南评估 Framework 7 / Servlet 6.1、模块化 starter 与测试依赖、Jackson 3；MP 改为 boot4 starter，springdoc 改为与 Boot4.1 兼容的3.x | 迁移必须单独记录变更并完整回归阶段1—4；迁移通过后再进入阶段5业务开发，不以改 parent 版本代表完成 |

Java21满足Boot4.1.1的Java要求。当前 WorkOrderService 和各 PostgreSQL IT 使用 com.fasterxml.jackson 与 Boot3 的测试注解，迁移需要逐处核验序列化、幂等响应快照、MockMvc、认证和OpenAPI，不能只替换依赖号。

官方来源：

- [Boot4.1.1系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [Boot4迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [MyBatis-Plus安装](https://baomidou.com/en/getting-started/install/)
- [springdoc兼容说明](https://springdoc.org/)
- [Spring支持政策](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)
- [官方维护窗口API](https://api.spring.io/projects/spring-boot/generations)

维护窗口API本轮读取遇到TLS证书校验失败，网页工具不支持其HAL JSON；不绕过TLS校验。计划记载4.1.x OSS至2027-07-31，该日期目前仍待官方API实读复核，不根据第三方摘要把截止日期记为已验证。

## 2026-10-09 第5阶段已确认规则

- 文件七类：NOTICE（通知单）、SHOP_QUOTE（网点报价/明细）、ASSESSMENT（定损单）、LOSS_ASSESSMENT（核损单）、ARRIVAL_PHOTO、PROGRESS_PHOTO、COMPLETION_PHOTO。
- PDF/JPG/PNG（JPEG同格式扩展名也接受），每文件10 MiB，每类20个当前有效附件。历史版不占有效数量；案件总量暂不另设限制。
- CUSTOMER_SERVICE 上传/读取全部七类，填写缺失原因、补传、替换和作废。ADMIN 全部只读及审计。当前网点上传/读取本次派单自己的报价和三类照片；绑定车主仅读取三类照片。旧版、作废版仅客服/管理员可读。
- 替换新增不可覆盖版本；历史对象保留，禁止业务物理删除，暂不自动清理。COMPLETED/CANCELLED案件只读，OCR异步结果也受此限制。
- 通知单补传以当前有效附件判定已齐；原缺失原因作为历史记录保留。报价审核门禁留阶段6。
- OCR只覆盖前四类文档，照片不识别。Mock输出合成脱敏案号、车辆/车主、明细数量及金额等候选，confidence=null，显式mock/candidateOnly。只有客服可修正/确认，管理员查看及审计；确认只保存复核快照，不更改正式案件或报价。
- Mock地图由客服使用：地址搜索、选点、正逆编码、区域内真实网点列表配模拟位置、直线距离。合成坐标不代表真实地理位置，不连接收费API。
## 确认后的实施顺序

1. 固化规则和字段权限，新增迁移，不修改已共享V1—V5。复用实时账号、案件范围、当前派单版本与审计；文件权限不能只调用案件详情就无条件放行所有分类。
2. ObjectStorageService + MinIO适配器：私有桶、服务端对象键、流式存取、哈希/真实类型/大小验证、分类版本和元数据；禁止返回内部对象键或桶访问凭据。数据库与存储失败通过明确状态及补偿处理，清理策略按确认规则执行。
3. 受控附件API：列表、上传、读取/下载与经确认的版本操作；每次读取重新验证角色和案件归属，不让旧JWT、旧派单或旧链接持续访问。记录操作审计，过滤文件名及元数据中的敏感信息。
4. OcrProvider + Mock：任务和尝试记录持久化、去重、数据库并发领取、租约与重启恢复、有限重试；Mock失败场景明确可测。候选结果与人工修订/确认分开保存，使用版本/并发保护，审计与复核变更同事务；未经确认不写正式结果。
5. MapProvider + Mock：只实现确认的操作，响应明确模拟标识；采用同一认证与权限，不接真实收费API。
6. 在现有管理端/H5案件详情中增加本阶段必需验证页面，接真实API，支持加载、错误和无权限；不重做完整UI。
7. 完成全套门禁，更新进度、决策、问题、数据字典和API文档，汇报后停止，等待阶段6授权。

## 验收证据要求

- 真实MinIO写入与授权下载内容/哈希一致；重启后保留；匿名、跨网点、非绑定车主、停用账号、旧派单访问拒绝；禁看类别及内部字段不泄露。
- 类型伪造、过大、超数量、并发上传与失败补偿按已确认规则验证。
- OCR失败、重试去重、并发领取、租约恢复、人工修订/确认、历史审计落库；未确认结果不进入正式业务。
- 后端单元、PostgreSQL集成与Flyway空schema迁移/validate、Maven构建；两个前端lint/typecheck/test/build；必要的真实HTTP业务链路。
- 每项记录通过、失败或未执行。基础设施健康不等同于附件业务和权限通过。

## 隔离验证环境

环境凭据仅保存在第二阶段目录 local-validation/.env，未提交。Compose project为ev-insurance-phase5-audit，独立卷；PostgreSQL端口25432，MinIO19000/19001，避免与现有5432及项目默认端口混用。不清空已有数据库或删除卷。启动与结果见PHASE5_VERIFICATION.md。
