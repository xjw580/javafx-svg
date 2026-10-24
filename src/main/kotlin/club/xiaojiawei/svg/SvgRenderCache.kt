package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.event.EventHandler
import javafx.scene.CacheHint
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.transform.Rotate
import javafx.scene.transform.Scale
import javafx.scene.transform.Shear
import javafx.scene.transform.Translate
import javafx.scene.transform.Transform
import javafx.scene.transform.TransformChangedEvent
import javafx.stage.Window
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Conservative device-pixel budgets without observing allocating local-to-scene snapshots. */
internal class SvgRenderCache(private val root: Node, private val nodes: List<Node>, private val limit: Int) {
    private var changing = false
    private var window: Window? = null
    private var scene: Scene? = null
    private val observed = IdentityHashMap<Node, LocalScale>()
    var enabled = true
        set(value) {
            if (field == value) return
            field = value
            observeHierarchy()
            refresh()
        }
    private val boundsChanged = InvalidationListener { refresh() }
    private val outputScaleChanged = InvalidationListener {
        window?.outputScaleX; window?.outputScaleY
        refresh()
    }
    private val parentChanged = ChangeListener<Parent?> { _, _, _ -> observeHierarchy(); refresh() }
    private val windowChanged = ChangeListener<Window?> { _, _, next -> watch(next) }

    /** The largest local stretch is invariant under translation and 2D rotation. */
    private inner class LocalScale(val node: Node) {
        var stretch = Double.NaN
        var twoDimensional = false
        private val properties = arrayOf(
            node.scaleXProperty(), node.scaleYProperty(), node.scaleZProperty(),
            node.translateZProperty(), node.rotateProperty(), node.rotationAxisProperty()
        )
        private val transforms = IdentityHashMap<Transform, TransformWatch>()
        private val changed = InvalidationListener { update() }
        private val transformed = EventHandler<TransformChangedEvent> { update() }
        private val listChanged = ListChangeListener<Transform> { bindTransforms(); update() }

        init {
            for (property in properties) property.addListener(changed)
            node.parentProperty().addListener(parentChanged)
            node.transforms.addListener(listChanged)
            bindTransforms()
            update(false)
        }
        private inner class TransformWatch(private val transform: Transform) {
            // Scalar listeners avoid allocating TransformChangedEvent for common application
            // transforms. Arbitrary/custom transforms retain the public event fallback.
            private val properties: Array<Observable>? = when (transform) {
                is Rotate -> arrayOf(transform.angleProperty(), transform.axisProperty())
                is Scale -> arrayOf(transform.xProperty(), transform.yProperty(), transform.zProperty())
                is Translate -> arrayOf(transform.zProperty())
                is Shear -> arrayOf(transform.xProperty(), transform.yProperty())
                else -> null
            }
            init {
                if (properties == null) transform.addEventHandler(TransformChangedEvent.TRANSFORM_CHANGED, transformed)
                else for (property in properties) property.addListener(changed)
            }
            fun dispose() {
                if (properties == null) transform.removeEventHandler(TransformChangedEvent.TRANSFORM_CHANGED, transformed)
                else for (property in properties) property.removeListener(changed)
            }
        }
        private fun bindTransforms() {
            for (binding in transforms.values) binding.dispose()
            transforms.clear()
            for (transform in node.transforms) if (!transforms.containsKey(transform))
                transforms[transform] = TransformWatch(transform)
        }
        private fun update(notify: Boolean = true) {
            // Reading these scalar properties also rearms their invalidation listeners.
            val x = node.scaleX; val y = node.scaleY; val z = node.scaleZ
            val tz = node.translateZ; val angle = node.rotate; val axis = node.rotationAxis
            var planar = z == 1.0 && tz == 0.0 && (angle == 0.0 || axis != null && axis.x == 0.0 && axis.y == 0.0)
            var size = max(abs(x), abs(y))
            for (transform in node.transforms) {
                planar = planar && transform.isType2D
                val a = transform.mxx; val b = transform.mxy; val c = transform.myx; val d = transform.myy
                val xx = a * a + c * c; val yy = b * b + d * d; val dot = a * b + c * d
                val norm = sqrt((xx + yy + hypot(xx - yy, 2.0 * dot)) / 2.0)
                // Round upward with a small margin: harmless rotation roundoff must not refresh
                // every view every pulse. Non-finite/oversized transforms remain uncached.
                size *= ceil((norm * (1.0 + 1e-12) + 1e-9) * 1e6) / 1e6
            }
            if (size != stretch || planar != twoDimensional) {
                stretch = size; twoDimensional = planar
                if (notify) refresh()
            }
        }
        fun dispose() {
            for (property in properties) property.removeListener(changed)
            node.parentProperty().removeListener(parentChanged)
            node.transforms.removeListener(listChanged)
            for (binding in transforms.values) binding.dispose()
            transforms.clear()
        }
    }

    init {
        nodes.forEach { it.cacheHint = CacheHint.ROTATE }
        observeHierarchy()
        root.sceneProperty().addListener { _, _, next ->
            scene?.windowProperty()?.removeListener(windowChanged)
            scene = next
            next?.windowProperty()?.addListener(windowChanged)
            observeHierarchy()
            watch(next?.window)
        }
        scene = root.scene
        scene?.windowProperty()?.addListener(windowChanged)
        watch(scene?.window)
    }

    private fun observeHierarchy() {
        for (state in observed.values) state.dispose()
        observed.clear()
        for (node in nodes) node.boundsInLocalProperty().removeListener(boundsChanged)
        if (!enabled || limit == 0) return
        for (node in nodes) {
            node.boundsInLocalProperty().addListener(boundsChanged)
            var ancestor: Node? = node
            while (ancestor != null && !observed.containsKey(ancestor)) {
                observed[ancestor] = LocalScale(ancestor)
                ancestor = ancestor.parent
            }
        }
    }

    private fun watch(next: Window?) {
        window?.outputScaleXProperty()?.removeListener(outputScaleChanged)
        window?.outputScaleYProperty()?.removeListener(outputScaleChanged)
        window = next
        next?.outputScaleXProperty()?.addListener(outputScaleChanged)
        next?.outputScaleYProperty()?.addListener(outputScaleChanged)
        refresh()
    }

    fun refresh() {
        if (changing) return
        if (!enabled || limit == 0) {
            for (node in nodes) node.isCache = false
            return
        }
        changing = true
        try {
            var remaining = limit.toDouble()
            for (node in nodes) {
                var stretch = 1.0
                var planar = true
                var ancestor: Node? = node
                while (ancestor != null) {
                    val state = observed[ancestor]
                    if (state == null || !state.twoDimensional) { planar = false; break }
                    stretch *= state.stretch
                    ancestor = ancestor.parent
                }
                val bounds = node.boundsInLocal
                // A transformed rectangle fits inside this scaled circle for every rotation,
                // reflection and shear. Product norms can overestimate, never relax the budget.
                val diameter = hypot(bounds.width, bounds.height) * stretch
                val w = ceil(diameter * (window?.outputScaleX ?: 1.0) + 2)
                val h = ceil(diameter * (window?.outputScaleY ?: 1.0) + 2)
                val pixels = w * h
                val allowed = root.parent != null && root.scene != null && planar &&
                    w > 2 && h > 2 && w <= 4096 && h <= 4096 && pixels.isFinite() && pixels <= remaining
                node.isCache = allowed
                if (allowed) remaining -= pixels
            }
        } finally { changing = false }
    }
}
