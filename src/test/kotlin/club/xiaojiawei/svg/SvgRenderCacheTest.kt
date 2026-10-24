package club.xiaojiawei.svg

import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgRenderCacheTest {
    private val body = """<defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>
        <g id="spin"><rect id="p" x="8" y="8" width="48" height="48" rx="8" fill="url(#g)"/></g>"""
    private fun view(content: String = body, options: SvgOptions = SvgOptions(strict=true)) =
        SvgView.parse("""<svg viewBox="0 0 64 64">$content</svg>""",options).apply {
            isManaged=false; animationAutoPlay=false; Scene(Group(this)); resize(32.0,32.0); applyCss(); layout()
        }
    private fun caches(v: SvgView) = v.lookupAll("*").filter { it !== v && it.isCache }
    private fun pixels(v: SvgView): javafx.scene.image.WritableImage = v.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null)
    private fun difference(a: javafx.scene.image.Image,b: javafx.scene.image.Image): Double {
        assertEquals(a.width,b.width); assertEquals(a.height,b.height)
        var delta=0.0
        for(y in 0 until a.height.toInt()) for(x in 0 until a.width.toInt()) {
            val p=a.pixelReader.getColor(x,y); val q=b.pixelReader.getColor(x,y)
            delta+=kotlin.math.abs(p.red*p.opacity-q.red*q.opacity)+kotlin.math.abs(p.blue*p.opacity-q.blue*q.opacity)+kotlin.math.abs(p.opacity-q.opacity)
        }
        return delta/(a.width*a.height*3)
    }
    @Test fun boundedCacheCanBeDisabledAndBoundThroughPublicProperty() = FxTest.run {
        val v=view()
        assertFalse(v.isCache)
        assertTrue(caches(v).isNotEmpty())
        val enabled=SimpleBooleanProperty(false)
        v.renderCacheEnabledProperty().bind(enabled)
        assertTrue(caches(v).isEmpty())
        enabled.set(true)
        assertTrue(caches(v).isNotEmpty())
        v.scaleX=100.0; v.scaleY=100.0
        assertTrue(caches(v).isEmpty())
        v.scaleX=1.0; v.scaleY=1.0
        assertTrue(caches(v).isNotEmpty())
        val zero=view(options=SvgOptions(strict=true,maxCachePixels=0))
        assertTrue(caches(zero).isEmpty())
    }
    @Test fun resizeZoomCssAndGeometryRemainLive() = FxTest.run {
        val v=view()
        val p=v.lookup("#p") as javafx.scene.shape.Rectangle
        p.style="-fx-fill: lime;"
        p.width=24.0
        v.applyCss(); v.layout()
        assertEquals(Color.LIME,p.fill)
        for(size in listOf(32.0,128.0,64.0)) {
            v.resize(size,size); v.layout()
            val cached=pixels(v)
            v.renderCacheEnabled=false
            val vector=pixels(v)
            assertTrue(difference(cached,vector)<.01)
            v.renderCacheEnabled=true
        }
    }
    @Test fun rotationHasBoundedRasterDifferenceAndKeepsCssTargets() = FxTest.run {
        val v=view()
        for(angle in listOf(0.0,30.0,90.0)) {
            v.rotate=angle
            val a=pixels(v)
            v.renderCacheEnabled=false
            val b=pixels(v)
            assertTrue(difference(a,b)<.04,"Rotation raster difference exceeds tolerance at "+angle)
            v.renderCacheEnabled=true
        }
        assertIs<Shape>(v.lookup("#p"))
    }
    @Test fun pureTransformCachesOnlyStaticContentsAndPaintAnimationUsesVectors() = FxTest.run {
        val motion="""<g id="spin"><rect x="16" y="16" width="24" height="24" fill="blue"/>
            <animateTransform attributeName="transform" type="rotate" from="0 32 32" to="360 32 32" dur="2s"/></g>"""
        val v=view(motion)
        assertTrue(v.lookup("#spin").isCache)
        v.seekAnimations(Duration.seconds(.5)); v.layout()
        assertTrue(v.lookup("#spin").isCache)
        val a=pixels(v)
        v.renderCacheEnabled=false
        assertTrue(difference(a,pixels(v))<.04)
        val changing=view("""<rect width="64" height="64"><animate attributeName="fill" from="red" to="blue" dur="2s"/></rect>""")
        assertTrue(caches(changing).isEmpty())
        for (scale in listOf(
            """<g><rect width="20" height="20"/><animateTransform attributeName="transform" type="scale" from="1" to="2" dur="2s"/></g>""",
            """<style>@keyframes k {from {transform:scale(1)} to {transform:scale(2)}} g {animation:k 2s linear infinite}</style><g><rect width="20" height="20"/></g>""")) {
            assertTrue(caches(view(scale)).isEmpty(),"Scaling animation should avoid raster cache regeneration")
        }

    }
    @Test fun detachAndDocumentReplacementReleaseOldCachingState() = FxTest.run {
        val v=view()
        val old=caches(v)
        (v.scene.root as Group).children.clear()
        assertTrue(old.none { it.isCache })
        val parent=Group(v); Scene(parent); v.layout()
        assertTrue(caches(v).isNotEmpty())
        val attached=caches(v)
        v.document=SvgLoader.parse("""<svg><circle r="5"/></svg>""")
        assertTrue(attached.none { it.isCache })
        v.layout()
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun ancestorTransformsAndReparentingKeepPixelBudgetBounded() = FxTest.run {
        val root = Group(javafx.scene.shape.Rectangle(100.0, 100.0))
        val host = Group(root)
        val outer = Group(host)
        Scene(outer)
        val cache = SvgRenderCache(root, listOf(root), 25_000)
        assertTrue(root.isCache)
        outer.scaleX = 2.0
        assertFalse(root.isCache, "Ancestor scaling must disable an oversized cache")
        outer.scaleX = 1.0
        assertTrue(root.isCache)
        val enlarged = Group().apply { scaleX = 2.0 }
        outer.children += enlarged
        host.children.clear()
        enlarged.children += root
        assertFalse(root.isCache, "Reparenting must observe the new ancestor chain")
        enlarged.scaleX = 1.0
        assertTrue(root.isCache)
        // The old branch must no longer control the cache.
        host.scaleX = 100.0
        assertTrue(root.isCache)
        cache.enabled = false
        enlarged.scaleX = 2.0
        cache.enabled = true
        assertFalse(root.isCache, "Enabling must recalculate the current ancestry")
    }

    @Test fun shearRotationAndThreeDimensionalAncestorsRespectBudget() = FxTest.run {
        val root = Group(javafx.scene.shape.Rectangle(100.0, 40.0))
        val parent = Group(root)
        Scene(parent)
        SvgRenderCache(root, listOf(root), 15_000)
        assertTrue(root.isCache)
        parent.transforms += javafx.scene.transform.Shear(3.0, 0.0)
        assertFalse(root.isCache)
        parent.transforms.clear()
        parent.rotate = 90.0
        assertTrue(root.isCache)
        parent.rotationAxis = javafx.scene.transform.Rotate.X_AXIS
        parent.rotate = 45.0
        assertFalse(root.isCache)
        parent.rotate = 0.0
        assertTrue(root.isCache)
    }

    @Test fun sharedAncestorsAndSeveralTargetsReleaseBudgetAfterGeometryChanges() = FxTest.run {
        val first = Group(javafx.scene.shape.Rectangle(60.0, 60.0))
        val rect = javafx.scene.shape.Rectangle(60.0, 60.0)
        val second = Group(rect)
        val root = Group(first, second)
        Scene(Group(root))
        SvgRenderCache(root, listOf(first, second), 12_000)
        assertTrue(first.isCache)
        assertFalse(second.isCache)
        (first.children.single() as javafx.scene.shape.Rectangle).width = 10.0
        assertTrue(second.isCache, "Freed cache budget must become available to later targets")
        rect.width = 100.0
        assertFalse(second.isCache)
    }

    @Test fun rotationsAndTranslationKeepCacheEligibilityStableButStretchInvalidates() = FxTest.run {
        val rect = javafx.scene.shape.Rectangle(60.0, 60.0)
        val root = Group(rect)
        val parent = Group(root)
        Scene(parent)
        SvgRenderCache(root, listOf(root), 10_000)
        val affine = javafx.scene.transform.Affine()
        parent.transforms += affine
        var changes = 0
        root.cacheProperty().addListener { _, _, _ -> changes++ }
        for (angle in 0..360) {
            parent.rotate = angle.toDouble()
            affine.setToTransform(javafx.scene.transform.Rotate(angle.toDouble()))
            parent.translateX = angle.toDouble()
            assertTrue(root.isCache)
        }
        assertEquals(0, changes)
        val rotation = javafx.scene.transform.Rotate(30.0)
        parent.transforms.setAll(rotation)
        assertTrue(root.isCache)
        rotation.axis = javafx.scene.transform.Rotate.X_AXIS
        assertFalse(root.isCache)
        rotation.axis = javafx.scene.transform.Rotate.Z_AXIS
        assertTrue(root.isCache)
        val stretch = javafx.scene.transform.Scale(1.0, 1.0)
        parent.transforms.setAll(stretch)
        stretch.x = 4.0
        assertFalse(root.isCache)
        stretch.x = 1.0
        assertTrue(root.isCache)
        val shift = javafx.scene.transform.Translate()
        parent.transforms.setAll(shift)
        shift.z = 1.0
        assertFalse(root.isCache)
        shift.z = 0.0
        assertTrue(root.isCache)
        parent.transforms.setAll(affine)
        affine.appendScale(4.0, 1.0)
        assertFalse(root.isCache)
        parent.transforms.clear()
        assertTrue(root.isCache)
    }
}
