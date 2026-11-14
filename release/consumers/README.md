# 独立消费者验证

在干净构建得到真实库 JAR 后，运行独立 Java/Kotlin 编译和执行；不把本项目的 `target/classes`、`target/test-classes` 或 Kotlin friend paths 放入消费者类路径。

```shell
python3 release/consumers/verify.py --project . --jar target/javafx-svg-1.0.0-rc.jar --maven-repository /path/to/.m2/repository --javafx-platform linux --output /tmp/javafx-svg-consumer-check --fx
```

输出目录必须不存在。脚本离线运行，缺少依赖时明确失败，不下载、不发布。`--fx` 使用额外的 test-only Monocle 后端；实际库依赖仍只有 JavaFX graphics/base、Kotlin stdlib 及其 annotations 依赖，没有 JUnit、controls 或 fxml。编译器自身有另外记录的依赖，不会混入消费者运行类路径。

验证内容：
- Java：静态加载重载、默认构造器、只读文档、流所有权、资源 resolver SAM 和缓存失效；`--fx` 另验证实际像素快照与清空生命周期。
- Kotlin：在独立编译单元调用命名/默认参数、options.copy、诊断解构、resolver SAM 及缓存；负例确认 `internal` 类不能从独立 Kotlin 消费者使用。
- Java JPMS：命名模块实际编译和执行，验证固定 automatic module name `club.xiaojiawei.svg`。消费者显式解析 JavaFX graphics 与 Kotlin stdlib 模块；自动模块不等于已提供强封装的 module-info。
- 保存使用的 JAR、依赖 SHA256、完整编译/运行命令、结果及 `javap -public -s` 签名，便于候选版间比较。javap 包含部分 JVM 暴露的 internal/synthetic 成员，不能把它们自动当作支持 API。

本轮运行环境和结果由发布验证报告记录；脚本存在并不表示 Windows/macOS、其他 JDK 或 javafx-ui 已经过验证。

可选 `--baseline-jar /path/to/previous.jar`：对十二个文档列明的公开类型比较 `javap` 成员描述符，并将兼容的 Java 源例与 Kotlin 源例针对旧 JAR 编译，然后不重新编译消费者字节码、仅替换为 RC JAR 执行。Java 旧版例图使用原本已有的两参数异常/诊断构造器。这是选定调用面的二进制链接检查，不是所有历史版本及全部行为的兼容认证；自动模块名称变更单独记录，不包含在 classpath 兼容结论中。

如果精简的 Java 21 环境保留编译器模块但没有 `javac`/`javap` 命令，脚本使用对应 `java -m` 工具入口。若缺失 `ct.sym` 导致 `--release 21` 不可用，可显式加 `--native-jdk21`；脚本先检查实际运行时 `java.specification.version=21`，然后使用 `-source 21 -target 21` 编译，并在结果中记录该模式。不能在较新 JDK 上用此开关冒充 Java 21 API 验证。
