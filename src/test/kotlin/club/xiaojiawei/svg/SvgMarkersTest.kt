package club.xiaojiawei.svg

import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.scene.shape.SVGPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import kotlin.math.atan2
import kotlin.test.*

class SvgMarkersTest {
    @TempDir lateinit var directory: java.nio.file.Path

    @Test fun definitionsAreHiddenAndStartMiddleEndUseTheWholePath() = FxTest.run {
        val v = view(arrow() + """<path id="p" d="M10 20H40V40 M60 60H90" stroke="red"
            marker-start="url(#a)" marker-mid="url(#a)" marker-end="url(#a)"/>""")
        snapshot(v)
        val marks = instances(v)
        assertEquals(5, marks.size)
        assertAnchor(marks[0], 10.0, 20.0)
        assertAnchor(marks[1], 40.0, 20.0)
        assertAnchor(marks[2], 40.0, 40.0)
        assertAnchor(marks[3], 60.0, 60.0)
        assertAnchor(marks[4], 90.0, 60.0)
        listOf(0.0, 45.0, 90.0, 0.0, 0.0).zip(marks).forEach { (angle, mark) -> assertEquals(angle, direction(mark), .001) }
        assertTrue(marks.all { it.isMouseTransparent })
        val hidden = view(arrow() + """<path d="M10 20H40"/>""")
        assertNull(hidden.lookup("#a"))
        assertTrue(hidden.diagnostics.isEmpty())
    }

    @Test fun autoStartReverseAndCurveTangentsPointOutward() = FxTest.run {
        val v = view(arrow(orient = "auto-start-reverse") + """<path id="p" d="M20 20C20 60 60 60 90 60"
            marker-start="url(#a)" marker-end="url(#a)"/>""")
        snapshot(v)
        val marks = instances(v)
        assertEquals(-90.0, normalized(direction(marks[0])), .001)
        assertEquals(0.0, direction(marks[1]), .001)
        assertAnchor(marks[0], 20.0, 20.0)
        assertAnchor(marks[1], 90.0, 60.0)
    }

    @Test fun closedSubpathsRenderBothMarkersAtCoincidentVertices() = FxTest.run {
        val v = view(arrow() + """<path id="p" d="M20 20H40V40Z M60 20H80V40Z" marker="url(#a)"/>""")
        snapshot(v)
        val marks = instances(v)
        assertEquals(8, marks.size)
        assertAnchor(marks[0], 20.0, 20.0)
        assertAnchor(marks[3], 20.0, 20.0)
        assertAnchor(marks[4], 60.0, 20.0)
        assertAnchor(marks[7], 60.0, 20.0)
        assertEquals(normalized(direction(marks[0])), normalized(direction(marks[3])), .001)
    }

    @Test fun markerShorthandAndInheritedLonghandsFollowCssCascade() = FxTest.run {
        val v = view(arrow() + """<style>.all { marker: url(#a); marker-mid: none; }
            #p { marker-end: none !important; }</style>
            <g marker-start="url(#a)"><path id="p" class="all" d="M20 20H40V40" style="marker-end: url(#a)"/></g>""")
        snapshot(v)
        assertEquals(1, instances(v).size)
        assertAnchor(instances(v).single(), 20.0, 20.0)
    }

    @Test fun markerDefinitionsInheritTheirOwnStylesAndIgnoreDisplayNone() = FxTest.run {
        val v = view("""<defs fill="orange" stroke="none"><marker id="a" display="none" markerUnits="userSpaceOnUse"
            markerWidth="10" markerHeight="10" refX="10" refY="5"><path d="M0 0L10 5L0 10Z"/></marker></defs>
            <g fill="purple" stroke="green"><path id="p" d="M20 50H90" marker-end="url(#a)"/></g>""")
        val image = snapshot(v)
        assertEquals(Color.ORANGE, image.pixelReader.getColor(85, 50))
        assertEquals(Color.ORANGE, markerShape(v).fill)
    }

