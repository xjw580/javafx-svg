package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgForeignObjectEffectsTest {
    @Test fun positionedForeignSourceStaysAlignedThroughEffectsAndViewportChanges() = FxTest.run {
        for ((primitive, offset) in listOf(
            "<feOffset dx='5' dy='3'/>" to (5 to 3),
            "<feGaussianBlur stdDeviation='0'/>" to (0 to 0),
            "<feDropShadow stdDeviation='0' dx='0' dy='0' flood-color='red'/>" to (0 to 0),
            "<feOffset/><feComposite in='SourceGraphic' operator='over'/>" to (0 to 0))) {
            val view = SvgView.parse("""<svg width="240" height="160"><defs>
                <filter id="f" x="-20%" y="-20%" width="140%" height="140%">$primitive</filter></defs>
                <foreignObject x="10%" y="20%" width="40%" height="30%" transform="translate(5 7)" filter="url(#f)">
                <div xmlns="http://www.w3.org/1999/xhtml" style="height:100%;background:red"/></foreignObject></svg>""", SvgOptions(strict=true))
            try {
                view.isManaged=false; Scene(Group(view))
                for (scale in listOf(1.0,2.0,1.25,1.0)) {
                    val width=240*scale; val height=160*scale
                    view.resize(width,height); view.applyCss(); view.layout()
                    val image=view.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
                    val x=(width*.1+5+offset.first).toInt()
                    val y=(height*.2+7+offset.second).toInt()
                    assertEquals(Color.RED,image.pixelReader.getColor(x+5,y+5),"$primitive at $scale")
                    assertEquals(Color.TRANSPARENT,image.pixelReader.getColor(x-5,y+5),"$primitive left at $scale")
                    assertEquals(Color.TRANSPARENT,image.pixelReader.getColor(x+5,y-5),"$primitive top at $scale")
                    assertTrue(view.diagnostics.isEmpty(),view.diagnostics.toString())
                }
            } finally {view.source=null}
        }
    }
}
