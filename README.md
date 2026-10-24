# javafx-svg

将 SVG 文档解析为 JavaFX 原生场景图的 Kotlin/JVM 库。使用 JavaFX 公共 API，不依赖 javafx-web、WebView、Swing 或 Batik。

构建基线：JDK 21+、Maven 3.8.8+；Kotlin 2.4.20，JavaFX 21.0.8。生成的字节码目标为 Java 21。

## 构建与示例

```shell
mvn clean verify
mvn install
mvn -Pdemo test-compile exec:java
```

示例提供 13 项特性画廊，涵盖图形/描边、渐变、裁剪、文字、引用、Alpha/亮度遮罩、模糊、视口适配、资源缓存和 FXML。支持三种主题、80%/100%/120% 预览缩放及悬停换色。窗口缩小时自动换列，可用滚轮或 Page Up/Down、Home/End 滚动；Tab 和 Enter/Space 操作按钮。默认全部离线，点击 Load network 才加载网络徽章并显示异步状态。详见 [Demo 使用说明](demo/README.md)。示例代码和资源位于 `src/test`，不进入库 JAR。

可见窗口交互基准（运行约 90 秒，完成后自动关闭窗口）：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --count=200 --warmup-seconds=2 --measure-seconds=5"
```

基准覆盖普通路径、渐变、文字和模糊滤镜，分别执行裁剪网格滚动、逐图标缩放、每个 pulse 全量 CSS 换色；滚动场景不包含 ListView 虚拟化。默认每组 200 个视图，共享同一文档，窗口中只显示网格的一部分。路径、渐变和滤镜样本各有 12 条路径，文字样本有 4 个 Text，不能视为相同复杂度的横向比较。

结果保存在 `target/render-benchmark/时间戳/`，也可用 `--output=目录` 指定新目录：包含汇总及逐 pulse CSV、GC 计数与累计收集时间、JFR 采样、环境信息和预览图。JFR 中的 `club.xiaojiawei.svg.RenderBenchmarkPhase` 事件标出正式测量区间，可排除场景创建和预热。截图仅在预热前生成；运行时请保持窗口可见且不被遮挡，关闭窗口会中断测试，只有完整运行才生成 `completed.txt`。

`pulse_*` 是 AnimationTimer 回调间隔，能反映 FX 调度卡顿，但不是 GPU 耗时或显示器实际呈现帧率；`work_p95_ms` 仅包含前一个回调中属性修改的耗时，不包含后续 CSS、布局及绘制。长帧按间隔超过 33.33 ms、50 ms 统计。GC 时间为 MXBean 收集耗时，并非精确停顿时间。基准显式覆盖的高频 CSS 换色属于压力场景。

首次测量结果见 [2026-10-24 渲染基线](benchmarks/render-baseline-2026-10-24.md)。

缓存对照可以限制类型、动作和缓存策略，例如：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--benchmark --kinds=gradient --motions=scale,transform,css --cache=scale"
mvn -Pdemo test-compile exec:java "-Dexec.args=--cache-visual-check --output=target/cache-visual-check-1"
```

`--cache` 支持 `off`（默认）、`scale`、`quality`，作用于每个 SvgView；`--kinds` 可选 `path,gradient,text,blur`，`--motions` 可选 `scroll,scale,transform,css`，均支持逗号分隔的子集。`scale` 通过 resize 改变视口，`transform` 固定视口大小并改变以左上角为中心的 Scale 变换。两者可能有不同的缓存失效成本。视觉检查会先预热缓存，再比较原始大小、放大、缩小、非整数缩放及 CSS 换色后的画面，输出设备缩放比例下的截图和像素差异；请为它指定空目录。

[节点缓存对照结果](benchmarks/cache-comparison-2026-10-24.md)：当前样本中，SCALE 缓存能改善固定视口的变换动画，但持续 resize 没有收益，放大还会出现位图模糊。SvgView 已继承 JavaFX 的 `isCache`、`cacheHint`，可在短时动画中按需启用，动画结束时关闭以恢复目标尺寸的矢量绘制。Scale 变换只改变显示效果，不改变 Region 的布局尺寸；库保持默认关闭缓存。