    @Test fun viewBoxRefCoordinatesAndAnglesMapToTheSourceVertex() = FxTest.run {
        val v = view("""<defs><marker id="a" markerUnits="userSpaceOnUse" markerWidth="20" markerHeight="10"
            viewBox="10 20 20 10" refX="20" refY="25" orient=".25turn" preserveAspectRatio="none">
            <rect x="10" y="20" width="20" height="10" fill="red" stroke="none"/></marker></defs>
            <path id="p" d="M20 20H80" marker-end="url(#a)"/>""")
        snapshot(v)
        val mark = instances(v).single()
        val point = mark.mapped.localToScene(20.0, 25.0)
        assertEquals(80.0, point.x, .001); assertEquals(20.0, point.y, .001)
        assertEquals(90.0, direction(mark), .001)
    }

    @Test fun defaultMarkerViewportClipsAndOverflowVisibleCanExtendIt() = FxTest.run {
        fun svg(overflow: String) = """<defs><marker id="a" markerUnits="userSpaceOnUse" markerWidth="10" markerHeight="10"
            refX="0" refY="5" $overflow><rect x="-10" width="30" height="10" fill="red" stroke="none"/></marker></defs>
            <path id="p" d="M20 50H60" marker-end="url(#a)"/>"""
        val clipped = snapshot(view(svg("")))
        val visible = snapshot(view(svg("""style="overflow: visible" """)))
        assertEquals(0.0, clipped.pixelReader.getColor(75, 47).opacity)
        assertEquals(Color.RED, visible.pixelReader.getColor(75, 47))
        assertEquals(Color.RED, clipped.pixelReader.getColor(65, 47))
    }

    @Test fun strokeUnitsFollowCssWidthBindingsAndContextColorOverridesThemeFill() = FxTest.run {
        val v = view(arrow(units = "strokeWidth") + """<path id="p" d="M20 50H100" stroke="red" stroke-width="2" marker-end="url(#a)"/>""")
        Scene(Group(v))
        v.svgFill = Color.PURPLE; v.svgStroke = Color.GREEN
        val css = directory.resolve("markers.css")
        Files.writeString(css, "#p { -fx-stroke: blue; -fx-stroke-width: 3; }")
        v.stylesheets += css.toUri().toString()
        snapshot(v)
        assertEquals(Color.BLUE, markerShape(v).fill)
        assertEquals(3.0, instances(v).single().placement.mxx)
        v.stylesheets.clear()
        snapshot(v)
        assertEquals(Color.GREEN, markerShape(v).fill)
        val width = SimpleDoubleProperty(4.0)
        (v.lookup("#p") as Shape).strokeWidthProperty().bind(width)
        snapshot(v)
        assertEquals(4.0, instances(v).single().placement.mxx)
        width.set(5.0); snapshot(v)
        assertEquals(5.0, instances(v).single().placement.mxx)
        markerShape(v).style = "-fx-fill: magenta;"
        snapshot(v)
        assertEquals(Color.MAGENTA, markerShape(v).fill)
    }

    @Test fun contextFillReadsTheCurrentFillAndNoneOutsideMarkersPaintsNothing() = FxTest.run {
        val v = view(arrow().replace("context-stroke", "context-fill") + """<path id="p" d="M20 50H100"
            fill="orange" stroke="blue" marker-end="url(#a)"/><rect id="outside" width="5" height="5" fill="context-fill"/>""")
        snapshot(v)
        assertEquals(Color.ORANGE, markerShape(v).fill)
        assertNull((v.lookup("#outside") as Shape).fill)
    }

    @Test fun elementOpacityCompositesSourceAndMarkersExactlyOnce() = FxTest.run {
        val v = view(arrow() + """<path id="p" d="M20 50H100" stroke="red" stroke-width="4" opacity=".5" marker-end="url(#a)"/>""")
        val image = snapshot(v)
        assertEquals(.5, image.pixelReader.getColor(95, 50).opacity, .01)
        assertEquals(.5, image.pixelReader.getColor(50, 50).opacity, .01)
        assertEquals(.5, image.pixelReader.getColor(92, 47).opacity, .01)
    }

