package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.text.Text
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Resource identity/budget regression, not a timing-based optimization claim. */
class SvgTextRadialReuseTest {
    private val gradient = """<defs><radialGradient id='g' fr='.1'><stop stop-color='red'/><stop offset='1' stop-color='blue'/></radialGradient></defs>"""
    private fun xml(text: String) = "<svg width='500' height='40' viewBox='0 0 500 40'>$gradient$text</svg>"
    private fun view(text: String, budget: Int = 50_000, strict: Boolean = true) = SvgView.parse(xml(text), SvgOptions(strict = strict, maxPatternPixels = budget)).apply {
        isManaged = false; animationAutoPlay = false; Scene(Group(this)); resize(500.0,40.0); applyCss(); layout()
    }
    private fun runs(v: SvgView) = v.lookupAll(".svg-text-run").filterIsInstance<Text>()
    private fun paints(v: SvgView) = runs(v).map { assertIs<ImagePattern>(it.fill) }
    private fun identityCount(values: List<Any>): Int = java.util.IdentityHashMap<Any, Boolean>().apply {values.forEach {put(it,true)}}.size
    private fun pixels(v: SvgView): List<Int> {
        val image=v.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null)
        return (0 until image.height.toInt()).flatMap { y -> (0 until image.width.toInt()).map { x -> image.pixelReader.getArgb(x,y) } }
    }
    private fun text(content: String, extra: String = "") = "<text x='2' y='24' rotate='0' font-family='monospace' font-size='10' fill='url(#g)' $extra>$content</text>"

    @Test fun sixtyPositionedGlyphsShareOneRasterWithinOriginalBudgetAndResizeBack()=FxTest.run {
        val v=view(text("M".repeat(60)))
        assertEquals(60,runs(v).size);assertEquals(1,identityCount(paints(v).map {it.image}))
        val first=paints(v).first().image;val original=pixels(v)
        v.resize(750.0,60.0);v.layout()
        assertEquals(1,identityCount(paints(v).map {it.image}));assertNotSame(first,paints(v).first().image)
        v.resize(500.0,40.0);v.layout()
        assertEquals(1,identityCount(paints(v).map {it.image}));assertEquals(original,pixels(v));assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun distinctSpanOwnersAndOpacityRemainSeparate()=FxTest.run {
        val v=view(text("<tspan fill-opacity='.25'>MMMM</tspan><tspan fill-opacity='.75'>MMMM</tspan>"))
        val images=paints(v).map {it.image}
        assertEquals(2,identityCount(images))
        val alphas=images.map {it.pixelReader.getColor(0,0).opacity}.distinct().sorted()
        assertEquals(2,alphas.size);assertEquals(.25,alphas[0],.005);assertEquals(.75,alphas[1],.005)
    }

    @Test fun initiallyEqualAnimatedSpanOpacityDoesNotAliasAfterSeeking()=FxTest.run {
        val v=view(text("<tspan>MMMM<animate attributeName='fill-opacity' values='1;.25' dur='2s' fill='freeze'/></tspan><tspan>MMMM</tspan>"))
        assertEquals(2,identityCount(paints(v).map {it.image}))
        v.seekAnimations(Duration.seconds(2.0));v.layout()
        val alphas=paints(v).map {it.image.pixelReader.getColor(0,0).opacity}.distinct().sorted()
        assertEquals(2,alphas.size);assertEquals(.25,alphas.first(),.005);assertEquals(1.0,alphas.last(),.005)
        v.seekAnimations(Duration.ZERO);v.layout()
        assertTrue(paints(v).all {it.image.pixelReader.getColor(0,0).opacity>.99})
        assertEquals(2,identityCount(paints(v).map {it.image}))
    }

    @Test fun fillAndStrokeChannelsNeverShareMutablePaintCache()=FxTest.run {
        val v=view(text("MMMM", "stroke='url(#g)' stroke-width='.5' stroke-opacity='.4'"))
        val rs=runs(v)
        assertEquals(1,identityCount(rs.map {assertIs<ImagePattern>(it.fill).image}))
        assertEquals(1,identityCount(rs.map {assertIs<ImagePattern>(it.stroke).image}))
        assertNotSame(assertIs<ImagePattern>(rs.first().fill).image,assertIs<ImagePattern>(rs.first().stroke).image)
        assertEquals(.4,assertIs<ImagePattern>(rs.first().stroke).image.pixelReader.getColor(0,0).opacity,.005)
    }

    @Test fun viewsAndReplacementDocumentsNeverReuseOldRasterOwners()=FxTest.run {
        val content=text("M".repeat(60));val a=view(content);val b=view(content)
        val before=paints(a).first().image
        assertNotSame(before,paints(b).first().image)
        repeat(4) {
            a.document=SvgLoader.parse(xml(content),SvgOptions(strict=true,maxPatternPixels=50_000));a.layout()
            assertEquals(1,identityCount(paints(a).map {it.image}));assertNotSame(before,paints(a).first().image)
        }
        assertTrue(a.diagnostics.isEmpty());assertTrue(b.diagnostics.isEmpty())
    }

    @Test fun failedTextRenderRollsBackUniqueRasterBudgetBeforeValidSibling()=FxTest.run {
        // First text registers a shared radial owner, then invalid dash validation fails.
        // Allocation timing is implementation-dependent; explicitly inspect owner rollback
        // rather than claiming this necessarily exercises charged-pixel release.
        val content=text("M".repeat(60),"stroke-dasharray='-1'")+text("M".repeat(60))
        val v=view(content,budget=10_000,strict=false)
        assertTrue(v.diagnostics.isNotEmpty())
        val renderer=SvgView::class.java.getDeclaredField("renderer").apply {isAccessible=true}.get(v)
        val retained=FxSvgRenderer::class.java.getDeclaredField("radialPaints").apply {isAccessible=true}.get(renderer) as List<*>
        assertEquals(1,retained.size,"Failed text must leave no stale radial owner")
        assertEquals(60,runs(v).size);assertEquals(1,identityCount(paints(v).map {it.image}))
        assertTrue(pixels(v).any {it.ushr(24)>0})
    }
}