IDEA 中重新加载 Maven 项目后，打开 `src/test/kotlin/club/xiaojiawei/svg/demo/SvgDemo.kt`，运行文件末尾的顶层 `main`，入口类为 `club.xiaojiawei.svg.demo.SvgDemoKt`。旧的 `SvgDemoLauncher` 运行配置已不适用。

Demo 使用 classpath 启动，项目没有 `module-info.java`。不要为它配置 `--module club.xiaojiawei.svg` 或 `--add-modules club.xiaojiawei.svg`；这会导致 `Module club.xiaojiawei.svg not found`。POM 不声明 `Automatic-Module-Name`，避免 IDEA 将编译输出目录误识别成可直接启动的命名模块。

不显示窗口的示例检查：

```shell
mvn -Pdemo test-compile exec:java "-Dexec.args=--smoke"
```

生成 `target/demo-preview.png`（蓝色100%）、`target/demo-preview-themed.png`（绿色120%）和 `target/demo-preview-compact.png`（紫色80%、紧凑布局），并检查主题、路径覆盖、缩放、滚动及本地资源清空/恢复，不访问网络。渲染测试及示例仍需要 JavaFX 图形环境；Linux 无显示器环境可使用 `xvfb-run`。测试不会打开窗口。

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

`SvgLoader` 不创建 JavaFX 节点，可在后台执行。缓存并复用 `SvgDocument` 即可避免重复解析；每个 `SvgView(document)` 会创建独立节点，分别接受 CSS，不应跨视图复用节点。需要按地址复用时可使用下述有界资源缓存；通过 `source` 加载网络地址时会使用后台虚拟线程。

同一个 `SvgDocument` 还会在线程安全的首次初始化中缓存 SVG 选择器、规则索引和内联样式声明，后续视图直接复用。缓存随文档释放，不持有视图或 JavaFX 节点；继承、`use` 实例上下文及应用侧 JavaFX CSS 仍逐实例计算。每个视图独立报告诊断，严格模式检查不会因缓存而跳过。重复调用 `parse/load` 会创建不同文档，不能共享这份缓存。

路径几何也按需缓存：相同 `d` 的校验结果、相同 `points` 转换出的路径字符串可在文档内复用，折线与多边形分别缓存。未渲染元素不会提前触发几何校验；失败结果仍由每个视图按当前元素报告。JavaFX `SVGPath` 节点及其内部几何处理保持独立，百分比尺寸、变换、填充规则和描边仍按实例处理。

重复布局时，仅依赖视口的几何回调在其所属视口宽高改变后重新执行；固定 `viewBox` 的图标缩放主要更新外层变换。根变换按尺寸和 `preserveRatio` 更新，边界裁剪复用变换对象。文字、滤镜及边界裁剪仍参与刷新，以响应相同尺寸下的 CSS 字体、颜色和几何变化。

长度解析复用预编译正则，并在文档内按需缓存数值与单位。坐标、描边、渐变、滤镜区域和字体百分比可复用解析结果；百分比每次按当前参考尺寸求值，保留单位换算及非有限结果检查。缓存不保存求值后的百分比或视图引用，也不提前校验未使用元素。

`SvgView` 是可布局的 `Region`。fitWidth/fitHeight 决定首选尺寸，0 表示采用自然尺寸，父布局仍可调整实际尺寸。需要固定图标尺寸时，同时设置 min/maxSize。默认遵循 SVG 的 preserveAspectRatio；`preserveRatio = false` 将 viewBox 拉伸到视口。没有 viewBox 时，调整的是视口，绝对坐标不会自动缩放。

可通过 `view.document = nextDocument` / `setDocument` 替换内容；失败时保留原文档和节点。`documentProperty()` 是只读观察属性。fitWidth、fitHeight、preserveRatio 提供标准 JavaFX 属性方法。


