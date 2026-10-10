# 第8阶段视觉验收（2026-10-10）

结论：四角色的基础布局、实际数据展示和主要入口已完成浏览器验收；发现并修复2处问题：H5进度记录文字/时间重叠、管理端案件列表loading指令未注册。当前交付为已约定的必要API验证面板，仍有下列可用性改进项，未把它认定为正式产品UI定稿。

## 环境与范围

Edge真实浏览器；桌面实际视口1432×721；H5模拟390×844及360×800。本轮用已通过的phase8-delivery-build JAR，专用ev_insurance_test随机phase8_visual schema、私有MinIO及合成数据，未改既有开发/生产案件。前端为实际Vue开发服务与真实API，不是静态数据页面。进入地址使用http://localhost:5173/5174；127.0.0.1来源登录被既有CORS拒绝403，切回允许来源正常，未放宽权限。

本轮采用computer-use技能与cua_repl实际登录、点击、输入、滚动、截图及DOM尺寸测量。截图在validation/phase8-visual，均为合成数据；浏览器第三方翻译/下载扩展浮标亦被捕获，未更改用户扩展或全局设置。

## 检查结果

| 身份/页面 | 实测结果 |
| --- | --- |
| 客服桌面 | 登录、案件列表、已完成详情、真实资金120.00/80.00及5笔历史可读；应收/应付编辑和导入入口可见 |
| 客服导入 | 真实本人批次展开显示3行RECORDED/DUPLICATE/RECORDED、原编号NO-MATCH保留、人工匹配历史和原文件下载入口 |
| 客服deadline | 补充独立合成待办；表单可填中国时间/原因，保存过去期限后显示已逾期，历史有原因，自动提醒仍关闭 |
| 管理员桌面 | 同案件资金只读；目标/流水写按钮及导入面板数量均0；独立投诉历史可见内部说明 |
| 网点H5 | 当前派单详情/维修证据/结算可读，仅应付80.00及2笔PAY历史，无应收/内部备注；个人通知换行可读 |
| 车主H5 | 已完成案件详情、收车/原评价及客服纠正、投诉和有权附件可见；没有报价/资金入口；空投诉显示错误而未新增记录 |
| 窄屏横向布局 | 390下页面宽375、360下345（扣除滚动条），检查样本无页面横向溢出；长UUID可换行 |
| H5进度历史 | 初始FAIL：长操作名与右侧时间相交；修复后360/390复查PASS，时间独立行且中国时区格式可读 |

数据准备复用阶段8真实HTTP脚本，在独立schema的14组HTTP链路PASS后保留自己的JAR供浏览器检查。本轮没有重跑完整19单元/62PG回归，既有结果见PHASE8_VERIFICATION；此次源码仅H5展示与管理端局部加载指令变化，未改后端、数据库、金额或权限。

## 修复与相关检查

h5-web/src/features/workorders/WorkOrderDetailView.vue：进度记录移除Vant右侧时间value，将操作/原因及time放在label两条独立行；说明overflow-wrap:anywhere，日期按Asia/Shanghai显示。保留原datetime值和原状态，不改变业务操作。

修复后H5 lint、vue-tsc类型检查、11测试和Vite生产构建全部PASS，零失败/跳过。管理端额外发现v-loading未注册，WorkOrdersView局部声明vLoading=ElLoading.directive，避免无效加载遮罩；随后管理端lint/typecheck/21测试/build全部PASS，重新浏览案件列表，Failed to resolve directive警告查询为空。管理端构建仍有大于500kB分包提醒，未放宽阈值。未增加镜像实现的测试；360/390实际截图作为显示修复证据。PowerShell视觉数据启动脚本语法检查及git diff --check另检查。后台日志/独立合成数据在第二阶段local-validation，测试密码通过必填VisualPassword参数传入，不写入脚本/报告；验收后只停止本轮创建的前后端进程，PG/MinIO/schema/对象保留。

## 可用性改进清单（未实施完整UI设计）

| 优先级 | 观察与影响 | 后续建议 |
| --- | --- | --- |
| P2 | 报价、导入与处理历史仍直接显示JSON和状态枚举；普通业务人员理解成本高 | 转为业务列名表格、中文状态与可展开技术详情 |
| P2 | 验证面板多、详情页较长；站内通知放在页面末尾 | 后续UI阶段采用分区导航和固定通知入口 |
| P2 | 部分原生按钮偏小，英文后端错误原样呈现（空投诉为英文提示） | 后续统一组件与触控尺寸、中文表单校验和错误文案 |
| P3 | 选择照片/人工匹配仍要求输入UUID | 后续用有权照片选择器与案件搜索选择，后端匹配规则保持 |

以上为当前验证面板可用性限制，未偷偷扩展到完整重设计或修改阶段决策。浏览器控制台捕获到翻译、Zotero等扩展错误及来源不明确的连接错误；未据此宣称控制台零错误，也未擅自关闭扩展。页面交互和实际数据显示正常。

## 截图证据

- [客服资金](validation/phase8-visual/customer-funds.png)、[导入行级结果](validation/phase8-visual/customer-import.png)、[deadline](validation/phase8-visual/customer-deadline.png)
- [列表loading修复](validation/phase8-visual/admin-list-loading-fixed.png)、[管理员只读台账](validation/phase8-visual/admin-ledger-readonly.png)
- [H5登录](validation/phase8-visual/h5-login.png)、[网点结算](validation/phase8-visual/shop-settlement.png)、[网点通知360](validation/phase8-visual/shop-notifications-360.png)
- [车主维修反馈](validation/phase8-visual/owner-repair.png)、[空投诉错误](validation/phase8-visual/owner-empty-complaint-error.png)
- [修复前重叠](validation/phase8-visual/h5-history-overlap-before.png)、[修复后360](validation/phase8-visual/h5-history-after-360.png)、[修复后390](validation/phase8-visual/h5-history-after-390.png)

未执行：物理手机/多浏览器/屏幕阅读器、所有工单状态的逐屏视觉覆盖、正式产品UI设计、2000行性能基准。本轮未推送/PR/部署，停在阶段8。

2026-10-10后续：上述可用性问题已完成代码修复、自动门禁和真实导入链路检查，详见[修复记录](USABILITY_FIXES.md)。本轮浏览器连接故障，新布局截图及实际移动端复查未完成；本报告旧截图不能用于确认新版视觉通过。

继续复查：内置浏览器完成新版代表性四角色/360/390交互，修复资金页面multipart请求头缺陷，见[新复查报告](USABILITY_VISUAL_RECHECK.md)。该报告覆盖新版所列页面，未宣称物理手机或全状态验收。