    @Test fun contextLinearGradientsRemainContinuousIntoTheArrow() = FxTest.run {
        val v = view("""<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" x2="120">
            <stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>""" + arrow() +
            """<path id="p" d="M20 50H100" stroke="url(#g)" stroke-width="2" marker-end="url(#a)"/>""")
        val image = snapshot(v)
        val color = image.pixelReader.getColor(95, 48)
        assertTrue(color.opacity > .99)
        assertEquals(95.5 / 120, color.blue, .025)
        assertEquals(1 - 95.5 / 120, color.red, .025)
    }

    @Test fun fixedStrokeMarkersStayFixedThroughViewportResizeAndShear() = FxTest.run {
        val marker = """<defs><marker id="a" viewBox="0 0 6 6" markerWidth="6" markerHeight="6" refX="3" refY="3">
            <rect width="6" height="6" fill="context-stroke" stroke="none"/></marker></defs>"""
        val v = view(marker + """<path id="p" d="M20 20L40 40H60" transform="matrix(1 .3 .5 1 0 0)" stroke-width="2"
            vector-effect="non-scaling-stroke" marker-end="url(#a)"/>""")
        for (size in listOf(120, 240, 60)) {
            snapshot(v, size, size * 100 / 120)
            val mark = instances(v).single()
            val bounds = mark.localToScene(mark.boundsInLocal)
            // Square rotates with the transformed horizontal tangent; its axis-aligned extent is known.
            val angle = atan2(.3, 1.0)
            assertEquals(12 * (kotlin.math.cos(angle) + kotlin.math.sin(angle)), bounds.width, .01)
            assertEquals(Math.toDegrees(angle), direction(mark), .001)
        }
    }

    @Test fun markerGeometryBoundsExcludeArrowExtentsAndResizeKeepsPercentages() = FxTest.run {
        val v = SvgView.parse("""<svg fill="none" stroke="black">""" + arrow() +
            """<line id="p" x1="10%" y1="50%" x2="80%" y2="50%" marker-end="url(#a)"/></svg>""", SvgOptions(strict = true)).apply { isManaged = false }
        snapshot(v, 200, 100)
        val layer = v.lookup("#p").parent as SvgMarkers
        assertEquals(140.0, svgGeometryBounds(layer).width, .001)
        assertAnchor(instances(v).single(), 160.0, 50.0)
        snapshot(v, 400, 200)
        assertEquals(280.0, svgGeometryBounds(layer).width, .001)
        assertAnchor(instances(v).single(), 320.0, 100.0)
    }

    @Test fun sourceGeometryUpdatesReuseInstancesAndDiagnoseTopologyChanges() = FxTest.run {
        val v = view(arrow() + """<path id="p" d="M20 20H80" marker-end="url(#a)"/>""", strict = false)
        snapshot(v)
        val mark = instances(v).single()
        (v.lookup("#p") as SVGPath).content = "M30 40H90"
        snapshot(v)
        assertSame(mark, instances(v).single())
        assertAnchor(mark, 90.0, 40.0)
        (v.lookup("#p") as SVGPath).content = "M30 40H90V60"
        snapshot(v)
        assertFalse(mark.isVisible)
        assertTrue(v.diagnostics.any { "vertex count" in it.message })
    }

    @Test fun zeroLengthGeometryAndZeroMarkerSizeRemainDefined() = FxTest.run {
        val v = view(arrow() + """<path id="p" d="M50 50L50 50" marker-start="url(#a)" marker-end="url(#a)"/>""")
        snapshot(v)
        assertEquals(2, instances(v).size)
        instances(v).forEach { assertAnchor(it, 50.0, 50.0) }
        val zero = view(arrow().replace("""markerWidth="10" """, """markerWidth="0" """) +
            """<path id="p" d="M20 50H100" marker-end="url(#a)"/>""")
        snapshot(zero)
        assertFalse(instances(zero).single().isVisible)
    }

