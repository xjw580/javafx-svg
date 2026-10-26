package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.*

class SvgLightingRenderingTest {
    private fun xml(primitives: String, filter: String = "", extra: String = "", content: String = """<rect x="20" y="30" width="40" height="20" fill="#376fb3" filter="url(#f)"/>""") =
        """<svg width="100" height="100" viewBox="0 0 100 100">$extra<defs>
        <filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" $filter>$primitives</filter>
        </defs>$content</svg>"""
    private fun view(primitives: String, filter: String = "", extra: String = "", options: SvgOptions = SvgOptions(strict = true)) =
        SvgView.parse(xml(primitives, filter, extra), options)
    private fun image(v: SvgView, scale: Double = 1.0): WritableImage {
        v.isManaged = false
        if (v.scene == null) Scene(Group(v))
        v.resize(100 * scale, 100 * scale); v.applyCss(); v.layout()
        return v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
    private fun diffuse(attributes: String = "", light: String = "<feDistantLight elevation='90'/>") =
        "<feDiffuseLighting $attributes>$light</feDiffuseLighting>"
    private fun specular(attributes: String = "", light: String = "<feDistantLight elevation='90'/>") =
        "<feSpecularLighting $attributes>$light</feSpecularLighting>"
    private fun same(a: WritableImage, b: WritableImage, tolerance: Int = 0) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        for (y in 0 until a.height.toInt()) for (x in 0 until a.width.toInt()) {
            val p = a.pixelReader.getArgb(x, y); val q = b.pixelReader.getArgb(x, y)
            for (shift in listOf(0, 8, 16, 24)) assertTrue(kotlin.math.abs(((p ushr shift) and 255) - ((q ushr shift) and 255)) <= tolerance,
                "Pixel ($x,$y), channel $shift: ${p.toUInt().toString(16)} != ${q.toUInt().toString(16)}")
        }
    }

    @Test fun diffuseUsesOnlyAlphaAndIsOpaqueAcrossPrimitiveRegion() = FxTest.run {
        val a = image(view(diffuse("surfaceScale='0' lighting-color='#4080c0'")))
        assertEquals(Color.web("#4080c0"), a.pixelReader.getColor(80, 80))
        assertEquals(a.pixelReader.getColor(80,80), a.pixelReader.getColor(40,40))
        val b = image(SvgView.parse(xml(diffuse("surfaceScale='0' lighting-color='#4080c0'"))
            .replace("#376fb3", "#ff0000"), SvgOptions(strict=true)))
        same(a,b)
    }

