package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableValue
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine
import kotlin.math.*

internal class SvgMarkerSpec(
    val element: SvgElement,
    private val lengths: SvgLengthCache,
    style: Map<String, String>
) {
    val strokeUnits = when (element["markerUnits"] ?: "strokeWidth") {
        "strokeWidth" -> true
        "userSpaceOnUse" -> false
        else -> throw SvgException("Invalid markerUnits")
    }
    val clipped = when (style.getValue("overflow")) {
        "visible" -> false
        "hidden", "scroll", "auto" -> true
        else -> throw SvgException("Unsupported marker overflow")
    }
    private val box = element["viewBox"]?.let(SvgBox::parse)
    private val orient = element["orient"] ?: "0"
    private val angle = if (orient in setOf("auto", "auto-start-reverse")) null else {
        val match = Regex("(.+?)(deg|rad|grad|turn)?").matchEntire(orient.trim()) ?: throw SvgException("Invalid marker orient")
        val amount = SvgNumbers.scalar(match.groupValues[1])
        when (match.groupValues[2]) {
            "rad" -> Math.toDegrees(amount); "grad" -> amount * .9; "turn" -> amount * 360; else -> amount
        }.also { require(it.isFinite()) { "Non-finite marker orient" } }
    }
    data class Frame(val width: Double, val height: Double, val mapping: Affine, val refX: Double, val refY: Double)

    fun frame(sourceViewport: SvgViewport, contentViewport: SvgViewport): Frame {
        val width = lengths.resolve(element["markerWidth"] ?: "3", sourceViewport.width)
        val height = lengths.resolve(element["markerHeight"] ?: "3", sourceViewport.height)
        require(width >= 0 && height >= 0) { "Negative marker size" }
        contentViewport.width = box?.width ?: width
        contentViewport.height = box?.height ?: height
        contentViewport.x = box?.x ?: 0.0; contentViewport.y = box?.y ?: 0.0
        val mapping = box?.transform(width, height, element["preserveAspectRatio"]) ?: Affine()
        fun reference(name: String, axis: Double): Double {
            val value = element[name] ?: "0"
            val converted = when (value) {
                "left" -> { require(name == "refX"); "0%" }
                "right" -> { require(name == "refX"); "100%" }
                "top" -> { require(name == "refY"); "0%" }
                "bottom" -> { require(name == "refY"); "100%" }
                "center" -> "50%"
                else -> value
            }
            return lengths.resolve(converted, axis)
        }
        val reference = mapping.transform(reference("refX", contentViewport.width), reference("refY", contentViewport.height))
        return Frame(width, height, mapping, reference.x, reference.y)
    }

    fun angle(vertex: SvgMarkerGeometry.Vertex, start: Boolean, transform: javafx.scene.transform.Transform?): Double {
        val degrees = if (angle == null) vertex.angle(transform) else if (transform == null) angle else {
            val vector = transform.deltaTransform(cos(Math.toRadians(angle)), sin(Math.toRadians(angle)))
            Math.toDegrees(atan2(vector.y, vector.x))
        }
        return degrees + if (start && orient == "auto-start-reverse") 180 else 0
    }
}

internal class SvgMarkerInstance(val spec: SvgMarkerSpec, sourceViewport: SvgViewport) : Group() {
    val contentViewport = SvgViewport(0.0, 0.0)
    val mapped = Group()
    private val mapping = Affine()
    private val viewport = Group(mapped)
    private val clipRectangle = Rectangle()
    val placement = Affine()
    var context: SvgMarkerContext? = null
    init {
        transforms += placement
        mapped.transforms += mapping
        children += viewport
        isMouseTransparent = true
        styleClass += "svg-marker-instance"
        if (spec.clipped) viewport.clip = clipRectangle
        frame(sourceViewport)
    }
    fun frame(sourceViewport: SvgViewport): SvgMarkerSpec.Frame = spec.frame(sourceViewport, contentViewport).also {
        mapping.setToTransform(it.mapping)
        clipRectangle.width = it.width; clipRectangle.height = it.height
    }
}

