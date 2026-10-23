# javafx-svg

将 SVG 文档解析为 JavaFX 原生场景图的 Kotlin/JVM 库。使用 JavaFX 公共 API，不依赖 javafx-web、WebView、Swing 或 Batik。

构建基线：JDK 21+、Maven 3.8.8+；Kotlin 2.4.20，JavaFX 21.0.8。生成的字节码目标为 Java 21。

## 构建与示例

```shell
mvn clean verify
mvn install
mvn -Pdemo test-compile exec:java
```

示例展示同一个 SVG 文档的原始样式和应用 CSS 后的样式，悬停可改变描边颜色。示例代码和资源位于 `src/test`，不进入库 JAR。

IDEA 中重新加载 Maven 项目后，打开 `src/test/kotlin/club/xiaojiawei/svg/demo/SvgDemo.kt`，运行文件末尾的顶层 `main`，入口类为 `club.xiaojiawei.svg.demo.SvgDemoKt`。旧的 `SvgDemoLauncher` 运行配置已不适用。

Demo 使用 classpath 启动，项目没有 `module-info.java`。不要为它配置 `--module club.xiaojiawei.svg` 或 `--add-modules club.xiaojiawei.svg`；这会导致 `Module club.xiaojiawei.svg not found`。POM 不声明 `Automatic-Module-Name`，避免 IDEA 将编译输出目录误识别成可直接启动的命名模块。

不显示窗口的示例检查：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke"
```

生成 `target/demo-preview.png`。渲染测试及示例仍需要 JavaFX 图形环境；Linux 无显示器环境可使用 `xvfb-run -a mvn verify`。测试不会打开窗口。

### 加载 javafx-ui 的实际图标

当 `javafx-ui` 与本项目位于同一父目录时，运行：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--javafx-ui"
```

IDEA 也可以直接选择项目自带的 `SVG Gallery - javafx-ui` 运行配置，或给 `SvgDemoKt` 添加程序参数 `--javafx-ui`。

预览程序直接读取 `../javafx-ui/src/main/resources/club/xiaojiawei/controls/images`，不修改源 SVG。每页 24 个图标，并排显示原始效果与 CSS 换色效果；白色及透明填充保留，以免遮掉内部细节。支持悬停换色、按钮或左右方向键翻页、单击图标查看诊断。

也可加载任意 SVG 目录，递归扫描其中的 SVG 文件：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--svg-dir ../javafx-ui/src/main/resources/club/xiaojiawei/controls/images"
```

批量检查并导出每页截图和逐文件诊断：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--javafx-ui --smoke"
```

输出位于 `target/svg-gallery/page-N.png` 和 `target/svg-gallery/report.tsv`。检查包含加载失败数、图形数、非透明像素数及不支持内容的诊断；有加载失败或空白图标时返回失败。非空绘制不代表完整 SVG 规范兼容，诊断仍须检查。

## 使用

依赖（先执行本地 `mvn install`；当前为本地 SNAPSHOT，尚未发布到 Maven Central）：

```xml
<dependency>
    <groupId>club.xiaojiawei</groupId>
    <artifactId>javafx-svg</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Kotlin，在 JavaFX Application Thread 上创建视图：

```kotlin
import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgView
import java.nio.file.Path

val document = SvgLoader.load(Path.of("icon.svg"))
val icon = SvgView(document).apply {
    fitWidth = 32.0
    fitHeight = 32.0
    styleClass += "toolbar-icon"
}
container.children.add(icon)
scene.stylesheets.add(javaClass.getResource("/icons.css")!!.toExternalForm())
```

Java：

```java
SvgDocument document = SvgLoader.load(Path.of("icon.svg"));
SvgView icon = new SvgView(document);
icon.setFitWidth(32);
icon.setFitHeight(32);
icon.getStyleClass().add("toolbar-icon");
container.getChildren().add(icon);
```

支持 `SvgLoader.parse(String)`、`load(Path)`、`load(URL)`、`load(InputStream)`；`SvgView` 也提供对应的静态便利方法。调用方传入的流不会被关闭，路径及 URL 方法自行关闭其创建的流。

`SvgLoader` 不创建 JavaFX 节点，可在后台执行。缓存并复用 `SvgDocument` 即可避免重复解析；每个 `SvgView(document)` 会创建独立节点，分别接受 CSS，不应跨视图复用节点。库没有全局缓存，也不会启动后台线程。

`SvgView` 是可布局的 `Region`。fitWidth/fitHeight 决定首选尺寸，0 表示采用自然尺寸，父布局仍可调整实际尺寸。需要固定图标尺寸时，同时设置 min/maxSize。默认遵循 SVG 的 preserveAspectRatio；`preserveRatio = false` 将 viewBox 拉伸到视口。没有 viewBox 时，调整的是视口，绝对坐标不会自动缩放。

可通过 `view.document = nextDocument` / `setDocument` 替换内容；失败时保留原文档和节点。`documentProperty()` 是只读观察属性。fitWidth、fitHeight、preserveRatio 提供标准 JavaFX 属性方法。

## JavaFX CSS

外层视图带有 `svg-view` 样式类，每个图形有 `svg-shape`，每种元素带有 `svg-元素名`，例如 `svg-path`、`svg-rect`。源 SVG 的 id 和 class 会映射到对应节点。

```css
/* 修改图标的所有路径填充 */
.toolbar-icon .svg-path {
    -fx-fill: #409eff;
}

