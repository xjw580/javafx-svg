package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.*

class SvgLightingParametersTest {
    private fun element(xml: String): SvgElement = SvgLoader.parse("<svg xmlns='http://www.w3.org/2000/svg'><defs>$xml</defs></svg>")
        .root.children.single().children.single()
    private fun parameters(xml: String) = SvgLightingParameters.parse(element(xml))

    @Test fun absentLightAndDefaultNumbers() {
        val empty = parameters("<feDiffuseLighting/>")
        assertEquals(SvgLightingLight.None, empty.light)
        assertEquals(1.0, empty.surfaceScale); assertEquals(1.0, empty.constant)
        assertNull(empty.kernelX); assertNull(empty.kernelY)
        assertEquals(SvgLightingLight.Point(), parameters("<feSpecularLighting><fePointLight/></feSpecularLighting>").light)
        assertEquals(SvgLightingLight.Distant(), parameters("<feDiffuseLighting><feDistantLight/></feDiffuseLighting>").light)
        assertEquals(SvgLightingLight.Spot(), parameters("<feDiffuseLighting><feSpotLight/></feDiffuseLighting>").light)
    }

    @Test fun firstLightWinsAndDescriptiveOrForeignMetadataDoNotCount() {
        val value = parameters("""<feDiffuseLighting><title>T</title><x:extra xmlns:x='urn:example'/>
            <feDistantLight azimuth='30'/><fePointLight x='50'/></feDiffuseLighting>""")
        assertEquals(SvgLightingLight.Distant(30.0, 0.0), value.light)
    }

    @Test fun kernelLengthsAndNonpositiveDefault() {
        for (raw in listOf("0", "-1", "0 2", "2 -1")) {
            val p = parameters("<feDiffuseLighting kernelUnitLength='$raw'><fePointLight/></feDiffuseLighting>")
            assertNull(p.kernelX); assertNull(p.kernelY)
        }
        val single = parameters("<feDiffuseLighting kernelUnitLength='.5'/>")
        assertEquals(.5, single.kernelX); assertEquals(.5, single.kernelY)
        val pair = parameters("<feSpecularLighting kernelUnitLength='.5 2'/>")
        assertEquals(.5, pair.kernelX); assertEquals(2.0, pair.kernelY)
    }

    @Test fun numbersDoNotAcceptLengthsPercentagesOrNonfiniteValues() {
        for (value in listOf("5px", "50%", "NaN", "Infinity", "1e309", "1 2")) {
            assertFailsWith<IllegalArgumentException>(value) {
                parameters("<feDiffuseLighting surfaceScale='$value'><feDistantLight/></feDiffuseLighting>")
            }
            assertFailsWith<IllegalArgumentException>(value) {
                parameters("<feDiffuseLighting><fePointLight x='$value'/></feDiffuseLighting>")
            }
        }
    }

    @Test fun constantsAndExponentsAreValidatedIndependently() {
        for (xml in listOf("<feDiffuseLighting diffuseConstant='-1'/>", "<feSpecularLighting specularConstant='-1'/>",
            "<feSpecularLighting specularExponent='0'/>", "<feSpecularLighting specularExponent='129'/>",
            "<feDiffuseLighting><feSpotLight specularExponent='-1'/></feDiffuseLighting>",
            "<feDiffuseLighting kernelUnitLength='1 2 3'/>")) {
            assertFailsWith<IllegalArgumentException>(xml) { parameters(xml) }
        }
        assertEquals(-3.0, parameters("<feDiffuseLighting surfaceScale='-3'/>").surfaceScale)
        assertEquals(128.0, parameters("<feSpecularLighting specularExponent='128'/>").exponent)
        val wide = parameters("<feDiffuseLighting><feSpotLight specularExponent='256'/></feDiffuseLighting>")
        assertEquals(256.0, (wide.light as SvgLightingLight.Spot).exponent)
    }

    @Test fun animationsAndUnknownPrimitiveChildrenAreNotSilentlyAccepted() {
        for (body in listOf("<animate/>", "<fePointLight><animate/></fePointLight>", "<path/>",
            "<feDistantLight/><feSpotLight><set/></feSpotLight>")) {
            assertFailsWith<IllegalArgumentException>(body) { parameters("<feDiffuseLighting>$body</feDiffuseLighting>") }
        }
    }

    @Test fun nonSquareBoundingBoxResolvesLightAndKernelButNotSurfaceScale() {
        val p = parameters("""<feSpecularLighting surfaceScale='3' kernelUnitLength='.1 .2'>
            <feSpotLight x='.25' y='.5' z='.5' pointsAtX='.75' pointsAtY='.25' pointsAtZ='.1'/>
            </feSpecularLighting>""")
        val bounds = BoundingBox(20.0, 30.0, 80.0, 40.0)
        val actual = p.resolved(bounds, true)
        val light = actual.light as SvgLightingLight.Spot
        val unit = hypot(80.0, 40.0) / sqrt(2.0)
        assertEquals(40.0, light.x); assertEquals(50.0, light.y); assertEquals(.5 * unit, light.z)
        assertEquals(80.0, light.pointsAtX); assertEquals(40.0, light.pointsAtY); assertEquals(.1 * unit, light.pointsAtZ)
        assertEquals(8.0, actual.kernelX); assertEquals(8.0, actual.kernelY)
        assertEquals(3.0, actual.surfaceScale)
        assertSame(p, p.resolved(bounds, false))
    }

    @Test fun boundingBoxDoesNotChangeDistantAngles() {
        val p = parameters("<feDiffuseLighting><feDistantLight azimuth='35' elevation='42'/></feDiffuseLighting>")
        assertEquals(p.light, p.resolved(BoundingBox(-5.0, 7.0, 90.0, 10.0), true).light)
    }
}
