# 阶段8合成数据验证辅助脚本

仅用于Start-Phase8-VisualQA.ps1启动的隔离开发环境（8080）；不连接生产，不真实付款。从仓库根目录运行，Python依赖requests/openpyxl，输出仍在仓库相邻的第二阶段local-validation。密码在运行时传入，禁止将值写入源码或提交。

- prepare_ui_review.py：创建到店合成案件和CSV冲突批次，输出ui-review-fixtures.json。重复判断遵守原门禁、合成继续原因留审计。首次无继续原因时服务端拒绝400，已补原因；不清理历史测试数据。
- verify_ui_results.py：在按USABILITY_VISUAL_RECHECK完成浏览器交互后，独立读取核对金额、版本、人工匹配、照片引用和车主资金403。初版拼接不完整待办键导致404，已去掉该无效查询；deadline预填/历史由真实界面检查覆盖。
- verify_xlsx_2000.py：新建独立合成应收案件，上传2000行XLSX，逐页核对首次RECORDED/第二次DUPLICATE、净额2000.00和台账2000行。会新增测试记录，保留原账目，不删除schema或文件。

运行示例：python scripts/validation/prepare_ui_review.py <本次临时测试密码>。对应XLSX和结果脚本亦用相同位置传入本次测试密码。脚本不会保存密码/Token到成果文件。
