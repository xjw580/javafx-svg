package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import javafx.animation.Animation
import javafx.beans.value.ChangeListener
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgPlaygroundTest {
    private val red = """<svg viewBox="0 0 100 100"><circle id="p" cx="50" cy="50" r="30" fill="red"/></svg>"""
    private fun result(editor: SvgPlayground): CountDownLatch {
        val ready=CountDownLatch(1)
        val listener=object : ChangeListener<String> {
            override fun changed(observable: javafx.beans.value.ObservableValue<out String>, old: String?, text: String?) {
                if(text?.startsWith("已更新") == true || "editor-error" in editor.statusLabel.styleClass) {
                    editor.statusLabel.textProperty().removeListener(this)
                    ready.countDown()
                }
            }
        }
        editor.statusLabel.textProperty().addListener(listener)
        return ready
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(8,TimeUnit.SECONDS),"Live preview timed out")

    @Test fun editsUpdateShapesAndRenderingErrorsKeepLastPreview() {
        var created=false
        lateinit var editor: SvgPlayground
        lateinit var ready: CountDownLatch
        try {
            FxTest.run {
                editor=SvgPlayground(red)
                Scene(editor,1100.0,720.0).stylesheets+=javaClass.getResource("/demo/demo.css")!!.toExternalForm()
                editor.resize(1100.0,720.0); editor.applyCss(); editor.layout()
                created=true
                ready=result(editor)
            }
            await(ready)
            FxTest.run {
                assertEquals(Color.RED,(editor.currentPreview.lookup("#p") as Circle).fill)
                created=true
                ready=result(editor)
                editor.codeArea.text=red.replace("red","blue")
                editor.refreshNow()
            }
            await(ready)
            FxTest.run {
                assertEquals(Color.BLUE,(editor.currentPreview.lookup("#p") as Circle).fill)
                created=true
                ready=result(editor)
                editor.codeArea.text="""<svg><path d="BROKEN"/></svg>"""
                editor.refreshNow()
            }
            await(ready)
            FxTest.run {
                assertEquals(Color.BLUE,(editor.currentPreview.lookup("#p") as Circle).fill)
                assertContains(editor.statusLabel.text,"保留上一次有效预览")
                assertContains(editor.statusLabel.styleClass,"editor-error")
                created=true
                ready=result(editor)
                editor.codeArea.text=red
                editor.refreshNow()
            }
            await(ready)
            FxTest.run {
                assertEquals(Color.RED,(editor.currentPreview.lookup("#p") as Circle).fill)
                assertFalse("editor-error" in editor.statusLabel.styleClass)
            }
        } finally { if(created) FxTest.run { editor.close() } }
    }

    @Test fun animatedInputAutoplaysAndCloseReleasesDocument() {
        var created=false
        lateinit var editor: SvgPlayground
        var shown=false
        lateinit var window: Stage
        lateinit var ready: CountDownLatch
        try {
            FxTest.run {
                editor=SvgPlayground("""<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="20">
                    <animate attributeName="r" values="10;30;10" dur="2s" repeatCount="indefinite"/></circle></svg>""")
                created=true
                ready=result(editor)
                window=Stage().apply { scene=Scene(editor,1000.0,650.0); setOnHidden { editor.close() }; show() }
                shown=true
            }
            await(ready)
            FxTest.run {
                assertTrue(editor.currentPreview.hasAnimations)
                assertTrue(editor.currentPreview.animationAutoPlay)
                assertEquals(Animation.Status.RUNNING,editor.currentPreview.animationStatus)
                window.close()
                assertNull(editor.currentPreview.document)
            }
        } finally { if(shown) FxTest.run { window.close(); editor.close() } else if(created) FxTest.run { editor.close() } }
    }
}
