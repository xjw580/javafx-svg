# JitPack 引用与验证边界

GitHub Release 成功**不等于** JitPack 构建成功。前者由本仓库 GitHub workflow 生成附件；后者按请求的 Git 标签/提交重新检出源码、执行自己的构建并提供 Maven 产物。本仓库增加 `jitpack.yml`：显式选 Java 21，以 `mvn -B -V -Pheadless clean install` 安装二进制、源码附件和 POM，保留全量测试而不采用 JitPack 默认跳过测试的 Maven 命令。

2026-10-29 的实际 JitPack 构建确认旧示例 `21-open` 已不可安装，原提交 `78da18d` 因此退回 JDK 17，并在 `Thread.ofVirtual` 编译处失败。现在固定 SDKMAN 当前可用的 Temurin `21.0.13.0+9-tem`；`sdk use` 与 Maven 用 `&&` 放在同一个 install 命令中，JDK 选择失败时不允许继续编译。此变更不改写旧提交或标签；须以新提交的实际远程构建和产物检查为准。

## javafx-ui 的候选依赖

在目标标签 **`v1.0.0-rc` 实际存在且对应 JitPack 构建已成功**之后，单模块仓库的候选坐标为：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.xjw580</groupId>
    <artifactId>javafx-svg</artifactId>
    <version>v1.0.0-rc</version>
</dependency>
```

版本取 Git 标签原文，**不要自行去掉 `v`**。源码 POM 的 `club.xiaojiawei:javafx-svg:1.0.0-rc` 是本地/Maven 打包坐标；JitPack 对外坐标由仓库和 Git 引用确定。不要将本地候选 POM 当作 JitPack 已返回的实际 POM，也不要将候选坐标写成“已可下载”。

## 依赖与业务接入

- 源码 POM 的生产依赖为 Kotlin stdlib 2.2.0 和 JavaFX graphics 21.0.8；JavaFX base、Kotlin annotations 由依赖元数据传递。controls、FXML、JUnit、Monocle 为测试依赖，不应传递给库消费者。正式验收还须检查 **JitPack 实际生成的 POM** 与消费者依赖树，不能只检查本地 POM。
- 核对的 javafx-ui 基线 `24d50fa` 使用 JavaFX 21.0.5，且已有 fxsvgimage 依赖。接入时应先统一 JavaFX modules 的版本（本候选已验证 21.0.8），检查 Maven nearest-wins 解析结果；不能让 controls/FXML 与 graphics/base 混用不同补丁版本。
- 添加 Maven 坐标只证明能解析类库，不能自动把现有 fxsvgimage 调用改成本库 API，也不能替代 javafx-ui 业务测试。这里没有修改 javafx-ui 工程。
- Java 21 字节码需要 Java 21+ 运行环境；本轮只验证 Linux Java 21 软件渲染。JavaFX 原生依赖仍须匹配消费者平台，不把 Linux 产物测试解释为 Windows/macOS/GPU 验证。

## 当前完成和未完成

本地 RC 的独立 Java/Kotlin/JPMS 消费者及 FX 检查已有记录（见 `VALIDATION.md`）。本次配置检查验证 JDK 21、headless 测试、install 生命周期和 POM scopes。2026-10-28 在隔离源码副本执行一次相同 `-B -V -Pheadless clean install` 生命周期（另加离线模式、当前 settings 与本地缓存路径），因 `org.apache.maven.plugins:maven-install-plugin:3.0.1` 未缓存而退出 1，发生于编译/测试执行前。日志为交付证据中的 `install-offline.log`；这是本环境离线插件解析阻塞，不是代码测试失败，也没有推翻既有 verify/消费者检查。未绕过网络限制补装插件，不能用手工复制 Maven 目录冒充实际 install。

**旧 RC 提交的远端 JitPack 构建已实际失败；新的 JDK 配置仍待远程验证。** `v1.0.0-rc` 标签尚未创建。可通过固定提交 SHA 请求 JitPack 构建并核对产物，不能把配置检查当成远端成功。公开仓库的正常 JitPack 依赖不需要为此新建 PAT；私有仓库有单独权限/订阅要求，本方案没有创建凭据或配置私有访问。

待明确授权发布后，应依次确认：标签指向已验证提交；GitHub Actions/Release 的实际结果；JitPack 对该精确版本的构建日志及成功状态；实际下载 POM/JAR/source JAR；独立 Maven 消费者的依赖树和运行结果；最后在 javafx-ui 工程验收。失败时保留日志，不移动既有发布标签来掩盖旧产物。

官方参考（2026-10-28 核对）：
- [构建、Maven install、Java 版本与自定义命令](https://docs.jitpack.io/building/)
- [单模块坐标、Git 引用及按需构建](https://docs.jitpack.io/)
- [只读构建状态 API](https://docs.jitpack.io/api/)
