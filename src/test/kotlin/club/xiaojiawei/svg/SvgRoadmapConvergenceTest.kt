package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** Final-roadmap convergence: unchanged third-party corpus and deterministic lifecycle properties.
 * Heap observations are evidence, not assertions that garbage collection or leaks are absent. */
class SvgRoadmapConvergenceTest {
    private val icons = listOf("activity", "airplay", "atom", "cpu")
    private val hashes = listOf("629b81c9fd020f3afec659daa5c9c7e5a1d07445", "03ad3669d3a2022e9cbfef2f1dbdcdb982a67e44", "9579a0eaafa396eb6b9702ee507726f7768bce4a", "0307c0e4ad2406f68158978dd183fd38d7b80dd4")
    private fun source(name: String) = checkNotNull(javaClass.getResource("/svg/real-corpus/$name.svg")).readText()
    private fun attach(xml: String, size: Double = 48.0) = SvgView.parse(xml, SvgOptions(strict = true)).apply {
        isManaged = false; animationAutoPlay = false; Scene(Group(this)); resize(size, size); applyCss(); layout()
    }
    private fun snapshot(v: SvgView): WritableImage = v.snapshot(SnapshotParameters().apply {fill = Color.TRANSPARENT}, null)
    private fun difference(a: WritableImage, b: WritableImage): Double {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var sum = 0.0
        for (y in 0 until a.height.toInt()) for (x in 0 until a.width.toInt()) {
            val p = a.pixelReader.getColor(x,y); val q = b.pixelReader.getColor(x,y)
            sum += abs(p.red*p.opacity-q.red*q.opacity)+abs(p.green*p.opacity-q.green*q.opacity)+abs(p.blue*p.opacity-q.blue*q.opacity)+abs(p.opacity-q.opacity)
        }
        return sum/(a.width*a.height*4)
    }
    @Test fun upstreamCorpusBytesAndLicenseArePreserved() {
        for ((i,name) in icons.withIndex()) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/svg/real-corpus/$name.svg")).use {it.readBytes()}
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray());digest.update(bytes)
            assertEquals(hashes[i],digest.digest().joinToString(""){"%02x".format(it)},name)
        }
        val license=checkNotNull(javaClass.getResource("/svg/real-corpus/LICENSE")).readText()
        assertContains(license,"ISC License");assertContains(license,"The MIT License")
    }
    @Test fun realCorpusHasStablePixelsAcrossCachingAndRepeatScale()=FxTest.run {
        val rows=mutableListOf("name,size,cache_mae,repeat_mae,nontransparent_pixels")
        for(name in icons) {
            val view=attach(source(name))
            try {
                for(size in listOf(24.0,48.0,96.0,48.0)) {
                    view.resize(size,size);view.rotate=1.0;view.rotate=0.0;view.layout()
                    val cached=snapshot(view)
                    view.renderCacheEnabled=false;view.layout();val plain=snapshot(view)
                    view.renderCacheEnabled=true;view.layout();val repeat=snapshot(view)
                    val d=difference(cached,plain);val r=difference(cached,repeat)
                    assertTrue(d<=.025,"$name size=$size cache MAE=$d")
                    assertTrue(r<=.025,"$name size=$size repeat MAE=$r")
                    var nonzero=0
                    for(y in 0 until plain.height.toInt())for(x in 0 until plain.width.toInt())if(plain.pixelReader.getArgb(x,y).ushr(24)>0)nonzero++
                    assertTrue(nonzero>8,"$name rendered blank");assertTrue(view.diagnostics.isEmpty(),view.diagnostics.toString())
                    if(size==96.0) {
                        val png=BufferedImage(plain.width.toInt(),plain.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                        for(y in 0 until png.height)for(x in 0 until png.width)png.setRGB(x,y,plain.pixelReader.getArgb(x,y))
                        assertTrue(ImageIO.write(png,"png",Path.of("target","stage6-corpus-$name.png").toFile()))
                    }
                    rows+="$name,$size,$d,$r,$nonzero"
                }
            } finally {view.source=null}
        }
        Files.write(Path.of("target","stage6-real-corpus-pixels.csv"),rows)
    }
    @Test fun realCorpusContactSheetUsesActualRenderer()=FxTest.run {
        val root=Group();Scene(root)
        for((i,name) in icons.withIndex()) {
            val view=SvgView.parse(source(name),SvgOptions(strict=true));view.isManaged=false;view.resize(96.0,96.0);view.layoutX=16.0+i*128;view.layoutY=16.0;root.children+=view
            val label=javafx.scene.text.Text(16.0+i*128,132.0,name);label.font=javafx.scene.text.Font.font(16.0);root.children+=label
        }
        root.applyCss();root.layout()
        val image=root.snapshot(SnapshotParameters().apply{fill=Color.web("#f5f7fa")},null)
        val output=BufferedImage(image.width.toInt(),image.height.toInt(),BufferedImage.TYPE_INT_ARGB)
        for(y in 0 until output.height)for(x in 0 until output.width)output.setRGB(x,y,image.pixelReader.getArgb(x,y))
        assertTrue(ImageIO.write(output,"png",Path.of("target","stage6-real-corpus.png").toFile()))
    }
    @Test fun repeatedDocumentEditingReparentingAndResizeReleasesOldCacheState() {
        lateinit var view:SvgView;lateinit var host:Group;lateinit var alternate:Group
        val rows=mutableListOf("edit,used_heap_bytes,node_count,diagnostics")
        FxTest.run {view=attach(source("activity"),48.0);host=view.parent as Group;alternate=Group();(view.scene.root as Group).children+=alternate}
        for(batch in 0 until 12) FxTest.run {
            repeat(20) {offset->
                val i=batch*20+offset
                val old=view.lookupAll("*").filter{it!==view&&it.isCache}
                view.document=SvgLoader.parse(source(icons[i%icons.size]).replace("currentColor",if(i%2==0)"#235ca8" else "#a84323"),SvgOptions(strict=true))
                assertTrue(old.none{it.isCache},"Retired document retained caches at edit $i")
                view.resize(if(i%3==0)72.0 else 48.0,if(i%3==0)72.0 else 48.0)
                view.rotate=1.0;view.rotate=0.0;view.applyCss();view.layout()
                if(i%4==0) {
                    (view.parent as Group).children.remove(view)
                    assertTrue(view.lookupAll("*").none{it!==view&&it.isCache},"Detached view retained cache flags")
                    (if(i%8==0)alternate else host).children+=view;view.layout()
                }
                snapshot(view)
                assertTrue(view.diagnostics.isEmpty(),view.diagnostics.toString())
                assertTrue(view.lookupAll("*").size<100,"Unexpected scene growth")
            }
            val rt=Runtime.getRuntime();rows+="${(batch+1)*20},${rt.totalMemory()-rt.freeMemory()},${view.lookupAll("*").size},${view.diagnostics.size}"
        }
        FxTest.run {view.source=null;(view.parent as Group).children.remove(view)}
        Files.write(Path.of("target","stage6-edit-lifecycle.csv"),rows)
    }
    @Test fun maliciousDocumentsRemainBoundedBeforeAndAfterValidLoads() {
        val hostile=listOf(
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><svg>&x;</svg>",
            "<svg>"+"<g>".repeat(40)+"</g>".repeat(40)+"</svg>",
            "<svg>"+"<rect/>".repeat(40)+"</svg>"
        )
        repeat(3) {
            for(xml in hostile)assertFailsWith<SvgException>{SvgLoader.parse(xml,SvgOptions(strict=true,maxDepth=16,maxElements=24))}
            assertEquals("svg",SvgLoader.parse(source("cpu"),SvgOptions(strict=true)).root.name)
        }
    }
    @Test fun newComposedFilterResourceCyclesDoNotEscapeGuards()=FxTest.run {
        val xml="""<svg width='32' height='32'><defs><filter id='a'><feImage href='#one'/></filter><filter id='b'><feImage href='#two'/></filter><g id='one' filter='url(#b)'><rect width='16' height='16'/></g><g id='two' filter='url(#a)'><rect width='16' height='16'/></g></defs><rect width='32' height='32' filter='url(#a)'/></svg>"""
        assertFailsWith<SvgException>{attach(xml,32.0).also{snapshot(it)}}
        val valid=attach(source("atom"),32.0);snapshot(valid);assertTrue(valid.diagnostics.isEmpty());valid.source=null
    }
    @Test fun oversizedNewFilterWorkIsRejectedAndOrdinaryLoadStillWorks()=FxTest.run {
        val xml="""<svg width='64' height='64' viewBox='0 0 64 64'><defs><filter id='f'><feConvolveMatrix order='3' kernelMatrix='1 1 1 1 1 1 1 1 1' kernelUnitLength='.000001'/></filter></defs><rect width='64' height='64' filter='url(#f)'/></svg>"""
        assertFailsWith<SvgException>{attach(xml,64.0).also{snapshot(it)}}
        val valid=attach(source("airplay"));snapshot(valid);assertTrue(valid.diagnostics.isEmpty());valid.source=null
    }
}
