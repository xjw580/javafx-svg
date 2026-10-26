package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import club.xiaojiawei.svg.SvgView
import javafx.beans.value.ChangeListener
import javafx.scene.Scene
import javafx.util.Duration
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgRoadmapDemoTest {
    private fun ready(editor:SvgPlayground):CountDownLatch {
        val latch=CountDownLatch(1)
        val listener=object:ChangeListener<String> {
            override fun changed(o:javafx.beans.value.ObservableValue<out String>,old:String?,next:String?) {
                if(next?.startsWith("已更新")==true || "editor-error" in editor.statusLabel.styleClass) {
                    editor.statusLabel.textProperty().removeListener(this);latch.countDown()
                }
            }
        }
        editor.statusLabel.textProperty().addListener(listener);return latch
    }
    private fun await(latch:CountDownLatch)=assertTrue(latch.await(15,TimeUnit.SECONDS),"Roadmap editor timed out")
    @Test fun everySelectableRoadmapResourceLoadsIntoEditorAndRecoversAfterFailure() {
        val entries=DemoRoadmapSamples.entries
        assertEquals(11,entries.size)
        lateinit var editor:SvgPlayground;lateinit var latch:CountDownLatch
        var created=false
        try {
            FxTest.run {
                editor=SvgPlayground(javaClass.getResource(entries.first().file)!!.readText());created=true
                Scene(editor,1320.0,820.0).stylesheets+=javaClass.getResource("/demo/demo.css")!!.toExternalForm()
                editor.resize(1320.0,820.0);editor.applyCss();editor.layout();latch=ready(editor)
            }
            await(latch)
            for((index,entry) in (entries+entries.take(2)).withIndex()) {
                FxTest.run {latch=ready(editor);editor.codeArea.text=javaClass.getResource(entry.file)!!.readText()+"\n<!-- edit $index -->";editor.refreshNow()}
                await(latch)
                FxTest.run {
                    editor.resize(if(index%2==0) 1320.0 else 1100.0,820.0);editor.applyCss();editor.layout()
                    assertTrue(editor.statusLabel.text.startsWith("已更新"),entry.key+": "+editor.statusLabel.text)
                    assertTrue(editor.currentPreview.diagnostics.isEmpty(),entry.key)
                    assertEquals(entry.animated,editor.currentPreview.hasAnimations)
                    if(entry.animated) {editor.currentPreview.seekAnimations(Duration.seconds(1.0));editor.currentPreview.pauseAnimations()}
                    editor.currentPreview.snapshot(null,null)
                }
            }
            lateinit var last:SvgView
            FxTest.run {last=editor.currentPreview;latch=ready(editor);editor.codeArea.text="<svg><broken";editor.refreshNow()}
            await(latch)
            FxTest.run {assertSame(last,editor.currentPreview);assertContains(editor.statusLabel.styleClass,"editor-error");latch=ready(editor);editor.codeArea.text=javaClass.getResource(entries[3].file)!!.readText();editor.refreshNow()}
            await(latch)
            FxTest.run {assertNotSame(last,editor.currentPreview);assertFalse("editor-error" in editor.statusLabel.styleClass);assertTrue(editor.currentPreview.diagnostics.isEmpty())}
        } finally {if(created) FxTest.run {editor.close()}}
    }
}