### 资源缓存

地址级复用可使用线程安全的 `SvgResourceCache`：

```kotlin
val cache = SvgResourceCache() // 128 项、源 SVG 合计 16 MiB、写入后 5 分钟过期
val first = cache.load(Path.of("icon.svg"))
val second = cache.load(Path.of("icon.svg")) // 同一份 SvgDocument
val firstView = SvgView(first)
val secondView = SvgView(second) // 独立节点，CSS 互不影响
```

`load(Path/URL, SvgOptions)` 是阻塞方法，远程地址应在后台调用。缓存键包含地址和完整 SvgOptions，不会把宽松模式或不同输入限制的结果用于另一组配置。`SvgLoader.parse/load` 保持直接解析、读取，不使用此缓存；调用方传入的流仍由调用方管理。

- 本地文件和 `jar:file:` 资源保存解析成功的文档；每次命中先检查文件/外层 JAR 的大小、修改时间及文件标识。文件变化或过期后重新读取。元数据完全相同的修改可手动 invalidate。
- 已完成文档采用 LRU 淘汰，构造参数 `maxEntries`、`maxSourceBytes`、`expireAfter` 可调整限制；任一项设为 0 都关闭已完成文档保留。`sourceBytes` 是原始输入字节总和，不是精确 JVM 内存占用。
- 同地址、同选项的正在进行的请求会合并下载和解析。HTTP 等远程资源在完成后不保留；下一次独立加载仍会请求服务器，适用于动态徽章。
- 单个等待者被中断，只取消它自己的等待；最后一个等待者退出后中断共享后台线程。网络 I/O 不一定立即响应中断，仍受连接/读取超时约束，过期请求结果不会回填缓存。
- 下载、解析失败不保存，可直接重试。`clear()` 清理全部缓存；`invalidate(Path/URL)` 清理该地址的全部选项。已在等待的调用可正常完成，但清理后启动的调用不会加入旧请求，旧请求也不会重新填入缓存。

可使用 `cache.size` / `cache.sourceBytes` 查看当前保留量，或显式使用 `SvgResourceCache.shared` 共享默认实例。缓存仅持有文档，不持有 JavaFX 节点或视图。

`SvgView.source` 默认使用 `SvgResourceCache.shared`，FXML 无需额外配置即可共享本地文档及合并正在进行的请求。地址仍可修改，新地址取消当前视图的旧等待；其他视图或调用方的共享请求继续执行。已完成网络资源不保留，重新赋相同 URL 仍会请求服务器；本地命中时复用文档，但每次赋值仍创建新节点。

可在指定 source 前设置 `view.resourceCache = customCache`，或设为 `null` 禁用该视图后续请求的缓存与合并。缓存实例在每次 source 赋值时捕获；更换 resourceCache 不影响现有内容和已经开始的请求。需要强制重读元数据相同的本地文件时，先 `view.resourceCache?.invalidate(url)`，再重新赋 source。清空视图不清理共享缓存，清理缓存也不会清空现有视图。

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

SVG 内部样式和应用 JavaFX CSS 是两套语法：SVG 使用 `fill/stroke/stroke-width`，应用使用 `-fx-fill/-fx-stroke/-fx-stroke-width`。普通 Group/Region 没有 fill 属性；SvgView 额外提供下述统一主题属性，也可继续用选择器定位具体图形。

统一主题属性直接作用于 SvgView，默认已有 `svg-view` 样式类，无需再设置 styleClass：

```css
.svg-view {
    -svg-fill: #409eff;
    -svg-stroke: #333333;
    -svg-stroke-width: 2;
}

.svg-view:hover {
    -svg-fill: #e67700;
}

/* 内部图形的 JavaFX CSS 优先于统一主题 */
.svg-view #accent {
    -fx-fill: purple;
}
```

