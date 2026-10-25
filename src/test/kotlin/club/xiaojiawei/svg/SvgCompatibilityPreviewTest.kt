package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** Reproducible review image; java.desktop/ImageIO is test-only, never part of the library renderer. */
class SvgCompatibilityPreviewTest {
    @Test fun renderCompatibilitySample() = FxTest.run {
        val source = checkNotNull(javaClass.getResource("/svg/compatibility-chain.svg"))
        val view = SvgView.load(source, SvgOptions(strict = true, maxFilterPixels = 8 * 1024 * 1024))
        view.isManaged = false
        Scene(Group(view)); view.resize(900.0, 420.0); view.applyCss(); view.layout()
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertTrue(view.diagnostics.isEmpty(), view.diagnostics.toString())
        val result = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until result.height) for (x in 0 until result.width) result.setRGB(x, y, image.pixelReader.getArgb(x, y))
        val output = Path.of("target", "compatibility-preview.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(result, "png", output.toFile()))
    }
}
