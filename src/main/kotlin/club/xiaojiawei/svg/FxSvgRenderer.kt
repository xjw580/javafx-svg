package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.*
import java.util.Collections
import kotlin.math.hypot
import kotlin.math.min

internal data class SvgViewport(var width: Double, var height: Double) {
    val diagonal: Double get() = hypot(width, height) / kotlin.math.sqrt(2.0)
}

internal class FxSvgRenderer(private val document: SvgDocument) {
    private val issues = linkedSetOf<SvgDiagnostic>().apply { addAll(document.diagnostics) }
    val diagnostics: List<SvgDiagnostic> get() = Collections.unmodifiableList(issues.toList())
    private fun report(message: String, element: SvgElement? = null) {
        if (document.options.strict) throw SvgException(message + (element?.id?.let { " (#$it)" } ?: ""))
        issues += SvgDiagnostic(message, element?.id)
    }
    private val styles = SvgStyles(document, ::report)
    private val updates = mutableListOf<() -> Unit>()
    private val box = document.root["viewBox"]?.let(SvgBox::parse)
    val intrinsicWidth = document.root["width"]?.let { SvgNumbers.length(it, box?.width ?: 300.0) } ?: box?.width ?: 300.0
    val intrinsicHeight = document.root["height"]?.let { SvgNumbers.length(it, box?.height ?: 150.0) } ?: box?.height ?: 150.0
    private val viewport = SvgViewport(box?.width ?: intrinsicWidth, box?.height ?: intrinsicHeight)
    private val content: Node
    val root: Group
    private val viewportClip = Rectangle()

    init {
        require(intrinsicWidth >= 0 && intrinsicHeight >= 0) { "Negative SVG dimensions" }
        content = render(document.root, emptyList(), emptyMap(), viewport, true) ?: Group()
        root = Group(content).apply { clip = viewportClip }
    }

    fun layout(width: Double, height: Double, preserveRatio: Boolean) {
        viewport.width = box?.width ?: width
        viewport.height = box?.height ?: height
        root.isVisible = width > 0 && height > 0 && viewport.width > 0 && viewport.height > 0
        viewportClip.width = width
        viewportClip.height = height
        root.transforms.clear()
        content.transforms.removeIf { it === viewportTransform }
        viewportTransform = box?.transform(width, height, if (preserveRatio) document.root["preserveAspectRatio"] else "none")
        viewportTransform?.let { content.transforms.add(0, it) }
        updates.forEach { it() }
    }
    private var viewportTransform: javafx.scene.transform.Affine? = null

    private fun render(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean = false
    ): Node? {
        return try {
            renderElement(element, ancestors, parentStyle, viewport, isRoot)
        } catch (e: IllegalArgumentException) {
            report(e.message ?: "Invalid SVG element", element)
            null
        }
    }

