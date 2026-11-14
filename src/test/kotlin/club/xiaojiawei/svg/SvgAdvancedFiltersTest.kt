package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class SvgAdvancedFiltersTest {
    @Test fun exactAdvancedFiltersRenderAtPreviewScales() = FxTest.run {
        val v = SvgView.load(checkNotNull(javaClass.getResource("/svg/advanced-filters.svg")), SvgOptions(strict = true))
        v.isManaged = false; Scene(Group(v))
        for (scale in listOf(1.0, 1.25, 1.5, 2.0, 1.0, 2.0)) {
            v.resize(640 * scale, 360 * scale); v.applyCss(); v.layout()
            val im = v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
            println("ADVANCED FILTERS scale=$scale diagnostics=${v.diagnostics}")
            assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
            if (scale == 1.0) {
                val image = BufferedImage(im.width.toInt(), im.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until image.height) for(x in 0 until image.width) image.setRGB(x,y,im.pixelReader.getArgb(x,y))
                assertTrue(ImageIO.write(image,"png",Path.of("target","advanced-filters-1x.png").toFile()))
                assertTrue(im.pixelReader.getColor(370,180).red > .2, "Morphology glow should extend beyond the card")
            }
        }
        v.source = null
    }

    @Test fun finiteNegativeMorphologyRadiusDisablesBeforeDeviceScaling() = FxTest.run {
        val v = SvgView.parse("""<svg width="50" height="50" viewBox="0 0 50 50"><defs>
            <filter id="f"><feMorphology radius="-1e308 3"/></filter></defs>
            <rect x="10" y="10" width="20" height="20" fill="red" filter="url(#f)"/></svg>""", SvgOptions(strict = true))
        v.isManaged=false; Scene(Group(v)); v.resize(100.0,100.0); v.applyCss(); v.layout()
        val image=v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
        assertEquals(Color.RED,image.pixelReader.getColor(30,30))
        assertTrue(v.diagnostics.isEmpty())
        v.source=null
    }

    private fun render(primitives: String, filter: String = "") = SvgView.parse("""<svg width="100" height="100" viewBox="0 0 100 100"><defs>
        <filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" $filter>$primitives</filter></defs>
        <rect x="40" y="40" width="20" height="20" fill="red" filter="url(#f)"/></svg>""",SvgOptions(strict=true)).let { v ->
        v.isManaged=false;Scene(Group(v));v.resize(100.0,100.0);v.applyCss();v.layout()
        assertTrue(v.diagnostics.isEmpty());v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
    }

    @Test fun morphologyAndDisplacementUsePrimitiveUnitsAndInputs() = FxTest.run {
        val dilate=render("<feMorphology operator='dilate' radius='3'/>")
        assertEquals(Color.RED,dilate.pixelReader.getColor(38,50))
        assertEquals(0.0,dilate.pixelReader.getColor(35,50).opacity)
        val erode=render("<feMorphology operator='erode' radius='.15'/>","primitiveUnits='objectBoundingBox'")
        assertEquals(0.0,erode.pixelReader.getColor(41,50).opacity)
        assertEquals(Color.RED,erode.pixelReader.getColor(44,50))
        val displacement=render("""<feFlood flood-color="rgb(100%,50%,50%)" result="map"/>
            <feDisplacementMap in="SourceGraphic" in2="map" scale="10" xChannelSelector="R" yChannelSelector="G"/>""",
            "color-interpolation-filters='sRGB'")
        assertEquals(Color.RED,displacement.pixelReader.getColor(36,50))
        assertEquals(0.0,displacement.pixelReader.getColor(62,50).opacity)
    }

    @Test fun emptyStitchedTurbulenceIsTransparentButInvalidSyntaxStillFails() = FxTest.run {
        for (dimension in listOf("width", "height")) {
            val image = render("<feTurbulence baseFrequency='.1' stitchTiles='stitch' $dimension='0'/>")
            assertEquals(0.0, image.pixelReader.getColor(50, 50).opacity)
        }
        assertFailsWith<SvgException> {
            render("<feTurbulence baseFrequency='-1' stitchTiles='stitch' width='0'/>")
        }
    }

    @Test fun invalidNewPrimitiveParametersRemainStrictErrors() = FxTest.run {
        for(p in listOf("<feTurbulence baseFrequency='-1'/>","<feTurbulence numOctaves='1.5'/>",
            "<feTurbulence numOctaves='17'/>","<feTurbulence type='unknown'/>",
            "<feMorphology operator='unknown'/>","<feMorphology radius='257'/>",
            "<feDisplacementMap xChannelSelector='X'/>")) assertFailsWith<SvgException>(p) {render(p)}
    }
}
