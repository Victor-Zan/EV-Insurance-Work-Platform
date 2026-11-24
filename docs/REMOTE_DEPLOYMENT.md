# 从 GitHub 发布正式平台

入口：仓库 **Actions → Remote production deployment → Run workflow**，分支选 **main**。
目前发布操作员：`Victor-Zan`、`xuerui522-lab`。其他有写权限的成员也须加入工作流的操作员列表。

## 操作选项

| operation | 用途 |
| --- | --- |
| deploy | 验证、构建并发布当前 main；成功后显示服务器版本 |
| check | 完整构建并上传、验证发布包，保持正式版本不变 |
| status | 显示当前版本、上一版本和后端服务状态 |
| rollback | 恢复上一版程序及页面；不覆盖生产数据库 |

代码先合并进 main，再运行 deploy。构建过程中 main 有新提交时，服务器会拒绝旧提交，需重新运行。失败日志在该次 Actions 运行中查看；服务器内部证据由运维保留。不要复制数据库或模型密钥到构建配置。

## 服务器约定

- 地址 `106.55.251.21`，发布身份 `ev-deploy`。已固定服务器 ED25519 公钥，不关闭 SSH 主机校验。
- GitHub 仓库 Secret `EV_DEPLOY_SSH_KEY` 保存专用发布私钥。只能由受信任的发布流程使用，不是 ubuntu 管理账号密钥。
- 发布身份仅允许 `upload/check/publish <完整提交SHA>`、`status`、`rollback previous`，无交互式 shell、SFTP/SCP、端口或代理转发。上传使用 SSH 标准输入。
- root 拥有的发布程序校验命令、上传大小、归档路径及逐文件摘要。发布目录 `/var/lib/ev-insurance/deployment/releases`；状态文件和服务器私有备份不向发布账号开放。
- 当前管理端和后端一起发布，H5构建产物保留在对应版本中；不在本次新增H5公网路由。
- 生产环境变量仍为 `/etc/ev-insurance/backend.env`，不会被发布包替换。既有用户、案件、附件、候选网点和价格数据不通过源码发布重新初始化。

## 数据库与恢复边界

普通发布只允许数据库迁移 SQL 与当前运行包一致。新增或修改迁移文件的版本会在停服前被拒绝；这类版本需另行进行数据库备份、影子库演练和兼容性评审，再更新发布基线。

切换时先进入维护状态、停止后端并备份数据库和服务配置，再启动新程序、更换网页入口，核对内部及HTTPS健康。失败时恢复旧程序和旧入口。恢复也失败时保留维护状态，需运维处理。

回退不意味着恢复历史业务数据。回退只恢复应用和网页，数据库继续保留用户在正式运行期间的业务记录。未来涉及数据结构或含义变化的代码，须另行评估兼容性。

## 本地手动上传

优先使用 Actions。需要从本人电脑直接上传时，由运维另行登记个人 SSH 公钥，不能复用 ubuntu 私钥。发布包须来自已经通过工作流验证的同一 main 提交。

```sh
ssh -T -i PRIVATE_KEY -o StrictHostKeyChecking=yes -o UserKnownHostsFile=ops/deployment/known_hosts ev-deploy@106.55.251.21 "upload FULL_COMMIT_SHA" < release.tar.gz
ssh -T -i PRIVATE_KEY -o StrictHostKeyChecking=yes -o UserKnownHostsFile=ops/deployment/known_hosts ev-deploy@106.55.251.21 "check FULL_COMMIT_SHA"
ssh -T -i PRIVATE_KEY -o StrictHostKeyChecking=yes -o UserKnownHostsFile=ops/deployment/known_hosts ev-deploy@106.55.251.21 "publish FULL_COMMIT_SHA"
```

服务器发布程序由运维审查后安装，不能随前端包自行替换。私钥更换须同步更新服务器授权公钥和 GitHub Secret。
