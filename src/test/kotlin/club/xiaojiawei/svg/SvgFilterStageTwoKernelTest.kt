package club.xiaojiawei.svg
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.random.Random
import kotlin.math.*
class SvgFilterStageTwoKernelTest {
 private fun element(xml:String)=SvgLoader.parse("<svg xmlns='http://www.w3.org/2000/svg'><defs>$xml</defs></svg>").root.children.single().children.single()
 private fun fn(attrs:String="")=SvgComponentTransferFunction.parse(element("<feFuncR $attrs/>"))
 private fun px(r:Double,g:Double,b:Double,a:Double=1.0,linear:Boolean=false)=SvgFilterPixels(1,1,linear,floatArrayOf((r*a).toFloat(),(g*a).toFloat(),(b*a).toFloat(),a.toFloat()))
 private fun check(p:SvgFilterPixels,vararg v:Double) { v.forEachIndexed { i,x->assertEquals(x,p.data[i].toDouble(),2e-6,"channel $i") } }
 @Test fun defaultsIdentity() { val p=px(.2,.4,.8,.5); assertContentEquals(p.data,p.componentTransfer(SvgFilterComponentTransfer.parse(element("<feComponentTransfer/>"))).data); assertEquals(.6,fn().apply(.6)) }
 @Test fun tableInterpolation() { val f=fn("type='table' tableValues='.2 .8 .4'"); assertEquals(.2,f.apply(0.0));assertEquals(.5,f.apply(.25),1e-12);assertEquals(.8,f.apply(.5));assertEquals(.4,f.apply(1.0)) }
 @Test fun emptyAndSingleTables() { for(t in listOf("table","discrete")){ assertEquals(.3,fn("type='$t'").apply(.3));for(v in listOf(0.0,.5,1.0))assertEquals(.6,fn("type='$t' tableValues='.6'").apply(v)) } }
 @Test fun discreteBoundaries() {val f=fn("type='discrete' tableValues='.1 .4 .8 1'");assertEquals(.1,f.apply(.24999));assertEquals(.4,f.apply(.25));assertEquals(1.0,f.apply(1.0))}
 @Test fun linearGamma() {for(t in listOf("linear","gamma"))assertEquals(.3,fn("type='$t'").apply(.3));assertEquals(.6,fn("type='linear' slope='2' intercept='-.2'").apply(.4),1e-12);assertEquals(.6,fn("type='gamma' amplitude='2' exponent='2' offset='.1'").apply(.5),1e-12)}
 @Test fun clampAfterInterpolation(){val f=fn("type='table' tableValues='-2 2'");assertEquals(0.0,f.apply(.5));assertEquals(.4,f.apply(.6),1e-12);assertEquals(1.0,f.apply(1.0))}
 @Test fun duplicateLastWins(){val f=SvgFilterComponentTransfer.parse(element("<feComponentTransfer><feFuncR type='linear' slope='0'/><feFuncR type='linear' slope='0' intercept='.8'/></feComponentTransfer>"));check(px(.1,.2,.3,.5).componentTransfer(f),.4,.1,.15,.5)}
 @Test fun unpremultiplyAndRepremultiply(){val f=SvgFilterComponentTransfer.parse(element("<feComponentTransfer><feFuncR type='linear' slope='2'/><feFuncA type='linear' slope='.5'/></feComponentTransfer>"));check(px(.2,.4,.6,.5).componentTransfer(f),.1,.1,.15,.25)}
 @Test fun transparentColorCreation(){val f=SvgFilterComponentTransfer.parse(element("<feComponentTransfer><feFuncR type='linear' intercept='.4'/><feFuncA type='linear' intercept='.5'/></feComponentTransfer>"));check(SvgFilterPixels(1,1,false,floatArrayOf(1f,1f,1f,0f)).componentTransfer(f),.2,0.0,0.0,.5)}
 @Test fun nonMutationAndColorSpace(){val p=px(.2,.4,.6,.5,true);val old=p.data.copyOf();assertTrue(p.componentTransfer(SvgFilterComponentTransfer.parse(element("<feComponentTransfer/>"))).linear);assertContentEquals(old,p.data)}
 @Test fun ignoreInapplicableParameters(){assertEquals(.3,fn("type='identity' slope='bad' tableValues='bad'").apply(.3));assertEquals(.3,fn("type='linear' tableValues='bad'").apply(.3))}
 @Test fun invalidParameters(){for(a in listOf("type='wrong'","type='linear' slope='NaN'","type='gamma' exponent='1 2'","type='table' tableValues='1 bad'"))assertFailsWith<IllegalArgumentException>{fn(a)};for(c in listOf("<rect/>","<feFuncR><animate/></feFuncR>"))assertFailsWith<IllegalArgumentException>{SvgFilterComponentTransfer.parse(element("<feComponentTransfer>$c</feComponentTransfer>"))}}
 @Test fun extremeValuesFinite(){for(a in listOf("type='linear' slope='1e308' intercept='-1e308'","type='table' tableValues='-1e308 1e308'","type='gamma' amplitude='0' exponent='-1e308' offset='.3'","type='gamma' amplitude='1e308' exponent='-1e308'"))for(v in listOf(0.0,.5,1.0))assertTrue(fn(a).apply(v).isFinite());assertEquals(.3,fn("type='gamma' amplitude='0' exponent='-2' offset='.3'").apply(0.0));assertTrue(fn("type='gamma' amplitude='1e-320' exponent='-1040'").apply(.5) in 1e-8..1e-6)}
 @Test fun sixteenModes(){assertEquals(16,SvgFilterColorBlend.BLEND_MODES.size)}
 @Test fun knownSeparableValues(){val vs=mapOf("overlay" to .32,"hard-light" to .68,"soft-light" to (.2+.6*((16*.2-12)*.2*.2+4*.2-.2)),"color-dodge" to 1.0,"color-burn" to 0.0,"difference" to .6,"exclusion" to .68);for((m,v)in vs)check(px(.8,.8,.8).blend(px(.2,.2,.2),m),v,v,v,1.0)}
 @Test fun dodgeBurnBranchPriority(){check(px(1.0,1.0,1.0).blend(px(0.0,0.0,0.0),"color-dodge"),0.0,0.0,0.0,1.0);check(px(0.0,0.0,0.0).blend(px(1.0,1.0,1.0),"color-burn"),1.0,1.0,1.0,1.0)}
 @Test fun softLightBranches(){check(px(.8,.2,.5).blend(px(.1,.4,.7),"soft-light"),.1+.6*((16*.1-12)*.1+4)*.1-.6*.1,.4-.6*.4*.6,.7,1.0)}
 @Test fun transparentIdentities(){val p=px(.2,.4,.8,.5);val c=px(.9,.2,.4,0.0);for(m in SvgFilterColorBlend.BLEND_MODES){check(c.blend(p,m),.1,.2,.4,.5);check(p.blend(c,m),.1,.2,.4,.5)}}
 @Test fun partialAlpha(){check(px(.8,.8,.8,.5).blend(px(.2,.2,.2,.25),"difference"),.4,.4,.4,.625)}
 @Test fun achromaticNonseparable(){for(m in listOf("hue","saturation","color","luminosity")){val p=px(.4,.4,.4).blend(px(.6,.6,.6),m);val e=if(m=="luminosity") .4 else .6;check(p,e,e,e,1.0)}}
 @Test fun nonseparableLuminosity(){for(m in listOf("hue","saturation","color","luminosity")){val p=px(.9,.2,.1).blend(px(.1,.8,.4),m);val l=.3*p.data[0]+.59*p.data[1]+.11*p.data[2];assertEquals(if(m=="luminosity") .3*.9+.59*.2+.11*.1 else .3*.1+.59*.8+.11*.4,l,2e-6)}}
 @Test fun backdropConverted(){val s=px(.6,.2,.4,.5,true);val b=px(.3,.7,.8,.7);for(m in SvgFilterColorBlend.BLEND_MODES)assertContentEquals(s.blend(b.converted(true),m).data,s.blend(b,m).data)}
 @Test fun randomPremultipliedBounds(){val r=Random(4);repeat(500){val s=px(r.nextDouble(),r.nextDouble(),r.nextDouble(),r.nextDouble());val b=px(r.nextDouble(),r.nextDouble(),r.nextDouble(),r.nextDouble());val old=s.data.copyOf();for(m in SvgFilterColorBlend.BLEND_MODES){val p=s.blend(b,m);for(c in 0..2)assertTrue(p.data[c].isFinite()&&p.data[c]>=0&&p.data[c]<=p.data[3]);assertEquals((s.data[3]+b.data[3]-s.data[3]*b.data[3]).toDouble(),p.data[3].toDouble(),2e-6)};assertContentEquals(old,s.data)}}
}
