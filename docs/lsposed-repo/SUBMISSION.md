# LSPosed 官方仓库自动同步

官方仓库： https://github.com/Xposed-Modules-Repo/io.github.luckylca.stagelab

## 首次设置

1. 接受官方仓库邀请。在官方仓库网页的 About 中将 Description 设置为 `StageLab Native`，Website 设置为源码仓库链接。
2. 创建有权写入官方仓库 Contents 的 GitHub token。优先使用仅授权目标仓库的细粒度令牌（若组织支持）；否则使用具有 public_repo 权限的 classic PAT（需符合组织策略）。在源码仓库 Settings → Secrets and variables → Actions 添加 `LSPOSED_REPO_TOKEN`。不要将 token 提交到 Git 或公开。
3. 运行源码仓库 Actions 中的 Sync LSPosed Repository 工作流一次。

## 后续发布

源码仓库 main 分支中增加版本号及 `docs/releases/<versionName>.md` 后，Build & Release StageLab Native 自动测试、编译、签名并发布。其成功完成后，Sync LSPosed Repository 自动同步 README、SUMMARY、SCOPE、SOURCE_URL、LICENSE，并拉取上游最新正式 APK，校验 SHA-256，发布相同 Tag 的官方 Release。已存在的 Release 验证摘要后跳过，不覆盖 APK。

官方仓库无需运行 Actions，也无需存放源码或签名密钥。

首个正式版本：`1-1.0.0`，`StageLab-Native-1.0.0.apk`。
