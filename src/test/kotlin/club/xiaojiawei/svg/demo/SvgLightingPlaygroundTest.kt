package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import club.xiaojiawei.svg.SvgView
import javafx.beans.value.ChangeListener
import javafx.scene.Scene
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class SvgLightingPlaygroundTest {
    private fun ready(editor: SvgPlayground): CountDownLatch {
        val latch=CountDownLatch(1)
        val listener=object:ChangeListener<String> {
            override fun changed(observable: javafx.beans.value.ObservableValue<out String>, old: String?, value: String?) {
                if(value?.startsWith("已更新")==true || "editor-error" in editor.statusLabel.styleClass) {
                    editor.statusLabel.textProperty().removeListener(this);latch.countDown()
                }
            }
        }
        editor.statusLabel.textProperty().addListener(listener)
        return latch
    }
    private fun await(latch: CountDownLatch)=assertTrue(latch.await(15,TimeUnit.SECONDS),"Lighting preview timed out")

    @Test fun lightingEditsResizeAndInvalidParameterRecoveryWorkInActualPreview() {
        val source=checkNotNull(javaClass.getResource("/svg/lighting-filters.svg")).readText()
        lateinit var editor:SvgPlayground
        lateinit var latch:CountDownLatch
        var created=false
        try {
            FxTest.run {
                editor=SvgPlayground(source);created=true
                // Environment-only screenshot font selection; production UI styles are unchanged.
                val font=javafx.scene.text.Font.loadFont("file:/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",12.0)
                if(font!=null) editor.style="-fx-font-family: '${font.family}';"
                Scene(editor,1320.0,820.0).stylesheets+=checkNotNull(javaClass.getResource("/demo/demo.css")).toExternalForm()
                editor.resize(1320.0,820.0);editor.applyCss();editor.layout()
                latch=ready(editor)
            }
            await(latch)
            for(width in listOf(1100.0,1600.0,1320.0)) FxTest.run {
                editor.resize(width,820.0);editor.applyCss();editor.layout()
                editor.currentPreview.snapshot(null,null)
                assertTrue(editor.statusLabel.text.startsWith("已更新"),editor.statusLabel.text)
                assertFalse("部分渲染" in editor.statusLabel.text)
                assertTrue(editor.currentPreview.diagnostics.isEmpty(),editor.currentPreview.diagnostics.toString())
                assertEquals(6,editor.currentPreview.lookupAll(".svg-filter-output").size)
            }
            lateinit var previous:SvgView
            FxTest.run {
                previous=editor.currentPreview;latch=ready(editor)
                editor.codeArea.text=source.replace("specularExponent=\"16\"","specularExponent=\"129\"")
                editor.refreshNow()
            }
            await(latch)
            FxTest.run {
                assertSame(previous,editor.currentPreview)
                assertContains(editor.statusLabel.styleClass,"editor-error")
                assertContains(editor.statusLabel.text,"保留上一次有效预览")
                latch=ready(editor)
                editor.codeArea.text=source.replace("azimuth=\"225\"","azimuth=\"135\"")
                editor.refreshNow()
            }
            await(latch)
            FxTest.run {
                assertNotSame(previous,editor.currentPreview)
                assertTrue(editor.statusLabel.text.startsWith("已更新"),editor.statusLabel.text)
                assertFalse("editor-error" in editor.statusLabel.styleClass)
                assertTrue(editor.currentPreview.diagnostics.isEmpty())
                editor.applyCss();editor.layout()
                val status=editor.statusLabel.localToScene(editor.statusLabel.boundsInLocal)
                assertTrue(status.minY>=0 && status.maxY<=editor.height)
                val pixels=editor.snapshot(null,null)
                val image=BufferedImage(pixels.width.toInt(),pixels.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until image.height) for(x in 0 until image.width) image.setRGB(x,y,pixels.pixelReader.getArgb(x,y))
                assertTrue(ImageIO.write(image,"png",Path.of("target","lighting-filters-editor-preview.png").toFile()))
            }
        } finally {if(created) FxTest.run {editor.close()}}
    }
}
