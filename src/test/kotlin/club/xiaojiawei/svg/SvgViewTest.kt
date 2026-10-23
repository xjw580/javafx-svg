package club.xiaojiawei.svg

import javafx.css.PseudoClass
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class SvgViewTest {
    @Test fun nativeShapesPreserveIdsClassesAndStyle() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 24 24" color="red"><g opacity=".5">
            <path id="outline" class="accent" d="M1 1H20V20Z" fill="currentColor" stroke="blue" stroke-width="2"/>
            <rect width="4" height="8"/><circle r="2"/><ellipse rx="2" ry="3"/>
            <line x2="4"/><polyline points="0 0 2 2"/><polygon points="0 0 3 0 2 2"/>
            </g></svg>""", SvgOptions(strict = true))
        val path = view.lookup("#outline") as SVGPath
        assertEquals(Color.RED, path.fill)
        assertEquals(Color.BLUE, path.stroke)
        assertEquals(2.0, path.strokeWidth)
        assertTrue("accent" in path.styleClass)
        assertEquals(.5, path.parent.opacity)
        assertEquals(1.0, path.opacity)
        assertEquals(7, view.lookupAll(".svg-shape").size)
    }

    @Test fun externalCssOverridesSvgAndSurvivesResizeAndPseudoClasses() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 24 24"><path id="outline" d="M0 0L20 20"
            style="fill:red;stroke:black;stroke-width:1"/></svg>""")
        val css = Files.createTempFile("svg-css-", ".css")
        try {
            Files.writeString(css, """
                .svg-view .svg-path { -fx-fill: blue; -fx-stroke: green; -fx-stroke-width: 4; }
                .svg-view:armed .svg-path { -fx-fill: purple; }
            """.trimIndent())
            val scene = Scene(Group(view))
            scene.stylesheets += css.toUri().toString()
            view.resize(48.0, 48.0)
            view.applyCss(); view.layout()
            val path = view.lookup("#outline") as SVGPath
            assertEquals(Color.BLUE, path.fill)
            assertEquals(Color.GREEN, path.stroke)
            assertEquals(4.0, path.strokeWidth)
            view.resize(96.0, 96.0); view.layout(); view.applyCss()
            assertSame(path, view.lookup("#outline"))
            assertEquals(4.0, path.strokeWidth)
            view.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), true)
            view.applyCss()
            assertEquals(Color.PURPLE, path.fill)
        } finally { Files.deleteIfExists(css) }
    }

    @Test fun scalesViewBoxAndUpdatesPercentageGeometry() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="10 20 100 50"><rect id="r" x="10" y="20" width="100" height="50"/></svg>""")
        view.resize(200.0, 200.0); view.layout()
        val rect = view.lookup("#r") as Rectangle
        val origin = rect.localToScene(10.0, 20.0)
        assertEquals(0.0, origin.x, 1e-8); assertEquals(50.0, origin.y, 1e-8)
        val percent = SvgView.parse("""<svg><rect id="p" width="50%" height="50%"/></svg>""")
        percent.resize(200.0, 100.0); percent.layout()
        val p = percent.lookup("#p") as Rectangle
        assertEquals(100.0, p.width); assertEquals(50.0, p.height)
        percent.resize(400.0, 200.0); percent.layout()
        assertEquals(200.0, p.width); assertEquals(100.0, p.height)
    }

    @Test fun documentsCanBeSharedButNodesCannot() = FxTest.run {
        val doc = SvgLoader.parse("""<svg viewBox="0 0 10 10"><path id="p" d="M0 0L10 10"/></svg>""")
        val first = SvgView(doc); val second = SvgView(doc)
        assertNotSame(first.lookup("#p"), second.lookup("#p"))
        first.fitWidth = 32.0
        assertEquals(32.0, first.prefHeight(-1.0))
    }

    @Test fun invalidAndUnsupportedContentIsDiagnosed() = FxTest.run {
        val view = SvgView.parse("""<svg><path d="M0"/><foreignObject/></svg>""")
        assertEquals(2, view.diagnostics.size)
        assertFailsWith<SvgException> { SvgView.parse("<svg><foreignObject/></svg>", SvgOptions(strict = true)) }
        assertFails { SvgPathData.validate("M0 0 A10 10 0 2 0 20 20") }
        SvgPathData.validate("M0 0a10 10 0 0110 20z")
    }

    @Test fun snapshotShowsGroupOpacityAndCorrectPixels() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 20 20"><g opacity=".5">
            <rect width="15" height="20" fill="red"/><rect x="5" width="15" height="20" fill="red"/>
            </g></svg>""")
        Scene(Group(view))
        view.resize(20.0, 20.0); view.applyCss(); view.layout()
        val params = javafx.scene.SnapshotParameters().apply { fill = Color.TRANSPARENT }
        val image = view.snapshot(params, null)
        val single = image.pixelReader.getColor(2, 10)
        val overlap = image.pixelReader.getColor(10, 10)
        assertEquals(.5, single.opacity, .03)
        assertEquals(single.opacity, overlap.opacity, .03)
        assertEquals(1.0, overlap.red, .03)
    }
}
