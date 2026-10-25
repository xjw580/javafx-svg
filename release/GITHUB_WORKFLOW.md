# GitHub 构建与候选发布

## 与 javafx-ui 保持一致的部分

2026-10-28 核对 `xjw580/javafx-ui@24d50fa61683a6d8209e629191a53cfe21ef11fe` 的 `.github/workflows/build.yml`：推送 `v*.*.*` 标签触发、Ubuntu / Temurin 21 / Maven 构建、使用内置 `GITHUB_TOKEN` 创建 GitHub Release。实际 `v3.0.0` Release 无附件且正文为空；其 workflow 引用了不存在的正文步骤。因此本项目保持发布渠道和触发方式一致，但不复制这些缺陷。UI 的私有 Nexus distributionManagement 不属于该 GitHub 流程，本项目不复制，也不部署 Maven Central。

## 本候选流程

`.github/workflows/build.yml` 目前只接受精确的 `v1.0.0-rc`，并要求 POM 为 `1.0.0-rc`、仓库为 `xjw580/javafx-svg`、标签和当前提交一致。未来版本必须先更新配置及测试，不能复用旧包冒充新版本。

1. 只读构建任务：固定提交检出；检查标签和已有 release；Temurin 21；发布保护单元测试；`mvn -B -V -Pheadless clean verify`；对实际 JAR 执行独立 Java、Kotlin、模块路径及 JavaFX 消费者验证。
2. 制作六项附件：主 JAR、sources JAR、POM、该提交完整源码 ZIP、verification.json、SHA256SUMS。测试失败、跳过、消费者失败或源码未提交时拒绝制作。
3. 独立发布任务才获得 `contents: write`。再次核对远端标签与已有 release，包括可见草稿；先创建 draft/prerelease 并上传固定附件。
4. 比对 GitHub 返回的每个附件名称、状态、长度及 SHA-256 和本地清单，再执行一次公开 PATCH。保持 prerelease，`make_latest=false`，不覆盖已有附件。

Action 使用核查过的完整提交 SHA 固定版本，不依赖可移动主版本标签。跨任务传递附件、预期摘要清单和正文。源码 ZIP 来自已验证提交，不含工作区缓存或凭据。

## 权限及失败恢复

不需要新增 PAT、Maven 密码或 GPG 密钥。仓库需要启用 Actions、允许所列固定 Action，发布任务需内置 `GITHUB_TOKEN` 的 `contents: write`。组织策略若禁止写入或 Action，必须由所有者调整；本地测试不能证明远端权限已可用。

按标签直接查询的 GitHub API 看不到草稿，所以还分页扫描 release 列表；扫描上限耗尽会拒绝继续，而不是假定不存在。只读令牌可能看不到草稿，发布任务的写权限预检不可省略。

已有公开 release 或草稿均拒绝重跑。上传中断留下的草稿需先人工检查，不自动删除/覆盖。远端未返回摘要也拒绝公开。公开请求超时或响应不确定时先检查实际状态，不能盲目重试；本工具不自动重试写请求。这些保护不替代仓库管理者对并发操作的协调。

## 验证边界与操作授权

本地验证包含完整项目测试、实际产物消费者、实际六附件制作及 API 模拟故障测试。它不等于 GitHub Actions 已运行、附件已上传或 JitPack 已成功构建。当前准备任务不推送、不创建远端标签、不公开 release；实际操作仍须授权并核对原分支期间的新提交。

JitPack 是另一条源码构建链，见 [JITPACK.md](JITPACK.md)。Linux 软件渲染验证不能外推 Windows/macOS/GPU；真实 javafx-ui 业务接入仍未验证。

参考：[javafx-ui workflow](https://github.com/xjw580/javafx-ui/blob/24d50fa61683a6d8209e629191a53cfe21ef11fe/.github/workflows/build.yml)、[实际 v3.0.0 release](https://github.com/xjw580/javafx-ui/releases/tag/v3.0.0)、[GitHub workflow 权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[JitPack 构建说明](https://docs.jitpack.io/building/)。
