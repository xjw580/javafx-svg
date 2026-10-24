package club.xiaojiawei.svg

import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.shape.Path
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class SvgNonScalingStrokeTest {
    @TempDir lateinit var directory: java.nio.file.Path

    @Test fun similarityAndOutlinePathsSwitchWithoutChangingCapsDashesOrPaint() = FxTest.run {
        val v = view("""<g id="group"><path id="p" d="M10 20H40" stroke="blue" stroke-width="4"
            stroke-dasharray="7 3" stroke-linecap="round" vector-effect="non-scaling-stroke"/></g>""")
        val matrix = v.lookup("#group").transforms.first() as javafx.scene.transform.Affine
        for ((sx,sy) in listOf(1.0 to 1.0, 2.0 to 1.0, 1.5 to 1.5, -1.0 to 1.0, 1.0 to 2.0, 1.0 to 1.0)) {
            val tx = if(sx<0) 60.0 else 0.0
            matrix.setToTransform(sx,0.0,tx,0.0,sy,0.0)
            val data = "M" + (10*sx+tx) + " " + (20*sy) + "H" + (40*sx+tx)
            val expected = view("""<path d="$data" stroke="blue" stroke-width="4"
                stroke-dasharray="7 3" stroke-linecap="round"/>""")
            assertSimilar(snapshot(expected),snapshot(v))
            assertTrue(v.diagnostics.isEmpty())
        }
    }

    @Test fun viewportResizeKeepsWidthWhileOrdinaryStrokeScales() = FxTest.run {
        val fixed = view("""<path id="p" d="M10 50H90" stroke-width="4" vector-effect="non-scaling-stroke"/>""")
        val ordinary = view("""<path d="M10 50H90" stroke-width="4"/>""")
        for (size in listOf(100, 200, 50, 100)) {
            val a = snapshot(fixed, size, size)
            val b = snapshot(ordinary, size, size)
            assertEquals(4.0, columnAlpha(a, size / 2), .05)
            assertEquals(4.0 * size / 100, columnAlpha(b, size / 2), .05)
            assertEquals(4.0, (fixed.lookup("#p") as Shape).strokeWidth)
        }
    }

    @Test fun nonuniformScaleShearRotationAndReflectionMatchHostSpaceReference() = FxTest.run {
        val transformed = view("""<path d="M5 5L20 5L20 15" transform="matrix(3 1 1 2 10 10)"
            vector-effect="non-scaling-stroke" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>""")
        val expected = view("""<path d="M30 25L75 40L85 60" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>""")
        assertSimilar(snapshot(expected), snapshot(transformed))
        val reflected = view("""<path d="M5 5L20 5L20 15" transform="matrix(-3 -1 1 2 90 30)"
            vector-effect="non-scaling-stroke" stroke-width="4" stroke-linecap="square" stroke-linejoin="bevel"/>""")
        val reflectedReference = view("""<path d="M80 35L35 20L45 40" stroke-width="4" stroke-linecap="square" stroke-linejoin="bevel"/>""")
        assertSimilar(snapshot(reflectedReference), snapshot(reflected))
        val rotated = view("""<path d="M5 5H30" transform="translate(70 10) rotate(90) scale(2 3)"
            vector-effect="non-scaling-stroke" stroke-width="4"/>""")
        assertSimilar(snapshot(view("""<path d="M55 20V70" stroke-width="4"/>""")), snapshot(rotated))
    }

    @Test fun svgPathCommandsAndOpenSubpathsMatchNativeRenderingAtIdentity() = FxTest.run {
        val data = listOf(
            "m10 10 10 5 h10 v10 l5 0 c10 0 10 10 0 10 s-10 10 0 10 q10 5 0 10 t10 10",
            "M10 20C20 5 30 5 40 20S60 35 70 20Q80 5 90 20T80 40M10 70H40V90",
            "M10 30A15 10 25 01140 40a20 10 -30 1 0 30 20Z",
            "M10 10H40V40H10z m50 50h20v20h-20z",
            "M20 20A0 10 0 0 1 40 20M50 50L50 50"
        )
        data.forEach { d ->
            val attributes = """d="$d" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" """
            assertSimilar(snapshot(view("<path $attributes/>")),
                snapshot(view("<path $attributes vector-effect=\"non-scaling-stroke\"/>")))
        }
    }

    @Test fun transformedEllipsesAndRoundedRectanglesKeepNativeGeometry() = FxTest.run {
        val actual = view("""<circle cx="20" cy="20" r="10" transform="matrix(2 0 1 1 0 20)"
            stroke-width="3" vector-effect="non-scaling-stroke"/>""")
        val expected = view("""<ellipse cx="60" cy="40" rx="22.882456" ry="8.740320"
            transform="rotate(13.282526 60 40)" stroke-width="3"/>""")
        assertSimilar(snapshot(expected), snapshot(actual))
        val geometries = listOf(
            """<rect x="10" y="10" width="60" height="40" rx="10" ry="5" """,
            """<circle cx="50" cy="50" r="20" """,
            """<ellipse cx="50" cy="50" rx="25" ry="15" """,
            """<line x1="10" y1="20" x2="80" y2="70" """,
            """<polyline points="10,10 70,15 30,75" """,
            """<polygon points="10,10 70,15 30,75" """
        )
        // Prism has a separate ellipse rasterizer; verify its contour width analytically below.
        geometries.forEach { geometry ->
            assertSimilar(snapshot(view("$geometry stroke-width=\"3\"/>")),
                snapshot(view("$geometry stroke-width=\"3\" vector-effect=\"non-scaling-stroke\"/>")), geometry,
                if (geometry.startsWith("<ellipse")) .07 else .04)
        }
    }

    @Test fun ellipseStrokeHasTheSpecifiedWidthAlongObliqueNormals() = FxTest.run {
        val v = view("""<ellipse id="p" cx="50" cy="50" rx="25" ry="15" stroke-width="3" vector-effect="non-scaling-stroke"/>""")
        snapshot(v)
        val outline = (v.lookup("#p").parent as Group).children.filterIsInstance<Shape>().last { it.isVisible }
        for (degrees in listOf(0.0, 30.0, 45.0, 60.0, 90.0, 135.0, 225.0, 315.0)) {
            val angle = Math.toRadians(degrees)
            val x = 50 + 25 * kotlin.math.cos(angle); val y = 50 + 15 * kotlin.math.sin(angle)
            val nx = kotlin.math.cos(angle) / 25; val ny = kotlin.math.sin(angle) / 15
            val length = kotlin.math.hypot(nx, ny)
            for (sign in listOf(-1, 1)) {
                assertTrue(outline.contains(x + sign * 1.3 * nx / length, y + sign * 1.3 * ny / length))
                assertFalse(outline.contains(x + sign * 1.7 * nx / length, y + sign * 1.7 * ny / length))
            }
        }
    }

    @Test fun reflectedArcsMatchTheirExplicitHostCoordinates() = FxTest.run {
        val actual = view("""<path d="M20 30A15 10 25 0 1 50 40" transform="matrix(-1 0 0 1 90 0)"
            stroke-width="3" vector-effect="non-scaling-stroke"/>""")
        val expected = view("""<path d="M70 30A15 10 -25 0 0 40 40" stroke-width="3"/>""")
        assertSimilar(snapshot(expected), snapshot(actual))
    }

    @Test fun unchangedLayoutAndTranslationReuseTheStrokeOutline() = FxTest.run {
        val v = view("""<path id="p" d="M10 20H70V70" vector-effect="non-scaling-stroke"/>""")
        snapshot(v)
        val wrapper = v.lookup("#p").parent as SvgNonScalingStroke
        wrapper.scaleX = 1.2
        snapshot(v)
        val outline = wrapper.children.filterIsInstance<Path>().single()
        val first = outline.elements.first()
        snapshot(v)
        assertSame(first, outline.elements.first())
        wrapper.translateX = 2.0
        snapshot(v)
        assertSame(first, outline.elements.first())
        v.resize(0.0, 0.0); v.layout()
        snapshot(v)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun nestedViewportsAndUseResolveToTheOutermostSvgViewport() = FxTest.run {
        val actual = view("""<defs><path id="p" d="M0 0H10" stroke-width="4" vector-effect="non-scaling-stroke"/></defs>
            <svg x="10" y="10" width="80" height="60" viewBox="0 0 20 20" preserveAspectRatio="none">
            <use href="#p" x="3" y="2"/></svg>""")
        assertSimilar(snapshot(view("""<path d="M22 16H62" stroke-width="4"/>""")), snapshot(actual))
        assertSimilar(snapshot(view("""<path d="M22 16H62" stroke-width="2"/>"""), 200, 200), snapshot(actual, 200, 200))
    }

    @Test fun vectorEffectStylesCascadeAndAreNotImplicitlyInherited() = FxTest.run {
        val v = view("""<style>.fixed { vector-effect: non-scaling-stroke; } #overridden { vector-effect: none !important; }</style>
            <g vector-effect="non-scaling-stroke">
              <path id="ordinary" d="M10 10H50"/>
              <path id="inherited" d="M10 20H50" vector-effect="inherit"/>
              <path id="fixed" class="fixed" d="M10 30H50"/>
              <path id="inline" d="M10 40H50" style="vector-effect: non-scaling-stroke"/>
              <path id="overridden" class="fixed" d="M10 50H50"/>
            </g>""")
        snapshot(v)
        for (id in listOf("inherited", "fixed", "inline")) assertIs<SvgNonScalingStroke>(v.lookup("#$id").parent)
        for (id in listOf("ordinary", "overridden")) assertFalse(v.lookup("#$id").parent is SvgNonScalingStroke)
    }

    @Test fun applicationCssAndBindingsKeepTheirWidthAndOverrideTheTheme() = FxTest.run {
        val v = view("""<path id="p" d="M10 50H90" stroke-width="2" vector-effect="non-scaling-stroke"/>""")
        Scene(Group(v))
        v.svgStroke = Color.GREEN; v.svgStrokeWidth = 4.0
        val css = directory.resolve("fixed.css")
        Files.writeString(css, "#p { -fx-stroke: blue; -fx-stroke-width: 6; }")
        v.stylesheets += css.toUri().toString()
        assertEquals(6.0, columnAlpha(snapshot(v, 200, 200), 100), .05)
        val target = v.lookup("#p") as Shape
        assertEquals(Color.BLUE, target.stroke)
        assertEquals(6.0, target.strokeWidth)
        v.stylesheets.clear()
        assertEquals(4.0, columnAlpha(snapshot(v, 200, 200), 100), .05)
        assertEquals(Color.GREEN, target.stroke)
        val width = SimpleDoubleProperty(3.0)
        target.strokeWidthProperty().bind(width)
        assertEquals(3.0, columnAlpha(snapshot(v), 50), .05)
        width.set(5.0)
        assertEquals(5.0, columnAlpha(snapshot(v), 50), .05)
        assertEquals(5.0, target.strokeWidth)
    }

    @Test fun dashLengthsCapsAndOffsetsAreMeasuredInHostUnits() = FxTest.run {
        val attributes = """stroke-width="4" stroke-linecap="round" stroke-dasharray="8 6" stroke-dashoffset="3" """
        val source = view("""<path d="M10 10H40" transform="translate(0 30) scale(2 3)" $attributes vector-effect="non-scaling-stroke"/>""")
        val expected = view("""<path d="M20 60H80" $attributes/>""")
        assertSimilar(snapshot(expected), snapshot(source))
        Scene(Group(source))
        (source.lookup(".svg-path") as Shape).style = "-fx-stroke-dash-offset: 0; -fx-stroke-line-cap: butt;"
        val plain = view("""<path d="M20 60H80" stroke-width="4" stroke-dasharray="8 6"/>""")
        assertSimilar(snapshot(plain), snapshot(source))
    }

    @Test fun fillGradientOpacityAndGeometryBoundsRemainInSourceCoordinates() = FxTest.run {
        val v = view("""<defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>
            <rect id="p" x="10" y="10" width="30" height="30" fill="yellow" stroke="url(#g)" stroke-width="4"
                opacity=".5" transform="scale(2)" vector-effect="non-scaling-stroke"/>""")
        val image = snapshot(v)
        val target = v.lookup("#p") as Shape
        val wrapper = target.parent as SvgNonScalingStroke
        val gradient = target.stroke as LinearGradient
        assertEquals(10.0, gradient.startX)
        assertEquals(40.0, gradient.endX)
        assertEquals(30.0, svgGeometryBounds(wrapper).width)
        assertEquals(10.0, svgGeometryBounds(wrapper).minX)
        val left = image.pixelReader.getColor(20, 50)
        val right = image.pixelReader.getColor(79, 50)
        assertEquals(.5, left.opacity, .02)
        assertTrue(left.red > .9 && left.blue < .1)
        assertTrue(right.blue > .9 && right.red < .1)
        val fill = image.pixelReader.getColor(50, 50)
        assertEquals(1.0, fill.red); assertEquals(1.0, fill.green); assertEquals(0.0, fill.blue)
        assertEquals(.5, fill.opacity, .01)
    }

    @Test fun proportionalGradientStrokeUsesTheOriginalGeometryBox() = FxTest.run {
        val definitions = """<defs><radialGradient id="g" cx="0" cy=".5" r="1">
            <stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs>"""
        val shape = """<rect id="p" x="20" y="20" width="60" height="40" stroke="url(#g)" stroke-width="12" """
        val expected = snapshot(view("$definitions$shape/>"))
        val actual = snapshot(view("$definitions$shape vector-effect=\"non-scaling-stroke\"/>"))
        for ((x, y) in listOf(20 to 40, 50 to 20, 80 to 40, 50 to 60)) {
            val a = expected.pixelReader.getColor(x, y); val b = actual.pixelReader.getColor(x, y)
            assertEquals(a.red, b.red, .025, "red at $x,$y")
            assertEquals(a.blue, b.blue, .025, "blue at $x,$y")
        }
        val ordinary = view("$definitions$shape/>")
        val fixed = view("$definitions$shape vector-effect=\"non-scaling-stroke\"/>")
        for (paint in listOf(
            LinearGradient(0.0, 0.0, 1.0, 1.0, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                javafx.scene.paint.Stop(0.0, Color.RED), javafx.scene.paint.Stop(1.0, Color.BLUE)),
            javafx.scene.paint.RadialGradient(30.0, .3, .5, .5, .7, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                javafx.scene.paint.Stop(0.0, Color.RED), javafx.scene.paint.Stop(1.0, Color.BLUE))
        )) {
            (ordinary.lookup("#p") as Shape).stroke = paint
            (fixed.lookup("#p") as Shape).stroke = paint
            val a = snapshot(ordinary); val b = snapshot(fixed)
            for ((x, y) in listOf(20 to 40, 50 to 20, 80 to 40, 50 to 60)) {
                val ca = a.pixelReader.getColor(x, y); val cb = b.pixelReader.getColor(x, y)
                assertEquals(ca.red, cb.red, .025)
                assertEquals(ca.blue, cb.blue, .025)
            }
        }
    }

    @Test fun geometricChangesZeroTransformsAndClearedStrokeRecover() = FxTest.run {
        val v = view("""<path id="p" d="M10 50H90" stroke-width="4" vector-effect="non-scaling-stroke"/>""")
        val target = v.lookup("#p") as javafx.scene.shape.SVGPath
        snapshot(v)
        target.content = "M10 20H90"
        val moved = snapshot(v)
        assertTrue(moved.pixelReader.getColor(50, 20).opacity > .99)
        assertEquals(0.0, moved.pixelReader.getColor(50, 50).opacity)
        val parent = target.parent
        parent.scaleX = 0.0
        assertEquals(0.0, columnAlpha(snapshot(v), 50), .01)
        parent.scaleX = 1.0
        assertEquals(4.0, columnAlpha(snapshot(v), 50), .05)
        target.stroke = null
        assertEquals(0.0, columnAlpha(snapshot(v), 50), .01)
        target.stroke = Color.RED
        assertEquals(4.0, columnAlpha(snapshot(v), 50), .05)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun rootClipMaskAndBlurWorkAroundTheFixedStroke() = FxTest.run {
        val clipped = view("""<defs><clipPath id="c"><rect x="0" y="0" width="50" height="100"/></clipPath></defs>
            <path d="M10 50H90" stroke-width="4" vector-effect="non-scaling-stroke" clip-path="url(#c)"/>""")
        val a = snapshot(clipped, 200, 200)
        assertEquals(4.0, columnAlpha(a, 80), .05)
        assertEquals(0.0, columnAlpha(a, 120), .01)
        val masked = view("""<defs><mask id="m" mask-type="alpha"><rect width="50" height="100" fill="white"/></mask></defs>
            <path d="M10 50H90" stroke-width="4" vector-effect="non-scaling-stroke" mask="url(#m)"/>""")
        // Give the masked shape a nonempty geometric box.
        (masked.lookup(".svg-path") as javafx.scene.shape.SVGPath).content = "M10 30H90V70"
        val b = snapshot(masked, 200, 200)
        assertTrue(columnAlpha(b, 80) > 3.5)
        assertEquals(0.0, columnAlpha(b, 120), .01)
        val blurred = view("""<defs><filter id="f" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="1"/></filter></defs>
            <path d="M10 30H90V70" stroke-width="4" vector-effect="non-scaling-stroke" filter="url(#f)"/>""")
        assertTrue(columnAlpha(snapshot(blurred), 50) > 3.0)
    }

    @Test fun strokesInsideMasksUseTheSameHostSpaceForBothContentUnits() = FxTest.run {
        for (objectBox in listOf(false, true)) {
            val units = if (objectBox) "objectBoundingBox" else "userSpaceOnUse"
            val path = if (objectBox) "M.1 .5H.9" else "M10 50H90"
            val v = view("""<defs><mask id="m" maskContentUnits="$units" mask-type="alpha">
                <path d="$path" stroke="white" stroke-width="4" vector-effect="non-scaling-stroke"/></mask></defs>
                <rect width="100" height="100" fill="red" stroke="none" mask="url(#m)"/>""")
            for (size in listOf(100, 200)) {
                assertEquals(4.0, columnAlpha(snapshot(v, size, size), size / 2), .15, units)
            }
        }
    }

    @Test fun nativeTextTspanAndTextLengthKeepTheirCssTargets() = FxTest.run {
        val v = view("""<text id="plain" x="5" y="25" font-size="20" stroke-width="1" vector-effect="non-scaling-stroke">SVG</text>
            <text x="5" y="50" font-size="18">A<tspan id="span" vector-effect="non-scaling-stroke" stroke-width="1">B</tspan>C</text>
            <text id="adjusted" x="5" y="80" textLength="80" font-size="16" stroke-width="1" vector-effect="non-scaling-stroke">ABC</text>""")
        snapshot(v, 200, 200)
        assertIs<Text>(v.lookup("#plain"))
        assertIs<SvgNonScalingStroke>(v.lookup("#plain").parent)
        assertIs<SvgNonScalingStroke>(v.lookup("#span").lookup(".svg-text-run").parent)
        assertIs<SvgAdjustedText>(v.lookup("#adjusted").parent)
        (v.lookup("#plain") as Text).style = "-fx-font-size: 22; -fx-stroke: blue;"
        assertTrue(columnAlpha(snapshot(v, 200, 200), 25) > 0)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun invalidValuesAndExpandedNodeLimitsAreExplicitlyDiagnosed() = FxTest.run {
        val xml = """<svg><path d="M0 0L10 10" vector-effect="invalid"/></svg>"""
        assertTrue(SvgView.parse(xml).diagnostics.any { "vector-effect" in it.message })
        assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true)) }
        val limited = """<svg><path d="M0 0L10 10" vector-effect="non-scaling-stroke"/></svg>"""
        assertFailsWith<SvgException> { SvgView.parse(limited, SvgOptions(strict = true, maxElements = 4)) }
        val loose = SvgView.parse(limited, SvgOptions(maxElements = 4))
        loose.layout()
        assertTrue(loose.diagnostics.any { "stroke node limit" in it.message })
    }

    private fun view(content: String): SvgView = SvgView.parse(
        """<svg viewBox="0 0 100 100" fill="none" stroke="black">$content</svg>""", SvgOptions(strict = true)).apply { isManaged = false }

    private fun snapshot(node: SvgView, width: Int = 100, height: Int = 100): WritableImage {
        node.resize(width.toDouble(), height.toDouble())
        node.requestLayout(); node.applyCss(); node.layout()
        return node.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, WritableImage(width, height))
    }

    private fun columnAlpha(image: WritableImage, x: Int): Double =
        (0 until image.height.toInt()).sumOf { image.pixelReader.getColor(x, it).opacity }

    private fun assertSimilar(expected: WritableImage, actual: WritableImage, context: String = "", tolerance: Double = .04) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var difference = 0.0
        var coverage = 0.0
        for (y in 0 until expected.height.toInt()) for (x in 0 until expected.width.toInt()) {
            val a = expected.pixelReader.getColor(x, y).opacity
            val b = actual.pixelReader.getColor(x, y).opacity
            difference += abs(a - b); coverage += a
        }
        assertTrue(coverage > 10, "Empty reference")
        assertTrue(difference / coverage < tolerance, "Relative coverage difference ${difference / coverage}; $context")
    }
}
