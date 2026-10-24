package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.scene.Group
import javafx.scene.Scene
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgAnimationBenchmarkTest {
    @Test fun allWorkloadsAreStrictValidAnimatedAndActuallyChangeGeometryOrPaint() = FxTest.run {
        for (kind in animationBenchmarkKinds) {
            val document = SvgLoader.parse(animationBenchmarkSvg(kind), SvgOptions(strict = true))
            val view = SvgView(document).apply { animationAutoPlay = false; resize(64.0, 64.0) }
            Scene(Group(view))
            assertTrue(view.hasAnimations, kind)
            assertTrue(view.animationDuration.isIndefinite, kind)
            fun pixels(seconds: Double): IntArray {
                view.seekAnimations(Duration.seconds(seconds)); view.applyCss(); view.layout()
                val image = view.snapshot(null, null)
                return IntArray(image.width.toInt() * image.height.toInt()).also {
                    image.pixelReader.getPixels(0, 0, image.width.toInt(), image.height.toInt(),
                        javafx.scene.image.PixelFormat.getIntArgbInstance(), it, 0, image.width.toInt())
                }
            }
            val before = pixels(.1)
            val after = pixels(.6)
            assertFalse(before.contentEquals(after), "$kind fixture produced identical frames")
            assertTrue(view.diagnostics.isEmpty(), view.diagnostics.toString())
            view.source = null
        }
    }
}