    @Test fun referencesHaveIndependentNodesStylesAndContextPaints() = FxTest.run {
        val document = SvgLoader.parse(svg(arrow() + """<path id="p" d="M20 50H100" stroke="red" marker-end="url(#a)"/>"""),
            SvgOptions(strict = true))
        val a = SvgView(document); val b = SvgView(document)
        snapshot(a); snapshot(b)
        assertNotSame(instances(a).single(), instances(b).single())
        a.svgStroke = Color.GREEN; snapshot(a)
        assertEquals(Color.GREEN, markerShape(a).fill)
        assertEquals(Color.RED, markerShape(b).fill)
    }

    @Test fun invalidReferencesCyclesAndExpansionLimitsKeepTheSourceInLooseMode() = FxTest.run {
        val examples = listOf(
            """<path id="p" d="M20 50H100" marker-end="url(#missing)"/>""" to "Missing marker",
            """<path id="p" d="M20 50H100" marker-end="url(https://example.com/a.svg#a)"/>""" to "local marker",
            arrow().replace("""markerWidth="10" """, """markerWidth="-1" """) +
                """<path id="p" d="M20 50H100" marker-end="url(#a)"/>""" to "Negative marker",
            arrow().replace("orient=\"auto\"", "orient=\"invalid\"") +
                """<path id="p" d="M20 50H100" marker-end="url(#a)"/>""" to "number",
            """<defs><marker id="a"><path d="M0 0H10" marker-end="url(#a)"/></marker></defs>
                <path id="p" d="M20 50H100" marker-end="url(#a)"/>""" to "Circular marker"
        )
        for ((body, diagnostic) in examples) {
            val v = view(body, strict = false)
            assertTrue(snapshot(v).pixelReader.getColor(50, 50).opacity > 0)
            assertTrue(v.diagnostics.any { diagnostic.lowercase() in it.message.lowercase() }, "$diagnostic: " + v.diagnostics.toString())
            assertFailsWith<SvgException> { view(body) }
        }
        val xml = svg(arrow() + """<path id="p" d="M20 50H100" marker-end="url(#a)"/>""")
        assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true, maxElements = 7)) }
        val limited = SvgView.parse(xml, SvgOptions(maxElements = 7))
        assertTrue(snapshot(limited).pixelReader.getColor(50, 50).opacity > 0)
        assertTrue(limited.diagnostics.any { "limit" in it.message })
    }

    @Test fun clipAndMaskApplyToTheSourceAndItsMarkers() = FxTest.run {
        val clip = view(arrow() + """<defs><clipPath id="c"><rect width="90" height="100"/></clipPath></defs>
            <path id="p" d="M20 50H100" stroke="red" marker-end="url(#a)" clip-path="url(#c)"/>""")
        val a = snapshot(clip)
        assertEquals(0.0, a.pixelReader.getColor(95, 50).opacity)
        assertTrue(a.pixelReader.getColor(50, 50).opacity > 0)
        val mask = view(arrow() + """<defs><mask id="m" mask-type="alpha" maskUnits="userSpaceOnUse" x="0" y="0" width="120" height="100">
            <rect width="120" height="100" fill="white" stroke="none"/></mask></defs>
            <path id="p" d="M20 30H100V70" stroke="red" marker-end="url(#a)" mask="url(#m)"/>""")
        val b = snapshot(mask)
        assertTrue(b.pixelReader.getColor(100, 65).opacity > .9)
        assertTrue(mask.diagnostics.isEmpty())
    }

    @Test fun userSpaceMarkersScaleNormallyWhileFixedStrokeUnitsStayFixed() = FxTest.run {
        val v = view(arrow(units = "userSpaceOnUse") + """<path id="p" d="M20 50H100" stroke-width="3"
            vector-effect="non-scaling-stroke" marker-end="url(#a)"/>""")
        snapshot(v)
        val mark = instances(v).single()
        assertEquals(10.0, mark.localToScene(mark.boundsInLocal).width, .01)
        snapshot(v, 240, 200)
        assertEquals(20.0, mark.localToScene(mark.boundsInLocal).width, .01)
    }

    @Test fun useAndNestedViewportsPreserveTheReferenceContext() = FxTest.run {
        val v = view(arrow() + """<defs><path id="p" d="M10 20H80" marker-end="url(#a)"/></defs>
            <svg x="10" y="10" width="100" height="80" viewBox="0 0 100 80">
            <use href="#p" x="5" y="3" stroke="blue"/></svg>""")
        snapshot(v)
        assertAnchor(instances(v).single(), 95.0, 33.0)
        assertEquals(Color.BLUE, markerShape(v).fill)
    }

    @Test fun circleAndRectangleMarkersFollowTheirEquivalentPaths() = FxTest.run {
        for (shape in listOf("""<circle id="p" cx="60" cy="50" r="20" """,
            """<rect id="p" x="20" y="20" width="60" height="40" """)) {
            val v = view(arrow() + "$shape marker=\"url(#a)\"/>")
            snapshot(v)
            assertEquals(5, instances(v).size)
        }
    }

    @Test fun unsupportedContextGradientsAreDiagnosedAndKeepTheReferencingShape() = FxTest.run {
        val body = """<defs><radialGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs>""" +
            arrow() + """<path id="p" d="M20 20H100V80" stroke="url(#g)" marker-end="url(#a)"/>"""
        val loose = view(body, strict = false)
        snapshot(loose)
        assertTrue(loose.diagnostics.any { "context paint" in it.message })
        assertNotNull((loose.lookup("#p") as Shape).stroke)
        assertFailsWith<SvgException> { view(body) }
    }

    private fun arrow(units: String = "userSpaceOnUse", orient: String = "auto") = """<defs><marker id="a"
        markerUnits="$units" markerWidth="10" markerHeight="10" refX="10" refY="5" orient="$orient">
        <path d="M0 0L10 5L0 10Z" fill="context-stroke" stroke="none"/></marker></defs>"""

    private fun svg(content: String) = """<svg viewBox="0 0 120 100" fill="none" stroke="black">$content</svg>"""
    private fun view(content: String, strict: Boolean = true) = SvgView.parse(svg(content), SvgOptions(strict = strict)).apply { isManaged = false }

    private fun instances(v: SvgView) = generateSequence(v.lookup("#p").parent) { it.parent }
        .filterIsInstance<SvgMarkers>().first().children.filterIsInstance<SvgMarkerInstance>()
    private fun markerShape(v: SvgView) = instances(v).first().lookup(".svg-shape") as Shape

    private fun snapshot(v: SvgView, width: Int = 120, height: Int = 100): WritableImage {
        v.resize(width.toDouble(), height.toDouble())
        v.requestLayout(); v.applyCss(); v.layout()
        return v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, WritableImage(width, height))
    }

    private fun assertAnchor(mark: SvgMarkerInstance, x: Double, y: Double) {
        val point = mark.mapped.localToScene(10.0, 5.0)
        assertEquals(x, point.x, .001); assertEquals(y, point.y, .001)
    }
    private fun direction(mark: SvgMarkerInstance): Double {
        val a = mark.mapped.localToScene(0.0, 0.0); val b = mark.mapped.localToScene(1.0, 0.0)
        return normalized(Math.toDegrees(atan2(b.y - a.y, b.x - a.x)))
    }
    private fun normalized(angle: Double): Double {
        var value = angle % 360
        if (value > 180) value -= 360
        if (value <= -180) value += 360
        return value
    }
}
