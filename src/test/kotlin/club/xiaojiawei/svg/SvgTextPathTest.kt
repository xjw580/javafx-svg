package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgTextPathTest {
    private fun view(path: String = "M4 40H96", attrs: String = "", textAttrs: String = "", defsAttrs: String = "", value: String = "SVG"): SvgView =
        SvgView.parse("""<svg viewBox="0 0 100 64"><defs><path id="p" d="$path" $defsAttrs/></defs>
            <text id="t" font-size="24" fill="blue" $textAttrs><textPath id="tp" href="#p" $attrs>$value</textPath></text></svg>""", SvgOptions(strict = true)).apply {
            isManaged = false; Scene(Group(this)); resize(100.0,64.0); applyCss(); layout()
        }
    private fun runs(v: SvgView) = v.lookupAll(".svg-text-glyph").filterIsInstance<Text>().sortedBy { it.localToParentTransform.tx }

    @Test fun horizontalNativeGlyphsAndCss() = FxTest.run {
        val v = view()
        assertEquals(3,runs(v).size)
        runs(v).forEach {
            assertEquals(Color.BLUE, it.fill)
            assertEquals(40.0, it.localToParentTransform.ty, 1e-8)
        }
        val text=v.lookup("#tp") as Text
        text.style="-fx-fill: red; -fx-font-size: 30px;"
        v.applyCss(); v.layout()
        assertTrue(runs(v).all { it.fill == Color.RED && it.font.size == 30.0 })
        assertTrue(v.diagnostics.isEmpty())
    }
    @Test fun offsetAnchorAndReferenceTransform() = FxTest.run {
        val v=view(attrs="""startOffset="50%" text-anchor="middle" """,defsAttrs="""transform="translate(2 3)" """)
        val glyphs=runs(v)
        val left=glyphs.first().localToParentTransform.tx-glyphs.first().layoutBounds.width/2
        val right=glyphs.last().localToParentTransform.tx+glyphs.last().layoutBounds.width/2
        assertEquals(52.0,(left+right)/2,.1)
        assertTrue(glyphs.all { it.localToParentTransform.ty == 43.0 })
    }
    @Test fun curvedAndVerticalGlyphsFollowTangent() = FxTest.run {
        val v=view("M50 8V60",value="AB")
        val r=runs(v).first()
        assertEquals(0.0,r.localToParentTransform.mxx,1e-8)
        assertEquals(1.0,r.localToParentTransform.myx,1e-8)
        assertEquals(50.0,r.localToParentTransform.tx,.001)
        val curved=view("M4 40Q50 0 96 40")
        assertTrue(runs(curved).any { kotlin.math.abs(it.localToParentTransform.myx)>.1 })
    }
    @Test fun pathLengthCalibrationAndClipping() = FxTest.run {
        val a=view(attrs="""startOffset="5" """,defsAttrs="""pathLength="10" """,value="A")
        val b=view(attrs="""startOffset="50%" """,value="A")
        assertEquals(runs(a).first().localToParentTransform.tx,runs(b).first().localToParentTransform.tx,.001)
        val outside=view(attrs="""startOffset="200%" """)
        assertTrue(runs(outside).none { it.isVisible })
    }
    @Test fun sharedDocumentsKeepFontAndGlyphsIndependent() = FxTest.run {
        val a=view()
        val b=SvgView(a.document).apply { isManaged=false; Scene(Group(this)); resize(100.0,64.0); applyCss(); layout() }
        (a.lookup("#tp") as Text).style="-fx-font-size: 36px;"
        a.applyCss(); a.layout()
        assertEquals(24.0,runs(b).first().font.size)
        assertEquals(36.0,runs(a).first().font.size)
        assertNotSame(runs(a).first(),runs(b).first())
    }
    @Test fun unsupportedReferencesAndMixedRunsAreDiagnosed() = FxTest.run {
        assertFailsWith<SvgException> { view(attrs="""method="stretch" """) }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><text><textPath href="other.svg#p">A</textPath></text></svg>""",SvgOptions(strict=true)) }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><text>A<textPath href="#p">B</textPath></text></svg>""",SvgOptions(strict=true)) }
    }
}
