package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.value.ObservableValue
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.geometry.Rectangle2D
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.scene.transform.Affine
import javafx.scene.transform.Scale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A per-renderer steady-state pixel budget; input/output images may coexist while refreshing. */
internal class SvgMaskBudget(private val limit: Int) {
    private val sizes = java.util.IdentityHashMap<SvgMask, Long>()
    private var used = 0L
    fun reserve(owner: SvgMask, pixels: Long) {
        val next = used - (sizes[owner] ?: 0) + pixels
        require(next <= limit) { "SVG masks exceed maxMaskPixels ($limit)" }
        sizes[owner] = pixels
        used = next
    }
    fun release(owner: SvgMask) { used -= sizes.remove(owner) ?: 0 }
}

/** Keeps mask shapes in the CSS tree, but renders only their alpha image as the source's clip. */
internal class SvgMask(
    private val source: Node,
    maskContent: Node,
    private val definition: SvgElement,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache,
    private val budget: SvgMaskBudget,
    private val invalidateRenderer: () -> Unit,
    private val report: (String) -> Unit
) : Group() {
    private val proportionalRegion = units(definition["maskUnits"] ?: "objectBoundingBox")
    private val proportionalContent = units(definition["maskContentUnits"] ?: "userSpaceOnUse")
    private val contentTransform = Affine()
    private val mapped = Group(maskContent).apply { transforms += contentTransform }
    private val regionClip = Rectangle()
    private val mask = Group(mapped).apply { clip = regionClip }
    private val placement = Affine()
    private val image = ImageView().apply { isSmooth = true; transforms += placement }
    private val output = Group(source).apply { clip = image }
    // Keep peers tree-visible for snapshot updates, while excluding this holder from drawing/bounds.
    private val hidden = Group(mask).apply { opacity = 0.0; clip = Rectangle(); isManaged = false; isMouseTransparent = true }
    private var dirty = true
    private var refreshing = false
    private var enabled = false
    private var previous: List<Double>? = null
    private val changed = InvalidationListener { observable ->
        // Validate lazy properties so another change can invalidate the raster again.
        (observable as? ObservableValue<*>)?.value
        if (!refreshing) { dirty = true; requestLayout(); invalidateRenderer() }
    }

    private var observedWindow: javafx.stage.Window? = null
    private val windowChanged = javafx.beans.value.ChangeListener<javafx.stage.Window?> { _, _, next -> watchWindow(next) }

    private fun watchWindow(next: javafx.stage.Window?) {
        if (observedWindow === next) return
        observedWindow?.outputScaleXProperty()?.removeListener(changed)
        observedWindow?.outputScaleYProperty()?.removeListener(changed)
        observedWindow = next
        next?.outputScaleXProperty()?.addListener(changed)
        next?.outputScaleYProperty()?.addListener(changed)
        dirty = true
        requestLayout()
        invalidateRenderer()
    }

    init {
        sceneProperty().addListener { _, previous, next ->
            previous?.windowProperty()?.removeListener(windowChanged)
            next?.windowProperty()?.addListener(windowChanged)
            watchWindow(next?.window)
        }
        transforms.setAll(source.transforms)
        source.transforms.clear()
        children.setAll(output, hidden)
        styleClass += "svg-mask-output"
        observe(mask)
        source.localToSceneTransformProperty().addListener(changed)
    }

    fun geometryBounds(): Bounds = source.localToParentTransform.transform(svgGeometryBounds(source))
    fun dispose() { budget.release(this) }

    override fun layoutChildren() {
        if (enabled) rasterize()
        super.layoutChildren()
    }

    fun refresh() {
        enabled = true
        rasterize()
    }

    private fun rasterize() {
        if (refreshing) return
        refreshing = true
        try {
            val bounds = svgGeometryBounds(source)
            placement.setToTransform(source.localToParentTransform)
            if (proportionalContent) contentTransform.setToTransform(bounds.width, 0.0, bounds.minX, 0.0, bounds.height, bounds.minY)
            val region = region(bounds)
            regionClip.x = region.minX; regionClip.y = region.minY
            regionClip.width = region.width; regionClip.height = region.height
            val visible = source.boundsInLocal
            val x = max(region.minX, visible.minX)
            val y = max(region.minY, visible.minY)
            val width = min(region.maxX, visible.maxX) - x
            val height = min(region.maxY, visible.maxY) - y
            if (width <= 0 || height <= 0) { clear(); return }
            val sceneTransform = source.localToSceneTransform
            val window = scene?.window
            val sx = hypot(sceneTransform.mxx, sceneTransform.myx) * (window?.outputScaleX ?: 1.0)
            val sy = hypot(sceneTransform.mxy, sceneTransform.myy) * (window?.outputScaleY ?: 1.0)
            if (sx == 0.0 || sy == 0.0) { clear(); return }
            require(sx.isFinite() && sy.isFinite()) { "Non-finite mask scale" }
            val left = floor(x * sx)
            val top = floor(y * sy)
            val pixelWidth = ceil((x + width) * sx) - left
            val pixelHeight = ceil((y + height) * sy) - top
            require(pixelWidth in 1.0..4096.0 && pixelHeight in 1.0..4096.0) { "SVG mask raster exceeds 4096 pixels per axis" }
            val key = listOf(left, top, pixelWidth, pixelHeight, sx, sy) +
                if (proportionalContent) listOf(bounds.minX, bounds.minY, bounds.width, bounds.height) else emptyList()
            if (!dirty && key == previous) return
            budget.reserve(this, pixelWidth.toLong() * pixelHeight.toLong())
            val params = SnapshotParameters().apply {
                fill = Color.TRANSPARENT
                transform = Scale(sx, sy)
                this.viewport = Rectangle2D(left, top, pixelWidth, pixelHeight)
            }
            val snapshot = mask.snapshot(params, null)
            image.x = left / sx; image.y = top / sy
            image.fitWidth = pixelWidth / sx; image.fitHeight = pixelHeight / sy
            image.image = snapshot
            previous = key
            dirty = false
        } catch (failure: IllegalArgumentException) {
            clear()
            report(failure.message ?: "Invalid SVG mask")
        } finally { refreshing = false }
    }

    private fun clear() {
        image.image = null
        budget.release(this)
        previous = null
        dirty = true
    }

    private fun region(bounds: Bounds): Bounds {
        fun value(name: String, horizontal: Boolean, position: Boolean): Double {
            val amount = lengths.resolve(definition[name] ?: if (position) "-10%" else "120%",
                if (proportionalRegion) 1.0 else if (horizontal) viewport.width else viewport.height)
            val extent = if (horizontal) bounds.width else bounds.height
            val origin = if (horizontal) bounds.minX else bounds.minY
            return if (proportionalRegion) amount * extent + if (position) origin else 0.0 else amount
        }
        val x = value("x", true, true)
        val y = value("y", false, true)
        val width = value("width", true, false)
        val height = value("height", false, false)
        require(listOf(x, y, width, height).all { it.isFinite() }) { "Non-finite mask region" }
        return BoundingBox(x, y, max(0.0, width), max(0.0, height))
    }

    private fun observe(node: Node) {
        node.boundsInLocalProperty().addListener(changed)
        node.localToParentTransformProperty().addListener(changed)
        node.opacityProperty().addListener(changed)
        node.visibleProperty().addListener(changed)
        node.effectProperty().addListener(changed)
        node.clipProperty().addListener(changed)
        node.clip?.let(::observe)
        if (node is Shape) {
            node.fillProperty().addListener(changed)
            node.strokeProperty().addListener(changed)
            node.strokeWidthProperty().addListener(changed)
        }
        if (node is Text) node.fontProperty().addListener(changed)
        if (node is ImageView) node.imageProperty().addListener(changed)
        if (node is Group) node.children.forEach(::observe)
    }

    private fun units(value: String): Boolean = when (value) {
        "objectBoundingBox" -> true
        "userSpaceOnUse" -> false
        else -> throw SvgException("Invalid mask coordinate units: $value")
    }
}
