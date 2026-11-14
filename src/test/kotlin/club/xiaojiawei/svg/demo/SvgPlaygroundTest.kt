package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import javafx.animation.Animation
import javafx.beans.value.ChangeListener
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.text.Text
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

    /** Capture the actual editor including its compatibility/error status, not only SvgView. */
    private fun screenshot(editor: SvgPlayground, name: String) {
        editor.applyCss(); editor.layout()
        val statusBounds = editor.statusLabel.localToScene(editor.statusLabel.boundsInLocal)
        assertTrue(editor.statusLabel.isVisible)
        assertTrue(statusBounds.minY >= 0 && statusBounds.maxY <= editor.height,
            "Status must be visible in the editor screenshot: $statusBounds")
        val pixels = editor.snapshot(null, null)
        val image = java.awt.image.BufferedImage(pixels.width.toInt(), pixels.height.toInt(),
            java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width)
            image.setRGB(x, y, pixels.pixelReader.getArgb(x, y))
        val file = java.nio.file.Path.of("target", "$name-preview.png")
        java.nio.file.Files.createDirectories(file.parent)
        assertTrue(javax.imageio.ImageIO.write(image, "png", file.toFile()))
    }

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

    @Test fun implementedFixturesRenderWithoutWarningsAndRecoverAcrossEditsAndResizes() {
        var created = false
        lateinit var editor: SvgPlayground
        lateinit var ready: CountDownLatch
        try {
            FxTest.run {
                editor = SvgPlayground(red)
                created = true
                Scene(editor, 1100.0, 720.0)
                editor.resize(1100.0, 720.0); editor.applyCss(); editor.layout()
                ready = result(editor)
            }
            await(ready)
            for (name in listOf("advanced-filters", "foreign-object", "advanced-filters")) {
                FxTest.run {
                    ready = result(editor)
                    editor.codeArea.text = checkNotNull(javaClass.getResource("/svg/$name.svg")).readText()
                    editor.refreshNow()
                }
                await(ready)
                FxTest.run {
                    assertTrue(editor.statusLabel.text.startsWith("已更新"), editor.statusLabel.text)
                    assertFalse("部分渲染" in editor.statusLabel.text, editor.statusLabel.text)
                    assertFalse("editor-error" in editor.statusLabel.styleClass)
                    assertNotNull(editor.currentPreview.document)
                    assertTrue(editor.currentPreview.lookupAll(".svg-rect").isNotEmpty())
                    for (width in listOf(900.0, 1500.0, 1100.0)) {
                        editor.resize(width, 720.0); editor.applyCss(); editor.layout()
                        editor.currentPreview.snapshot(null, null)
                        assertTrue(editor.currentPreview.diagnostics.isEmpty(), editor.currentPreview.diagnostics.toString())
                    }
                    if (name == "foreign-object") {
                        val html = editor.currentPreview.lookupAll(".svg-xhtml-text").filterIsInstance<Text>()
                        assertTrue(html.any { "HTML" in it.text })
                        assertTrue(html.any { it.text == "inside" && it.fill == Color.web("#66e7d8") })
                        assertTrue(html.any { it.text == "✓" && it.fill == Color.web("#ffc282") })
                        assertEquals(1, editor.currentPreview.lookupAll(".svg-xhtml-box").size)
                    } else {
                        assertEquals(2, editor.currentPreview.lookupAll(".svg-filter-output").size)
                    }
                    screenshot(editor, name)
                }
            }
            lateinit var previous: club.xiaojiawei.svg.SvgView
            FxTest.run {
                previous = editor.currentPreview
                ready = result(editor)
                editor.codeArea.text = "<svg><path d=\"BROKEN\"/></svg>"
                editor.refreshNow()
            }
            await(ready)
            FxTest.run {
                assertSame(previous, editor.currentPreview)
                assertContains(editor.statusLabel.text, "保留上一次有效预览")
                assertContains(editor.statusLabel.styleClass, "editor-error")
                ready = result(editor)
                editor.codeArea.text = red
                editor.refreshNow()
            }
            await(ready)
            FxTest.run {
                assertEquals(Color.RED, (editor.currentPreview.lookup("#p") as Circle).fill)
                assertFalse("editor-error" in editor.statusLabel.styleClass)
                assertFalse("部分渲染" in editor.statusLabel.text)
            }
        } finally { if (created) FxTest.run { editor.close() } }
    }

    @Test fun genuinelyUnsupportedVisualFeaturesStillShowNarrowPartialWarnings() {
        var created = false
        lateinit var editor: SvgPlayground
        lateinit var ready: CountDownLatch
        try {
            FxTest.run {
                editor = SvgPlayground(red)
                created = true
                Scene(editor, 1100.0, 720.0)
                editor.resize(1100.0, 720.0); editor.applyCss(); editor.layout()
                ready = result(editor)
            }
            await(ready)
            for ((xml, warning) in listOf(
                """<svg viewBox="0 0 100 100"><defs><filter id="f"><feUnsupported/></filter></defs>
                    <circle id="p" cx="50" cy="50" r="30" fill="red" filter="url(#f)"/></svg>""" to
                    "Unsupported filter primitive: feUnsupported",
                """<svg viewBox="0 0 100 100"><circle id="p" cx="50" cy="50" r="30" fill="red"/>
                    <foreignObject width="100" height="100"><p xmlns="http://www.w3.org/1999/xhtml">Unsupported paragraph</p>
                    </foreignObject></svg>""" to "Unsupported foreignObject element: {http://www.w3.org/1999/xhtml}p"
            )) {
                FxTest.run {
                    ready = result(editor)
                    editor.codeArea.text = xml
                    editor.refreshNow()
                }
                await(ready)
                FxTest.run {
                    assertTrue(editor.statusLabel.text.startsWith("已更新"), editor.statusLabel.text)
                    assertContains(editor.statusLabel.text, "部分渲染")
                    assertContains(editor.statusLabel.text, warning)
                    assertFalse("editor-error" in editor.statusLabel.styleClass)
                    assertEquals(Color.RED, (editor.currentPreview.lookup("#p") as Circle).fill)
                    assertEquals(listOf(warning), editor.currentPreview.diagnostics.map { it.message }.distinct())
                }
            }
        } finally { if (created) FxTest.run { editor.close() } }
    }

    @Test fun regularFiltersRenderInActualLargePreviewAcrossEditsResizesAndRecovery() {
        var created = false
        lateinit var editor: SvgPlayground
        lateinit var ready: CountDownLatch
        val source = checkNotNull(javaClass.getResource("/svg/regular-filters.svg")).readText()
        try {
            FxTest.run {
                editor = SvgPlayground(red)
                created = true
                Scene(editor, 2300.0, 1100.0)
                editor.resize(2300.0, 1100.0); editor.applyCss(); editor.layout()
                ready = result(editor)
            }
            await(ready)
            repeat(2) {
                FxTest.run {
                    ready = result(editor)
                    editor.codeArea.text = source
                    editor.refreshNow()
                }
                await(ready)
                FxTest.run {
                    assertTrue(editor.statusLabel.text.startsWith("已更新"), editor.statusLabel.text)
                    assertFalse("editor-error" in editor.statusLabel.styleClass)
                    for (width in listOf(2200.0, 2500.0, 2300.0)) {
                        editor.resize(width, 1100.0); editor.applyCss(); editor.layout()
                        val preview = editor.currentPreview
                        val scale = minOf(preview.width / 640.0, preview.height / 360.0)
                        assertTrue(scale >= 1.25, "Actual preview scale was $scale")
                        preview.snapshot(null, null)
                        assertTrue(preview.diagnostics.isEmpty(), preview.diagnostics.toString())
                    }
                    screenshot(editor, "regular-filters")
                }
                if (it == 0) {
                    lateinit var previous: club.xiaojiawei.svg.SvgView
                    FxTest.run {
                        previous = editor.currentPreview
                        ready = result(editor)
                        editor.codeArea.text = "<svg><path d=\"BROKEN\"/></svg>"
                        editor.refreshNow()
                    }
                    await(ready)
                    FxTest.run {
                        assertSame(previous, editor.currentPreview)
                        assertContains(editor.statusLabel.text, "保留上一次有效预览")
                        assertContains(editor.statusLabel.styleClass, "editor-error")
                    }
                }
            }
        } finally { if (created) FxTest.run { editor.close() } }
    }

    @Test fun partialCompatibilityWarningsDoNotDowngradeParsingSecurityOrBudgetErrors() {
        var created = false
        lateinit var editor: SvgPlayground
        lateinit var ready: CountDownLatch
        try {
            FxTest.run {
                editor = SvgPlayground(red)
                created = true
                Scene(editor, 1100.0, 720.0)
                editor.resize(1100.0, 720.0); editor.applyCss(); editor.layout()
                ready = result(editor)
            }
            await(ready)
            val partialHtml = """<foreignObject width="100" height="100">
                <p xmlns="http://www.w3.org/1999/xhtml">Unsupported paragraph</p></foreignObject>"""
            for (xml in listOf(
                "<svg>$partialHtml<script/></svg>",
                "<svg>$partialHtml<path d=\"BROKEN\"/></svg>",
                "<svg><foreignObject></svg>",
                """<!DOCTYPE svg [<!ENTITY forbidden "value">]><svg>$partialHtml</svg>""",
                """<svg>$partialHtml<foreignObject width="100" height="100"><div xmlns="http://www.w3.org/1999/xhtml"
                    onclick="alert(1)">Unsafe attribute</div></foreignObject></svg>""",
                """<svg viewBox="0 0 100 100">$partialHtml<defs>
                    <filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="10000" height="10000">
                    <feFlood flood-color="blue"/></filter></defs>
                    <rect width="100" height="100" filter="url(#f)"/></svg>""",
                """<svg viewBox="0 0 100 100">$partialHtml<defs><filter id="f">
                    <feTurbulence numOctaves="17"/></filter></defs>
                    <rect width="100" height="100" filter="url(#f)"/></svg>""",
                """<svg viewBox="0 0 100 100">$partialHtml<defs><filter id="f">
                    <feMorphology operator="dilate" radius="257"/></filter></defs>
                    <rect width="100" height="100" filter="url(#f)"/></svg>"""
            )) {
                lateinit var previous: club.xiaojiawei.svg.SvgView
                FxTest.run {
                    previous = editor.currentPreview
                    ready = result(editor)
                    editor.codeArea.text = xml
                    editor.refreshNow()
                }
                await(ready)
                FxTest.run {
                    assertSame(previous, editor.currentPreview)
                    assertEquals(Color.RED, (editor.currentPreview.lookup("#p") as Circle).fill)
                    assertContains(editor.statusLabel.styleClass, "editor-error")
                    assertContains(editor.statusLabel.text, "保留上一次有效预览")
                }
            }
        } finally { if (created) FxTest.run { editor.close() } }
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
