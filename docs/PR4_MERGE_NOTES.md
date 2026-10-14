# PR #4 冲突解决与数据库使用说明

## 原因与合并方式

OCR分支基于2000f61。main后来新增38dca0d与c6c9820，修改相同的创建表单及文档插入位置，引发WorkOrdersView.vue、LOCAL_DEVELOPMENT.md、PROGRESS.md三处文本冲突。

将最新main合并进功能分支，保留双方提交，不rebase、不强推、不合并PR。建单页同时保留OCR原图/预填/人工冲突选择/原业务提交与main的区域分页；独立OCR复核页同步适配分页API。文档保留双方说明与验收历史。main的D-044工作流决策保留，OCR决策唯一编号改为D-045，规则不变。

## V15撞号及已确认处理

main的V15为车主联系/取消通知；OCR分支原V15为OCR与建单前资料。Git会自动保留两个文件，却无法检测Flyway版本重复。用户已明确批准：只把OCR文件重编号为V16，SQL内容完全不变；保留旧数据库。main的V15及其他已共享迁移内容均不修改。

- 空数据库：正常执行全部迁移至V16。
- 已运行main V15（owner_contact_cancellation_notifications）的数据库：正常追加V16；集成测试另验证15→16且V15 checksum不变。
- 已运行原OCR V15（baidu_ocr_and_pre_case_intake）的旧数据库：不能直接指向合并后的迁移集。保留该库及旧运行包，本轮不执行repair、不改flyway_schema_history、不清库、不搬移真实原件。合并版使用新的隔离schema验证。以后若需迁移这些旧OCR环境，须先备份并单独确认带数据/历史保留的升级方案。

当前本机已授权真实OCR任务及原始材料仍位于原隔离库，运行目录不切换、不重启、不增加百度请求。新的冲突解决工作区只进行离线模拟、合成数据与新的测试schema验证。