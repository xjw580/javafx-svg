package club.xiaojiawei.svg

import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.effect.ColorAdjust
import javafx.scene.shape.Rectangle
import javafx.scene.transform.Translate
import kotlin.math.max
import kotlin.math.min

/** A single feOffset: clip input, translate it, then apply the primitive/filter output region. */
internal class SvgOffsetFilter(
    private val source: Node,
    private val filter: SvgElement,
    private val primitive: SvgElement,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache,
    private val report: (String) -> Unit
) : Group() {
    private val filterProportional = svgFilterProportional(filter["filterUnits"] ?: "objectBoundingBox")
    private val primitiveProportional = svgFilterProportional(filter["primitiveUnits"] ?: "userSpaceOnUse")
    private val alphaEffect = if (primitive["in"] == "SourceAlpha") ColorAdjust().apply { brightness = -1.0 } else null
    private val inputRegion = Rectangle()
    private val outputRegion = Rectangle()
    private val input = Group(source).apply { clip = inputRegion }
    private val translation = Translate()
    private val shifted = Group(input).apply { transforms += translation }
    private val output = Group(shifted).apply { clip = outputRegion }

    init {
        require(primitive["in"] in setOf(null, "SourceGraphic", "SourceAlpha")) { "Unsupported feOffset input" }
        input.effect = alphaEffect
        refresh()
        transforms.setAll(source.transforms)
        source.transforms.clear()
        children.setAll(output)
        styleClass += "svg-filter-output"
        source.boundsInLocalProperty().addListener { _ -> requestLayout() }
    }

    override fun layoutChildren() {
        refresh()
        super.layoutChildren()
    }

    fun refresh() {
        try {
            val bounds = svgGeometryBounds(source)
            val region = svgFilterRegion(filter, filterProportional, bounds, null, viewport, lengths)
            val subregion = svgFilterRegion(primitive, primitiveProportional, bounds, region, viewport, lengths)
            val dx = lengths.resolve(primitive["dx"] ?: "0", if (primitiveProportional) 1.0 else viewport.width) *
                if (primitiveProportional) bounds.width else 1.0
            val dy = lengths.resolve(primitive["dy"] ?: "0", if (primitiveProportional) 1.0 else viewport.height) *
                if (primitiveProportional) bounds.height else 1.0
            require(dx.isFinite() && dy.isFinite()) { "Non-finite feOffset displacement" }
            translation.x = dx; translation.y = dy
            input.clip = inputRegion; output.clip = outputRegion; input.effect = alphaEffect
            setRegion(inputRegion, region)
            val x = max(region.minX, subregion.minX); val y = max(region.minY, subregion.minY)
            outputRegion.x = x; outputRegion.y = y
            outputRegion.width = max(0.0, min(region.maxX, subregion.maxX) - x)
            outputRegion.height = max(0.0, min(region.maxY, subregion.maxY) - y)
        } catch (e: IllegalArgumentException) {
            translation.x = 0.0; translation.y = 0.0
            input.clip = null; output.clip = null; input.effect = null
            report(e.message ?: "Invalid feOffset")
        }
    }

    private fun setRegion(rect: Rectangle, bounds: Bounds) {
        rect.x = bounds.minX; rect.y = bounds.minY
        rect.width = bounds.width; rect.height = bounds.height
    }
}
