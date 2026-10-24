package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgDocument
import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.animation.Animation
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/** An offline-first, graphics-only SVG workbench. Network access requires an explicit button. */
class SvgDemo : Application() {
    private data class Sample(val key: String, val title: String, val group: String, val file: String, val tag: String, val description: String, val tip: String)
    private val samples = listOf(
        Sample("path-motion", "沿曲线运动", "动画", "showcase-path-motion.svg", "ANIMATE MOTION", "箭头沿曲线匀速运动并自动朝向切线，圆点展示路径缓动。", "默认自动播放；暂停、定位与重复共用现有时间轴。"),
        Sample("patterns", "图案填充与变换", "颜色与效果", "showcase-pattern.svg", "PATTERN", "重复圆点、旋转条纹与 viewBox 棋盘，展示三种图案坐标配置。", "图案保留透明区域；稳定布局复用图像，CSS 变化后刷新。"),
        Sample("shadows", "柔和与偏移投影", "颜色与效果", "showcase-shadow.svg", "DROP SHADOW", "柔和蓝色阴影与零模糊偏移阴影，保留原图填充和圆角。", "SVG 的 flood-color 与 flood-opacity 分别控制阴影颜色和透明度。"),
        Sample("path-text", "沿路径排版", "文本与布局", "showcase-text-path.svg", "TEXT PATH", "原生字形沿弧线和波浪排版，按切线旋转，支持居中锚定。", "调整缩放和主题；源文件中的 startOffset 控制文字沿路径的位置。"),

        Sample("spinner", "加载与等待", "动画", "showcase-animation-spinner.svg", "ROTATE · OPACITY", "旋转圆弧、错峰闪烁和轨道运动。拖动时间轴观察同一文档时间的不同动画。", "负 begin 形成相位差；无限循环在视图隐藏时自动暂停。"),
        Sample("breathe", "呼吸与颜色过渡", "动画", "showcase-animation-breathe.svg", "COLOR · SPLINE", "缩放和透明度组合成呼吸效果，纯色与描边属性独立插值。", "开启统一换色可观察主题覆盖；关闭后恢复当前动画颜色。"),
        Sample("draw", "路径描边与进度", "动画", "showcase-animation-draw.svg", "STROKE DASH", "曲线逐步绘制、环形进度和成功勾选。使用原生描边虚线偏移实现。", "暂停后拖动时间轴，比较起笔、绘制完成和回退阶段。"),
        Sample("motion", "变换与缓动", "动画", "showcase-animation-transform.svg", "TRANSFORM · ADDITIVE", "平移缓动、旋转与缩放叠加，以及倾斜。整个过程复用原生节点。", "多个 animateTransform 可通过 additive=sum 组合。"),
        Sample("geometry", "跳动圆点与半径", "动画", "showcase-animation-geometry.svg", "POSITION · RADIUS", "圆点同时改变半径和纵坐标，脉冲圆环与椭圆直接修改几何尺寸。", "所有图形复用原生节点；拖动时间轴查看位置与尺寸的同步变化。"),
        Sample("bars", "音量柱与尺寸变化", "动画", "showcase-animation-bars.svg", "HEIGHT · WIDTH · RX", "音量柱保持底部对齐，进度条从零宽恢复，矩形圆角连续变化。", "柱子的 y 与 height 共用时间参数，始终保持 y + height = 112。"),
        Sample("css", "CSS 关键帧动画", "动画", "showcase-css-animation.svg", "CSS @KEYFRAMES", "纯 CSS 驱动旋转、呼吸和描边进度。支持关键帧、往返播放和立方贝塞尔缓动。", "SVG 页可查看内嵌 style 源码；这些示例没有 animate 元素。"),
        Sample("css-timing", "CSS 时序与叠加", "动画", "showcase-css-animation-timing.svg", "STEPS · ANIMATION LIST", "负延迟、反向往返、步进旋转，以及两个动画同时控制颜色和缩放。", "旋转中心按 fill-box 计算，拖动时间轴比较不同动画的相位。"),
        Sample("original", "源 SVG", "图形", "sample.svg", "PATH · USE", "保留文件中的填充、描边和渐变。右侧可以直接查看对应源文件。", "对比视图共享 SvgDocument，每个视图独立创建节点。"),
        Sample("shapes", "图形与描边", "图形", "showcase-shapes.svg", "GEOMETRY", "基本图形、贝塞尔曲线、圆角、虚线，以及不同端点和连接方式。", "调整缩放与背景，观察轮廓细节及透明区域。"),
        Sample("fixed", "固定描边", "图形", "showcase-fixed-stroke.svg", "VECTOR EFFECT", "蓝色描边随视口缩放，绿色描边保持 3 个逻辑像素。", "切换 80% / 100% / 120%，比较两种线宽的变化。"),
        Sample("markers", "箭头与顶点标记", "图形", "showcase-markers.svg", "MARKER", "双向箭头、曲线顶点和固定尺寸标记。箭头可以继承路径当前颜色。", "开启统一换色，context-stroke 会同步线条与箭头。"),
        Sample("theme", "主题与路径 CSS", "颜色与效果", "sample.svg", "JAVAFX CSS", "根主题统一换色，独立路径 CSS 保留紫色勾选；悬停图标观察橙色描边。", "打开源图对比，直接比较源样式与应用侧 CSS 的优先级。"),
        Sample("gradients", "线性与径向渐变", "颜色与效果", "showcase-gradients.svg", "GRADIENT", "原生线性、径向与反射渐变，包含透明度和多个颜色停止点。", "深色背景更容易观察透明渐变边缘。"),
        Sample("clip", "几何裁剪", "颜色与效果", "showcase-clip.svg", "CLIP PATH", "圆形裁剪和 even-odd 镂空，通过几何边界控制可见区域。", "切换保持比例，观察视口拉伸对裁剪区域的影响。"),
        Sample("masks", "透明与亮度遮罩", "颜色与效果", "showcase-masks.svg", "ALPHA · LUMINANCE", "同一组黑灰白遮罩，分别按 Alpha 和亮度计算透明度。", "网格和深色背景用于辨认遮罩后的透明区域。"),
        Sample("blur", "高斯模糊", "颜色与效果", "showcase-blur.svg", "FILTER", "原图与 sRGB 高斯模糊并列展示，观察边缘扩散与滤镜区域。", "较复杂的滤镜会增加节点处理和绘制成本。"),
        Sample("text", "文字、片段与定长", "文本与布局", "showcase-text.svg", "TEXT · TSPAN", "嵌套文字片段、斜体、基线偏移，以及间距和字形缩放。", "使用原生 Text，文字外观会受系统字体影响。"),
        Sample("symbols", "符号复用", "文本与布局", "showcase-symbols.svg", "SYMBOL · USE", "一个 symbol 的多个实例，分别应用颜色、缩放和组合变换。", "每个 use 实例可独立接受 CSS。"),
        Sample("layout", "视口适配", "文本与布局", "showcase-layout.svg", "VIEWBOX", "嵌套视口比较 meet、slice 和非等比拉伸。", "保持比例开关控制最外层视口，源 SVG 内的嵌套适配规则保持有效。"),
        Sample("cache", "共享文档与独立样式", "资源", "showcase-cache.svg", "RESOURCE CACHE", "同一 source 共享解析文档，两个实例使用各自的主题和节点。", "此示例默认显示对比：蓝色与绿色视图共享文档。"),
        Sample("fxml", "直接从 FXML 使用", "资源", "icon.fxml", "FXML", "直接加载现有 FXML，相对 SVG 与样式表由 FXMLLoader 解析。", "源码页展示实际 FXML；运行时无需 Controller 手动创建 SVG。"),
        Sample("badge", "本地与网络资源", "资源", "/svg/shields-release.svg", "ASYNC SOURCE", "默认显示离线徽章。点击网络加载才访问远程地址，失败保留原内容。", "观察加载状态，也可以清空视图或重新加载本地资源。")
    )
    private val groups = listOf("动画", "图形", "颜色与效果", "文本与布局", "资源", "全部")
    private val documents = mutableMapOf<String, SvgDocument>()
    private val categoryButtons = linkedMapOf<String, DemoAction>()
    private val sampleButtons = linkedMapOf<String, DemoAction>()
    private val themeButtons = mutableListOf<DemoAction>()
    private val zoomButtons = mutableListOf<DemoAction>()
    private val codeButtons = mutableListOf<DemoAction>()
    private val colours = listOf("#2563eb", "#059669", "#7c3aed")
    private val remoteSource = "https://img.shields.io/github/release/xjw580/Hearthstone-Script.svg"
    private var category = "动画"
    private var selected = samples.first()
    private var theme = 0
    private var zoom = 1.0
    private var compare = false
    private var themed = false
    private var preserveRatio = true
    private var codeMode = 0
    private var resumeAfterSeek = false
    private var smoke = false
    private lateinit var active: SvgView
    private lateinit var reference: SvgView
    private lateinit var content: VBox
    private lateinit var viewport: DemoViewport
    private lateinit var navigation: VBox
    private lateinit var title: Text
    private lateinit var description: Text
    private lateinit var tip: Text
    private lateinit var codeText: Text
    private lateinit var codeFilename: Text
    private lateinit var codeViewport: DemoViewport
    private lateinit var metrics: Text
    private lateinit var status: Text
    private lateinit var timeText: Text
    private lateinit var sourceStatus: Text
    private lateinit var board: DemoBoard
    private lateinit var previewHost: HBox
    private lateinit var timeline: DemoTimeline
    private lateinit var playButton: DemoAction
    private lateinit var pauseButton: DemoAction
    private lateinit var stopButton: DemoAction
    private lateinit var compareButton: DemoAction
    private lateinit var themeToggle: DemoAction
    private lateinit var ratioButton: DemoAction
    private lateinit var darkButton: DemoAction
    private lateinit var gridButton: DemoAction
    private lateinit var resources: VBox