| CSS 属性 | Kotlin/Java 属性 | 默认值及恢复方式 |
| --- | --- | --- |
| `-svg-fill` | `svgFill` / `svgFillProperty()` | `null`：使用源 SVG 填充 |
| `-svg-stroke` | `svgStroke` / `svgStrokeProperty()` | `null`：使用源 SVG 描边 |
| `-svg-stroke-width` | `svgStrokeWidth` / `svgStrokeWidthProperty()` | `-1`：使用源 SVG 线宽；`0`：隐藏描边 |

填充和描边接受 JavaFX Paint（颜色或渐变），支持 looked-up color。设置统一填充会替换已有填充，包括原有渐变和多色填充；原本 `fill="none"` 的路径保持镂空，若要为它填充，应显式设置该路径的 `-fx-fill`。统一描边可为原本无描边的图形添加描边；仅设置线宽不会自动产生描边。剪裁路径和图片像素不参与统一换色。

优先级为：图形绑定属性、图形的应用侧 `-fx-*` CSS（含内联样式）优先于统一主题；统一主题优先于源 SVG 样式。移除主题 CSS 或将属性恢复默认值后，使用当前视口对应的源值，保留独立的路径 CSS。统一 Paint 是完整替换，不额外叠乘源 `fill-opacity/stroke-opacity`，与直接设置 `-fx-fill/-fx-stroke` 一致。线宽通常以 SVG 用户单位表示，随视口变换缩放；non-scaling-stroke 图形按视口逻辑像素解释，缩放时保持线宽；不支持 SVG 百分比字符串，属性值只允许 -1 或有限非负数。

主题属性支持绑定，绑定后的属性不被根节点 CSS 覆盖；所有读写仍在 FX 线程上执行：

```kotlin
icon.svgFill = Color.web("#409eff")
icon.svgStrokeWidth = 2.0
icon.svgFillProperty().bind(themePaintProperty) // ObservableValue<Paint?>
// 取消覆盖前先解除绑定
icon.svgFillProperty().unbind()
icon.svgFill = null
icon.svgStrokeWidth = -1.0
```

FXML 也可设置这些 JavaBean 属性，并继续由 JavaFX CSS 覆盖：

```xml
<SvgView source="@icon.svg" svgFill="#409eff"
         svgStroke="#333333" svgStrokeWidth="2"/>
```

统一主题会保留在视图上，后续同步/异步加载或替换 document 时应用于新节点。主题未启用的视图保持原有样式；同一文档的不同视图互不影响。Demo 的 Theme & path CSS 卡片展示根主题与路径 CSS 的组合，悬停改变统一描边色，紫色勾选保留自己的路径样式。

线宽表示描边宽度，随节点变换一起缩放；无法直接改变填充路径自身的几何厚度。多色图标建议按 id/class 修改，避免对所有图形统一覆盖颜色。CSS 替换填充时提供的是新的完整 Paint，源 fill-opacity 不会额外乘到新颜色上，半透明覆盖可使用 rgba()。

## 支持范围

| 类型 | 已实现 |
| --- | --- |
| 图形 | path（M/L/H/V/C/S/Q/T/A/Z，大小写与重复参数）、rect/圆角、circle、ellipse、line、polyline、polygon |
| 容器和布局 | svg、嵌套 svg、g、a 的静态内容；viewBox、preserveAspectRatio 的对齐及 meet/slice/none |
| 坐标 | matrix、translate、scale、rotate、skewX/Y；无单位数、px、%、in、cm、mm、pt、pc、q |
| 样式 | 属性、style 属性、style 元素；类型/class/id/复合/后代/逗号选择器；继承、优先级、!important、currentColor |
| 描边 | 颜色、宽度、透明度、端点、连接、miterlimit、虚线、non-scaling-stroke |
| 引用 | 本文档内 defs/use/symbol、href/xlink:href；独立实例、循环和展开数量限制 |
| 渐变 | linearGradient、radialGradient、stop；objectBoundingBox/userSpaceOnUse、spreadMethod、模板引用、线性渐变变换 |
| 裁剪 | clipPath、userSpaceOnUse/objectBoundingBox、clip-rule、嵌套裁剪 |
| 遮罩 | alpha/luminance（默认）；区域、两种坐标单位、嵌套遮罩、CSS 与引用 |
| 滤镜 | 单个 feGaussianBlur；独立 X/Y 标准差、SourceGraphic/SourceAlpha、filterUnits/primitiveUnits、输入区域及输出子区域裁剪 |
| 文本 | text/嵌套 tspan、单值 x/y/dx/dy、字体/字号/粗细/斜体、text-anchor、xml:space；单段文字支持 textLength/lengthAdjust；字号支持 em 和相对父字号的百分比 |
| 图片 | image 中的 base64 PNG/JPEG，preserveAspectRatio，视口裁剪 |