internal class SvgMarkers(
    val styleShape: Shape,
    private val sourceViewport: SvgViewport,
    private val nonScaling: Boolean,
    private val basicShape: Boolean,
    private val vertexLimit: Int,
    private val animatedVertexCapacity: Int?,
    private val host: () -> Node?,
    private val invalidateRenderer: () -> Unit,
    private val report: (String) -> Unit
) : Group() {
    private data class Mark(val vertex: Int, val start: Boolean, val end: Boolean, val instance: SvgMarkerInstance)
    private val marks = mutableListOf<Mark>()
    private val placement = Affine()
    private var key = SvgMarkerGeometry.key(styleShape)
    private var vertices = SvgMarkerGeometry.vertices(styleShape, vertexLimit, basicShape)
    val vertexCount: Int get() = animatedVertexCapacity ?: vertices.size
    private val expectedVertexCount = vertices.size
    private var refreshing = false
    private val changed = InvalidationListener { value ->
        (value as? ObservableValue<*>)?.value
        if (!refreshing) { requestLayout(); invalidateRenderer() }
    }

    fun add(vertex: Int, start: Boolean, instance: SvgMarkerInstance, end: Boolean = false) {
        marks += Mark(vertex, start, end, instance)
        children += instance
    }

    fun attachSource(source: Node) {
        val visible = source.isVisible
        transforms.setAll(source.transforms)
        source.transforms.clear()
        if (source === styleShape) {
            val copy = SvgStrokeGeometry.copy(styleShape)
            copy.styleClass += "svg-marker-source"
            copy.fillProperty().bind(styleShape.fillProperty()); copy.strokeProperty().bind(styleShape.strokeProperty())
            copy.strokeWidthProperty().bind(styleShape.strokeWidthProperty()); copy.strokeTypeProperty().bind(styleShape.strokeTypeProperty())
            copy.strokeLineCapProperty().bind(styleShape.strokeLineCapProperty()); copy.strokeLineJoinProperty().bind(styleShape.strokeLineJoinProperty())
            copy.strokeMiterLimitProperty().bind(styleShape.strokeMiterLimitProperty()); copy.strokeDashOffsetProperty().bind(styleShape.strokeDashOffsetProperty())
            copy.smoothProperty().bind(styleShape.smoothProperty())
            Bindings.bindContent(copy.strokeDashArray, styleShape.strokeDashArray)
            copy.transforms += placement
            styleShape.isVisible = false
            styleShape.isManaged = false
            children.addAll(0, listOf(styleShape, copy))
        } else {
            // Apply element opacity once to the combined source and markers.
            source.opacityProperty().unbind()
            source.opacity = 1.0
            source.isVisible = true
            children.add(0, source)
        }
        opacityProperty().bind(styleShape.opacityProperty())
        isVisible = visible
        styleClass += "svg-marker-layer"
        listOf(styleShape.fillProperty(), styleShape.strokeProperty(), styleShape.strokeWidthProperty(),
            styleShape.boundsInLocalProperty(), styleShape.localToParentTransformProperty()).forEach { it.addListener(changed) }
    }

    fun geometryBounds(): Bounds = (svgTransformTo(styleShape, this) ?: Affine()).transform(svgGeometryBounds(styleShape))

    override fun layoutChildren() {
        refresh()
        super.layoutChildren()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            val nextKey = SvgMarkerGeometry.key(styleShape)
            if (key != nextKey) {
                vertices = SvgMarkerGeometry.vertices(styleShape, vertexLimit, basicShape)
                key = nextKey
            }
            if (animatedVertexCapacity == null && vertices.size != expectedVertexCount || animatedVertexCapacity != null && vertices.size > animatedVertexCapacity) {
                marks.forEach { it.instance.isVisible = false }
                report("Changing marker vertex count requires replacing the SVG document")
                return
            }
            if (vertices.isEmpty()) { marks.forEach { it.instance.isVisible = false }; return }
            val sourceToOwner = svgTransformTo(styleShape, this) ?: return
            placement.setToTransform(styleShape.localToParentTransform)
            val fixedHost = if (nonScaling) host()?.let { svgTransformTo(this, it) } else null
            marks.forEach { mark ->
                val instance = mark.instance
                val vertexIndex = if (animatedVertexCapacity != null && mark.end) vertices.lastIndex else mark.vertex
                if (vertexIndex > vertices.lastIndex || animatedVertexCapacity != null && !mark.start && !mark.end && vertexIndex >= vertices.lastIndex) {
                    instance.isVisible = false
                    return@forEach
                }
                val frame = instance.frame(sourceViewport)
                val unit = if (instance.spec.strokeUnits) styleShape.strokeWidth else 1.0
                val fixed = nonScaling && instance.spec.strokeUnits
                if (unit <= 0 || frame.width <= 0 || frame.height <= 0 ||
                    sourceToOwner.determinant() == 0.0 || fixed && (fixedHost == null || fixedHost.determinant() == 0.0)) {
                    instance.isVisible = false
                    return@forEach
                }
                instance.isVisible = true
                val vertex = vertices[vertexIndex]
                val matrix = if (fixed) checkNotNull(fixedHost).createConcatenation(sourceToOwner) else sourceToOwner
                val point = matrix.transform(vertex.point)
                val angle = instance.spec.angle(vertex, mark.start, if (fixed) matrix else null)
                val transform = if (fixed) checkNotNull(fixedHost).createInverse() else Affine()
                if (!fixed) {
                    transform.setToTransform(sourceToOwner)
                    transform.appendTranslation(vertex.point.x, vertex.point.y)
                } else transform.appendTranslation(point.x, point.y)
                transform.appendRotation(angle)
                transform.appendScale(unit, unit)
                transform.appendTranslation(-frame.refX, -frame.refY)
                instance.placement.setToTransform(transform)
                instance.context?.updates?.forEach { it() }
            }
        } catch (failure: IllegalArgumentException) {
            marks.forEach { it.instance.isVisible = false }
            report(failure.message ?: "Invalid SVG marker")
        } catch (failure: javafx.scene.transform.NonInvertibleTransformException) {
            marks.forEach { it.instance.isVisible = false }
            report(failure.message ?: "Noninvertible marker transform")
        } finally { refreshing = false }
    }
}
