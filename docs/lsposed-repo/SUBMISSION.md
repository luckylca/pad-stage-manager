# 提交至官方 LSPosed 模块仓库（Xposed-Modules-Repo）流程

本目录包含用于推送到官方分发仓库 `Xposed-Modules-Repo/io.github.luckylca.stagelab` 的全部纯元数据文件（与 `WidgetEnhancer` 流程一致）。

## 1. 源码仓库自动发版（已配置好）

当 `luckylca/pad-stage-manager` 推送匹配 `<versionCode>-<versionName>` 的标签（例如 `1-1.0.0`）时，GitHub Actions 会自动：
1. 执行 `:core:test`、`assembleDebug` 与 `assembleRelease`；
2. 校验 Release APK 证书指纹与 `45a7a172c03d567cd910b304c2c83d43257c5fd988385712ad30589dd5661b70` 一致；
3. 自动创建或更新 GitHub Release `1-1.0.0`，挂载 `StageLab-Native-1.0.0.apk` 与 `docs/releases/1.0.0.md` 发布说明。

## 2. 提交官方仓库收录申请

1. 前往 `https://github.com/Xposed-Modules-Repo/submission/issues/new` 提交收录 Issue：
   - **Title**：`[submission] io.github.luckylca.stagelab`
   - 填写源码仓库 `https://github.com/luckylca/pad-stage-manager` 与模块说明即可（采用 GitHub 用户名前缀，无需独立域名）。
2. 收到 Bot 邀请成为 `Xposed-Modules-Repo/io.github.luckylca.stagelab` 仓库管理员并点击 **Accept invitation**。
3. 在 GitHub 网页端为 `Xposed-Modules-Repo/io.github.luckylca.stagelab` 填写 **About / Description**（必填，即模块在 LSPosed 中的展示名称，例如：`StageLab Native`）。

## 3. 初始化分发仓库并自动同步 Release

在任意空目录执行（也可由我代劳推送）：

```bash
git clone https://github.com/Xposed-Modules-Repo/io.github.luckylca.stagelab.git
cd io.github.luckylca.stagelab
cp <源码目录>/LICENSE .
cp <源码目录>/docs/lsposed-repo/README.md .
cp <源码目录>/docs/lsposed-repo/SCOPE .
cp <源码目录>/docs/lsposed-repo/SOURCE_URL .
cp <源码目录>/docs/lsposed-repo/SUMMARY .
mkdir -p .github/workflows
cp <源码目录>/docs/lsposed-repo/publish-release.yml .github/workflows/publish-release.yml
git add -A
git commit -m "init: LSPosed module metadata and release mirror workflow"
git push -u origin main

# 同步首个版本 Release（从上游 luckylca/pad-stage-manager 拉取并校验 SHA-256 后自动发布）
git tag 1-1.0.0
git push origin 1-1.0.0
```
