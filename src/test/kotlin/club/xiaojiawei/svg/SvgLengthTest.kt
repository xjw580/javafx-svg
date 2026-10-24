package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgLengthTest {
    @Test fun supportsAbsoluteUnitsCaseAndNumberFormats() {
        for (value in listOf("96", "96px", "1in", "2.54cm", "25.4mm", "101.6q", "72pt", "6pc", " 1IN ")) {
            assertEquals(96.0, SvgNumbers.length(value, 13.0), 1e-12, value)
        }
        assertEquals(.5, SvgNumbers.length(".5PX", 1.0))
        assertEquals(1.0, SvgNumbers.length("1.", 1.0))
        assertEquals(-100.0, SvgNumbers.length("-1e+2px", 1.0))
        assertEquals(100.0, SvgNumbers.length("+1E2", 1.0))
    }

    @Test fun resolvesPercentagesAgainstEachReference() {
        assertEquals(50.0, SvgNumbers.length("25%", 200.0))
        assertEquals(100.0, SvgNumbers.length("25%", 400.0))
        assertEquals(0.0, SvgNumbers.length("25%", 0.0))
        assertEquals(-50.0, SvgNumbers.length("-25%", 200.0))
        assertEquals(12.0, SvgNumbers.length("12px", Double.NaN))
    }

    @Test fun rejectsUnsupportedSyntaxWithoutChangingDiagnosticText() {
        for (value in listOf("", "NaN", "Infinity", "1em", "1vw", "1 px", "1e", "1,2", "1%%")) {
            assertEquals("Unsupported length: $value", assertFailsWith<SvgException> {
                SvgNumbers.length(value, 100.0)
            }.message)
        }
    }

    @Test fun checksNumericAndConversionOverflowOnEveryResolution() {
        for (value in listOf("1e309", "1e308in", "1e308cm", "1e308pt")) {
            assertFailsWith<IllegalArgumentException> { SvgNumbers.length(value, 1.0) }
        }
        assertEquals(0.0, SvgNumbers.length("1e308%", 0.0))
        assertFailsWith<IllegalArgumentException> { SvgNumbers.length("1e308%", 100.0) }
        assertEquals(0.0, SvgNumbers.length("1e308%", 0.0))
        assertFailsWith<IllegalArgumentException> { SvgNumbers.length("10%", Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SvgNumbers.length("10%", Double.POSITIVE_INFINITY) }
    }
}
