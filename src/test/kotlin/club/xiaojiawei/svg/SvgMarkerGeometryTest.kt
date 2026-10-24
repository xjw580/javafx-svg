package club.xiaojiawei.svg

import javafx.geometry.Point2D
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Affine
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgMarkerGeometryTest {
    @Test fun lineVerticesUseTangentsAndCornerBisectors() = FxTest.run {
        val vertices = vertices("M1 2H3V5")
        assertEquals(listOf(Point2D(1.0, 2.0), Point2D(3.0, 2.0), Point2D(3.0, 5.0)), vertices.map { it.point })
        assertAngles(listOf(0.0, 45.0, 90.0), vertices)
    }

    @Test fun closedSubpathIncludesDistinctStartAndEndVerticesWithTheSameBisector() = FxTest.run {
        val vertices = vertices("M0 0H10V10Z")
        assertEquals(4, vertices.size)
        assertEquals(vertices.first().point, vertices.last().point)
        assertAngles(listOf(-67.5, 45.0, 157.5, -67.5), vertices)
    }

    @Test fun movetoAndClosedCommandsPreserveAllSubpathBoundaries() = FxTest.run {
        val vertices = vertices("M0 0H10 M20 20V30Z M40 40 M50 50H60")
        assertEquals(8, vertices.size)
        assertEquals(Point2D(0.0, 0.0), vertices.first().point)
        assertEquals(Point2D(60.0, 50.0), vertices.last().point)
        assertEquals(Point2D(20.0, 20.0), vertices[2].point)
        assertEquals(Point2D(20.0, 20.0), vertices[4].point)
        assertEquals(Point2D(40.0, 40.0), vertices[5].point)
    }

    @Test fun cubicAndQuadraticCurvesUseEndpointDerivativesIncludingDegenerateControls() = FxTest.run {
        assertAngles(listOf(90.0, 0.0), vertices("M0 0C0 10 10 20 20 20"))
        assertAngles(listOf(90.0, 0.0), vertices("M0 0C0 0 0 20 20 20"))
        assertAngles(listOf(45.0, 45.0), vertices("M0 0Q0 0 20 20"))
        assertAngles(listOf(0.0, 0.0), vertices("M2 3C2 3 2 3 2 3"))
    }

    @Test fun arcsUseAnalyticTangentsAndCorrectSmallRadii() = FxTest.run {
        assertAngles(listOf(90.0, 180.0), vertices("M10 0A10 10 0 0 1 0 10"))
        assertAngles(listOf(-90.0, 90.0), vertices("M0 0A1 1 0 0 1 10 0"))
        assertAngles(listOf(0.0, 0.0), vertices("M0 0A0 1 0 0 1 10 0"))
        assertEquals(1, vertices("M0 0A10 10 0 0 1 0 0").size)
    }

    @Test fun zeroLengthSegmentsAndMovetosUseNeighboringNonzeroDirections() = FxTest.run {
        assertAngles(listOf(0.0, 0.0, 0.0), vertices("M1 2L1 2L1 2"))
        assertAngles(listOf(90.0, 90.0, 90.0), vertices("M1 2L1 2V8"))
        assertAngles(listOf(0.0, 0.0, 90.0, 90.0), vertices("M0 0H10L10 0V10"))
        assertAngles(listOf(90.0, 90.0, 90.0), vertices("M10 10 M20 20V30"))
    }

    @Test fun hostSpaceBisectorIsComputedAfterNonuniformTransform() = FxTest.run {
        val middle = vertices("M0 0L10 10H20")[1]
        val transform = Affine(3.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertEquals(Math.toDegrees(kotlin.math.atan2(1.0, 3.0)) / 2, middle.angle(transform), .0001)
    }

    @Test fun basicShapesHaveTheirEquivalentVerticesWithoutAnExtraZeroClose() = FxTest.run {
        val circle = SvgMarkerGeometry.vertices(Circle(0.0, 0.0, 10.0), 100)
        assertEquals(5, circle.size)
        assertAngles(listOf(90.0, 180.0, -90.0, 0.0, 90.0), circle)
        val rect = SvgMarkerGeometry.vertices(Rectangle(0.0, 0.0, 20.0, 10.0), 100)
        assertEquals(5, rect.size)
        assertAngles(listOf(-45.0, 45.0, 135.0, -135.0, -45.0), rect)
        val rounded = SvgMarkerGeometry.vertices(Rectangle(0.0, 0.0, 20.0, 10.0).apply { arcWidth = 4.0; arcHeight = 4.0 }, 100)
        assertEquals(9, rounded.size)
        assertEquals(rounded.first().point, rounded.last().point)
    }

    @Test fun expansionLimitsAndEmptyGeometryAreBounded() = FxTest.run {
        assertTrue(vertices("").isEmpty())
        assertTrue(SvgMarkerGeometry.vertices(Circle(0.0, 0.0, 0.0), 10).isEmpty())
        assertFailsWith<IllegalArgumentException> { SvgMarkerGeometry.vertices(SVGPath().apply { content = "M0 0H10V10Z" }, 3) }
        assertEquals(1, vertices("M1 2").size)
    }

    private fun vertices(data: String) = SvgMarkerGeometry.vertices(SVGPath().apply { content = data }, 100)

    private fun assertAngles(expected: List<Double>, actual: List<SvgMarkerGeometry.Vertex>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (angle, vertex) ->
            var difference = (vertex.angle() - angle) % 360
            if (difference > 180) difference -= 360
            if (difference < -180) difference += 360
            assertEquals(0.0, difference, .0001)
        }
    }
}
