package club.xiaojiawei.svg

import javafx.animation.Animation
import javafx.animation.AnimationTimer
import javafx.beans.value.ChangeListener
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.Node
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgAnimationLifecycleTest {
    @TempDir lateinit var directory: Path
    private fun document(repeat: Boolean = true): SvgDocument {
        val repeats = if (repeat) "indefinite" else "1"
        return SvgLoader.parse("""<svg viewBox="0 0 24 24"><circle id="p" cx="12" cy="12" r="8"><animate attributeName="r" values="4;8;4" dur="100ms" repeatCount="$repeats"/></circle></svg>""")
    }
    private fun window(root: Group) = Stage().apply { opacity = 0.0; scene = Scene(root, 240.0, 240.0); show() }
    private fun pulses(count: Int = 4) {
        val done = CountDownLatch(1)
        FxTest.run {
            object : AnimationTimer() {
                var remaining = count
                override fun handle(now: Long) { if (--remaining == 0) { stop(); done.countDown() } }
            }.start()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "JavaFX pulses timed out")
    }
    private fun collected(references: List<WeakReference<*>>) {
        repeat(50) {
            System.gc()
            pulses(2)
            if (references.all { it.get() == null }) return
        }
        assertEquals(0, references.count { it.get() != null }, "Detached animation objects remain reachable")
    }

    @Test fun pauseHideDetachAndCloseStopTimeAndReattachResumes() {
        lateinit var stage: Stage
        lateinit var root: Group
        lateinit var view: SvgView
        FxTest.run { view = SvgView(document()); root = Group(view); stage = window(root) }
        try {
            pulses()
            for (operation in listOf("pause", "view", "ancestor", "window", "detach", "close")) {
                lateinit var time: Duration
                FxTest.run {
                    when (operation) {
                        "pause" -> view.pauseAnimations()
                        "view" -> view.isVisible = false
                        "ancestor" -> root.isVisible = false
                        "window" -> stage.hide()
                        "detach" -> root.children.clear()
                        "close" -> stage.close()
                    }
                    time = view.animationTime
                    assertEquals(Animation.Status.PAUSED, view.animationStatus, operation)
                }
                pulses()
                FxTest.run {
                    assertEquals(time, view.animationTime, operation)
                    when (operation) {
                        "pause" -> view.playAnimations()
                        "view" -> view.isVisible = true
                        "ancestor" -> root.isVisible = true
                        "window", "close" -> stage.show()
                        "detach" -> root.children += view
                    }
                    assertEquals(Animation.Status.RUNNING, view.animationStatus, operation)
                }
                pulses()
                FxTest.run { assertTrue(view.animationTime.greaterThan(time), operation) }
            }
        } finally { FxTest.run { stage.close(); root.children.clear() } }
    }

    @Test fun minimizedStageSuspendsAndRestoringResumes() {
        lateinit var stage: Stage
        lateinit var anchor: Stage
        lateinit var view: SvgView
        FxTest.run { anchor = window(Group()); view = SvgView(document()); stage = window(Group(view)) }
        try {
            pulses()
            lateinit var time: Duration
            FxTest.run { stage.isIconified = true; assertTrue(stage.isIconified); time = view.animationTime }
            pulses()
            FxTest.run {
                assertEquals(Animation.Status.PAUSED, view.animationStatus)
                assertEquals(time, view.animationTime)
                stage.isIconified = false
                assertEquals(Animation.Status.RUNNING, view.animationStatus)
            }
            pulses()
            FxTest.run { assertTrue(view.animationTime.greaterThan(time)) }
        } finally { FxTest.run { stage.close(); anchor.close() } }
    }

    @Test fun detachedViewsAreCollectibleWhileDocumentCacheAndWindowStayAlive() {
        val source = directory.resolve("animated.svg")
        Files.writeString(source, """<svg><circle r="8"><animate attributeName="r" values="4;8" dur="1s" repeatCount="indefinite"/></circle></svg>""")
        val cache = SvgResourceCache()
        val retainedDocument = cache.load(source)
        lateinit var stage: Stage
        lateinit var root: Group
        lateinit var references: List<WeakReference<SvgView>>
        FxTest.run {
            root = Group(); stage = window(root)
            references = List(40) {
                val view = SvgView().apply { resourceCache = cache; this.source = source.toUri().toString() }
                assertSame(retainedDocument, view.document)
                root.children += view
                assertEquals(Animation.Status.RUNNING, view.animationStatus)
                WeakReference(view)
            }
        }
        try {
            pulses()
            FxTest.run { root.children.clear() }
            collected(references)
            FxTest.run { assertTrue(stage.isShowing); assertTrue(root.children.isEmpty()) }
            assertEquals(1, cache.size)
            assertSame(retainedDocument, cache.load(source))
        } finally { FxTest.run { stage.close() } }
    }

    @Test fun sourceAndDocumentReplacementReleaseOldRenderersWhileViewStaysAlive() {
        val a = directory.resolve("a.svg")
        val b = directory.resolve("b.svg")
        Files.writeString(a, """<svg><circle id="p" r="8"><animate attributeName="r" values="4;8" dur="1s" repeatCount="indefinite"/></circle></svg>""")
        Files.writeString(b, """<svg><rect id="p" width="10" height="10"><animate attributeName="width" values="10;20" dur="1s" repeatCount="indefinite"/></rect></svg>""")
        val cache = SvgResourceCache()
        val retainedDocument = cache.load(a)
        lateinit var stage: Stage
        lateinit var view: SvgView
        lateinit var references: List<WeakReference<Node>>
        FxTest.run {
            view = SvgView().apply { resourceCache = cache }; stage = window(Group(view))
            references = List(40) { index ->
                if (index % 3 == 0) view.document = retainedDocument
                else view.source = (if (index % 2 == 0) a else b).toUri().toString()
                WeakReference(checkNotNull(view.lookup("#p")))
            }
            view.document = document()
        }
        try {
            pulses()
            collected(references)
            FxTest.run {
                assertTrue(stage.isShowing)
                assertEquals(Animation.Status.RUNNING, view.animationStatus)
                view.document = SvgLoader.parse("<svg><circle r=\"8\"/></svg>")
                assertEquals(Animation.Status.STOPPED, view.animationStatus)
            }
            assertSame(retainedDocument, cache.load(a))
        } finally { FxTest.run { stage.close(); (view.parent as Group).children.clear() } }
    }

    @Test fun timeObserverCanRestartFiniteAnimationAtItsEndpoint() {
        lateinit var stage: Stage
        lateinit var view: SvgView
        val repeated = CountDownLatch(2)
        FxTest.run {
            view = SvgView(document(false))
            view.animationTimeProperty().addListener { _, _, time ->
                if (time.greaterThanOrEqualTo(view.animationDuration)) { repeated.countDown(); view.playAnimations() }
            }
            stage = window(Group(view))
        }
        try { assertTrue(repeated.await(3, TimeUnit.SECONDS), "Endpoint observer replay was overwritten by the finished pulse") }
        finally { FxTest.run { view.stopAnimations(); stage.close() } }
    }

    @Test fun newestReentrantControlWinsOverOuterStopAndSeek() = FxTest.run {
        val view = SvgView(document(false)).apply { animationAutoPlay = false }
        val stage = window(Group(view))
        try {
            view.seekAnimations(Duration.millis(50.0))
            val onReset = ChangeListener<Duration> { _, _, time -> if (time == Duration.ZERO) view.playAnimations() }
            view.animationTimeProperty().addListener(onReset)
            view.stopAnimations()
            assertEquals(Animation.Status.RUNNING, view.animationStatus)
            view.animationTimeProperty().removeListener(onReset)
            val onEnd = ChangeListener<Duration> { _, _, time ->
                if (time.greaterThanOrEqualTo(view.animationDuration)) view.seekAnimations(Duration.millis(25.0))
            }
            view.animationTimeProperty().addListener(onEnd)
            view.seekAnimations(view.animationDuration)
            assertEquals(25.0, view.animationTime.toMillis())
            assertEquals(Animation.Status.RUNNING, view.animationStatus)
            view.animationTimeProperty().removeListener(onEnd)
        } finally { stage.close() }
    }

    @Test fun commandsDuringDocumentResetKeepNewDurationAndRequestedState() = FxTest.run {
        val view = SvgView(document()).apply { animationAutoPlay = false }
        view.seekAnimations(Duration.millis(50.0))
        view.animationTimeProperty().addListener { _, _, time -> if (time == Duration.ZERO) view.pauseAnimations() }
        view.animationAutoPlay = true
        view.document = document(false)
        assertEquals(Duration.millis(100.0), view.animationDuration)
        assertEquals(Animation.Status.PAUSED, view.animationStatus)
    }
}
