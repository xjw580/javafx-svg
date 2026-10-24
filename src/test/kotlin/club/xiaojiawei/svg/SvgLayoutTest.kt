package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgLayoutTest {
    @Test fun repeatedLayoutAndRatioChangesKeepRootTransformCorrect() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 100 100"><rect id="r" width="100%" height="100%"/></svg>""")
        val rect = view.lookup("#r") as Rectangle
        view.resize(200.0, 100.0); view.layout()
        repeat(3) {
            view.requestLayout(); view.layout()
            assertEquals(50.0, rect.localToScene(0.0, 0.0).x)
            assertEquals(150.0, rect.localToScene(100.0, 100.0).x)
        }
        view.preserveRatio = false; view.layout()
        assertEquals(0.0, rect.localToScene(0.0, 0.0).x)
        assertEquals(200.0, rect.localToScene(100.0, 100.0).x)
        view.resize(0.0, 0.0); view.layout()
        assertFalse(view.childrenUnmodifiable.single().isVisible)
        view.resize(200.0, 100.0); view.layout()
        assertTrue(view.childrenUnmodifiable.single().isVisible)
        view.preserveRatio = true; view.layout()
        assertEquals(50.0, rect.localToScene(0.0, 0.0).x)
        assertEquals(100.0, rect.width)
    }

    @Test fun nestedPercentageViewportsUpdateInParentOrderAndRespectPadding() = FxTest.run {
        val view = attached("""<svg><svg x="10%" width="50%" height="100%">
            <svg width="50%" height="50%"><rect id="r" width="100%" height="100%"/></svg>
            </svg></svg>""")
        val rect = view.lookup("#r") as Rectangle
        view.resize(200.0, 100.0); view.layout()
        assertEquals(50.0, rect.width)
        assertEquals(50.0, rect.height)
        assertEquals(20.0, rect.localToScene(0.0, 0.0).x)
        view.resize(400.0, 100.0); view.layout()
        assertEquals(100.0, rect.width)
        assertEquals(40.0, rect.localToScene(0.0, 0.0).x)
        view.style = "-fx-padding: 10;"
        view.applyCss(); view.layout()
        assertEquals(95.0, rect.width)
        assertEquals(40.0, rect.height)
        assertEquals(48.0, rect.localToScene(0.0, 0.0).x)
        repeat(2) { view.requestLayout(); view.layout() }
        assertEquals(95.0, rect.width)
    }

    @Test fun cssFontChangeRefreshesTextAnchorAndBoundingBoxClipAtSameSize() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 300 100"><defs>
            <clipPath id="c" clipPathUnits="objectBoundingBox"><rect width=".5" height="1"/></clipPath>
            </defs><text id="t" x="150" y="60" font-size="12" text-anchor="middle"
            clip-path="url(#c)">Hello</text></svg>""")
        view.resize(300.0, 100.0); view.layout()
        val text = view.lookup("#t") as Text
        val clipTransform = text.clip.transforms.single()
        val oldWidth = clipTransform.mxx
        text.style = "-fx-font-size: 32; -fx-fill: blue;"
        view.applyCss(); view.requestLayout(); view.layout()
        assertEquals(32.0, text.font.size)
        assertEquals(Color.BLUE, text.fill)
        assertEquals(150.0, text.x + text.layoutBounds.width / 2, .001)
        assertTrue(clipTransform.mxx > oldWidth * 2)
        assertEquals(svgGeometryBounds(text).width, clipTransform.mxx, .001)
        assertEquals(svgGeometryBounds(text).minX, clipTransform.tx, .001)
        assertSame(clipTransform, text.clip.transforms.single())
    }

    @Test fun cssPaintChangesRefreshFilterAtSameSize() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 100 100"><defs><filter id="f"
            filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
            <feGaussianBlur stdDeviation="2"/></filter></defs>
            <rect id="r" x="40" y="40" width="20" height="20" fill="red" filter="url(#f)"/></svg>""")
        view.resize(100.0, 100.0); view.layout()
        val parameters = SnapshotParameters().apply { fill = Color.TRANSPARENT }
        assertTrue(view.snapshot(parameters, null).pixelReader.getColor(38, 50).red > .9)
        view.lookup("#r").style = "-fx-fill: blue;"
        view.applyCss(); view.requestLayout(); view.layout()
        val pixel = view.snapshot(parameters, null).pixelReader.getColor(38, 50)
        assertTrue(pixel.opacity > .05)
        assertEquals(1.0, pixel.blue, .03)
        assertEquals(0.0, pixel.red, .03)
    }

    @Test fun failedGeometryUpdateCanRetryAtSameViewport() = FxTest.run {
        val renderer = FxSvgRenderer(SvgLoader.parse("""<svg><rect id="r" width="100%" height="100%"/></svg>"""))
        renderer.layout(100.0, 100.0, true)
        val rect = renderer.root.lookup("#r") as Rectangle
        val external = javafx.beans.property.SimpleDoubleProperty(80.0)
        rect.widthProperty().bind(external)
        assertFails { renderer.layout(200.0, 100.0, true) }
        rect.widthProperty().unbind()
        renderer.layout(200.0, 100.0, true)
        assertEquals(200.0, rect.width)
    }

    private fun attached(xml: String): SvgView = SvgView.parse(xml, SvgOptions(strict = true)).also {
        it.isManaged = false
        Scene(Group(it))
        it.applyCss()
    }
}
