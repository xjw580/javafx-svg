package club.xiaojiawei.svg

import java.net.URI

/** Parsing limits also apply to caller-provided streams. Streams are never closed by load(InputStream). */
data class SvgOptions @JvmOverloads constructor(
    val strict: Boolean = false,
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxDepth: Int = 128,
    val maxElements: Int = 100_000,
    val maxMaskPixels: Int = 4 * 1024 * 1024,
    val maxPatternPixels: Int = 4 * 1024 * 1024,
    val maxCachePixels: Int = 64 * 1024,
    /** Null denies every external CSS/image reference. Base URIs alone never grant access. */
    val resourceResolver: SvgResourceResolver? = null,
    /** Caps unique resolver requests and stylesheet applications (including repeated imports). */
    val maxResourceRequests: Int = 64,
    val maxResourceDepth: Int = 8,
    val maxResourceBytes: Int = 4 * 1024 * 1024,
    val maxTotalResourceBytes: Long = 16L * 1024 * 1024,
    /** Total decoded pixels across unique image resources in one document. */
    val maxImagePixels: Long = 16L * 1024 * 1024,
    val maxFilterPixels: Int = 4 * 1024 * 1024,
    val maxFilterPrimitives: Int = 64
) {
    init {
        require(maxBytes in 1 until Int.MAX_VALUE)
        require(maxDepth in 1..512)
        require(maxElements > 0)
        require(maxMaskPixels > 0)
        require(maxPatternPixels > 0)
        require(maxCachePixels >= 0)
        require(maxResourceRequests >= 0)
        require(maxResourceDepth in 0..128)
        require(maxResourceBytes in 1 until Int.MAX_VALUE)
        require(maxTotalResourceBytes > 0)
        require(maxImagePixels > 0)
        require(maxFilterPixels > 0)
        require(maxFilterPrimitives in 1..1024)
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
    internal val sourceBytes: Int,
    /** Origin used for references during loading; changing a view cannot change this snapshot. */
    val baseUri: URI?,
    internal val resources: SvgPreparedResources
) {
    internal val styleSheet: SvgStyleSheet by lazy { SvgStyleSheet.compile(root, resources.styles, resources.styleIssues) }
    internal val geometry: SvgGeometryCache by lazy { SvgGeometryCache(options.maxElements) }
    internal val lengths: SvgLengthCache by lazy { SvgLengthCache() }
}
