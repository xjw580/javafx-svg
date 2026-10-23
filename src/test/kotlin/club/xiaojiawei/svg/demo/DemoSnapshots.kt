package club.xiaojiawei.svg.demo

import javafx.scene.image.Image
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** PNG export is kept in the test/demo source set. */
internal fun writeDemoSnapshot(snapshot: Image, output: Path) {
    val image = BufferedImage(snapshot.width.toInt(), snapshot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) for (x in 0 until image.width) {
        image.setRGB(x, y, snapshot.pixelReader.getArgb(x, y))
    }
    Files.createDirectories(output.parent)
    check(ImageIO.write(image, "png", output.toFile()))
}