/* 修改指定路径的描边颜色、宽度和端点 */
.toolbar-icon #outline {
    -fx-fill: null;
    -fx-stroke: #333333;
    -fx-stroke-width: 2;
    -fx-stroke-line-cap: round;
    -fx-stroke-line-join: round;
}

/* JavaFX 支持的伪类可用于应用侧样式表 */
.toolbar-icon:hover #outline {
    -fx-stroke: #e67700;
}

.toolbar-icon:disabled {
    -fx-opacity: 0.4;
}
```

还支持 Shape 自带的虚线、描边连接、填充等样式。源 SVG 的属性及样式先计算为节点初始值，应用的 JavaFX CSS 可以覆盖它们，包括源 SVG 内联样式中的值。

SVG 内部样式和应用 JavaFX CSS 是两套语法：SVG 使用 `fill/stroke/stroke-width`，应用使用 `-fx-fill/-fx-stroke/-fx-stroke-width`。普通 Group/Region 没有 fill 属性，必须用选择器定位图形；本版本没有自定义 `-svg-fill` 属性。

线宽表示描边宽度，随节点变换一起缩放；无法直接改变填充路径自身的几何厚度。多色图标建议按 id/class 修改，避免对所有图形统一覆盖颜色。CSS 替换填充时提供的是新的完整 Paint，源 fill-opacity 不会额外乘到新颜色上，半透明覆盖可使用 rgba()。

## 支持范围

| 类型 | 已实现 |
| --- | --- |
| 图形 | path（M/L/H/V/C/S/Q/T/A/Z，大小写与重复参数）、rect/圆角、circle、ellipse、line、polyline、polygon |
| 容器和布局 | svg、嵌套 svg、g、a 的静态内容；viewBox、preserveAspectRatio 的对齐及 meet/slice/none |
| 坐标 | matrix、translate、scale、rotate、skewX/Y；无单位数、px、%、in、cm、mm、pt、pc、q |
| 样式 | 属性、style 属性、style 元素；类型/class/id/复合/后代/逗号选择器；继承、优先级、!important、currentColor |
| 描边 | 颜色、宽度、透明度、端点、连接、miterlimit、虚线 |
| 引用 | 本文档内 defs/use/symbol、href/xlink:href；独立实例、循环和展开数量限制 |
| 渐变 | linearGradient、radialGradient、stop；objectBoundingBox/userSpaceOnUse、spreadMethod、模板引用、线性渐变变换 |
| 裁剪 | clipPath、userSpaceOnUse/objectBoundingBox、clip-rule、嵌套裁剪 |
| 文本 | 单段 text、x/y/dx/dy、字体/字号/粗细/斜体、text-anchor、xml:space；字号支持 em 和相对父字号的百分比 |
| 图片 | image 中的 base64 PNG/JPEG，preserveAspectRatio，视口裁剪 |

当前不支持完整 SVG 规范。限制包括：

- 不执行脚本、事件处理器、SMIL/CSS 动画，不渲染 foreignObject。
- 不支持 filter、mask、pattern、marker、non-scaling-stroke、radialGradient 的 gradientTransform 和非零 fr。
- SVG 内部 CSS 不支持子/兄弟/属性/伪类选择器、CSS 变量、at-rule、外部样式表；应用侧 JavaFX CSS 按 JavaFX 自身规则处理。
- 不支持 tspan、textPath、逐字位置列表、复杂排版或嵌入字体；文本外观依赖系统字体。
- 不自动加载 image/use/gradient/clipPath 中的外部文件或网络引用；load(URL) 仅打开调用方明确提供的顶层地址。
- 未实现的属性不构成完整的规范校验。严格模式用于拒绝已检测到的错误或不支持内容，不是 SVG 全规范验证器。

## 诊断与输入限制

```kotlin
val document = SvgLoader.parse(xml, SvgOptions(strict = true))
val view = SvgView(document)
```

默认宽松模式会跳过无法渲染的元素或使用指定的填充回退颜色，并通过 `view.diagnostics` 返回诊断。严格模式会抛出 `SvgException`。XML 格式错误、重复 ID、DTD 和超限输入始终拒绝。

默认最大文档 8 MiB、元素深度 128、元素数量 100,000；引用展开也受限制。DTD、外部实体和 XML 外部资源访问已禁用。

## 验证

测试覆盖解析限制、坐标矩阵、样式优先级、实例隔离、JavaFX CSS 覆盖与伪类、百分比布局、引用循环、渐变、文本和图片。使用 JavaFX 离屏快照验证透明度叠加及裁剪区域的像素。

```shell
mvn clean verify
mvn dependency:tree "-Dincludes=org.openjfx"
```

核心运行依赖仅为 Kotlin 标准库和 javafx-graphics（传递引入 javafx-base）；构建会禁止引入 javafx-web。测试及示例 PNG 导出使用的 JUnit/Java2D 不属于库运行接口。
