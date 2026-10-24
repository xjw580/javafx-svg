package club.xiaojiawei.svg

import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.shape.Circle
import javafx.scene.shape.Ellipse
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.*

class SvgGeometryAnimationsTest {
    private fun view(body: String, strict: Boolean = true, dimensions: String = """width="100" height="100" viewBox="0 0 100 100" """): SvgView =
        SvgView(SvgLoader.parse("""<svg xmlns="http://www.w3.org/2000/svg" $dimensions>$body</svg>""", SvgOptions(strict = strict))).apply {
            animationAutoPlay = false
            fitWidth = 100.0; fitHeight = 100.0
            setMinSize(0.0, 0.0); Scene(Group(this)); resize(100.0, 100.0)
        }
    private fun frame(v: SvgView, time: Double) { v.seekAnimations(Duration.seconds(time)); v.applyCss(); v.layout() }
    private fun image(v: SvgView) = v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    private fun marks(v: SvgView) = v.lookupAll(".svg-marker-instance").filterIsInstance<SvgMarkerInstance>()
    private fun marker() = """<defs><marker id="m" markerUnits="userSpaceOnUse" markerWidth="10" markerHeight="10"
        refX="0" refY="0" orient="auto" overflow="visible"><circle r="1" fill="context-stroke"/></marker></defs>"""

