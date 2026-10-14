# Windows 本地网站快捷入口

本目录保留已验证的三个快捷脚本。它们需要放在仓库上级的“第二阶段网站搭建”目录使用，与 `EV-Insurance-Work-Platform` 文件夹并列；不要直接在本目录运行。

如上级尚无同名文件，从仓库根目录执行：

```powershell
Copy-Item scripts/local-shortcuts/启动本地网站.ps1 ../
Copy-Item scripts/local-shortcuts/停止本地网站.ps1 ../
Copy-Item scripts/local-shortcuts/检查本地网站.ps1 ../
```

已有文件时先比较，保留自己的修改。当前本机上级已是本目录对应版本，无需再次复制。脚本调用仓库 `scripts/Manage-Website.ps1`；配置文件仍放在本机第二阶段 `local-validation/.env` 和 `.env.phase5`，不上传 GitHub。

使用与恢复步骤见 [本地网站使用说明](../../docs/本地网站使用说明.md)。
