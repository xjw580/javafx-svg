package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgClipTest {
    @Test fun clippingUsesGeometryRegardlessOfFillAndOpacity() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 20 20"><defs>
            <clipPath id="clip"><rect width="10" height="20" fill="none" opacity="0"/></clipPath>
            </defs><rect width="20" height="20" fill="red" clip-path="url(#clip)"/></svg>""", SvgOptions(strict = true))
        Scene(Group(view)); view.resize(20.0, 20.0); view.applyCss(); view.layout()
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertEquals(1.0, image.pixelReader.getColor(5, 10).opacity)
        assertEquals(0.0, image.pixelReader.getColor(15, 10).opacity)
    }

    @Test fun objectBoundingBoxClipTracksGeometryOnResize() = FxTest.run {
        val view = SvgView.parse("""<svg><defs>
            <clipPath id="clip" clipPathUnits="objectBoundingBox"><rect width=".5" height="1"/></clipPath>
            </defs><rect width="100%" height="100%" fill="blue" clip-path="url(#clip)"/></svg>""", SvgOptions(strict = true))
        view.isManaged = false
        Scene(Group(view)); view.resize(20.0, 20.0); view.applyCss(); view.layout()
        view.resize(40.0, 20.0); view.layout()
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertEquals(1.0, image.pixelReader.getColor(15, 10).opacity)
        assertEquals(0.0, image.pixelReader.getColor(25, 10).opacity)
    }

    @Test fun clipRuleAndCycleDetection() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 20 20"><defs>
            <clipPath id="clip"><path clip-rule="evenodd" d="M0 0H20V20H0Z M5 5H15V15H5Z"/></clipPath>
            </defs><rect width="20" height="20" fill="red" clip-path="url(#clip)"/></svg>""", SvgOptions(strict = true))
        Scene(Group(view)); view.resize(20.0, 20.0); view.applyCss(); view.layout()
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertEquals(1.0, image.pixelReader.getColor(2, 2).opacity)
        assertEquals(0.0, image.pixelReader.getColor(10, 10).opacity)
        assertFailsWith<SvgException> {
            SvgView.parse("""<svg><defs><clipPath id="a" clip-path="url(#a)"/></defs><rect clip-path="url(#a)"/></svg>""", SvgOptions(strict = true))
        }
    }
}
