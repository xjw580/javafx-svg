package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.transform.Scale
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Exact user-supplied fixture, including its intentionally generous filter regions. */
class SvgRegularFiltersTest {
    private fun view(options: SvgOptions = SvgOptions(strict = true)): SvgView =
        SvgView.load(checkNotNull(javaClass.getResource("/svg/regular-filters.svg")), options).apply {
            isManaged = false
            Scene(Group(this))
        }

    private fun pixels(view: SvgView, scale: Double): WritableImage {
        view.resize(640.0 * scale, 360.0 * scale); view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }

    private fun assertPicture(image: WritableImage, scale: Double) {
        val p = image.pixelReader
        assertEquals(Color.web("#d5ffff"), p.getColor((170 * scale).toInt(), (178 * scale).toInt()))
        assertEquals(Color.web("#7456d2"), p.getColor((400 * scale).toInt(), (100 * scale).toInt()))
        val shadow = p.getColor((568 * scale).toInt(), (180 * scale).toInt())
        val background = Color.web("#0b1327")
        assertTrue(shadow.brightness < background.brightness, "Shadow must remain visible, not disappear behind a diagnostic")
        val glow = p.getColor((260 * scale).toInt(), (178 * scale).toInt())
        assertTrue(glow.green > .2, "The native glow must also remain visible")
    }

    @Test fun exactRegularFiltersRenderAtCommonPreviewAndDeviceScales() = FxTest.run {
        val view = view()
        for (scale in listOf(1.0, 1.25, 1.5, 2.0, 1.0, 2.0)) {
            val image = pixels(view, scale)
            assertTrue(view.diagnostics.isEmpty(), "scale=$scale ${view.diagnostics}")
            assertPicture(image, scale)
            println("REGULAR FILTER FIX scale=$scale image=${image.width}x${image.height} diagnostics=${view.diagnostics}")
            if (scale == 1.0 || scale == 2.0) save(image, "regular-filters-${scale.toInt()}x.png")
        }
        // Exercise ancestor-of-filter scaling; no physical HiDPI device is required for this test.
        view.transforms.setAll(Scale(2.0, 2.0))
        val scaled = pixels(view, 1.0)
        assertPicture(scaled, 2.0)
        assertTrue(view.diagnostics.isEmpty())
        view.transforms.clear()
        for (scale in listOf(1.5, 2.0)) {
            (view.parent as Group).transforms.setAll(Scale(scale, scale))
            // Node.snapshot excludes its parent's transform, while filter allocation observes it.
            assertPicture(pixels(view, 1.0), 1.0)
            assertTrue(view.diagnostics.isEmpty())
        }
    }

    @Test fun oversizedPreviewStillFailsAndRecoversAfterShrinking() = FxTest.run {
        val view = view(SvgOptions())
        assertPicture(pixels(view, 1.0), 1.0)
        pixels(view, 4.0)
        assertTrue(view.diagnostics.any { it.message.contains("maxFilterPixels") })
        assertPicture(pixels(view, 1.0), 1.0)
    }

    private fun save(image: WritableImage, filename: String) {
        val result = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until result.height) for (x in 0 until result.width) result.setRGB(x, y, image.pixelReader.getArgb(x, y))
        val output = Path.of("target", filename)
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(result, "png", output.toFile()))
    }
}
