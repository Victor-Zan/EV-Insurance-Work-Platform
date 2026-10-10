# 可用性修复后的浏览器复查

2026-10-10，继续用户授权的第8阶段复查和必要修复；独立schema phase8_visual_afb7c54748954da89382b6e5a1ca9dc6，真实PostgreSQL/MinIO/API，全部为合成资料。未修改业务规则或后端代码，未推送、部署或清空数据。

## 结果

Edge仍无法加载浏览器连接策略，改用Codex内置浏览器完成以下范围。桌面截图1265×约720；H5按实际DOM核对360/390视口，360时页面内容宽345，未横向溢出。

| 项目 | 结果与证据 |
|---|---|
| 客服报价明细及分区导航 | PASS；数量2/5、零金额行可读，内部与对外金额列明确；[截图](validation/usability-visual/customer-quote.png) |
| 人工匹配 | PASS；搜索EVR-20261010-000001、显式选择、填写原因、更正历史保留，确认后新增1.00元；[历史](validation/usability-visual/manual-match-history.png) |
| 截止时间 | PASS；保存2026-10-12 14:30中国时间，再次编辑预填同值，历史保留；[截图](validation/usability-visual/deadline-preserved.png) |
| 网点原始报价H5 | PASS；1.001被中文提示拒绝，1.23×3真实提交v1=3.69，开修四条件缺失时按钮禁用；[360截图](validation/usability-visual/shop-quote-360.png) |
| 车主照片选择与投诉 | PASS；空正文拒绝，有效照片勾选后真实提交，原文不可改，照片引用1张；[360](validation/usability-visual/owner-photo-360.png)、[390](validation/usability-visual/owner-photo-390.png) |
| 车主字段/面板范围 | PASS；无报价及资金面板，照片仅有效有权照片；独立API资金接口403 |
| 管理员台账 | PASS；只读，无新增目标、流水、冲销或导入按钮；[截图](validation/usability-visual/admin-readonly.png) |
| CSV页面上传 | 修复后PASS；真实文件选择器成功建立预览，冲突行待人工、不猜归属；[截图](validation/usability-visual/csv-upload-fixed.png) |
| XLSX页面上传 | PASS；文件选择器上传2000行合成XLSX，完整预览。编号来自其他隔离schema，转待人工，不执行记账；[截图](validation/usability-visual/xlsx-2000-preview.png) |

浏览器操作后用独立API读取核对：[结果](validation/usability-visual/ui-api-results.json)，报价3.69、实收121.00、台账6笔、人工匹配RECORDED、投诉1照片、预览2000行、车主资金403。照片选择不是仅“页面成功”，已核对服务端投诉照片引用。通知顶部可展开，修改期限有明确历史；当前截图不代表所有任务类型、所有状态均已逐屏验证。

## 新发现和修复

首次通过资金导入页面上传CSV失败，后端16:33:00记录MultipartException，前端显示请求失败。共用Axios默认application/json把FormData转成JSON；之前直接HTTP上传通过未覆盖此缺陷。两端create-client的请求拦截器现在遇FormData清除默认Content-Type，保持文件内容，让浏览器生成multipart boundary；JSON登录/业务请求仍保留原行为。

两端各新增真实FormData/Blob回归测试，验证file字节仍存在且请求不以application/json发送。初版setContentType(undefined)被TS类型检查拒绝，改用AxiosHeaders.delete；未放宽类型检查。之后页面CSV与XLSX真实上传通过。首次失败及类型错误保留本节；后端运行日志在第二阶段local-validation/phase8-visual-runtime.log。

## 自动验证

修复后两端lint、typecheck、test、build全部PASS。管理端25测试，H5 15测试，无失败/跳过，日志在validation/usability-visual/*-{lint,typecheck,test,build}.log。后端本轮未改；隔离环境启动时14条真实HTTP链路再次PASS（[记录](validation/usability-visual/http-recheck.json)），并执行浏览器提交后的独立API核对；19单元/62 PG集成/Flyway完整门禁仍引用上一轮通过结果，未无理由重复完整构建。

捕获的管理员与车主页面warn/error日志为空；客服刷新后的捕获日志也为空。这仅覆盖当前捕获窗口，不能抹去首次上传已确认的错误，也不宣称所有浏览器所有页面永远零错误。

## 边界和剩余

此次完成新版代表性界面和交互复查，替代USABILITY_FIXES关于“新版界面复查未完成”的结论，历史失败不删除。未执行：物理手机、Edge修复后复验、多浏览器、屏幕阅读器、所有状态逐屏、并发压力/生产部署。现有其他模块部分状态记录/材料状态仍显示英文代码，诊断型OCR/Mock地图仍有原验证输出，未称全产品中文化或正式UI定稿。完成/取消只读规则与独立投诉/资金例外保持。

结束时停止本轮验证前后端，不删除schema/MinIO文件，所有截图报告保留在第二阶段网站搭建。正常启动按LOCAL_DEVELOPMENT执行后端及两端npm run dev。
