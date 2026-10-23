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

`SvgLoader` 不创建 JavaFX 节点，可在后台执行。缓存并复用 `SvgDocument` 即可避免重复解析；每个 `SvgView(document)` 会创建独立节点，分别接受 CSS，不应跨视图复用节点。库没有全局缓存；通过 `source` 加载网络地址时会使用后台虚拟线程。

`SvgView` 是可布局的 `Region`。fitWidth/fitHeight 决定首选尺寸，0 表示采用自然尺寸，父布局仍可调整实际尺寸。需要固定图标尺寸时，同时设置 min/maxSize。默认遵循 SVG 的 preserveAspectRatio；`preserveRatio = false` 将 viewBox 拉伸到视口。没有 viewBox 时，调整的是视口，绝对坐标不会自动缩放。

可通过 `view.document = nextDocument` / `setDocument` 替换内容；失败时保留原文档和节点。`documentProperty()` 是只读观察属性。fitWidth、fitHeight、preserveRatio 提供标准 JavaFX 属性方法。

## FXML 使用

`SvgView` 提供无参构造和 `source` 属性，可以直接声明 SVG 地址，无需在 Controller 中手动加载：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import club.xiaojiawei.svg.SvgView?>
<?import javafx.scene.control.Button?>

<Button xmlns:fx="http://javafx.com/fxml/1" text="打开" stylesheets="@icons.css">
    <graphic>
        <SvgView fx:id="icon" source="@icons/open.svg" styleClass="toolbar-icon"
                 fitWidth="24" fitHeight="24"
                 minWidth="24" minHeight="24" maxWidth="24" maxHeight="24"/>
    </graphic>
