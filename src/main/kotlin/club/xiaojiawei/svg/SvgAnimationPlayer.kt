package club.xiaojiawei.svg

import javafx.animation.Animation
import javafx.animation.AnimationTimer
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ChangeListener
import javafx.scene.Scene
import javafx.stage.Window
import javafx.stage.Stage
import javafx.util.Duration

/** One pulse clock per playing view; detached and hidden windows do not retain a running timer. */
internal class SvgAnimationPlayer(private val view: SvgView) {
    val autoPlay = SimpleBooleanProperty(view, "animationAutoPlay", true)
    val status = ReadOnlyObjectWrapper(view, "animationStatus", Animation.Status.STOPPED)
    val currentTime = ReadOnlyObjectWrapper(view, "animationTime", Duration.ZERO)
    val duration = ReadOnlyObjectWrapper(view, "animationDuration", Duration.ZERO)
    private var renderer: FxSvgRenderer? = null
    private var requested = false
    private var stopped = true
    private var generation = 0L
    private var commandRevision = 0L
    private val ancestors = mutableListOf<javafx.scene.Node>()
    private val ancestorChanged = javafx.beans.InvalidationListener { observeAncestors() }
    private var running = false
    private var lastPulse = 0L
    private var scene: Scene? = null
    private var window: Window? = null
    private val showing = ChangeListener<Boolean> { _, _, _ -> updateRunning() }
    private val sceneWindow = ChangeListener<Window> { _, _, _ -> observeWindow() }
    private val timer = object : AnimationTimer() {
        override fun handle(now: Long) {
            if (lastPulse != 0L) {
                val seconds = currentTime.get().toSeconds() + (now - lastPulse) / 1_000_000_000.0
                setTime(seconds)
            }
            lastPulse = if (running) now else 0L
        }
    }

    init {
        view.sceneProperty().addListener { _, _, next ->
            scene?.windowProperty()?.removeListener(sceneWindow)
            scene = next
            scene?.windowProperty()?.addListener(sceneWindow)
            observeWindow()
        }
        view.parentProperty().addListener { _, _, _ -> observeAncestors() }
        view.visibleProperty().addListener { _, _, _ -> updateRunning() }
        autoPlay.addListener { _, _, enabled ->
            check(javafx.application.Platform.isFxApplicationThread()) { "Animation controls require the JavaFX application thread" }
            if (enabled) play() else pause()
        }
    }

    private fun observeAncestors() {
        ancestors.forEach { it.visibleProperty().removeListener(ancestorChanged); it.parentProperty().removeListener(ancestorChanged) }
        ancestors.clear()
        var next = view.parent
        while (next != null) { ancestors += next; next = next.parent }
        ancestors.forEach { it.visibleProperty().addListener(ancestorChanged); it.parentProperty().addListener(ancestorChanged) }
        updateRunning()
    }

    private fun observeWindow() {
        window?.showingProperty()?.removeListener(showing)
        (window as? Stage)?.iconifiedProperty()?.removeListener(showing)
        window = scene?.window
        window?.showingProperty()?.addListener(showing)
        (window as? Stage)?.iconifiedProperty()?.addListener(showing)
        updateRunning()
    }

    fun install(next: FxSvgRenderer?) {
        val acceptedGeneration = ++generation
        commandRevision++
        timer.stop()
        running = false
        lastPulse = 0L
        renderer = next
        requested = autoPlay.get()
        stopped = !requested
        currentTime.set(Duration.ZERO)
        if (acceptedGeneration != generation) return
        duration.set(if (next?.animationDuration == Double.POSITIVE_INFINITY) Duration.INDEFINITE else Duration.seconds(next?.animationDuration ?: 0.0))
        if (acceptedGeneration != generation) return
        updateRunning()
    }

    fun play() {
        val revision = ++commandRevision
        if (renderer?.hasAnimations != true) return
        if (!duration.get().isIndefinite && currentTime.get().greaterThanOrEqualTo(duration.get())) setTime(0.0)
        if (revision != commandRevision) return
        requested = true
        stopped = false
        updateRunning()
    }

    fun pause() {
        commandRevision++
        requested = false
        updateRunning()
    }

    fun stop() {
        val revision = ++commandRevision
        requested = false
        setTime(0.0)
        if (revision != commandRevision) return
        stopped = true
        updateRunning()
    }

    fun seek(time: Duration) {
        require(!time.isUnknown && !time.isIndefinite && time.toMillis().isFinite() && time.toMillis() >= 0) {
            "Animation time must be finite and nonnegative"
        }
        commandRevision++
        setTime(time.toSeconds())
    }

    private fun setTime(seconds: Double) {
        val acceptedGeneration = generation
        val revision = commandRevision
        val limit = duration.get().toSeconds()
        val value = seconds.coerceAtMost(limit)
        renderer?.seekAnimations(value)
        currentTime.set(Duration.seconds(value))
        // Observers may replace the document or issue a newer playback command synchronously.
        if (acceptedGeneration != generation || revision != commandRevision) return
        view.requestLayout()
        if (value >= limit) {
            requested = false
            stopped = true
            updateRunning()
        }
    }

    private fun updateRunning() {
        val available = renderer?.hasAnimations == true && duration.get().toMillis() > 0
        val next = available && requested && view.isVisible && ancestors.all { it.isVisible } && window?.isShowing == true && (window as? Stage)?.isIconified != true
        if (next != running) {
            running = next
            lastPulse = 0L
            if (next) timer.start() else timer.stop()
        }
        status.set(when {
            !available || stopped -> Animation.Status.STOPPED
            next -> Animation.Status.RUNNING
            else -> Animation.Status.PAUSED
        })
    }
}
