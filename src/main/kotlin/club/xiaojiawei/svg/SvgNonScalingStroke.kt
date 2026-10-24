package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.beans.value.ChangeListener
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.*
import javafx.scene.shape.Path
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine

/** Keep the source Shape as the CSS target; draw a host-space stroke outline back in source space. */
internal class SvgNonScalingStroke(
    val styleShape: Shape,
    private val host: () -> Node?,
    private val invalidateRenderer: () -> Unit,
    private val report: (String) -> Unit
) : Group() {
    private val fillShape = SvgStrokeGeometry.copy(styleShape)
    private val strokeShape = Path()
    private val nativeStroke = SvgStrokeGeometry.copy(styleShape)
    private val centerline = Path()
    private val empty = Path()
    private val placement = Affine()
    private val paintMapping = Affine()
    private val strokePaint = SimpleObjectProperty<Paint?>()
    private val noPaint = SimpleObjectProperty<Paint?>(null)
    private var dirty = true
    private var previous: List<Double>? = null
    private var refreshing = false
    private val changed = InvalidationListener { observable ->
        (observable as? ObservableValue<*>)?.value
        if (!refreshing) {
            dirty = true
            requestLayout()
            invalidateRenderer()
        }
    }

    init {
        styleClass += "svg-non-scaling-stroke"
        styleShape.isVisible = false
        styleShape.isManaged = false
        fillShape.styleClass += "svg-vector-fill"
        strokeShape.styleClass += "svg-vector-stroke"
        nativeStroke.styleClass += "svg-native-vector-stroke"
        nativeStroke.fillProperty().bind(noPaint)
        nativeStroke.strokeProperty().bind(styleShape.strokeProperty())
        nativeStroke.smoothProperty().bind(styleShape.smoothProperty())
        nativeStroke.transforms.setAll(placement)
        nativeStroke.isVisible = false
        fillShape.fillProperty().bind(styleShape.fillProperty())
        strokeShape.fillProperty().bind(strokePaint)
        fillShape.strokeProperty().bind(noPaint)
        strokeShape.strokeProperty().bind(noPaint)
        fillShape.smoothProperty().bind(styleShape.smoothProperty())
        strokeShape.smoothProperty().bind(styleShape.smoothProperty())
        fillShape.transforms.setAll(placement)
        strokeShape.transforms.setAll(placement, paintMapping)
        opacityProperty().bind(styleShape.opacityProperty())
        children.setAll(styleShape, fillShape, strokeShape, nativeStroke)
        listOf(styleShape.boundsInLocalProperty(), styleShape.strokeWidthProperty(), styleShape.strokeTypeProperty(),
            styleShape.strokeLineCapProperty(), styleShape.strokeLineJoinProperty(), styleShape.strokeMiterLimitProperty(),
            styleShape.strokeDashOffsetProperty(), styleShape.localToParentTransformProperty()).forEach { it.addListener(changed) }
        styleShape.strokeDashArray.addListener(changed)
        styleShape.strokeProperty().addListener(ChangeListener { _, _, _ -> changed.invalidated(styleShape.strokeProperty()) })
    }

    fun geometryBounds(): Bounds = styleShape.localToParentTransform.transform(svgGeometryBounds(styleShape))

    override fun layoutChildren() {
        refresh()
        super.layoutChildren()
    }

    private fun updatePaint(): javafx.scene.transform.Transform? {
        val paint = styleShape.stroke
        val proportional = when (paint) {
            is LinearGradient -> paint.isProportional
            is RadialGradient -> paint.isProportional
            is ImagePattern -> paint.isProportional
            else -> false
        }
        paintMapping.setToIdentity()
        strokePaint.set(paint)
        if (!proportional) return null
        val bounds = svgGeometryBounds(styleShape)
        if (bounds.width <= 0 || bounds.height <= 0) {
            strokePaint.set(Color.TRANSPARENT)
            return null
        }
        // JavaFX linear gradients project onto endpoints after bbox scaling; radial/image paints
        // use a scaled paint coordinate system. Both must retain the source geometry's bbox.
        if (paint is LinearGradient) {
            strokePaint.set(LinearGradient(bounds.minX + paint.startX * bounds.width,
                bounds.minY + paint.startY * bounds.height, bounds.minX + paint.endX * bounds.width,
                bounds.minY + paint.endY * bounds.height, false, paint.cycleMethod, paint.stops))
            return null
        }
        paintMapping.setToTransform(bounds.width, 0.0, bounds.minX, 0.0, bounds.height, bounds.minY)
        strokePaint.set(when (paint) {
            is RadialGradient -> RadialGradient(paint.focusAngle, paint.focusDistance, paint.centerX,
                paint.centerY, paint.radius, false, paint.cycleMethod, paint.stops)
            is ImagePattern -> ImagePattern(paint.image, paint.x, paint.y, paint.width, paint.height, false)
            else -> paint
        })
        return paintMapping.createInverse()
    }

    private fun similarity(mapping: Affine): Boolean {
        val x = mapping.mxx * mapping.mxx + mapping.myx * mapping.myx
        val y = mapping.mxy * mapping.mxy + mapping.myy * mapping.myy
        val dot = mapping.mxx * mapping.mxy + mapping.myx * mapping.myy
        val tolerance = kotlin.math.max(x,y) * 1e-10
        return x > 0 && x.isFinite() && y.isFinite() && kotlin.math.abs(x-y) <= tolerance && kotlin.math.abs(dot) <= tolerance
    }

    fun refresh() {
        if (refreshing) return
        val target = host() ?: return
        var node: Node? = this
        val mapping = Affine()
        // Stop at the SVG viewport: transforms on SvgView or the application's parents are external.
        while (node !== target) {
            if (node == null) return
            val transform = node.localToParentTransform
            if (!transform.isType2D) { report("non-scaling-stroke requires a 2D SVG transform"); return }
            mapping.prepend(transform)
            node = node.parent
        }
        mapping.append(styleShape.localToParentTransform)
        // Translation cancels out when the outline is mapped back; it does not require regeneration.
        val linear = Affine(mapping.mxx, mapping.mxy, 0.0, mapping.myx, mapping.myy, 0.0)
        val key = listOf(linear.mxx, linear.mxy, linear.myx, linear.myy)
        if (!dirty && previous == key) return
        refreshing = true
        try {
            placement.setToTransform(styleShape.localToParentTransform)
            require(mapping.isType2D) { "non-scaling-stroke requires a 2D SVG transform" }
            nativeStroke.isVisible = false
            strokeShape.isVisible = true
            require(key.all { it.isFinite() }) { "Non-finite stroke transform" }
            val determinant = linear.determinant()
            require(determinant.isFinite()) { "Non-finite stroke transform determinant" }
            if (determinant == 0.0 || styleShape.strokeWidth <= 0) {
                strokeShape.elements.clear()
            } else if (similarity(linear)) {
                val scale = kotlin.math.hypot(linear.mxx, linear.myx)
                nativeStroke.strokeWidth = styleShape.strokeWidth / scale
                nativeStroke.strokeType = styleShape.strokeType
                nativeStroke.strokeLineCap = styleShape.strokeLineCap
                nativeStroke.strokeLineJoin = styleShape.strokeLineJoin
                nativeStroke.strokeMiterLimit = styleShape.strokeMiterLimit
                nativeStroke.strokeDashOffset = styleShape.strokeDashOffset / scale
                nativeStroke.strokeDashArray.setAll(styleShape.strokeDashArray.map { it / scale })
                strokeShape.isVisible = false
                nativeStroke.isVisible = true
                strokeShape.elements.clear()
            } else {
                val sourceToPaint = updatePaint()
                centerline.elements.setAll(SvgStrokeGeometry.transform(SvgStrokeGeometry.centerline(styleShape), linear))
                centerline.fill = null
                centerline.stroke = Color.BLACK
                centerline.strokeWidth = styleShape.strokeWidth
                centerline.strokeType = styleShape.strokeType
                centerline.strokeLineCap = styleShape.strokeLineCap
                centerline.strokeLineJoin = styleShape.strokeLineJoin
                centerline.strokeMiterLimit = styleShape.strokeMiterLimit
                centerline.strokeDashOffset = styleShape.strokeDashOffset
                centerline.strokeDashArray.setAll(styleShape.strokeDashArray)
                val outline = Shape.union(centerline, empty) as Path
                strokeShape.fillRule = outline.fillRule
                val inverse = linear.createInverse()
                if (sourceToPaint != null) inverse.prepend(sourceToPaint)
                strokeShape.elements.setAll(SvgStrokeGeometry.transform(outline.elements, inverse))
            }
            dirty = false
            previous = key
        } catch (failure: IllegalArgumentException) {
            strokeShape.elements.clear()
            report(failure.message ?: "Invalid non-scaling stroke")
        } catch (failure: javafx.scene.transform.NonInvertibleTransformException) {
            strokeShape.elements.clear()
            report(failure.message ?: "Noninvertible stroke transform")
        } finally { refreshing = false }
    }
}
