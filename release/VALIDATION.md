# 1.0.0-rc 候选验证（2026-10-28）

本记录对应准备阶段，不是公开发布证明。原始基线为 `7e35a44eec3aa69b8fd1672c1f3666d8d47d36cc`。最终补丁重建、源码树身份、逐字节可复现结果和全部 SHA-256 见交付包根目录 `verification.json`；本页记录已经执行的构建、实际候选消费者及演示检查。

- 干净全量：**811/811**，失败/错误/跳过均为0；相对806基线新增5项API契约回归。
- 实际候选二进制：220个主类，Java21字节码；不含Demo、JUnit、JavaFX/Kotlin依赖副本。
- 源码附件：60份生产Kotlin源码逐字节匹配；主JAR和源码JAR中的LICENSE/NOTICE均与仓库一致；POM/manifest版本为1.0.0-rc。
- 独立Java和Kotlin消费者从实际JAR编译/运行，未使用项目target/classes、test-classes或friend路径；解析、缓存、只读集合、流所有权、resolver及FX像素/清空生命周期通过。Kotlin访问internal的负例以预期诊断拒绝。
- Java命名模块编译/运行通过，固定模块名为`club.xiaojiawei.svg`。旧自动模块名`javafx.svg`必须迁移，不能把classpath二进制兼容解读为旧module-info不需修改。
- 对12个已记录公开类型比较实际JVM描述符：删除0项，新增`SvgException(String)`和`SvgDiagnostic(String)`两项。针对旧JAR编译的Java/Kotlin例图字节码不重新编译、仅替换候选JAR后运行通过。这不是所有历史版本和所有行为的完整ABI认证。
- 实际Demo入口`SvgDemoKt --smoke-show`运行通过38个条目，检查独立节点、动画帧、主题、源码页、响应布局和本地加载；类加载记录确认使用本次候选JAR。已检查中文界面截图。
- 4个Python测量工具的JAR路径解析已适配RC版本：9项路径测试、帮助入口和语法检查通过；**本轮没有重跑性能计时**，不会将此前不稳定的计时结果转换为本候选性能承诺。

## 环境与诚实边界

本轮是Linux x86_64、Java21.0.12.1、Kotlin2.2.0、JavaFX21.0.8、Monocle软件后端。当前精简JDK保留编译器模块但缺少ct.sym，初次独立javac --release21尝试明确失败并保留日志；验证工具随后显式核对实际Java21，再以-source21/-target21完成编译。普通完整JDK仍默认使用--release21，不能在更高JDK用该替代模式冒充Java21 API验证。

Demo通过同一已编译入口直接运行，而不是声称离线缓存缺失的Maven exec插件已验证。字体为环境已有Noto CJK，只用于演示界面；不表示SVG嵌入字体支持。Windows/macOS/GPU/真实HiDPI和javafx-ui业务工程接入未验证，独立消费者不能替代实际下游验收。

工程准备之外仍需确认发布渠道、命名空间/签名/凭据及公开tag/Release/上传/推送授权。项目许可已经按所有者指示与javafx-ui一致采用MPL-2.0，测试素材原许可保留。未进行任何公开发布动作。