    @Test fun circleCoordinatesAndRadiusAnimateOnTheSameNativeNode() = FxTest.run {
        val v = view("""<circle id="p" cx="10" cy="20" r="5" fill="red">
            <animate attributeName="cx" from="10" to="70" dur="2s" fill="freeze"/>
            <animate attributeName="cy" from="20" to="60" dur="2s" fill="freeze"/>
            <animate attributeName="r" from="5" to="25" dur="2s" fill="freeze"/></circle>""")
        val p = v.lookup("#p") as Circle
        frame(v, 1.0)
        assertSame(p, v.lookup("#p"))
        assertEquals(40.0, p.centerX); assertEquals(40.0, p.centerY); assertEquals(15.0, p.radius)
        assertEquals(Color.RED, image(v).pixelReader.getColor(40, 40))
        frame(v, 2.0); assertEquals(25.0, p.radius)
        assertTrue(v.hasAnimations); assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun rectanglePositionDimensionsAndByAnimationsUseSourceDefaults() = FxTest.run {
        val v = view("""<rect id="p" x="10" y="10" width="20" height="30" fill="blue">
            <animate attributeName="x" by="20" dur="2s"/>
            <animate attributeName="y" from="10" to="30" dur="2s"/>
            <animate attributeName="width" to="60" dur="2s"/>
            <animate attributeName="height" from="30" by="-20" dur="2s"/></rect>""")
        frame(v, 1.0)
        val p = v.lookup("#p") as Rectangle
        assertEquals(20.0, p.x); assertEquals(20.0, p.y); assertEquals(40.0, p.width); assertEquals(20.0, p.height)
        frame(v, 2.0)
        assertEquals(10.0, p.x); assertEquals(20.0, p.width); assertEquals(30.0, p.height)
    }

    @Test fun percentagesUseTheCorrectAxesAndResizeWithoutAViewBox() = FxTest.run {
        val v = view("""<ellipse id="p" cx="10%" cy="10%" rx="5%" ry="5%">
            <animate attributeName="cx" to="50%" dur="2s"/>
            <animate attributeName="cy" to="50%" dur="2s"/>
            <animate attributeName="rx" to="25%" dur="2s"/>
            <animate attributeName="ry" to="25%" dur="2s"/></ellipse>
            <circle id="c"><animate attributeName="r" values="10%;20%" dur="2s"/></circle>""",
            dimensions = """width="200" height="100" """)
        v.resize(200.0, 100.0); frame(v, 1.0)
        val p = v.lookup("#p") as Ellipse
        assertEquals(60.0, p.centerX); assertEquals(30.0, p.centerY)
        assertEquals(30.0, p.radiusX); assertEquals(15.0, p.radiusY)
        assertEquals(hypot(200.0, 100.0) / kotlin.math.sqrt(2.0) * .15, (v.lookup("#c") as Circle).radius, 1e-8)
        v.resize(400.0, 200.0); frame(v, 1.0)
        assertEquals(120.0, p.centerX); assertEquals(60.0, p.centerY)
        assertEquals(60.0, p.radiusX); assertEquals(30.0, p.radiusY)
    }

    @Test fun rectangleCornerRadiiBorrowResolvedValuesAndClampToAnimatedSize() = FxTest.run {
        val v = view("""<rect id="p" width="80" height="40" ry="20%">
            <animate attributeName="width" values="80;20" dur="2s" fill="freeze"/>
            <animate attributeName="rx" to="40" begin="1s" dur="1s" fill="freeze"/></rect>""",
            dimensions = """width="200" height="100" viewBox="0 0 200 100" """)
        frame(v, .5)
        val p = v.lookup("#p") as Rectangle
        assertEquals(40.0, p.arcWidth); assertEquals(40.0, p.arcHeight)
        frame(v, 1.1); assertEquals(44.0, p.arcWidth, 1e-8)
        frame(v, 1.5)
        assertEquals(35.0, p.width)
        assertEquals(35.0, p.arcWidth); assertEquals(40.0, p.arcHeight)
        frame(v, 2.0); assertEquals(20.0, p.arcWidth)
    }

    @Test fun zeroDimensionsSuppressStrokeAndRecoverWithoutRecreatingShapes() = FxTest.run {
        val v = view("""<circle id="p" cx="50" cy="50" r="20" fill="none" stroke="red" stroke-width="12">
            <animate attributeName="r" values="0;20;0" dur="2s" fill="freeze"/></circle>""")
        val p = v.lookup("#p")
        frame(v, 0.0); assertEquals(0.0, image(v).pixelReader.getColor(50, 50).opacity)
        frame(v, 1.0); assertTrue(image(v).pixelReader.getColor(70, 50).opacity > .9)
        frame(v, 2.0); assertEquals(0.0, image(v).pixelReader.getColor(50, 50).opacity)
        assertSame(p, v.lookup("#p"))
        val rectangle = view("""<rect x="50" y="10" height="80" fill="none" stroke="red" stroke-width="10">
            <animate attributeName="width" values="0;20;0" dur="2s" fill="freeze"/></rect>""")
        frame(rectangle, 0.0); assertEquals(0.0, image(rectangle).pixelReader.getColor(50, 50).opacity)
        frame(rectangle, 1.0); assertTrue(image(rectangle).pixelReader.getColor(50, 50).opacity > .9)
        frame(rectangle, 2.0); assertEquals(0.0, image(rectangle).pixelReader.getColor(50, 50).opacity)
    }

    @Test fun boundGeometryAndSourceVisibilityRemainEffective() = FxTest.run {
        val v = view("""<circle id="p" cx="50" cy="50" r="5">
            <animate attributeName="r" values="0;20" dur="2s" fill="freeze"/></circle>
            <rect id="hidden" x="10" y="10" width="20" height="20" visibility="hidden">
            <animate attributeName="width" values="20;50" dur="2s"/></rect>""")
        val p = v.lookup("#p") as Circle
        val radius = SimpleDoubleProperty(8.0)
        p.radiusProperty().bind(radius)
        frame(v, 1.0); assertEquals(8.0, p.radius)
        radius.set(12.0); frame(v, 1.5); assertEquals(12.0, p.radius)
        assertEquals(0.0, image(v).pixelReader.getColor(15, 15).opacity)
        p.radiusProperty().unbind(); frame(v, 1.0); assertEquals(10.0, p.radius)
    }

    @Test fun objectBoundingBoxGradientTracksWidthAndPositionInTheSameFrame() = FxTest.run {
        val v = view("""<defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>
            <rect id="p" x="10" y="10" width="20" height="40" fill="url(#g)">
                <animate attributeName="x" values="10;30" dur="2s" fill="freeze"/>
                <animate attributeName="width" values="20;60" dur="2s" fill="freeze"/></rect>""")
        frame(v, 1.0)
        val p = v.lookup("#p") as Rectangle
        val paint = p.fill as LinearGradient
        assertEquals(20.0, paint.startX, 1e-8); assertEquals(60.0, paint.endX, 1e-8)
        val pixel = image(v).pixelReader.getColor(40, 30)
        assertEquals(pixel.red, pixel.blue, .04)
        frame(v, 2.0)
        val next = p.fill as LinearGradient
        assertEquals(30.0, next.startX, 1e-8); assertEquals(90.0, next.endX, 1e-8)
    }

    @Test fun objectBoundingBoxClipUsesTheAnimatedGeometryImmediately() = FxTest.run {
        val v = view("""<defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width=".5" height="1"/></clipPath></defs>
            <rect x="10" y="10" width="20" height="80" fill="red" clip-path="url(#c)">
                <animate attributeName="width" values="20;60" dur="2s" fill="freeze"/></rect>""")
        frame(v, 1.0); val first = image(v)
        assertEquals(Color.RED, first.pixelReader.getColor(20, 50))
        assertEquals(0.0, first.pixelReader.getColor(35, 50).opacity)
        frame(v, 2.0); val second = image(v)
        assertEquals(Color.RED, second.pixelReader.getColor(35, 50))
        assertEquals(0.0, second.pixelReader.getColor(45, 50).opacity)
    }

    @Test fun objectBoundingBoxMaskAndAnimatedMaskContentsRefreshInOrder() = FxTest.run {
        val v = view("""<defs><mask id="m" maskContentUnits="objectBoundingBox">
            <rect width=".5" height="1" fill="white"><animate attributeName="width" values=".25;.75" dur="2s" fill="freeze"/></rect>
            </mask></defs>
            <rect x="10" y="10" width="20" height="80" fill="red" mask="url(#m)">
                <animate attributeName="width" values="20;60" dur="2s" fill="freeze"/></rect>""")
        frame(v, 1.0)
        assertTrue(image(v).pixelReader.getColor(20, 50).opacity > .95)
        assertEquals(0.0, image(v).pixelReader.getColor(35, 50).opacity)
        frame(v, 2.0)
        assertTrue(image(v).pixelReader.getColor(50, 50).opacity > .95)
        assertEquals(0.0, image(v).pixelReader.getColor(60, 50).opacity)
    }

    @Test fun animationInsideClipPathChangesItsClippingRegion() = FxTest.run {
        val v = view("""<defs><clipPath id="c"><circle cx="50" cy="50">
            <animate attributeName="r" values="0;30" dur="2s" fill="freeze"/></circle></clipPath></defs>
            <rect width="100" height="100" fill="red" clip-path="url(#c)"/>""")
        frame(v, 0.0); assertEquals(0.0, image(v).pixelReader.getColor(50, 50).opacity)
        frame(v, 1.0); assertEquals(Color.RED, image(v).pixelReader.getColor(50, 50))
        assertEquals(0.0, image(v).pixelReader.getColor(70, 50).opacity)
        frame(v, 2.0); assertEquals(Color.RED, image(v).pixelReader.getColor(70, 50))
    }

    @Test fun lineEndpointsMoveMarkersAndFixedStrokeWithoutReplacingInstances() = FxTest.run {
        val v = view(marker() + """<line id="p" x1="10" y1="20" x2="60" y2="60" stroke="red" stroke-width="2"
            vector-effect="non-scaling-stroke" marker-start="url(#m)" marker-end="url(#m)">
            <animate attributeName="x1" values="10;30" dur="2s"/>
            <animate attributeName="y1" values="20;40" dur="2s"/>
            <animate attributeName="x2" values="60;90" dur="2s"/>
            <animate attributeName="y2" values="60;80" dur="2s"/></line>""")
        val original = marks(v).toSet()
        frame(v, 1.0)
        val p = v.lookup("#p") as Line
        assertEquals(20.0, p.startX); assertEquals(30.0, p.startY); assertEquals(75.0, p.endX); assertEquals(70.0, p.endY)
        val points = marks(v).map { it.mapped.localToScene(0.0, 0.0) }
        assertTrue(points.any { kotlin.math.abs(it.x - 20) < 1e-6 && kotlin.math.abs(it.y - 30) < 1e-6 })
        assertTrue(points.any { kotlin.math.abs(it.x - 75) < 1e-6 && kotlin.math.abs(it.y - 70) < 1e-6 })
        assertEquals(original, marks(v).toSet()); assertTrue(v.diagnostics.isEmpty())
        assertTrue(image(v).pixelReader.getColor(75, 70).opacity > .5)
    }

    @Test fun zeroRadiusAndCornerTopologyChangesReuseReservedMarkerInstances() = FxTest.run {
        val circle = view(marker() + """<circle cx="50" cy="50" stroke="red" fill="none" marker-end="url(#m)">
            <animate attributeName="r" values="0;20;0" dur="2s" fill="freeze"/></circle>""")
        val original = marks(circle).toSet()
        frame(circle, 0.0); assertTrue(marks(circle).none { it.isVisible })
        frame(circle, 1.0); assertEquals(1, marks(circle).count { it.isVisible })
        frame(circle, 2.0); assertTrue(marks(circle).none { it.isVisible })
        assertEquals(original, marks(circle).toSet())
        val rectangle = view(marker() + """<rect x="20" y="20" width="60" height="60" stroke="red" fill="none" marker="url(#m)">
            <animate attributeName="rx" values="0;15;0" dur="2s" fill="freeze"/></rect>""")
        val pool = marks(rectangle).toSet()
        frame(rectangle, 0.0); assertEquals(5, marks(rectangle).count { it.isVisible })
        frame(rectangle, 1.0); assertEquals(9, marks(rectangle).count { it.isVisible })
        frame(rectangle, 2.0); assertEquals(5, marks(rectangle).count { it.isVisible })
        assertEquals(pool, marks(rectangle).toSet())
        assertTrue(rectangle.diagnostics.isEmpty())
    }

    @Test fun nestedViewportGeometryUpdatesItsChildrenAndPreservesFixedViewBoxUnits() = FxTest.run {
        val v = view("""<svg id="nested" x="10" y="10" width="20" height="80">
            <animate attributeName="x" values="10;20" dur="2s"/>
            <animate attributeName="width" values="20;80" dur="2s"/>
            <rect id="p" width="100%" height="100%" fill="red"/>
            <circle id="c" cx="50%" cy="50%"><animate attributeName="r" values="10%;20%" dur="2s"/></circle>
            </svg>""")
        frame(v, 1.0)
        assertEquals(50.0, (v.lookup("#p") as Rectangle).width)
        assertEquals(25.0, (v.lookup("#c") as Circle).centerX)
        assertEquals(Color.RED, image(v).pixelReader.getColor(60, 20))
        assertEquals(0.0, image(v).pixelReader.getColor(70, 20).opacity)
    }

    @Test fun usePositionsAndReferencedGeometryHaveIndependentInstances() = FxTest.run {
        val v = view("""<defs><circle id="dot" r="4" fill="red"><animate attributeName="r" values="4;8" dur="2s"/></circle></defs>
            <use id="a" href="#dot" x="10" y="30"><animate attributeName="x" values="10;50" dur="2s"/></use>
            <use id="b" href="#dot" x="70" y="30"/>""")
        frame(v, 1.0)
        assertEquals(30.0, v.lookup("#a").layoutX)
        assertEquals(70.0, v.lookup("#b").layoutX)
        val dots = v.lookupAll("#dot").filterIsInstance<Circle>()
        assertEquals(2, dots.size); assertTrue(dots.all { it.radius == 6.0 })
        assertEquals(Color.RED, image(v).pixelReader.getColor(30, 30))
        val other = SvgView(v.document).apply { animationAutoPlay = false; Scene(Group(this)); resize(100.0, 100.0) }
        frame(other, .5); assertEquals(20.0, other.lookup("#a").layoutX)
        assertEquals(30.0, v.lookup("#a").layoutX)
    }

    @Test fun textAndTextLengthCoordinatesUseTheSameGeometryClock() = FxTest.run {
        val v = view("""<text id="a" x="10" y="30" font-size="10">A
            <animate attributeName="x" values="10;50" dur="2s"/>
            <animate attributeName="dy" values="0;20" dur="2s"/></text>
            <text id="b" x="10" y="70" textLength="30">ABC<animate attributeName="x" values="10;50" dur="2s"/></text>""")
        frame(v, 1.0)
        val a = v.lookup("#a") as Text
        assertEquals(30.0, a.x); assertEquals(40.0, a.y)
        val adjusted = (v.lookup("#b") as Text).parent as SvgAdjustedText
        assertEquals(30.0, adjusted.geometryBounds().minX, 2.0)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun tspanCoordinatesAppearOnlyWhileContributingAndRespectInnerScopes() = FxTest.run {
        val v = view("""<text x="10" y="30" font-size="10"><animate attributeName="x" values="10;50" dur="2s"/>
            A<tspan id="span">B<animate attributeName="x" values="50;70" begin=".5s" dur="1s"/></tspan>
            <tspan id="fixed" x="80">C</tspan></text>""")
        frame(v, .25)
        val run = v.lookup("#span").lookup(".svg-text-run") as Text
        assertTrue(run.x < 30.0)
        frame(v, 1.0); assertEquals(60.0, run.x)
        assertEquals(80.0, (v.lookup("#fixed").lookup(".svg-text-run") as Text).x)
        frame(v, 1.75); assertTrue(run.x < 60.0)
    }

    @Test fun negativeSizesWrongTargetsAndUnsupportedGeometryAreDiagnosed() = FxTest.run {
        val cases = listOf(
            """<circle r="10"><animate attributeName="r" values="10;-1" dur="2s"/></circle>""",
            """<rect width="20" height="20"><animate attributeName="width" by="-30" dur="2s"/></rect>""",
            """<g><animate attributeName="r" values="1;2" dur="2s"/></g>""",
            """<circle r="10"><animate attributeName="cx" values="0;10" attributeType="CSS" dur="2s"/></circle>""",
            """<use href="#p"><animate attributeName="width" values="10;20" dur="2s"/></use><defs><path id="p"/></defs>""",
            """<rect width="20" height="20"><animate attributeName="points" values="0 0;10 10" dur="2s"/></rect>"""
        )
        cases.forEach { source ->
            val loose = view(source, false)
            assertTrue(loose.diagnostics.isNotEmpty(), source)
            assertFalse(loose.hasAnimations, source)
            assertFailsWith<SvgException> { view(source) }
        }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><animate attributeName="width" values="10;20" dur="1s"/></svg>""", SvgOptions(strict = true)) }
    }

    @Test fun geometryFramesDoNotGrowDocumentLengthCacheOrNodeCount() = FxTest.run {
        val v = view("""<circle id="p" cx="50" cy="50"><animate attributeName="r" values="1;30" dur="2s"/></circle>""")
        frame(v, .5)
        val field = SvgLengthCache::class.java.getDeclaredField("values").apply { isAccessible = true }
        val values = field.get(v.document!!.lengths) as Map<*, *>
        val count = values.size; val nodes = v.lookupAll("*").toSet()
        repeat(500) { frame(v, it / 500.0) }
        assertEquals(count, values.size); assertEquals(nodes, v.lookupAll("*").toSet())
    }

    @Test fun embeddedImagePositionAndSizeAnimateTogetherWithItsClip() = FxTest.run {
        val data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1cAAAAASUVORK5CYII="
        val v = view("""<image id="p" x="10" y="10" width="20" height="20" href="data:image/png;base64,$data" preserveAspectRatio="none">
            <animate attributeName="x" values="10;30" dur="2s"/>
            <animate attributeName="y" values="10;30" dur="2s"/>
            <animate attributeName="width" values="20;40" dur="2s"/>
            <animate attributeName="height" values="20;40" dur="2s"/></image>""")
        frame(v, 1.0)
        val pixels = image(v).pixelReader
        assertTrue(pixels.getColor(25, 25).opacity > .9)
        assertEquals(0.0, pixels.getColor(10, 10).opacity)
        assertEquals(0.0, pixels.getColor(55, 55).opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun animatedGeometryKeepsFilterTransformsAndRegionsAligned() = FxTest.run {
        val v = view("""<defs><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"
            color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="1"/></filter></defs>
            <rect x="20" y="40" width="10" height="20" fill="red" transform="translate(10)" filter="url(#f)">
            <animate attributeName="width" values="10;50" dur="2s" fill="freeze"/></rect>""")
        frame(v, 1.0); assertTrue(image(v).pixelReader.getColor(65, 50).opacity < .05)
        frame(v, 2.0); assertTrue(image(v).pixelReader.getColor(65, 50).opacity > .95)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun zeroEllipseWithFixedStrokeRecoversAndNegativePercentSizesAreRejected() = FxTest.run {
        val v = view("""<ellipse cx="50" cy="50" ry="12" fill="none" stroke="green" stroke-width="8"
            vector-effect="non-scaling-stroke"><animate attributeName="rx" values="0;20;0" dur="2s" fill="freeze"/></ellipse>""")
        frame(v, 0.0); assertEquals(0.0, image(v).pixelReader.getColor(50, 50).opacity)
        frame(v, 1.0); assertTrue(image(v).pixelReader.getColor(70, 50).opacity > .9)
        frame(v, 2.0); assertEquals(0.0, image(v).pixelReader.getColor(50, 50).opacity)
        assertTrue(v.diagnostics.isEmpty())
        assertFailsWith<SvgException> {
            view("""<circle><animate attributeName="r" values="10%;-10%" dur="2s"/></circle>""",
                dimensions = """width="0" height="0" viewBox="0 0 0 0" """)
        }
    }
}
