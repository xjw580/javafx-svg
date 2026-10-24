package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.value.ObservableValue
import javafx.geometry.Bounds
import javafx.geometry.Rectangle2D
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.Paint
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.scene.transform.Affine
import javafx.scene.transform.Scale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

internal class SvgPatternBudget(private val limit: Int) {
    private val sizes = java.util.IdentityHashMap<SvgPattern, Long>()
    private var used = 0L
    fun reserve(owner: SvgPattern, pixels: Long) {
        val next = used - (sizes[owner] ?: 0) + pixels
        require(next <= limit) { "SVG patterns exceed maxPatternPixels ($limit)" }
        sizes[owner] = pixels; used = next
    }
    fun release(owner: SvgPattern) { used -= sizes.remove(owner) ?: 0 }
}

/** Per-target paint server. Only the tile and the current target raster are retained. */
internal class SvgPattern(
    private val target: Node, val definition: SvgElement, private val viewport: SvgViewport,
    private val lengths: SvgLengthCache, private val budget: SvgPatternBudget,
    private val invalidateRenderer: () -> Unit
) {
    val content = Group()
    private val contentMapping = Affine()
    private val mapped = Group(content).apply { transforms += contentMapping }
    val hidden = Group(mapped).apply { opacity = 0.0; clip = javafx.scene.shape.Rectangle(); isManaged = false; isMouseTransparent = true }
    private val box = definition["viewBox"]?.let(SvgBox::parse)
    private val proportional = units(definition["patternUnits"] ?: "objectBoundingBox")
    private val proportionalContent = units(definition["patternContentUnits"] ?: "userSpaceOnUse")
    val contentViewport = SvgViewport(box?.width ?: if (proportionalContent) 1.0 else viewport.width,
        box?.height ?: if (proportionalContent) 1.0 else viewport.height, box?.x ?: 0.0, box?.y ?: 0.0)
    private var dirty = true
    private var refreshing = false
    private var previous: List<Double>? = null
    private var cached: Paint = Color.TRANSPARENT
    private val changed = InvalidationListener { observable ->
        (observable as? ObservableValue<*>)?.value
        if (!refreshing) { dirty = true; invalidateRenderer() }
    }
    private val scaleChanged = InvalidationListener { observable ->
        (observable as? ObservableValue<*>)?.value
        // The cache key detects scale; pure rotation must not recreate the raster.
        if (!refreshing) invalidateRenderer()
    }

    init { target.localToSceneTransformProperty().addListener(scaleChanged) }

    fun observe() {
        fun visit(node: Node) {
            node.boundsInLocalProperty().addListener(changed)
            node.localToParentTransformProperty().addListener(changed)
            node.opacityProperty().addListener(changed)
            node.visibleProperty().addListener(changed)
            if (node is Shape) {
                node.fillProperty().addListener(changed); node.strokeProperty().addListener(changed)
                node.strokeWidthProperty().addListener(changed)
            }
            if (node is Text) node.fontProperty().addListener(changed)
            if (node is Group) node.children.forEach(::visit)
        }
        visit(content)
    }
    fun dispose() { budget.release(this); target.localToSceneTransformProperty().removeListener(scaleChanged); hidden.children.clear() }

    fun prepare() {
        contentViewport.width = box?.width ?: if (proportionalContent) 1.0 else viewport.width
        contentViewport.height = box?.height ?: if (proportionalContent) 1.0 else viewport.height
    }

    fun paint(opacity: Double): Paint {
        if (refreshing) return cached
        refreshing = true
        try {
            val bounds = svgGeometryBounds(target)
            val sx = hypot(target.localToSceneTransform.mxx, target.localToSceneTransform.myx) * (target.scene?.window?.outputScaleX ?: 1.0)
            val sy = hypot(target.localToSceneTransform.mxy, target.localToSceneTransform.myy) * (target.scene?.window?.outputScaleY ?: 1.0)
            fun coordinate(name: String, horizontal: Boolean): Double {
                val extent = if (horizontal) bounds.width else bounds.height
                val origin = if (horizontal) bounds.minX else bounds.minY
                val value = lengths.resolve(definition[name] ?: "0", if (proportional) 1.0 else if (horizontal) viewport.width else viewport.height)
                return if (proportional) value * extent + if (name == "x" || name == "y") origin else 0.0 else value
            }
            val x = coordinate("x",true); val y = coordinate("y",false)
            val w = coordinate("width",true); val h = coordinate("height",false)
            require(w >= 0 && h >= 0) { "Negative pattern dimensions" }
            if (w == 0.0 || h == 0.0 || bounds.width <= 0 || bounds.height <= 0 || sx == 0.0 || sy == 0.0) {
                budget.release(this); previous=null; cached=Color.TRANSPARENT; return cached
            }
            val transform = SvgTransforms.parse(definition["patternTransform"])
            val mapping = if (proportional) Affine(bounds.width,0.0,bounds.minX,0.0,bounds.height,bounds.minY).apply {
                append(transform); append(Affine(1/bounds.width,0.0,-bounds.minX/bounds.width,0.0,1/bounds.height,-bounds.minY/bounds.height))
            } else transform
            val inverse = mapping.createInverse()
            val extra = if (target is Shape && target.stroke != null) target.strokeWidth * max(1.0,target.strokeMiterLimit) else 0.0
            val left=bounds.minX-extra; val top=bounds.minY-extra
            val width=bounds.width+2*extra; val height=bounds.height+2*extra
            val tw=ceil(w*sx).coerceAtLeast(1.0); val th=ceil(h*sy).coerceAtLeast(1.0)
            val pw=ceil(width*sx); val ph=ceil(height*sy)
            require(listOf(tw,th,pw,ph).all { it.isFinite() && it in 1.0..4096.0 }) { "SVG pattern raster exceeds 4096 pixels per axis" }
            val key=listOf(x,y,w,h,left,top,width,height,tw,th,pw,ph,opacity,bounds.width,bounds.height)
            if (!dirty && previous == key) return cached
            budget.reserve(this,tw.toLong()*th.toLong()+pw.toLong()*ph.toLong())
            if (box != null) contentMapping.setToTransform(box.transform(w,h,definition["preserveAspectRatio"]))
            else if (proportionalContent) contentMapping.setToTransform(bounds.width,0.0,0.0,0.0,bounds.height,0.0)
            else contentMapping.setToIdentity()
            val tile=mapped.snapshot(SnapshotParameters().apply {
                fill=Color.TRANSPARENT; this.transform=Scale(tw/w,th/h)
                this.viewport=Rectangle2D(0.0,0.0,tw,th)
            },null)
            val canvas=Canvas(pw,ph)
            val gc=canvas.graphicsContext2D
            gc.globalAlpha=opacity
            gc.setTransform(pw/width,0.0,0.0,ph/height,-left*pw/width,-top*ph/height)
            gc.transform(mapping)
            gc.fill=ImagePattern(tile,x,y,w,h,false)
            val cover=inverse.transform(javafx.geometry.BoundingBox(left,top,width,height))
            gc.fillRect(cover.minX,cover.minY,cover.width,cover.height)
            val image=canvas.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null)
            cached=ImagePattern(image,left,top,width,height,false)
            previous=key; dirty=false
            return cached
        } finally { refreshing=false }
    }
    private fun units(value: String) = when(value) {
        "objectBoundingBox" -> true
        "userSpaceOnUse" -> false
        else -> throw SvgException("Invalid pattern coordinate units: $value")
    }
}
