package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgFilterAnimationTest {
    private fun xml(p: String, extra: String = "", filter: String = "") = """<svg width="80" height="80" viewBox="0 0 80 80"><defs><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="80" height="80" color-interpolation-filters="sRGB" $filter>$p</filter></defs><rect x="20" y="20" width="20" height="20" fill="red" filter="url(#f)"/>$extra</svg>"""
    private fun view(source: String, strict: Boolean = true) = SvgView.parse(source, SvgOptions(strict = strict)).apply {
        animationAutoPlay = false; isManaged = false; Scene(Group(this)); resize(80.0,80.0); applyCss(); layout()
    }
    private fun image(v: SvgView, seconds: Double) = run { v.seekAnimations(Duration.seconds(seconds)); v.applyCss(); v.layout(); v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null) }
    private fun pixel(v: SvgView, seconds: Double, x: Int=30, y:Int=30) = image(v,seconds).pixelReader.getColor(x,y)
    private fun offset(anim:String, dx:String="0")="<feOffset dx='$dx'>$anim</feOffset>"
    private fun animate(name:String, values:String, more:String="")="<animate attributeName='$name' values='$values' dur='2s' fill='freeze' $more/>"

    @Test fun offsetUsesSoftwareChainAndReverseSeekingRestoresPixels()=FxTest.run {
        val v=view(xml(offset(animate("dx","0;20"))))
        assertEquals(1,v.lookupAll(".svg-filter-output").size)
        assertEquals(Color.RED,pixel(v,0.0,25,25));assertEquals(0.0,pixel(v,2.0,25,25).opacity)
        assertEquals(Color.RED,pixel(v,2.0,45,25));assertEquals(Color.RED,pixel(v,0.0,25,25))
        assertTrue(v.diagnostics.isEmpty())
    }
    @Test fun blurVectorAndMorphologyVectorReallyAffectRaster()=FxTest.run {
        val blur=view(xml("<feGaussianBlur>${animate("stdDeviation","0;4 0")}</feGaussianBlur>"))
        assertEquals(0.0,pixel(blur,0.0,17,30).opacity)
        assertTrue(pixel(blur,2.0,17,30).opacity>.05)
        assertEquals(0.0,pixel(blur,2.0,30,15).opacity)
        val morph=view(xml("<feMorphology operator='dilate'>${animate("radius","0;5 1")}</feMorphology>"))
        assertEquals(Color.RED,pixel(morph,2.0,16,30));assertEquals(0.0,pixel(morph,2.0,30,16).opacity)
    }
    @Test fun colourAndOpacityInterpolateOnFlood()=FxTest.run {
        val v=view(xml("<feFlood flood-color='blue'>${animate("flood-color","red;blue")}${animate("flood-opacity","0;1")}</feFlood>"))
        val c=pixel(v,1.0)
        assertEquals(.5,c.red,.02);assertEquals(.5,c.blue,.02);assertEquals(.5,c.opacity,.01)
        assertEquals(Color.BLUE,pixel(v,2.0));assertEquals(0.0,pixel(v,0.0).opacity)
    }
    @Test fun hrefTargetsAndSharedDocumentClocksRemainIndependent()=FxTest.run {
        val source=xml("<feOffset id='moving'/>","<animate href='#moving' attributeName='dx' from='0' to='20' dur='2s' fill='freeze'/>")
        val doc=SvgLoader.parse(source,SvgOptions(strict=true))
        fun shared()=SvgView(doc).apply{animationAutoPlay=false;isManaged=false;Scene(Group(this));resize(80.0,80.0);layout()}
        val a=shared();val b=shared()
        assertEquals(0.0,pixel(a,2.0,25,25).opacity);assertEquals(Color.RED,pixel(b,0.0,25,25))
        assertNull(doc.elementsById.getValue("moving")["dx"])
        assertEquals(Color.RED,pixel(a,0.0,25,25))
    }
    @Test fun repeatFreezeRemoveAndPauseDoNotLeakOldFrames()=FxTest.run {
        val frozen=view(xml(offset("<animate attributeName='dx' values='0;20' dur='1s' repeatCount='2' fill='freeze'/>")))
        assertEquals(Color.RED,pixel(frozen,1.0,25,25));assertEquals(0.0,pixel(frozen,2.0,25,25).opacity)
        frozen.pauseAnimations();val before=pixel(frozen,.5,35,25);frozen.layout();assertEquals(before,frozen.snapshot(null,null).pixelReader.getColor(35,25))
        val removed=view(xml(offset("<animate attributeName='dx' values='0;20' dur='1s'/>")))
        assertEquals(Color.RED,pixel(removed,2.0,25,25))
    }
    @Test fun toAndByUseStaticBaseAndVectorExpansion()=FxTest.run {
        val v=view(xml(offset("<animate attributeName='dx' to='20' dur='2s' fill='freeze'/>","10")))
        assertEquals(Color.RED,pixel(v,0.0,35,25));assertEquals(0.0,pixel(v,0.0,25,25).opacity)
        val by=view(xml(offset("<animate attributeName='dx' by='10' dur='2s' fill='freeze'/>","10")))
        assertEquals(Color.RED,pixel(by,2.0,45,25))
    }
    @Test fun diffuseSpecularAndDisplacementParametersAreAcceptedAndVisible()=FxTest.run {
        val diffuse=view(xml("<feDiffuseLighting lighting-color='white'><feDistantLight elevation='90'/>${animate("diffuseConstant","0;1")}${animate("surfaceScale","1;2")}${animate("lighting-color","red;blue")}</feDiffuseLighting>"))
        assertEquals(0.0,pixel(diffuse,0.0).red,.01);assertEquals(1.0,pixel(diffuse,2.0).blue,.01)
        val spec=view(xml("<feSpecularLighting><feDistantLight elevation='90'/>${animate("specularConstant","0;1")}${animate("specularExponent","1;64")}</feSpecularLighting>"))
        assertEquals(0.0,pixel(spec,0.0).opacity);assertEquals(1.0,pixel(spec,2.0).opacity,.01)
        val displacement=view(xml("<feFlood flood-color='white' result='m'/><feDisplacementMap in='SourceGraphic' in2='m'>${animate("scale","0;20")}</feDisplacementMap>"))
        assertTrue(pixel(displacement,0.0,35,35).opacity>.9);assertEquals(0.0,pixel(displacement,2.0,35,35).opacity)
    }
    @Test fun structuralAndInvalidNumericAnimationsDiagnoseInsteadOfMutatingDocument()=FxTest.run {
        for(p in listOf("<feOffset>${animate("in","SourceGraphic;SourceAlpha")}</feOffset>","<feOffset>${animate("dx","0;NaN")}</feOffset>","<feGaussianBlur>${animate("stdDeviation","0;1 2 3")}</feGaussianBlur>","<feSpecularLighting><feDistantLight/>${animate("specularExponent","1;200")}</feSpecularLighting>"))
            assertFailsWith<SvgException>(p) { view(xml(p)) }
    }
    @Test fun laterWorkBudgetFailureCanSeekBackToValidFrame()=FxTest.run {
        val v=view(xml("<feGaussianBlur>${animate("stdDeviation","0;1000")}</feGaussianBlur>"),strict=false)
        assertEquals(Color.RED,pixel(v,0.0));assertEquals(0.0,pixel(v,2.0).opacity)
        assertTrue(v.diagnostics.any { "limit" in it.message || "budget" in it.message })
        assertEquals(Color.RED,pixel(v,0.0))
    }
    @Test fun deterministicAnimatedSampleScalesAndExportsActualPixels()=FxTest.run {
        val source=checkNotNull(javaClass.getResource("/svg/animated-filter-parameters.svg")).readText()
        val v=view(source)
        for(scale in listOf(1.0,2.0,1.0)) {
            v.resize(600*scale,200*scale);v.applyCss();v.layout()
            for(t in listOf(0.0,1.0,2.0,1.0)) {
                val result=image(v,t)
                assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString())
                if(scale==1.0 && t==1.0) {
                    val b=java.awt.image.BufferedImage(result.width.toInt(),result.height.toInt(),java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for(y in 0 until b.height) for(x in 0 until b.width) b.setRGB(x,y,result.pixelReader.getArgb(x,y))
                    assertTrue(javax.imageio.ImageIO.write(b,"png",java.nio.file.Path.of("target","stage5-filter-animation.png").toFile()))
                }
            }
        }
    }

    @Test fun rejectedAnimatedFilterDoesNotLeaveAnActiveClockBinding()=FxTest.run {
        val v=view(xml("<feGaussianBlur edgeMode='duplicate'>${animate("stdDeviation","0;2")}</feGaussianBlur>"),strict=false)
        assertTrue(v.diagnostics.isNotEmpty())
        assertEquals(Duration.ZERO,v.animationDuration)
        assertTrue(v.lookupAll(".svg-filter-output").isEmpty())
    }
    @Test fun motionOnFilterIsExplicitlyRejected()=FxTest.run {
        assertFailsWith<SvgException> { view(xml("<feOffset><animateMotion path='M0 0 L20 0' dur='2s'/></feOffset>")) }
    }
    @Test fun animatedPrimitiveValidationUsesItsOriginalInheritedStyle()=FxTest.run {
        assertFailsWith<SvgException> { view(xml("<feOffset>${animate("dx","0;2")}</feOffset>",filter="style='color-interpolation-filters: invalid'")) }
    }

    @Test fun templateImageAndAnimatedOffsetPreserveResourceAndStyleIdentity()=FxTest.run {
        val source="""<svg width="80" height="80" viewBox="0 0 80 80"><defs>
          <rect id="tile" x="20" y="20" width="20" height="20" fill="blue"/>
          <filter id="base" filterUnits="userSpaceOnUse" x="0" y="0" width="80" height="80" color-interpolation-filters="sRGB">
            <feImage href="#tile"/><feOffset><animate attributeName="dx" values="0;20" dur="2s" fill="freeze"/></feOffset>
          </filter><filter id="f" href="#base"/></defs>
          <rect x="20" y="20" width="20" height="20" fill="red" filter="url(#f)"/></svg>"""
        val v=view(source)
        assertEquals(Color.BLUE,pixel(v,0.0,25,25))
        assertEquals(0.0,pixel(v,2.0,25,25).opacity)
        assertEquals(Color.BLUE,pixel(v,2.0,45,25))
        assertEquals(Color.BLUE,pixel(v,0.0,25,25))
        assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString())
    }

    @Test fun failedImageFilterRollsBackRadialPaintAndAnimationState()=FxTest.run {
        val xml="""<svg width="40" height="20"><defs>
          <radialGradient id="g" fr=".1"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient>
          <rect id="source" width="20" height="20" fill="url(#g)"/>
          <filter id="bad"><feImage href="#source"/><feOffset><animate attributeName="dx" values="0;1" dur="1s"/></feOffset><feUnsupported/></filter>
          </defs><rect width="20" height="20" filter="url(#bad)" fill="green"/>
          <rect id="valid" x="20" width="20" height="20" fill="url(#g)"/></svg>"""
        val renderer=FxSvgRenderer(SvgLoader.parse(xml))
        fun retained(name:String)=FxSvgRenderer::class.java.getDeclaredField(name).apply {isAccessible=true}.get(renderer) as List<*>
        assertEquals(1,retained("radialPaints").size)
        assertTrue(retained("filterChains").isEmpty())
        assertFalse(renderer.hasAnimations)
        Scene(renderer.root);renderer.layout(40.0,20.0,true)
        val image=renderer.root.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
        assertTrue(image.pixelReader.getColor(30,10).red>.7)
        assertTrue(renderer.diagnostics.any {"Unsupported filter primitive" in it.message})
    }

}