    private fun renderElement(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean
    ): Node? {
        if (element.name in setOf("defs", "style", "title", "desc", "metadata")) return null
        val style = styles.compute(element, ancestors, parentStyle)
        if (style.getValue("display") == "none") return null
        for (attribute in listOf("filter", "mask", "marker-start", "marker-mid", "marker-end", "vector-effect", "paint-order")) {
            if (element[attribute] != null) report("Unsupported SVG attribute: $attribute", element)
        }
        fun length(name: String, axis: Double, default: String = "0") = SvgNumbers.length(element[name] ?: default, axis)
        fun positive(value: Double): Double = value.also { require(it >= 0) { "Negative SVG size" } }
        val node: Node = when (element.name) {
            "svg", "g", "a" -> {
                val group = Group()
                if (element.name == "svg" && !isRoot) {
                    val inner = Group()
                    val nestedBox = element["viewBox"]?.let(SvgBox::parse)
                    val nested = SvgViewport(nestedBox?.width ?: viewport.width, nestedBox?.height ?: viewport.height)
                    val clip = Rectangle()
                    group.children += inner
                    group.clip = clip
                    update(element) {
                        val x = length("x", viewport.width)
                        val y = length("y", viewport.height)
                        val width = positive(length("width", viewport.width, "100%"))
                        val height = positive(length("height", viewport.height, "100%"))
                        nested.width = nestedBox?.width ?: width
                        nested.height = nestedBox?.height ?: height
                        inner.transforms.setAll(javafx.scene.transform.Translate(x, y))
                        nestedBox?.let { inner.transforms += it.transform(width, height, element["preserveAspectRatio"]) }
                        clip.x = x; clip.y = y; clip.width = width; clip.height = height
                    }
                    element.children.mapNotNullTo(inner.children) { render(it, ancestors + element, style, nested) }
                } else {
                    element.children.mapNotNullTo(group.children) { render(it, ancestors + element, style, viewport) }
                }
                group
            }
            "path" -> SVGPath().apply {
                content = element["d"] ?: ""
                SvgPathData.validate(content)
                fillRule = fillRule(style.getValue("fill-rule"))
            }
            "rect" -> Rectangle().also { shape ->
                update(element) {
                    shape.x = length("x", viewport.width); shape.y = length("y", viewport.height)
                    shape.width = positive(length("width", viewport.width)); shape.height = positive(length("height", viewport.height))
                    val rx = positive(SvgNumbers.length(element["rx"] ?: element["ry"] ?: "0", viewport.width))
                    val ry = positive(SvgNumbers.length(element["ry"] ?: element["rx"] ?: "0", viewport.height))
                    shape.arcWidth = 2 * min(rx, shape.width / 2)
                    shape.arcHeight = 2 * min(ry, shape.height / 2)
                }
            }
            "circle" -> Circle().also { shape -> update(element) {
                shape.centerX = length("cx", viewport.width); shape.centerY = length("cy", viewport.height)
                shape.radius = positive(length("r", viewport.diagonal))
            } }
            "ellipse" -> Ellipse().also { shape -> update(element) {
                shape.centerX = length("cx", viewport.width); shape.centerY = length("cy", viewport.height)
                shape.radiusX = positive(length("rx", viewport.width)); shape.radiusY = positive(length("ry", viewport.height))
            } }
            "line" -> Line().also { shape -> update(element) {
                shape.startX = length("x1", viewport.width); shape.startY = length("y1", viewport.height)
                shape.endX = length("x2", viewport.width); shape.endY = length("y2", viewport.height)
            } }
            "polyline", "polygon" -> SVGPath().apply {
                val points = SvgNumbers.list(element["points"] ?: "")
                require(points.size % 2 == 0) { "Odd number of polygon coordinates" }
                content = points.chunked(2).mapIndexed { index, pair ->
                    (if (index == 0) "M" else "L") + pair.joinToString(" ")
                }.joinToString(" ") + if (element.name == "polygon" && points.isNotEmpty()) " Z" else ""
                fillRule = fillRule(style.getValue("fill-rule"))
            }
            else -> {
                report("Unsupported SVG element: ${element.name}", element)
                return null
            }
        }
        node.id = element.id
        node.styleClass += "svg-${element.name}"
        element["class"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { node.styleClass.addAll(it) }
        node.transforms += SvgTransforms.parse(element["transform"])
        node.opacity = SvgNumbers.fraction(style.getValue("opacity"))
        if (node !is Group) node.isVisible = style.getValue("visibility") == "visible"
        if (node is Shape) {
            node.styleClass += "svg-shape"
            applyShapeStyle(node, style, viewport, element)
        }
        if (style.getValue("clip-path") != "none") report("clip-path is not supported yet", element)
        return node
    }

    private fun update(element: SvgElement, action: () -> Unit) {
        action()
        updates += {
            try { action() } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid geometry", element) }
        }
    }

    private fun applyShapeStyle(shape: Shape, style: Map<String, String>, viewport: SvgViewport, element: SvgElement) {
        shape.fill = paint(style.getValue("fill"), style, SvgNumbers.fraction(style.getValue("fill-opacity")), element)
        shape.stroke = paint(style.getValue("stroke"), style, SvgNumbers.fraction(style.getValue("stroke-opacity")), element)
        shape.strokeWidth = SvgNumbers.length(style.getValue("stroke-width"), viewport.diagonal).also { require(it >= 0) }
        shape.strokeType = StrokeType.CENTERED
        shape.strokeLineCap = when (style.getValue("stroke-linecap")) {
            "butt" -> StrokeLineCap.BUTT; "round" -> StrokeLineCap.ROUND; "square" -> StrokeLineCap.SQUARE
            else -> throw SvgException("Invalid stroke-linecap")
        }
        shape.strokeLineJoin = when (style.getValue("stroke-linejoin")) {
            "miter" -> StrokeLineJoin.MITER; "round" -> StrokeLineJoin.ROUND; "bevel" -> StrokeLineJoin.BEVEL
            else -> throw SvgException("Invalid stroke-linejoin")
        }
        shape.strokeMiterLimit = SvgNumbers.scalar(style.getValue("stroke-miterlimit")).also { require(it >= 1) }
        val dash = style.getValue("stroke-dasharray")
        if (dash != "none") {
            val values = dash.trim().split(Regex("[,\\s]+")).map {
                SvgNumbers.length(it, viewport.diagonal).also { n -> require(n >= 0) }
            }
            if (values.any { it > 0 }) shape.strokeDashArray.setAll(if (values.size % 2 == 0) values else values + values)
        }
        shape.strokeDashOffset = SvgNumbers.length(style.getValue("stroke-dashoffset"), viewport.diagonal)
    }

    private fun paint(value: String, style: Map<String, String>, opacity: Double, element: SvgElement): Paint? {
        if (value == "none") return null
        if (value.startsWith("url(")) {
            report("Paint server is not supported yet: $value", element)
            return null
        }
        val color = Color.web(if (value == "currentColor") style.getValue("color") else value)
        return Color(color.red, color.green, color.blue, color.opacity * opacity)
    }

    private fun fillRule(value: String): FillRule = when (value) {
        "nonzero" -> FillRule.NON_ZERO
        "evenodd" -> FillRule.EVEN_ODD
        else -> throw SvgException("Invalid fill rule: $value")
    }
}
