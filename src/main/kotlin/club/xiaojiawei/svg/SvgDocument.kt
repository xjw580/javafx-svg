package club.xiaojiawei.svg

/** Parsing limits also apply to caller-provided streams. Streams are never closed by load(InputStream). */
data class SvgOptions @JvmOverloads constructor(
    val strict: Boolean = false,
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxDepth: Int = 128,
    val maxElements: Int = 100_000,
    val maxMaskPixels: Int = 4 * 1024 * 1024,
    val maxPatternPixels: Int = 4 * 1024 * 1024
) {
    init {
        require(maxBytes in 1 until Int.MAX_VALUE)
        require(maxDepth in 1..512)
        require(maxElements > 0)
        require(maxMaskPixels > 0)
        require(maxPatternPixels > 0)
    }
}

class SvgException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class SvgDiagnostic(val message: String, val elementId: String? = null)

class SvgElement internal constructor(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<SvgElement>,
    val text: String,
    internal val content: List<SvgContent>
) {
    val id: String? get() = attributes["id"]
    operator fun get(name: String): String? = attributes[name]
}

/** Ordered mixed XML content, including text before and after child elements. */
internal sealed interface SvgContent {
    data class Characters(val value: String) : SvgContent
    data class Child(val element: SvgElement) : SvgContent
}

/** Immutable and safe to share between views. Contains no JavaFX nodes. */
class SvgDocument internal constructor(
    val root: SvgElement,
    val elementsById: Map<String, SvgElement>,
    val diagnostics: List<SvgDiagnostic>,
    val options: SvgOptions,
    internal val sourceBytes: Int
) {
    internal val styleSheet: SvgStyleSheet by lazy { SvgStyleSheet.compile(root) }
    internal val geometry: SvgGeometryCache by lazy { SvgGeometryCache(options.maxElements) }
    internal val lengths: SvgLengthCache by lazy { SvgLengthCache() }
}
