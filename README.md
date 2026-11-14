# javafx-svg

`javafx-svg` 是一个基于 Kotlin/JVM 的 SVG 渲染库，可将 SVG 文档转换为 JavaFX 原生场景图，无需 WebView、Swing 或 Batik。

## SVG 特性

- 基本图形、路径、变换和视口，以及文档内元素引用。
- 填充与描边、线性和径向渐变、图案、裁剪及遮罩。
- 文字、沿路径文字，以及部分文字定位和排版功能。
- 模糊、投影、颜色变换、混合、卷积和光照等滤镜。
- 部分 SMIL 动画、SVG 内嵌 CSS 动画，以及 JavaFX CSS 样式。
- 部分 `foreignObject` XHTML 内容及显式授权的外部图片和样式表。

项目实现了 SVG 的常用子集，并非完整的浏览器 SVG 引擎。

## 使用

先在项目源码目录执行 `mvn install`，将库安装到本地 Maven 仓库，然后添加依赖：

```xml
<dependency>
    <groupId>club.xiaojiawei</groupId>
    <artifactId>javafx-svg</artifactId>
    <version>1.0.0-rc</version>
</dependency>
```

也可以通过 JitPack 引用 Git 标签对应的版本：

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
    <version>1.0.0-rc</version>
</dependency>
```

JitPack 版本号应与 Git 标签一致，能否下载取决于对应版本的 JitPack 构建结果。

在 JavaFX 应用线程中加载 SVG 并添加到容器：

```kotlin
import club.xiaojiawei.svg.SvgView
import java.nio.file.Path

val icon = SvgView.load(Path.of("icon.svg")).apply {
    fitWidth = 32.0
    fitHeight = 32.0
}
container.children.add(icon)
```

基于 JDK 21 和 JavaFX 21，采用 [MPL-2.0](LICENSE) 许可证。