    @Test fun specularAlphaIsMaximumLitChannelWithoutDoublePremultiplication() = FxTest.run {
        val v = view(specular("surfaceScale='0' specularConstant='.5' lighting-color='red'"), "color-interpolation-filters='sRGB'")
        val c = image(v).pixelReader.getColor(40,40)
        assertEquals(1.0,c.red,.01); assertEquals(0.0,c.green,.01); assertEquals(.5,c.opacity,.01)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun lightingColorDefaultsWhiteDoesNotInheritButExplicitInheritWorks() = FxTest.run {
        assertEquals(Color.WHITE,image(view(diffuse("surfaceScale='0'"),"lighting-color='red'")).pixelReader.getColor(40,40))
        assertEquals(Color.RED,image(view(diffuse("surfaceScale='0' lighting-color='inherit'"),"lighting-color='red'")).pixelReader.getColor(40,40))
    }

    @Test fun lightingColorStylesheetCurrentColorAndInlinePrecedenceWork() = FxTest.run {
        val v = view(diffuse("id='light' surfaceScale='0' lighting-color='red' style='lighting-color:currentColor'"),"color='#33aa77'",
            "<style>#light {lighting-color:blue}</style>")
        assertEquals(Color.web("#33aa77"),image(v).pixelReader.getColor(40,40))
        val important = view(diffuse("id='light' surfaceScale='0' style='lighting-color:red'"), extra="<style>#light {lighting-color:blue !important}</style>")
        assertEquals(Color.BLUE,image(important).pixelReader.getColor(40,40))
    }

    @Test fun lightingColorAlphaIsIgnored() = FxTest.run {
        same(image(view(diffuse("surfaceScale='0' lighting-color='rgba(255,0,0,0)'"))),
            image(view(diffuse("surfaceScale='0' lighting-color='red'"))))
    }

    @Test fun lightingOutputHonorsLinearRgbAndSrgb() = FxTest.run {
        val primitive=diffuse("surfaceScale='0' diffuseConstant='.5' lighting-color='#808080'")
        val linear=image(view(primitive)).pixelReader.getColor(40,40)
        val srgb=image(view(primitive,"color-interpolation-filters='sRGB'")).pixelReader.getColor(40,40)
        assertEquals(.362,linear.red,.012); assertEquals(.251,srgb.red,.012)
        assertTrue(linear.red > srgb.red + .08)
    }

    @Test fun namedLightingOutputCanBeMaskedAndMerged() = FxTest.run {
        val v=view(diffuse("surfaceScale='0' lighting-color='red' result='light'") +
            "<feComposite in='light' in2='SourceAlpha' operator='in' result='masked'/><feMerge><feMergeNode in='masked'/></feMerge>")
        val p=image(v).pixelReader
        assertEquals(Color.RED,p.getColor(40,40)); assertEquals(0.0,p.getColor(80,80).opacity)
    }

    @Test fun diffuseReadsNamedAlphaInsteadOfSourceRgb() = FxTest.run {
        val v=view("<feFlood flood-color='blue' flood-opacity='.4' result='map'/>"+
            diffuse("in='map' surfaceScale='8' lighting-color='red'"))
        assertEquals(Color.RED,image(v).pixelReader.getColor(40,40))
    }

    @Test fun primitiveSubregionClipsLightingAndPropagatesToFollowingPrimitive() = FxTest.run {
        val v=view(diffuse("surfaceScale='0' x='10' y='10' width='20' height='20' result='lit'")+"<feOffset in='lit' dx='0'/>")
        val p=image(v).pixelReader
        assertEquals(Color.WHITE,p.getColor(15,15)); assertEquals(0.0,p.getColor(35,35).opacity)
        for (dimension in listOf("width", "height")) {
            assertEquals(0.0,image(view(diffuse("$dimension='0'"))).pixelReader.getColor(40,40).opacity)
        }
    }

    @Test fun objectBoundingBoxPointCoordinatesAndSurfaceScaleMatchResolvedUserUnits() = FxTest.run {
        val unitZ=hypot(40.0,20.0)/sqrt(2.0)
        val bbox=diffuse("surfaceScale='.15'", "<fePointLight x='.5' y='.4' z='.8'/>")
        val user=diffuse("surfaceScale='.15'", "<fePointLight x='40' y='38' z='${.8*unitZ}'/>")
        same(image(view(bbox,"primitiveUnits='objectBoundingBox'")),image(view(user)),1)
    }

    @Test fun objectBoundingBoxSpotTargetCoordinatesMatchResolvedUserUnits() = FxTest.run {
        val z=hypot(40.0,20.0)/sqrt(2.0)
        val bbox=specular("surfaceScale='.1' specularExponent='5'", "<feSpotLight x='.2' y='-.3' z='1.2' pointsAtX='.6' pointsAtY='.4' pointsAtZ='.1' specularExponent='3' limitingConeAngle='60'/>")
        val user=specular("surfaceScale='.1' specularExponent='5'", "<feSpotLight x='28' y='24' z='${1.2*z}' pointsAtX='44' pointsAtY='38' pointsAtZ='${.1*z}' specularExponent='3' limitingConeAngle='60'/>")
        same(image(view(bbox,"primitiveUnits='objectBoundingBox'")),image(view(user)),1)
    }

    @Test fun distantLightIsUnaffectedByBoundingBoxAxisAspectRatioAtFlatSurface() = FxTest.run {
        val p=diffuse("surfaceScale='0'", "<feDistantLight azimuth='35' elevation='42'/>")
        same(image(view(p)),image(view(p,"primitiveUnits='objectBoundingBox'")))
    }

    @Test fun missingLightIsTransparentAndFirstRecognizedLightWins() = FxTest.run {
        for (tag in listOf("feDiffuseLighting","feSpecularLighting")) {
            assertEquals(0.0,image(view("<$tag/>")).pixelReader.getColor(40,40).opacity)
            val first="<$tag surfaceScale='0'><desc>light</desc><feDistantLight elevation='90'/><feDistantLight elevation='-90'/></$tag>"
            assertEquals(Color.WHITE,image(view(first)).pixelReader.getColor(40,40))
        }
    }

    @Test fun zeroConstantsAndBackFacingLightsHaveExpectedCoverage() = FxTest.run {
        assertEquals(Color.BLACK,image(view(diffuse("diffuseConstant='0'"))).pixelReader.getColor(40,40))
        assertEquals(0.0,image(view(specular("specularConstant='0'"))).pixelReader.getColor(40,40).opacity)
        assertEquals(Color.BLACK,image(view(diffuse("surfaceScale='0'","<feDistantLight elevation='-90'/>"))).pixelReader.getColor(40,40))
    }

    @Test fun nonpositiveKernelSpacingFallsBackToRasterDefault() = FxTest.run {
        val expected=image(view(diffuse("surfaceScale='5'","<feDistantLight elevation='30'/>")))
        for (k in listOf("0","-1","1 -2")) same(expected,image(view(diffuse("surfaceScale='5' kernelUnitLength='$k'","<feDistantLight elevation='30'/>"))))
    }

    @Test fun explicitKernelSpacingSupportsAnisotropicUnitsAndFiniteLargeAngles() = FxTest.run {
        val v=view(diffuse("surfaceScale='6' kernelUnitLength='.5 2'","<feDistantLight azimuth='1e308' elevation='45'/>"))
        val p=image(v).pixelReader
        assertTrue(p.getColor(40,40).opacity > .99); assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun malformedLightingParametersAndAnimationAreStrictErrors() = FxTest.run {
        val invalid=listOf(diffuse("diffuseConstant='-1'"),specular("specularConstant='-1'"),specular("specularExponent='0'"),
            specular("specularExponent='129'"),diffuse("surfaceScale='NaN'"),diffuse("kernelUnitLength='1 2 3'"),
            diffuse(light="<fePointLight x='1px'/>"),diffuse(light="<feSpotLight specularExponent='-1'/>"),
            diffuse(light="<feSpotLight limitingConeAngle='NaN'/>"),diffuse(light="<feDistantLight><animate attributeName='elevation' values='0;90' dur='1s'/></feDistantLight>"),
            "<feDiffuseLighting><animate attributeName='surfaceScale' values='1;2' dur='1s'/><feDistantLight/></feDiffuseLighting>")
        for (p in invalid) assertFailsWith<SvgException>(p) { image(view(p)) }
    }

    @Test fun pixelBudgetFailureClearsOutputAndRecoveryReplacesTheDocument() = FxTest.run {
        val v=view(diffuse(),options=SvgOptions(maxFilterPixels=100))
        val failed=image(v)
        assertTrue(v.diagnostics.any { "maxFilterPixels" in it.message },v.diagnostics.toString())
        assertEquals(0.0,failed.pixelReader.getColor(40,40).opacity)
        v.document=SvgLoader.parse(xml(diffuse("surfaceScale='0' lighting-color='blue'")),SvgOptions(strict=true))
        assertEquals(Color.BLUE,image(v).pixelReader.getColor(40,40))
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun aggregateWorkBudgetClearsOldOutputBeforeAnotherPrimitiveRuns() = FxTest.run {
        val chain=diffuse("surfaceScale='0'")+specular("surfaceScale='0'")
        val v=view(chain,options=SvgOptions())
        assertEquals(Color.WHITE,image(v).pixelReader.getColor(40,40))
        val failed=image(v,9.0)
        assertTrue(v.diagnostics.any { "work limit" in it.message },v.diagnostics.toString())
        assertEquals(0.0,failed.pixelReader.getColor(360,360).opacity)
        v.document=SvgLoader.parse(xml(chain),SvgOptions(strict=true))
        assertEquals(Color.WHITE,image(v).pixelReader.getColor(40,40))
    }

    @Test fun objectTransformsKeepLightingAttachedAndSourceChangesInvalidateIt() = FxTest.run {
        val source=xml(diffuse("surfaceScale='0' lighting-color='red'")+"<feComposite in2='SourceAlpha' operator='in'/>",
            content="""<g transform="translate(10 5) scale(.8 1.3)"><rect id="shape" x="20" y="30" width="40" height="20" fill="blue" filter="url(#f)"/></g>""")
        val v=SvgView.parse(source,SvgOptions(strict=true))
        val a=image(v).pixelReader
        assertEquals(Color.RED,a.getColor(40,55));assertEquals(0.0,a.getColor(20,55).opacity)
        val shape=v.lookup("#shape") as javafx.scene.shape.Rectangle
        shape.width=20.0
        val b=image(v).pixelReader
        assertEquals(0.0,b.getColor(50,55).opacity)
        assertEquals(Color.RED,b.getColor(35,55))
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun croppedFloodInputUsesFlatLightingNormalsAtItsOwnEdges() = FxTest.run {
        val v=view("<feFlood x='20' y='20' width='20' height='20' result='map'/>"+
            diffuse("in='map' surfaceScale='5'", "<feDistantLight azimuth='0' elevation='30'/>"),"color-interpolation-filters='sRGB'")
        val p=image(v).pixelReader
        for(x in listOf(20,21,30,38,39)) for(y in listOf(20,30,39)) {
            assertEquals(.5,p.getColor(x,y).red,.01,"Flat cropped input at ($x,$y)")
            assertEquals(1.0,p.getColor(x,y).opacity)
        }
        assertEquals(0.0,p.getColor(19,30).opacity)
    }

    @Test fun expandedLightingOutputRetainsTransparentNeighbourBevel() = FxTest.run {
        val v=view("<feFlood x='20' y='20' width='20' height='20' result='map'/>"+
            diffuse("in='map' surfaceScale='5' x='0' y='0' width='100' height='100'", "<feDistantLight azimuth='0' elevation='30'/>"),"color-interpolation-filters='sRGB'")
        val p=image(v).pixelReader
        assertEquals(.5,p.getColor(30,30).red,.01)
        assertTrue(p.getColor(20,30).red < .2)
        assertTrue(p.getColor(39,30).red > .65)
        assertEquals(1.0,p.getColor(10,30).opacity)
    }

    @Test fun narrowerLightingOutputStillSamplesOriginalInputOutsideItsClip() = FxTest.run {
        val prefix="<feFlood x='20' y='20' width='20' height='20' result='map'/><feComposite in='SourceGraphic' in2='map' operator='in' x='20' y='20' width='20' height='20' result='ramp'/>"
        val gradient="""<defs><linearGradient id="b"><stop offset="0" stop-opacity="0"/><stop offset=".1" stop-opacity=".8"/><stop offset=".4" stop-opacity=".85"/><stop offset="1" stop-opacity="1"/></linearGradient></defs>"""
        val content="""<rect x="20" y="20" width="20" height="20" fill="url(#b)" filter="url(#f)"/>"""
        fun rendered(region:String)=image(SvgView.parse(xml(prefix+diffuse("in='ramp' surfaceScale='3' $region","<feDistantLight azimuth='180' elevation='35'/>"),
            "color-interpolation-filters='sRGB'",gradient,content),SvgOptions(strict=true)))
        val full=rendered("x='0' y='0' width='100' height='100'")
        val crop=rendered("x='22' y='22' width='16' height='16'")
        for(x in 22..37) assertEquals(full.pixelReader.getArgb(x,30),crop.pixelReader.getArgb(x,30),"Clipped normal at x=$x")
        assertEquals(0.0,crop.pixelReader.getColor(21,30).opacity)
    }

    @Test fun sixLightingCombinationsRenderAcrossRepeatedPreviewScales() = FxTest.run {
        val v=SvgView.load(checkNotNull(javaClass.getResource("/svg/lighting-filters.svg")),SvgOptions(strict=true))
        v.isManaged=false;Scene(Group(v))
        for (scale in listOf(1.0,1.25,1.5,2.0,1.0,2.0)) {
            v.resize(720*scale,440*scale);v.applyCss();v.layout()
            val pixels=v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
            assertTrue(v.diagnostics.isEmpty(),"scale=$scale ${v.diagnostics}")
            assertEquals(6,v.lookupAll(".svg-filter-output").size)
            if(scale==1.0) {
                val image=BufferedImage(pixels.width.toInt(),pixels.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until image.height) for(x in 0 until image.width) image.setRGB(x,y,pixels.pixelReader.getArgb(x,y))
                assertTrue(ImageIO.write(image,"png",Path.of("target","lighting-filters-1x.png").toFile()))
            }
        }
        v.source=null
    }
}
