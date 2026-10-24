package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.value.ObservableValue
import javafx.scene.CacheHint
import javafx.scene.Node
import javafx.scene.Scene
import javafx.stage.Window
import kotlin.math.abs
import kotlin.math.ceil

/** Native bitmap caching for static content; scale changes are rerasterized by CacheHint.ROTATE. */
internal class SvgRenderCache(private val root: Node, private val nodes: List<Node>, private val limit: Int) {
    private var changing = false
    private var window: Window? = null
    private var scene: Scene? = null
    var enabled = true
        set(value) {
            if (field == value) return
            field=value; listen(value && limit > 0); refresh()
        }
    private val changed = InvalidationListener { value ->
        (value as? ObservableValue<*>)?.value
        refresh()
    }
    private val windowChanged = javafx.beans.value.ChangeListener<Window?> { _,_,next -> watch(next) }
    init {
        nodes.forEach {
            it.cacheHint=CacheHint.ROTATE
        }
        listen(limit > 0)
        root.sceneProperty().addListener { _,_,next ->
            scene?.windowProperty()?.removeListener(windowChanged)
            scene=next
            next?.windowProperty()?.addListener(windowChanged)
            watch(next?.window)
        }
        refresh()
    }
    private fun listen(add: Boolean) {
        nodes.forEach {
            if(add) {
                it.localToSceneTransformProperty().addListener(changed)
                it.boundsInLocalProperty().addListener(changed)
            } else {
                it.localToSceneTransformProperty().removeListener(changed)
                it.boundsInLocalProperty().removeListener(changed)
            }
        }
    }
    private fun watch(next: Window?) {
        window?.outputScaleXProperty()?.removeListener(changed)
        window?.outputScaleYProperty()?.removeListener(changed)
        window=next
        next?.outputScaleXProperty()?.addListener(changed)
        next?.outputScaleYProperty()?.addListener(changed)
        refresh()
    }
    fun refresh() {
        if(changing) return
        if(!enabled || limit == 0) { nodes.forEach { it.isCache=false }; return }
        changing=true
        try {
            var remaining=limit.toDouble()
            nodes.forEach { node ->
                val t=node.localToSceneTransform
                val b=node.boundsInLocal
                val w=ceil((abs(t.mxx)*b.width+abs(t.mxy)*b.height)*(window?.outputScaleX ?: 1.0)+2)
                val h=ceil((abs(t.myx)*b.width+abs(t.myy)*b.height)*(window?.outputScaleY ?: 1.0)+2)
                val pixels=w*h
                val allowed=enabled && root.parent != null && root.scene != null && t.isType2D && w>2 && h>2 &&
                    w<=4096 && h<=4096 && pixels.isFinite() && pixels<=remaining
                node.isCache=allowed
                if(allowed) remaining-=pixels
            }
        } finally { changing=false }
    }
}