### 遮罩

支持本地 `mask: url(#id)`，包含 `mask-type="alpha"` 和默认的 `luminance`。Alpha 模式按遮罩合成结果的透明度控制源图可见程度，颜色本身不影响 Alpha：

```xml
<svg viewBox="0 0 100 100">
    <defs>
        <mask id="half" mask-type="alpha" maskContentUnits="objectBoundingBox">
            <rect width="1" height="1" fill="black" opacity=".5"/>
        </mask>
    </defs>
    <circle cx="50" cy="50" r="40" fill="#409eff" mask="url(#half)"/>
</svg>
```

支持 `maskUnits`（默认 objectBoundingBox）、`maskContentUnits`（默认 userSpaceOnUse）、区域 x/y/width/height（SVG 1.1 默认 -10%/-10%/120%/120%），以及嵌套 mask、clipPath、use 和元素变换。objectBoundingBox 使用不含描边、滤镜及裁剪的几何边界；零或负区域尺寸隐藏源图。只允许本文档内的单个遮罩引用；外部地址、遮罩模板和多层 mask shorthand 暂不支持。循环、深度和引用展开数量受限制。

遮罩使用定义处继承的 SVG 样式，不继承引用者的样式。`mask` 元素本身不直接显示，其 display/opacity/filter 不作用于整个遮罩；子元素仍正常应用这些属性。遮罩内容保留在 JavaFX CSS 树中，可用 `.svg-mask .svg-shape` 或 id 定位；统一 `-svg-*` 主题不会改写遮罩颜色或线宽。

实现使用 JavaFX 快照生成 Alpha 图像，再由原生 clip 合成；无需 WebView 或 Java2D。图像按当前场景变换与窗口输出比例采样，内容和尺寸未变时复用。超高倍率离屏导出仍可能重采样现有遮罩图像。每个视图所有遮罩共用 `SvgOptions.maxMaskPixels` 像素预算（默认 4,194,304），每张图像单轴最多 4096 像素；这是稳态像素数量限制，不是精确堆内存上限。超限严格模式抛异常，宽松模式报告诊断并隐藏受影响图形；缩小后可恢复。

省略 mask-type 或指定 luminance 时，按最终合成图像的非预乘 RGB 计算亮度，再乘透明度：`alpha × (0.2126 R + 0.7152 G + 0.0722 B)`。白色保留、黑色隐藏、灰色和半透明颜色产生中间透明度。支持遮罩上的 color-interpolation（默认 sRGB，auto 按 sRGB；linearRGB 先对最终像素做线性化再计算亮度）。这只控制遮罩最后的颜色转透明度步骤，不扩展现有绘制器的渐变插值及混色能力。颜色或字体 CSS 更新后会重新生成图像，普通布局复用已有图像。