    override fun start(stage: Stage) {
        smoke = "--smoke" in parameters.raw
        content = VBox(20.0).apply { styleClass += listOf("showcase", "blue"); padding = Insets(26.0) }
        val side = sidebar()
        val editor = editor()
        HBox.setHgrow(editor, Priority.ALWAYS)
        val workspace = HBox(20.0, side, editor).apply { alignment = Pos.TOP_LEFT }
        content.children.setAll(header(), workspace, text("JavaFX 原生场景图  /  SVG · CSS · FXML · ANIMATION", "footer"))
        viewport = DemoViewport(content).apply { styleClass += "page-viewport" }
        val scene = Scene(viewport, 1440.0, 980.0).apply { stylesheets += resource("demo.css") }
        stage.title = "JavaFX SVG · 原生渲染实验室"
        stage.minWidth = 940.0; stage.minHeight = 650.0; stage.scene = scene
        selectCategory("动画")
        if (smoke) runSmoke() else stage.show()
    }

    private fun header(): VBox {
        val heading = VBox(6.0, text("JAVAFX SVG  /  INTERACTIVE LAB", "eyebrow"),
            text("SVG 原生渲染实验室", "page-title"),
            text("从静态图标到动态反馈，直接观察样式、布局与播放行为。", "subtitle"))
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val badge = VBox(7.0, text("●  本地示例已就绪", "ready"), text(samples.size.toString() + " 个示例 · 5 个分类", "hint")).apply { alignment = Pos.CENTER_RIGHT }
        val row = HBox(20.0, heading, spacer, badge).apply { alignment = Pos.CENTER_LEFT }
        val themes = HBox(7.0, text("界面主题", "toolbar-label")).apply { alignment = Pos.CENTER_LEFT }
        listOf("蓝色", "绿色", "紫色").forEachIndexed { i, label ->
            val button = DemoAction(label) { selectTheme(i) }
            themeButtons += button; themes.children += button
        }
        val sizes = HBox(7.0, text("视口缩放", "toolbar-label")).apply { alignment = Pos.CENTER_LEFT }
        listOf(.8, 1.0, 1.2).forEach { size ->
            val button = DemoAction((size * 100).toInt().toString() + "%") { selectZoom(size) }
            zoomButtons += button; sizes.children += button
        }
        val reset = DemoAction("恢复默认") {
            selectTheme(0); selectZoom(1.0); board.dark = false; board.showGrid = true
            darkButton.selected = false; gridButton.selected = true
            selectCategory("动画"); selectSample(samples.first()); viewport.scrollTo(0.0)
        }
        return VBox(20.0, row, FlowPane(22.0, 10.0, themes, sizes, reset)).apply { padding = Insets(0.0, 0.0, 4.0, 0.0) }
    }