</Button>
```

例如 FXML 位于 `src/main/resources/club/xiaojiawei/TipNode.fxml`，上面的 SVG 应放在 `src/main/resources/club/xiaojiawei/icons/open.svg`，CSS 放在 FXML 同目录的 `icons.css`。保留原有 `fx:root` 结构时，将 `graphic` 部分放入对应 Button 即可，并在 FXML 顶部导入 `SvgView`。

```css
.toolbar-icon .svg-path {
    -fx-fill: #409eff;
    -fx-stroke: #333333;
    -fx-stroke-width: 2;
}
```

`@icons/open.svg` 相对于当前 FXML；`@/icons/open.svg` 相对于 classpath 根目录（`src/main/resources/icons/open.svg`）。这是 [FXML 的地址解析语法](https://openjfx.io/javadoc/21/javafx.fxml/javafx/fxml/doc-files/introduction_to_fxml.html#location_resolution)。加载相对资源时必须向 `FXMLLoader` 提供 FXML 的 URL：

```kotlin
val loader = FXMLLoader(javaClass.getResource("/club/xiaojiawei/TipNode.fxml"))
// fx:root 自定义组件还需保留 loader.setRoot(this) 和 loader.setController(this)。
```

应用需要自行引入 `javafx-fxml`，上例 Button 还需要 `javafx-controls`，版本应与应用 JavaFX 一致。使用命名模块时，Controller 所在包需要 `opens ... to javafx.fxml`。

`source` 接收完整 URL 字符串，FXML 的 `@` 会自动转换；Kotlin/Java 中使用 `getResource(...).toExternalForm()`。加载使用默认 `SvgOptions`：本地 `file:` / `jar:file:` 资源同步加载，HTTP(S) 等其他地址在后台下载和解析，仅节点创建及属性更新在 FX 线程执行。本地大文件和严格模式可自行在后台用 `SvgLoader` 加载，再在 FX 线程设置 `document`。`sourceProperty()` 提供只读观察，不支持绑定写入。

网络地址直接写入 FXML，无需 `@`：

```xml
<SvgView source="https://img.shields.io/github/release/xjw580/Hearthstone-Script.svg"/>
```

网络加载期间保留旧图标，`source` 表示本次请求地址。通过只读 `loadingProperty()` / `isLoading` 和 `loadExceptionProperty()` / `loadException` 观察状态；异步失败保留旧文档和节点，通过异常属性报告，不会从 FXML 加载方法抛出。新请求会清空旧异常，重复设置同一 URL 可重试。本地加载失败仍同步抛出异常并保留原内容和地址。

```kotlin
icon.loadExceptionProperty().addListener { _, _, error ->
    if (error != null) println("SVG 加载失败：${error.message}")
}
icon.loadingProperty().addListener { _, _, loading ->
    println("SVG 加载中：$loading")
}
icon.source = "https://img.shields.io/github/release/xjw580/Hearthstone-Script.svg"
```

切换地址、将 `source` 设为 null/空白字符串或直接设置 `document` 时会取消旧任务；过期结果不会覆盖新内容。清空 `source` 同时清空视图，直接设置 `document` 会清空 `source`。连接超时 5 秒，单次读取等待超时 10 秒；这不是整个请求的总时限。后台任务使用 Java 21 虚拟线程，不阻止应用退出。

`SvgLoader.load(URL)` 和静态 `SvgView.load(URL)` 仍为同步方法，也使用上述连接/读取超时；自动异步加载由 `source` 属性提供。

可直接加载的 FXML 示例位于 `src/test/resources/demo/icon.fxml`，测试 `SvgFxmlTest` 验证资源加载、CSS 覆盖和失败回退。

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
| 滤镜 | 单个 feGaussianBlur；独立 X/Y 标准差、SourceGraphic/SourceAlpha、filterUnits/primitiveUnits、输入区域及输出子区域裁剪 |
| 文本 | 单段 text、x/y/dx/dy、字体/字号/粗细/斜体、text-anchor、xml:space、textLength/lengthAdjust；字号支持 em 和相对父字号的百分比 |
| 图片 | image 中的 base64 PNG/JPEG，preserveAspectRatio，视口裁剪 |

`textLength` 支持数值、长度单位和百分比，默认 `lengthAdjust="spacing"` 只调整字素簇间距，`spacingAndGlyphs` 同时缩放字形的横向尺寸。支持文本锚点、视口尺寸更新及应用 CSS 修改字体、填充和描边。单个字素簇在 spacing 模式下保持自然宽度。带 textLength 的文字由多个关联 Text 节点绘制，源 id/class 和 `.svg-text` 仍用于样式控制。

spacing 模式当前支持单行水平的拉丁、汉字等非上下文文字；双向文字、复杂连写和强制换行会报告诊断，宽松模式回退到自然排版。spacingAndGlyphs 保留整段文字的 JavaFX 原生字形处理。文字展开节点计入 `maxElements` 限制。

基础高斯模糊通过 JavaFX 原生效果实现，支持 SVG 属性、内联 style 和样式表中的 `filter: url(#id)`。`stdDeviation` 可为一个或两个数值；0 关闭对应方向的模糊，负值使该原语退回输入图像。滤镜坐标先于元素变换计算，滤镜输入按 filter 区域裁剪，输出再按原语子区域和 clipPath 裁剪。应用 CSS 修改填充或字体后会更新效果。

支持 `edgeMode="none"` 和单个 `feGaussianBlur`，暂不支持其他滤镜原语、滤镜链、模板引用、filterRes 和 duplicate/wrap 边缘扩展。sRGB/auto 支持多色输入；默认 linearRGB 仅支持单色或 SourceAlpha 输入，多色线性 RGB 合成会报告诊断并在宽松模式下保留未滤镜处理的原图。较大的标准差通过分解方差执行多轮模糊，最多 16 轮，超限会报告诊断而不会静默截断。

Shields 徽章回归样本位于 `src/test/resources/svg/shields-release.svg`，可在严格模式下渲染；测试使用固定资源，不依赖网络或 GitHub 版本变化。

当前不支持完整 SVG 规范。限制包括：

- 不执行脚本、事件处理器、SMIL/CSS 动画，不渲染 foreignObject。
- 不支持完整 filter 管线、mask、pattern、marker、non-scaling-stroke、radialGradient 的 gradientTransform 和非零 fr。
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

测试覆盖解析限制、坐标矩阵、样式优先级、实例隔离、JavaFX CSS 覆盖与伪类、百分比布局、引用循环、渐变、文本和图片。使用 JavaFX 离屏快照验证透明度叠加及裁剪区域的像素；网络加载测试使用本地 HTTP 服务验证 UI 响应、超时、失败回退及请求切换，不依赖外网。

```shell
mvn clean verify
mvn dependency:tree "-Dincludes=org.openjfx"
```

核心运行依赖仅为 Kotlin 标准库和 javafx-graphics（传递引入 javafx-base）；构建会禁止引入 javafx-web。FXML 集成测试使用的 javafx-fxml，以及测试及示例 PNG 导出使用的 JUnit/Java2D 不属于库运行接口。