语义依据 [SVG Masking](https://www.w3.org/TR/css-masking-1/#MaskElement) 与 [luminanceToAlpha](https://www.w3.org/TR/filter-effects-1/#element-attrdef-fecolormatrix-type)。

含 `tspan` 的 `text` 按原始顺序渲染文字与嵌套片段，支持继承字体、填充、描边、透明度，以及单值 `x/y/dx/dy`。没有绝对坐标的片段接续当前文字位置，新的 `x` 或 `y` 开始独立锚定的文字块，可配合 `dy` 手动换行。`xml:space` 跨片段处理；渐变按整段文字的几何边界计算。

```xml
<text x="20" y="30" font-size="20" fill="#334155">
    Hello <tspan id="accent" fill="#2563eb" font-weight="bold">SVG</tspan>
    <tspan x="20" dy="26">第二行</tspan>
</text>
```

含片段的文字使用 Group 保留层级：根节点为 `.svg-text`，片段为 `.svg-tspan`，实际原生 Text 为 `.svg-text-run.svg-shape`。源 id/class 保留在对应容器上；应用 CSS 用后代选择器设置文字外观：

```css
#accent .svg-text-run {
    -fx-fill: #ef4444;
    -fx-stroke: #7f1d1d;
    -fx-stroke-width: 0.5;
    -fx-font-size: 24px;
}
```

修改字体后会重新计算后续位置和整块锚点，根 `-svg-*` 主题也作用于文字片段。单段、无 `tspan` 的文字仍直接使用原有 Text 样式节点。

当前片段排版支持单行水平的拉丁、汉字等非上下文文字；可用绝对坐标和偏移组织多行。暂不支持 `tspan` 与 `textLength/lengthAdjust` 组合、逐字位置列表、旋转、双向或跨片段连写排版，以及片段自身的 transform/filter/mask/clip-path；整段 text 仍支持变换、滤镜、遮罩和裁剪。上述已检测到的不支持内容在严格模式抛出异常，宽松模式报告诊断并保留自然文字排版。规则参考 [SVG 文本](https://www.w3.org/TR/SVG11/text.html)。

`vector-effect="non-scaling-stroke"` 支持 path、基本图形与文字（含 tspan/textLength），也可在源 SVG 的内联 style、内部样式表中设置。线宽、虚线及端点/连接在最外层 SVG 视口坐标系中计算，支持 viewBox 缩放、嵌套 SVG、引用图形、旋转、非等比缩放、倾斜和镜像。

```xml
<path d="M10 10L30 20L50 10" fill="none" stroke="#2563eb"
      stroke-width="2" vector-effect="non-scaling-stroke"/>
```

该属性默认 none，**不自动继承**；在组内可用 `path { vector-effect: non-scaling-stroke; }` 统一指定，或在子图形上显式 inherit。引用时放在被引用的图形上；直接设置在 use 上暂不支持，会报告诊断。clipPath 只使用填充几何，固定描边对其无效。

原 Shape 作为隐藏的样式节点保留 id/class 和应用 CSS 属性，`-fx-stroke/-fx-stroke-width`、绑定及根 `-svg-stroke/-svg-stroke-width` 优先级保持一致。固定描边的数值线宽以 SVG 视口的逻辑像素解释；例如值 2 在 SvgView resize 时仍保持 2 逻辑像素。SvgView 自身或应用父节点的 JavaFX Scale 属于 SVG 外部变换，仍会缩放整个控件，系统 HiDPI 映射也不改变逻辑像素定义。

实现使用 JavaFX 原生路径轮廓，先在宿主坐标系描边，再映射回原坐标填充，因此渐变描边仍按源图形坐标绘制；填充、透明度、裁剪、遮罩和滤镜可组合使用。每个固定描边增加三个场景节点，计入 `maxElements`；轮廓按需缓存，几何、描边参数或内部缩放/旋转矩阵变化时重新生成，适合图标和中小规模图形。非可逆的变换不绘制描边，恢复有效尺寸后可继续显示。语义参考 [SVG non-scaling-stroke](https://www.w3.org/TR/SVG2/painting.html#NonScalingStroke)。

`textLength` 支持数值、长度单位和百分比，默认 `lengthAdjust="spacing"` 只调整字素簇间距，`spacingAndGlyphs` 同时缩放字形的横向尺寸。支持文本锚点、视口尺寸更新及应用 CSS 修改字体、填充和描边。单个字素簇在 spacing 模式下保持自然宽度。带 textLength 的文字由多个关联 Text 节点绘制，源 id/class 和 `.svg-text` 仍用于样式控制。

spacing 模式当前支持单行水平的拉丁、汉字等非上下文文字；双向文字、复杂连写和强制换行会报告诊断，宽松模式回退到自然排版。spacingAndGlyphs 保留整段文字的 JavaFX 原生字形处理。文字展开节点计入 `maxElements` 限制。

基础高斯模糊通过 JavaFX 原生效果实现，支持 SVG 属性、内联 style 和样式表中的 `filter: url(#id)`。`stdDeviation` 可为一个或两个数值；0 关闭对应方向的模糊，负值使该原语退回输入图像。滤镜坐标先于元素变换计算，滤镜输入按 filter 区域裁剪，输出再按原语子区域和 clipPath 裁剪。应用 CSS 修改填充或字体后会更新效果。

支持 `edgeMode="none"` 和单个 `feGaussianBlur`，暂不支持其他滤镜原语、滤镜链、模板引用、filterRes 和 duplicate/wrap 边缘扩展。sRGB/auto 支持多色输入；默认 linearRGB 仅支持单色或 SourceAlpha 输入，多色线性 RGB 合成会报告诊断并在宽松模式下保留未滤镜处理的原图。较大的标准差通过分解方差执行多轮模糊，最多 16 轮，超限会报告诊断而不会静默截断。

Shields 徽章回归样本位于 `src/test/resources/svg/shields-release.svg`，可在严格模式下渲染；测试使用固定资源，不依赖网络或 GitHub 版本变化。

当前不支持完整 SVG 规范。限制包括：

- 不执行脚本、事件处理器、SMIL/CSS 动画，不渲染 foreignObject。
- 不支持完整 filter 管线、pattern、marker、radialGradient 的 gradientTransform 和非零 fr；vector-effect 目前只支持 none/non-scaling-stroke。
- SVG 内部 CSS 不支持子/兄弟/属性/伪类选择器、CSS 变量、at-rule、外部样式表；应用侧 JavaFX CSS 按 JavaFX 自身规则处理。
- 不支持 textPath、逐字位置列表、复杂排版或嵌入字体；tspan 的组合限制见文本说明，文本外观依赖系统字体。
- 不自动加载 image/use/gradient/clipPath 中的外部文件或网络引用；load(URL) 仅打开调用方明确提供的顶层地址。
- 未实现的属性不构成完整的规范校验。严格模式用于拒绝已检测到的错误或不支持内容，不是 SVG 全规范验证器。

## 诊断与输入限制

```kotlin
val document = SvgLoader.parse(xml, SvgOptions(strict = true))
val view = SvgView(document)
```

默认宽松模式会跳过无法渲染的元素或使用指定的填充回退颜色，并通过 `view.diagnostics` 返回诊断。严格模式会抛出 `SvgException`。XML 格式错误、重复 ID、DTD 和超限输入始终拒绝。

默认最大文档 8 MiB、元素深度 128、元素数量 100,000；引用展开也受限制。遮罩另受 maxMaskPixels 像素预算约束。DTD、外部实体和 XML 外部资源访问已禁用。

## 验证

测试覆盖解析限制、坐标矩阵、样式优先级、实例隔离、JavaFX CSS 覆盖与伪类、百分比布局、引用循环、渐变、文本和图片。使用 JavaFX 离屏快照验证透明度叠加及裁剪区域的像素；网络加载测试使用本地 HTTP 服务验证 UI 响应、超时、失败回退及请求切换，不依赖外网。

```shell
mvn clean verify
mvn dependency:tree "-Dincludes=org.openjfx"
```

核心运行依赖仅为 Kotlin 标准库和 javafx-graphics（传递引入 javafx-base）；构建会禁止引入 javafx-web。FXML 集成测试使用的 javafx-fxml，以及测试及示例 PNG 导出使用的 JUnit/Java2D 不属于库运行接口。