    private fun sidebar(): VBox {
        val filters = VBox(5.0)
        groups.forEach { group ->
            val count = if (group == "全部") samples.size else samples.count { it.group == group }
            val button = DemoAction(group + "    " + count) { selectCategory(group) }.apply {
                styleClass += "category-action"; maxWidth = Double.MAX_VALUE
            }
            categoryButtons[group] = button; filters.children += button
        }
        navigation = VBox(7.0)
        val guide = text("先选择示例，再调整主题、背景或时间轴。\n\nTab 切换控件，Enter / Space 激活。\n页面支持滚轮与 Page Up / Down。", "hint")
        guide.wrappingWidth = 146.0
        return VBox(15.0, text("示例目录", "section-label"), filters, separator(), text("当前分类", "section-label"), navigation, separator(), guide).apply {
            styleClass += "sidebar"; padding = Insets(18.0, 14.0, 18.0, 14.0)
            minWidth = 190.0; prefWidth = 190.0; maxWidth = 190.0
        }
    }

    private fun editor(): VBox {
        title = text("", "sample-title")
        description = text("", "description")
        tip = text("", "tip")
        val tag = text("实时预览", "section-label")
        compareButton = DemoAction("源图对比") { compare = !compare; refreshPreview() }
        themeToggle = DemoAction("统一换色") { themed = !themed; refreshPreview(); updateCode() }
        ratioButton = DemoAction("保持比例") { preserveRatio = !preserveRatio; refreshPreview() }
        darkButton = DemoAction("深色背景") { board.dark = !board.dark; darkButton.selected = board.dark }
        gridButton = DemoAction("网格") { board.showGrid = !board.showGrid; gridButton.selected = board.showGrid }.apply { selected = true }
        val options = FlowPane(7.0, 7.0, compareButton, themeToggle, ratioButton, darkButton, gridButton)
        board = DemoBoard()
        previewHost = HBox(20.0).apply { alignment = Pos.CENTER; isPickOnBounds = false }
        board.children += previewHost
        board.widthProperty().addListener { _, _, _ -> updatePreviewSize() }
        playButton = DemoAction("▶  播放") { active.playAnimations(); reference.playAnimations(); refreshTransport() }.apply { styleClass += "primary-action" }
        pauseButton = DemoAction("Ⅱ  暂停") { active.pauseAnimations(); reference.pauseAnimations(); refreshTransport() }
        stopButton = DemoAction("↺  回到起点") { active.stopAnimations(); reference.stopAnimations(); refreshTransport() }
        status = text("静态 SVG", "playback-status")
        val transport = HBox(8.0, playButton, pauseButton, stopButton, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, status).apply { alignment = Pos.CENTER_LEFT }
        timeline = DemoTimeline(
            { resumeAfterSeek = active.animationStatus == Animation.Status.RUNNING; active.pauseAnimations(); reference.pauseAnimations() },
            { fraction -> seek(fraction * horizon()) },
            { if (resumeAfterSeek) { active.playAnimations(); reference.playAnimations() }; refreshTransport() })
        HBox.setHgrow(timeline, Priority.ALWAYS)
        timeText = text("0.00 s", "time-label")
        val timeRow = HBox(12.0, timeline, timeText).apply { alignment = Pos.CENTER_LEFT }
        metrics = text("", "metrics")
        val previewPanel = VBox(12.0, tag, title, description, options, board, transport, timeRow, separator(), metrics, tip).apply {
            styleClass += "panel"; padding = Insets(20.0); minWidth = 0.0
        }
        description.wrappingWidthProperty().bind(previewPanel.widthProperty().subtract(40))
        tip.wrappingWidthProperty().bind(previewPanel.widthProperty().subtract(40))
        metrics.wrappingWidthProperty().bind(previewPanel.widthProperty().subtract(40))
        codeFilename = text("", "code-filename")
        val codeTabs = HBox(6.0)
        listOf("SVG / FXML", "CSS", "Kotlin").forEachIndexed { i, name ->
            val button = DemoAction(name) { codeMode = i; updateCode() }
            codeButtons += button; codeTabs.children += button
        }
        codeText = text("", "code-text")
        val codeContent = VBox(codeText).apply { padding = Insets(15.0) }
        codeViewport = DemoViewport(codeContent).apply { styleClass += "code-viewport" }
        codeText.wrappingWidthProperty().bind(codeViewport.widthProperty().subtract(30))
        VBox.setVgrow(codeViewport, Priority.ALWAYS)
        val copy = DemoAction("复制当前源码") {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(codeText.text) })
            codeFilename.text = "已复制 · " + selected.file.substringAfterLast('/')
        }
        val codePanel = VBox(12.0, text("查看与复用", "section-label"), codeTabs, codeFilename, codeViewport, copy).apply {
            styleClass += "panel"; padding = Insets(18.0); minWidth = 0.0
        }
        val panels = FlowPane(16.0, 16.0, previewPanel, codePanel).apply { minWidth = 0.0 }
        val column = VBox(16.0, panels, resourcePanel()).apply { minWidth = 0.0; maxWidth = Double.MAX_VALUE }
        panels.prefWrapLengthProperty().bind(column.widthProperty())
        column.widthProperty().addListener { _, _, width ->
            val available = max(1.0, width.toDouble())
            val wide = available >= 960.0
            previewPanel.prefWidth = if (wide) available - 348.0 else available
            previewPanel.maxWidth = previewPanel.prefWidth
            codePanel.prefWidth = if (wide) 332.0 else available
            codePanel.maxWidth = codePanel.prefWidth
            codePanel.prefHeight = if (wide) 732.0 else 330.0
            codePanel.maxHeight = codePanel.prefHeight
        }
        return column
    }

    private fun resourcePanel(): VBox {
        sourceStatus = text("", "resource-status")
        val actions = FlowPane(8.0, 8.0,
            DemoAction("加载网络徽章") { active.source = remoteSource; updateSourceStatus() },
            DemoAction("本地资源") { active.source = resource("/svg/shields-release.svg"); updateSourceStatus() },
            DemoAction("清空") { active.source = null; updateSourceStatus() })
        resources = VBox(12.0, text("资源加载", "sample-title"), actions, sourceStatus).apply {
            styleClass += "panel"; padding = Insets(20.0); minWidth = 0.0; isVisible = false; isManaged = false
        }
        sourceStatus.wrappingWidthProperty().bind(resources.widthProperty().subtract(40))
        return resources
    }

    private fun selectCategory(group: String) {
        category = group
        categoryButtons.forEach { (name, button) -> button.selected = name == group }
        navigation.children.clear(); sampleButtons.clear()
        val entries = samples.filter { group == "全部" || it.group == group }
        entries.forEachIndexed { i, sample ->
            val button = DemoAction(sample.title) { selectSample(sample) }.apply {
                styleClass += "nav-item"; alignment = Pos.CENTER_LEFT; maxWidth = Double.MAX_VALUE
                children.setAll(VBox(5.0, text(sample.title, "nav-title"), text(String.format(Locale.ROOT, "%02d  /  ", i + 1) + sample.tag, "nav-tag")))
            }
            navigation.children += button; sampleButtons[sample.key] = button
        }
        selectSample(entries.firstOrNull { it.key == selected.key } ?: entries.first())
    }

    private fun makeView(sample: Sample): SvgView = when (sample.key) {
        "fxml" -> FXMLLoader(javaClass.getResource("/demo/icon.fxml")).load<SvgView>()
        "cache", "badge" -> SvgView().apply { source = resource(sample.file) }
        else -> SvgView(documents.getOrPut(sample.file) { SvgLoader.load(javaClass.getResource(location(sample.file))!!, SvgOptions(strict = true)) })
    }.apply {
        animationAutoPlay = !smoke
        setMinSize(0.0, 0.0)
    }

    private fun selectSample(sample: Sample) {
        if (::active.isInitialized) { active.source = null; reference.source = null }
        selected = sample
        active = makeView(sample); reference = makeView(sample)
        check(active.document === reference.document) { "Comparison views must share the parsed document" }
        compare = sample.key in setOf("theme", "cache")
        themed = sample.key == "theme"
        preserveRatio = true
        title.text = sample.title
        description.text = sample.description
        tip.text = "观察提示  /  " + sample.tip
        active.styleClass += "live-preview"; reference.styleClass += "reference-preview"
        if (sample.group == "动画") active.styleClass += "animation-preview"
        if (sample.key == "theme") active.styleClass += "theme-preview"
        if (sample.key == "markers") active.styleClass += "marker-preview"
        if (sample.key == "cache") { active.styleClass += "cache-blue"; reference.styleClass += "cache-green" }
        sampleButtons.forEach { (key, button) -> button.selected = key == sample.key }
        active.animationTimeProperty().addListener { _, _, _ -> refreshTransport() }
        active.animationStatusProperty().addListener { _, _, _ -> refreshTransport() }
        active.loadingProperty().addListener { _, _, _ -> updateSourceStatus() }
        active.loadExceptionProperty().addListener { _, _, _ -> updateSourceStatus() }
        active.documentProperty().addListener { _, _, _ -> updateSourceStatus(); refreshTransport() }
        resources.isVisible = sample.key == "badge"; resources.isManaged = resources.isVisible
        refreshPreview(); refreshTransport(); updateCode(); updateSourceStatus()
        selectTheme(theme); selectZoom(zoom)
    }

    private fun refreshPreview() {
        compareButton.selected = compare; themeToggle.selected = themed; ratioButton.selected = preserveRatio
        active.styleClass.remove("theme-target")
        if (themed) active.styleClass += "theme-target"
        active.preserveRatio = preserveRatio; reference.preserveRatio = preserveRatio
        reference.isVisible = compare; reference.isManaged = compare
        val original = VBox(8.0, reference, text("源 SVG  /  独立实例", "canvas-label")).apply { alignment = Pos.CENTER; isVisible = compare; isManaged = compare }
        val current = VBox(8.0, active, text(if (compare) "当前效果" else "LIVE  /  当前效果", "canvas-label")).apply { alignment = Pos.CENTER }
        previewHost.children.setAll(listOfNotNull(if (compare) original else null, current))
        if (compare && active.hasAnimations) {
            reference.seekAnimations(active.animationTime)
            if (content.scene?.window?.isShowing == true) {
                if (active.animationStatus == Animation.Status.RUNNING) reference.playAnimations() else reference.pauseAnimations()
            }
        }
        updatePreviewSize()
    }

    private fun updatePreviewSize() {
        if (!::active.isInitialized || !::board.isInitialized) return
        val available = max(120.0, board.width - 44.0)
        val width = min(if (compare) (available - 20) / 2 else available, 600.0) * zoom
        val height = 220.0 * zoom
        listOf(active, reference).forEach { view ->
            view.fitWidth = width; view.fitHeight = height
            view.setMaxSize(width, height)
        }
    }

    private fun selectTheme(index: Int) {
        theme = index
        content.styleClass.removeAll("blue", "green", "violet")
        content.styleClass += listOf("blue", "green", "violet")[index]
        themeButtons.forEachIndexed { i, button -> button.selected = i == index }
        if (::codeText.isInitialized && ::active.isInitialized) updateCode()
    }

    private fun selectZoom(value: Double) {
        zoom = value
        zoomButtons.forEachIndexed { i, button -> button.selected = listOf(.8, 1.0, 1.2)[i] == value }
        updatePreviewSize()
    }

    private fun horizon(): Double = if (!::active.isInitialized || !active.hasAnimations) 1.0 else
        if (active.animationDuration.isIndefinite) 6.0 else max(.001, active.animationDuration.toSeconds())

    private fun seek(seconds: Double) {
        active.seekAnimations(Duration.seconds(seconds)); reference.seekAnimations(Duration.seconds(seconds))
        refreshTransport()
    }

    private fun refreshTransport() {
        if (!::active.isInitialized) return
        val animated = active.hasAnimations
        playButton.isDisable = !animated; pauseButton.isDisable = !animated; stopButton.isDisable = !animated; timeline.isDisable = !animated
        status.text = if (!animated) "静态 SVG" else when (active.animationStatus) {
            Animation.Status.RUNNING -> "● 正在播放"
            Animation.Status.PAUSED -> "Ⅱ 已暂停"
            else -> "■ 已停止"
        }
        val seconds = active.animationTime.toSeconds()
        timeText.text = String.format(Locale.ROOT, "%.2f s", seconds) + if (animated && active.animationDuration.isIndefinite) " / ∞" else ""
        timeline.progress.set(if (!animated) 0.0 else if (active.animationDuration.isIndefinite) (seconds % horizon()) / horizon() else seconds / horizon())
        metrics.text = countNodes(active).toString() + " 个原生节点    ·    " + (if (animated) "独立动画时钟" else "静态渲染") +
            "    ·    " + active.diagnostics.size + " 项诊断"
    }

    private fun updateSourceStatus() {
        if (!::active.isInitialized) return
        sourceStatus.text = when {
            active.isLoading -> "正在后台下载并解析；预览保留上一次内容。"
            active.loadException != null -> "加载失败，保留原内容：" + active.loadException?.message?.take(180)
            active.document == null -> "视图已清空，可重新选择本地或网络资源。"
            active.source == remoteSource -> "网络徽章已加载。重新点击会再次请求远程地址。"
            else -> "离线资源已就绪；当前操作不会自动访问网络。"
        }
    }

    private fun updateCode() {
        codeButtons.forEachIndexed { i, button -> button.selected = i == codeMode }
        codeFilename.text = when (codeMode) { 0 -> selected.file.substringAfterLast('/') + " · 滚轮查看"; 1 -> "application.css"; else -> "Example.kt" }
        codeText.text = when (codeMode) {
            0 -> javaClass.getResource(location(selected.file))!!.readText()
            1 -> if (selected.key == "theme") """.svg-view.theme-target {
    -svg-fill: ACCENT;
    -svg-stroke: ACCENT;
    -svg-stroke-width: 3;
}
.theme-preview #check {
    -fx-stroke: #7c3aed;
    -fx-stroke-width: 5;
}
.theme-preview:hover {
    -svg-stroke: #e67700;
}""".replace("ACCENT", colours[theme])
                else """.svg-view.theme-target {
    -svg-fill: ACCENT;
    -svg-stroke: ACCENT;
    -svg-stroke-width: 3;
}

/* 在演示中点击「统一换色」应用此样式。
   应用侧路径 CSS 优先于根主题。 */
.svg-view #yourPath {
    -fx-stroke-line-cap: round;
}""".replace("ACCENT", colours[theme])
            else -> if (selected.key == "fxml") """val icon = FXMLLoader(
    javaClass.getResource("/demo/icon.fxml")
).load<SvgView>()
container.children.add(icon)
""" else """val document = SvgLoader.load(
    javaClass.getResource("LOCATION")!!
)
val icon = SvgView(document).apply {
    fitWidth = 320.0
    fitHeight = 220.0
    preserveRatio = true
}
container.children.add(icon)
""".replace("LOCATION", location(selected.file)) + if (selected.group == "动画") """
icon.playAnimations()
icon.pauseAnimations()
icon.seekAnimations(Duration.seconds(0.75))
icon.stopAnimations()
""" else if (selected.key == "badge") """
icon.source =
    "https://img.shields.io/github/release/xjw580/Hearthstone-Script.svg"
icon.loadingProperty().addListener { _, _, loading ->
    println(loading)
}
""" else ""
        }
        if (codeMode == 2 && themed) codeText.text += "\nicon.styleClass += \"theme-target\"\n// 将 CSS 页内容保存并加入 Scene.stylesheets。\n"
        codeViewport.scrollTo(0.0)
    }

    private fun countNodes(node: Node): Int = 1 + if (node is Parent) node.childrenUnmodifiable.sumOf(::countNodes) else 0
    private fun location(name: String) = if (name.startsWith('/')) name else "/demo/" + name
    private fun resource(name: String) = javaClass.getResource(location(name))!!.toExternalForm()
    private fun text(value: String, css: String) = Text(value).apply { styleClass += css }
    private fun separator() = Region().apply { styleClass += "separator"; minHeight = 1.0; prefHeight = 1.0; maxHeight = 1.0 }

    private fun runSmoke() {
        viewport.resize(1440.0, 980.0)
        samples.forEach { sample ->
            selectCategory(sample.group); selectSample(sample)
            if (active.hasAnimations) seek(.75)
            layoutPage()
            check(active.diagnostics.isEmpty() && reference.diagnostics.isEmpty()) { sample.key + ": " + active.diagnostics }
            check(active.hasAnimations == (sample.group == "动画")) { sample.key }
            if (sample.key == "css") check(kotlin.math.abs(active.lookup("#css-spin").transforms.first().mxx) < 1e-8)
            if (sample.key == "css-timing") {
                check(kotlin.math.abs(active.lookup("#css-step").transforms.first().mxx) < 1e-8)
                check((active.lookup("#css-mix") as Shape).fill != Color.web("#7c3aed"))
            }
            if (sample.key == "geometry") check((active.lookup("#ring") as javafx.scene.shape.Circle).radius > 8.0)
            if (sample.key == "bars") {
                val bar = active.lookup("#audio-bar") as javafx.scene.shape.Rectangle
                check(kotlin.math.abs(bar.y + bar.height - 112.0) < 1e-8)
                check(bar.height > 20.0)
                check((active.lookup("#progress") as javafx.scene.shape.Rectangle).width > 0.0)
            }
            check(active.document === reference.document)
            check(active.lookupAll(".svg-shape").none { it in reference.lookupAll(".svg-shape") })
            check(codeText.text.contains("<"))
            saveImage(board, Path.of("target", "demo-cases", sample.key + ".png"))
        }
        selectCategory("动画"); selectSample(samples.first()); seek(.75)
        selectTheme(0); selectZoom(1.0)
        savePreview("demo-preview.png")
        val first = board.snapshot(null, null)
        seek(1.25); layoutPage()
        val second = board.snapshot(null, null)
        var changed = 0
        for (y in 0 until min(first.height, second.height).toInt()) for (x in 0 until min(first.width, second.width).toInt()) {
            if (first.pixelReader.getArgb(x, y) != second.pixelReader.getArgb(x, y)) changed++
        }
        check(changed > 50) { "Animation preview did not change between frames" }
        selectCategory("颜色与效果"); selectSample(samples.first { it.key == "theme" })
        selectTheme(1); selectZoom(1.2)
        savePreview("demo-preview-themed.png")
        check((active.lookup("#outline") as Shape).stroke == Color.web(colours[1]))
        check((active.lookup("#check") as Shape).stroke == Color.web("#7c3aed"))
        codeMode = 1; updateCode(); check(codeText.text.contains(colours[1]))
        codeMode = 2; updateCode(); check(codeText.text.contains("SvgView"))
        codeMode = 0; updateCode()
        selectCategory("动画"); selectSample(samples.first { it.key == "draw" })
        selectTheme(2); selectZoom(.8); seek(1.0); board.dark = true; darkButton.selected = true
        viewport.resize(960.0, 760.0)
        savePreview("demo-preview-compact.png")
        viewport.scrollTo(Double.MAX_VALUE); check(viewport.offset > 0.0)
        viewport.scrollTo(0.0)
        selectCategory("资源"); selectSample(samples.first { it.key == "badge" })
        val local = active.document
        active.source = null; check(active.document == null)
        active.source = resource("/svg/shields-release.svg")
        check(active.document === local && !active.isLoading && active.loadException == null)
        selectCategory("动画"); selectSample(samples.first { it.key == "geometry" })
        selectTheme(0); selectZoom(1.0); board.dark = false; darkButton.selected = false
        viewport.resize(1440.0, 980.0); seek(.75)
        savePreview("demo-preview-geometry.png")
        selectSample(samples.first { it.key == "css" }); seek(.75)
        savePreview("demo-preview-css.png")
        println("SVG lab smoke passed: " + samples.size + " examples, independent nodes, animation frames, themes, source tabs, responsive layout and local loading.")
        Platform.exit()
    }

    private fun layoutPage() {
        repeat(6) { content.applyCss(); viewport.requestLayout(); viewport.layout(); content.layout() }
        if (content.boundsInLocal.width > content.width + 1.0) {
            saveImage(content, Path.of("target", "demo-overflow.png"))
            error("Demo horizontal overflow: " + content.width + " / " + content.boundsInLocal + " / " + content.children.map { it.boundsInParent })
        }
        refreshTransport()
    }

    private fun savePreview(name: String) {
        viewport.scrollTo(0.0); layoutPage()
        saveImage(content, Path.of("target", name))
    }

    /** Java2D is used only by this test/demo PNG exporter, never by the SVG library. */
    private fun saveImage(node: Node, output: Path) {
        val snapshot = node.snapshot(null, null)
        val image = BufferedImage(snapshot.width.toInt(), snapshot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, snapshot.pixelReader.getArgb(x, y))
        Files.createDirectories(output.parent)
        check(ImageIO.write(image, "png", output.toFile()))
        println("SVG lab snapshot: " + output.toAbsolutePath())
    }
}

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--animation-report=") }?.let {
        require(args.size == 1) { "Report mode accepts only --animation-report=<recording>" }
        animationProfileReport(Path.of(it.substringAfter('=')))
        return
    }
    val benchmark = "--benchmark" in args
    val animations = "--animation-benchmark" in args
    val visualCheck = "--cache-visual-check" in args
    require(listOf(benchmark, animations, visualCheck).count { it } <= 1) { "Choose one benchmark or visual check" }
    Application.launch(when {
        benchmark -> SvgRenderBenchmark::class.java
        animations -> SvgAnimationBenchmark::class.java
        visualCheck -> SvgCacheVisualCheck::class.java
        else -> SvgDemo::class.java
    }, *args.filterNot { it in listOf("--benchmark", "--animation-benchmark", "--cache-visual-check") }.toTypedArray())
}
