package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.Base64
import kotlin.test.*

class SvgFilterImageTileTest {
 private fun doc(defs:String,content:String="<rect x='20' y='20' width='60' height='60' fill='red' filter='url(#f)'/>")="<svg width='100' height='100' viewBox='0 0 100 100'><defs>$defs</defs>$content</svg>"
 private fun filter(body:String,attrs:String="")="<filter id='f' filterUnits='userSpaceOnUse' x='0' y='0' width='100' height='100' $attrs>$body</filter>"
 private fun view(defs:String,options:SvgOptions=SvgOptions(strict=true))=SvgView.parse(doc(defs),options)
 private fun image(v:SvgView,scale:Double=1.0):WritableImage {v.isManaged=false;if(v.scene==null)Scene(Group(v));v.resize(100*scale,100*scale);v.applyCss();v.layout();return v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null)}
 private fun color(p:WritableImage,x:Int,y:Int,c:Color){val a=p.pixelReader.getColor(x,y);assertEquals(c.red,a.red,.025);assertEquals(c.green,a.green,.025);assertEquals(c.blue,a.blue,.025);assertEquals(c.opacity,a.opacity,.025)}
 private fun png():ByteArray {val b=BufferedImage(4,2,BufferedImage.TYPE_INT_ARGB);for(y in 0..1)for(x in 0..3)b.setRGB(x,y,if(x<2)0xffff0000.toInt() else 0xff0000ff.toInt());return ByteArrayOutputStream().also{ImageIO.write(b,"png",it)}.toByteArray()}
 private fun data()="data:image/png;base64,"+Base64.getEncoder().encodeToString(png())
 @Test fun integerTileRepeatsRegionIncludingTransparentPixels(){val p=SvgFilterPixels(6,2,false);p.data[4]=1f;p.data[7]=1f;val o=p.tile(1.0,0.0,2.0,2.0);for(x in 0..5)assertEquals(if(x%2==1)1f else 0f,o.data[x*4+3]);assertEquals(0f,o.data[3])}
 @Test fun nonzeroTileOriginAndTwoAxes(){val p=SvgFilterPixels(8,8,false);val i=(3*8+2)*4;p.data[i+1]=1f;p.data[i+3]=1f;val o=p.tile(2.0,3.0,2.0,2.0);for(y in 0..7)for(x in 0..7)assertEquals(if(x%2==0&&y%2==1)1f else 0f,o.data[(y*8+x)*4+3])}
 @Test fun fractionalPeriodsProduceFiniteBoundedPixels(){val p=SvgFilterPixels(8,8,true);for(i in p.data.indices step 4){p.data[i]=.2f;p.data[i+3]=.5f};val o=p.tile(.3,.6,2.5,3.2);assertTrue(o.linear);for(i in o.data.indices step 4){assertEquals(.2f,o.data[i],1e-6f);assertEquals(.5f,o.data[i+3],1e-6f)}}
 @Test fun emptyAndInvalidTiles(){val p=SvgFilterPixels(3,3,false);p.data.fill(1f);assertTrue(p.tile(0.0,0.0,0.0,2.0).data.all{it==0f});assertFailsWith<IllegalArgumentException>{p.tile(Double.NaN,0.0,1.0,1.0)}}
 @Test fun tileDoesNotMutateInput(){val p=SvgFilterPixels(4,4,false);p.data[3]=1f;val old=p.data.copyOf();p.tile(0.0,0.0,2.0,2.0);assertContentEquals(old,p.data)}
 @Test fun tileDefaultExpandsToWholeFilterRegion()=FxTest.run {val p=image(view(filter("<feFlood flood-color='lime' x='20' y='20' width='10' height='10'/><feTile/>")));color(p,5,5,Color.LIME);color(p,95,95,Color.LIME)}
 @Test fun tileExplicitRegionClipsRepeatedPattern()=FxTest.run {val p=image(view(filter("<feFlood flood-color='lime' x='20' y='20' width='10' height='10'/><feTile x='30' y='30' width='20' height='20'/>")));color(p,35,35,Color.LIME);color(p,5,5,Color.TRANSPARENT);color(p,55,55,Color.TRANSPARENT)}
 @Test fun tileSourceGraphicAndNamedResult()=FxTest.run {val p=image(view(filter("<feFlood flood-color='blue' x='0' y='0' width='20' height='20' result='tile'/><feFlood flood-color='red'/><feTile in='tile'/>")));color(p,50,50,Color.BLUE)}
 @Test fun embeddedImageMeetAndNone()=FxTest.run {val href=data();val meet=image(view(filter("<feImage href='$href' x='10' y='10' width='80' height='80'/>")));color(meet,20,50,Color.RED);color(meet,80,50,Color.BLUE);color(meet,20,15,Color.TRANSPARENT);val none=image(view(filter("<feImage href='$href' x='10' y='10' width='80' height='80' preserveAspectRatio='none'/>")));color(none,20,15,Color.RED)}
 @Test fun embeddedImageSliceIsClipped()=FxTest.run {val p=image(view(filter("<feImage href='${data()}' x='20' y='20' width='40' height='60' preserveAspectRatio='xMidYMid slice'/>")));color(p,25,30,Color.RED);color(p,55,30,Color.BLUE);color(p,15,30,Color.TRANSPARENT)}
 @Test fun internalGroupUsesItsGeometryNotBoundingBoxFit()=FxTest.run {val defs="<g id='g'><rect x='10' y='20' width='20' height='30' fill='blue'/></g>"+filter("<feImage href='#g'/>");val p=image(view(defs));color(p,20,30,Color.BLUE);color(p,60,60,Color.TRANSPARENT)}
 @Test fun localImageCoordinatesAndUseReferences()=FxTest.run {val defs="<g id='g'><rect width='10' height='10' fill='lime'/></g><use id='u' href='#g' x='5' y='5'/>"+filter("<feImage href='#u' x='20' y='30' width='40' height='40'/>");val p=image(view(defs));color(p,28,38,Color.LIME);color(p,10,10,Color.TRANSPARENT)}
 @Test fun localObjectBoundingBoxCoordinates()=FxTest.run {val defs="<rect id='r' x='.25' y='.25' width='.5' height='.5' fill='blue'/>"+filter("<feImage href='#r' x='0' y='0' width='1' height='1'/>","primitiveUnits='objectBoundingBox'");val p=image(view(defs));color(p,50,50,Color.BLUE);color(p,25,25,Color.TRANSPARENT)}
 @Test fun missingImageIsTransparentAndDiagnosed()=FxTest.run {val v=view(filter("<feImage href='#absent'/>"),SvgOptions());color(image(v),50,50,Color.TRANSPARENT);assertTrue(v.diagnostics.any{it.message.contains("Missing feImage")})}
 @Test fun externalImageNeedsExplicitResolver()=FxTest.run {val denied=view(filter("<feImage href='https://example.invalid/a.png'/>"),SvgOptions());color(image(denied),50,50,Color.TRANSPARENT);assertTrue(denied.diagnostics.isNotEmpty());var count=0;val allowed=view(filter("<feImage href='https://example.invalid/a.png'/><feImage href='https://example.invalid/a.png'/>"),SvgOptions(strict=true,resourceResolver=SvgResourceResolver{count++;ByteArrayInputStream(png())}));image(allowed);assertEquals(1,count);image(allowed,2.0);assertEquals(1,count)}
 @Test fun imageDecodeAndByteBudgetsRemainEnforced(){assertFailsWith<SvgException>{SvgLoader.parse(doc(filter("<feImage href='${data()}'/>")),SvgOptions(strict=true,maxImagePixels=1))};assertFailsWith<SvgException>{SvgLoader.parse(doc(filter("<feImage href='${data()}'/>")),SvgOptions(strict=true,maxResourceBytes=8))}}
 @Test fun imageDoesNotTreatLocalReferenceAsExternalRequest()=FxTest.run {var count=0;val v=view("<rect id='g' width='20' height='20' fill='blue'/>"+filter("<feImage href='#g'/>"),SvgOptions(strict=true,resourceResolver=SvgResourceResolver{count++;null}));image(v);assertEquals(0,count)}
 @Test fun filterTemplateInheritsAttrsAndOriginalPrimitiveIdentity(){val d=SvgLoader.parse(doc("<filter id='base' x='-25%' primitiveUnits='objectBoundingBox'><feImage href='${data()}'/></filter><filter id='f' href='#base' width='200%'/ >".replace("/ >","/>")));val r=svgResolvedFilter("f",d);assertEquals("-25%",r["x"]);assertEquals("200%",r["width"]);assertEquals("objectBoundingBox",r["primitiveUnits"]);assertSame(d.elementsById.getValue("base").children.single(),r.children.single());assertTrue(d.resources.images.containsKey(r.children.single()))}
 @Test fun templatePrimitivesOverrideRatherThanAppend()=FxTest.run {val defs="<filter id='base'><feFlood flood-color='red'/></filter><filter id='f' href='#base'><feFlood flood-color='blue'/></filter>";color(image(view(defs)),50,50,Color.BLUE)}
 @Test fun templateRetainsSourceStyles()=FxTest.run {val defs="<style>#base feFlood { flood-color: blue; }</style><filter id='base'><feFlood/></filter><filter id='f' href='#base'/>";color(image(view(defs)),50,50,Color.BLUE)}
 @Test fun templateCycleAndNonLocalTemplateRejected()=FxTest.run {for(defs in listOf("<filter id='f' href='#g'/><filter id='g' href='#f'/>","<filter id='f' href='other.svg#f'/>","<filter id='f' filterRes='10'><feFlood/></filter>")){assertFailsWith<SvgException>{image(view(defs))}}}
 @Test fun imageFilterCycleIsBoundedAndStrictlyRejected()=FxTest.run {val defs=filter("<feImage href='#r'/>");val xml=doc(defs,"<rect id='r' width='50' height='50' filter='url(#f)'/>");assertFailsWith<SvgException>{image(SvgView.parse(xml,SvgOptions(strict=true)))}}
 @Test fun imageExpandedElementBudgetEnforced()=FxTest.run {val defs="<g id='g'>"+(1..10).joinToString(""){"<rect width='10' height='10'/>"}+"</g>"+filter((1..5).joinToString(""){"<feImage href='#g'/>"});assertFailsWith<SvgException>{image(view(defs,SvgOptions(strict=true,maxElements=35)))}}
 @Test fun repeatedScaleStaysStable()=FxTest.run {val v=view(filter("<feImage href='${data()}'/>"));for(s in listOf(1.0,1.5,2.0,1.0)){val p=image(v,s);color(p,(20*s).toInt(),(50*s).toInt(),Color.RED);assertTrue(v.diagnostics.isEmpty())}}
 @Test fun sampleRendersAndExportsScreenshot()=FxTest.run {
  val v=SvgView.load(checkNotNull(javaClass.getResource("/svg/filter-image-tile.svg")),SvgOptions(strict=true));v.isManaged=false;Scene(Group(v))
  for(scale in listOf(1.0,1.5,2.0,1.0)) {
   v.resize(600*scale,220*scale);v.applyCss();v.layout();val p=v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null)
   assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString());assertEquals(3,v.lookupAll(".svg-filter-output").size)
   if(scale==1.0){val b=BufferedImage(p.width.toInt(),p.height.toInt(),BufferedImage.TYPE_INT_ARGB);for(y in 0 until b.height)for(x in 0 until b.width)b.setRGB(x,y,p.pixelReader.getArgb(x,y));assertTrue(ImageIO.write(b,"png",java.nio.file.Path.of("target","filter-image-tile.png").toFile()))}
  }
 }
 @Test fun imagePixelBudgetClearsAndReplacementRecovers()=FxTest.run {
  val v=view(filter("<feImage href='${data()}'/>"),SvgOptions(maxFilterPixels=100))
  assertEquals(0.0,image(v).pixelReader.getColor(50,50).opacity);assertTrue(v.diagnostics.any{"maxFilterPixels" in it.message})
  v.document=SvgLoader.parse(doc(filter("<feImage href='${data()}'/>")),SvgOptions(strict=true));color(image(v),20,50,Color.RED);assertTrue(v.diagnostics.isEmpty())
 }

 @Test fun localImageWithoutPositionKeepsNaturalUserCoordinatesDespiteFilterOrigin()=FxTest.run {
  val defs="<rect id='r' x='20' y='20' width='8' height='8' fill='blue'/><filter id='f' filterUnits='userSpaceOnUse' x='10' y='10' width='80' height='80'><feImage href='#r'/></filter>"
  val p=image(view(defs));color(p,24,24,Color.BLUE);color(p,34,34,Color.TRANSPARENT)
 }
 @Test fun inheritedImageUsesOriginalPreparedResource()=FxTest.run {
  val defs="<filter id='base' filterUnits='userSpaceOnUse' x='0' y='0' width='100' height='100'><feImage href='${data()}'/></filter><filter id='f' href='#base'/>"
  val v=view(defs);color(image(v),20,50,Color.RED);assertTrue(v.diagnostics.isEmpty())
 }
 @Test fun indirectImageFilterCycleIsRejected()=FxTest.run {
  val defs="<rect id='b' width='30' height='30' filter='url(#g)'/><filter id='g'><feImage href='#a'/></filter><filter id='f'><feImage href='#b'/></filter>"
  val xml=doc(defs,"<rect id='a' width='50' height='50' filter='url(#f)'/>")
  assertFailsWith<SvgException>{image(SvgView.parse(xml,SvgOptions(strict=true)))}
 }

 @Test fun fractionalTilePeriodRetainsSubpixelPhase() {
  val p=SvgFilterPixels(6,1,false)
  for(x in 0..5){p.data[x*4]=x*.2f;p.data[x*4+3]=1f}
  val out=p.tile(.5,0.0,2.5,1.0)
  val expected=floatArrayOf(0f,.2f,.4f,.1f,.3f,0f)
  for(x in 0..5)assertEquals(expected[x],out.data[x*4],1e-6f)
 }

}
