package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.*
import javafx.scene.shape.*
import javafx.scene.transform.Affine
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal class SvgPaints(private val document: SvgDocument, private val styles: SvgStyles) {
    private val ancestors = mutableMapOf<SvgElement, List<SvgElement>>()
    private val computedStyles = mutableMapOf<SvgElement, Map<String, String>>()
    init {
        fun visit(element: SvgElement, parents: List<SvgElement>) {
            ancestors[element] = parents
            element.children.forEach { visit(it, parents + element) }
        }
        visit(document.root, emptyList())
    }

    private fun computed(element: SvgElement): Map<String, String> = computedStyles.getOrPut(element) {
        val parents = ancestors[element].orEmpty()
        styles.compute(element, parents, parents.lastOrNull()?.let(::computed) ?: emptyMap())
    }

    fun ancestorsOf(element: SvgElement): List<SvgElement> = ancestors[element].orEmpty()
    fun styleOf(element: SvgElement): Map<String, String> = computed(element)
    fun parentStyleOf(element: SvgElement): Map<String, String> = ancestors[element]?.lastOrNull()?.let(::computed) ?: emptyMap()

    fun gradient(id: String, opacity: Double, viewport: SvgViewport, bounds: Bounds): Paint {
        val chain = mutableListOf<SvgElement>()
        val seen = mutableSetOf<String>()
        var next: String? = id
        while (next != null) {
            require(seen.add(next)) { "Circular gradient reference: $next" }
            require(chain.size < document.options.maxDepth) { "Gradient reference depth exceeded" }
            val element = document.elementsById[next] ?: throw SvgException("Missing paint server: #$next")
            require(element.name in setOf("linearGradient", "radialGradient")) { "Unsupported paint server: ${element.name}" }
            chain += element
            val href = element["href"] ?: element["xlink:href"]
            require(href == null || href.startsWith('#')) { "Only local gradient references are supported" }
            next = href?.drop(1)
        }
        val attrs = chain.asReversed().flatMap { it.attributes.entries }.associate { it.key to it.value }
        val source = chain.firstOrNull { e -> e.children.any { it.name == "stop" } }
        var previous = 0.0
        val stops = source?.children?.filter { it.name == "stop" }?.map { stop ->
            val style = computed(stop)
            val offset = max(previous, SvgNumbers.fraction(stop["offset"] ?: "0"))
            previous = offset
            val color = Color.web(style.getValue("stop-color").let { if (it == "currentColor") style.getValue("color") else it })
            Stop(offset, color.deriveColor(0.0, 1.0, 1.0, opacity * SvgNumbers.fraction(style.getValue("stop-opacity"))))
        }.orEmpty()
        if (stops.isEmpty()) return Color.TRANSPARENT
        if (stops.size == 1) return stops[0].color
        val proportional = when (attrs["gradientUnits"] ?: "objectBoundingBox") {
            "objectBoundingBox" -> true
            "userSpaceOnUse" -> false
            else -> throw SvgException("Invalid gradientUnits")
        }
        if (proportional && (bounds.width <= 0 || bounds.height <= 0)) return Color.TRANSPARENT
        val cycle = when (attrs["spreadMethod"] ?: "pad") {
            "pad" -> CycleMethod.NO_CYCLE
            "reflect" -> CycleMethod.REFLECT
            "repeat" -> CycleMethod.REPEAT
            else -> throw SvgException("Invalid spreadMethod")
        }
        fun coordinate(key: String, fallback: String, reference: Double): Double =
            document.lengths.resolve(attrs[key] ?: fallback, if (proportional) 1.0 else reference)
        val transform = SvgTransforms.parse(attrs["gradientTransform"])
        if (chain.first().name == "linearGradient") {
            val x1 = coordinate("x1", "0%", viewport.width)
            val y1 = coordinate("y1", "0%", viewport.height)
            val x2 = coordinate("x2", "100%", viewport.width)
            val y2 = coordinate("y2", "0%", viewport.height)
            val dx = x2 - x1; val dy = y2 - y1
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) return stops.last().color
            val mapping = if (proportional) Affine(bounds.width, 0.0, bounds.minX, 0.0, bounds.height, bounds.minY) else Affine()
            mapping.append(transform)
            val determinant = mapping.mxx * mapping.myy - mapping.mxy * mapping.myx
            require(kotlin.math.abs(determinant) > 1e-15) { "Singular gradient transform" }
            // Transform the gradient's normal, not just its endpoints: important for skew/nonuniform scale.
            val nx = (mapping.myy * dx - mapping.myx * dy) / determinant / lengthSquared
            val ny = (-mapping.mxy * dx + mapping.mxx * dy) / determinant / lengthSquared
            val normalSquared = nx * nx + ny * ny
            val start = mapping.transform(x1, y1)
            return LinearGradient(start.x, start.y, start.x + nx / normalSquared, start.y + ny / normalSquared, false, cycle, stops)
        }
        require(transform.isIdentity) { "radialGradient gradientTransform is unsupported" }
        require(attrs["fr"] == null || document.lengths.resolve(attrs.getValue("fr"), 1.0) == 0.0) { "Radial focal radius is unsupported" }
        val cx = coordinate("cx", "50%", viewport.width)
        val cy = coordinate("cy", "50%", viewport.height)
        val radius = coordinate("r", "50%", viewport.diagonal)
        require(radius >= 0) { "Negative gradient radius" }
        if (radius == 0.0) return stops.last().color
        val fx = coordinate("fx", attrs["cx"] ?: "50%", viewport.width)
        val fy = coordinate("fy", attrs["cy"] ?: "50%", viewport.height)
        return RadialGradient(Math.toDegrees(atan2(fy - cy, fx - cx)),
            (hypot(fx - cx, fy - cy) / radius).coerceAtMost(.999999), cx, cy, radius, proportional, cycle, stops)
    }
}

/** Geometric bounds exclude stroke and effects, as required by SVG objectBoundingBox. */
internal fun svgGeometryBounds(node: Node): Bounds = when (node) {
    is SvgAdjustedText -> node.geometryBounds()
    is SvgMask -> node.geometryBounds()
    is Rectangle -> BoundingBox(node.x, node.y, node.width, node.height)
    is Circle -> BoundingBox(node.centerX - node.radius, node.centerY - node.radius, node.radius * 2, node.radius * 2)
    is Ellipse -> BoundingBox(node.centerX - node.radiusX, node.centerY - node.radiusY, node.radiusX * 2, node.radiusY * 2)
    is Line -> BoundingBox(minOf(node.startX, node.endX), minOf(node.startY, node.endY),
        kotlin.math.abs(node.endX - node.startX), kotlin.math.abs(node.endY - node.startY))
    is SVGPath -> SVGPath().apply { content = node.content; fillRule = node.fillRule }.boundsInLocal
    is Group -> {
        val children = node.children.map { it.localToParentTransform.transform(svgGeometryBounds(it)) }
        if (children.isEmpty()) BoundingBox(0.0, 0.0, 0.0, 0.0)
        else {
            val x = children.minOf { it.minX }; val y = children.minOf { it.minY }
            BoundingBox(x, y, children.maxOf { it.maxX } - x, children.maxOf { it.maxY } - y)
        }
    }
    else -> node.layoutBounds
}
