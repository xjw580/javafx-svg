package club.xiaojiawei.svg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Collections

/** The purpose of an external reference; resolvers may allow different locations for each kind. */
enum class SvgResourceKind { STYLESHEET, IMAGE }

/** A normalized, absolute URI, without credentials or a fragment. */
data class SvgResourceRequest(val uri: URI, val kind: SvgResourceKind)

/**
 * Explicit opt-in for external resources. Return null to deny a request; returned streams are closed.
 * Called synchronously on the loading thread, never while rendering. Implementations used by multiple
 * loads must be thread-safe. Enforce an allowlist BEFORE opening a resource. A network implementation
 * must also enforce timeouts, redirect/destination and DNS/IP policy (including private/loopback IPs).
 * The library never opens an external URI itself and cannot sandbox caller-provided resolver code.
 */
fun interface SvgResourceResolver {
    fun open(request: SvgResourceRequest): InputStream?
}

object SvgResourceResolvers {
    /**
     * Allow only regular files whose real paths remain inside this directory. Rejects network URLs,
     * file authorities, queries and symlink escapes. The directory must already exist. Do not use
     * with a directory concurrently modified by an untrusted process (filesystem race protection
     * is the host application's responsibility). No network access or redirects are performed.
     */
    @JvmStatic
    fun directory(directory: Path): SvgResourceResolver {
        val root = directory.toRealPath()
        require(Files.isDirectory(root)) { "Resource root must be a directory" }
        return SvgResourceResolver { request ->
            val uri = request.uri
            if (!uri.scheme.equals("file", true) || uri.rawAuthority != null || uri.rawQuery != null) null
            else {
                val path = Path.of(uri).toRealPath()
                if (!path.startsWith(root) || !Files.isRegularFile(path)) null else Files.newInputStream(path)
            }
        }
    }
}

internal data class SvgStyleSource(val text: String, val element: SvgElement)

/** Owns bytes privately; no mutable byte arrays or JavaFX objects escape into the public document. */
internal class SvgImageResource(private val bytes: ByteArray) {
    fun open(): InputStream = ByteArrayInputStream(bytes)
}

internal data class SvgPreparedResources(
    val styles: List<SvgStyleSource>,
    val images: Map<SvgElement, SvgImageResource>,
    val styleIssues: List<Pair<String, SvgElement>>
)

/** A single load's bounded, thread-confined resource session, discarded when the document is built. */
internal class SvgResourceSession(
    private val options: SvgOptions,
    private val documentBase: URI?,
    private val report: (String, SvgElement?) -> Unit
) {
    private data class Key(val uri: URI, val kind: SvgResourceKind)
    private val resources = mutableMapOf<Key, ByteArray?>()
    private val imageResources = mutableMapOf<String, SvgImageResource?>()
    private val styles = mutableListOf<SvgStyleSource>()
    private val styleIssues = mutableListOf<Pair<String, SvgElement>>()
    private val images = mutableMapOf<SvgElement, SvgImageResource>()
    private val activeSheets = mutableSetOf<URI>()
    private var requests = 0
    private var sheetApplications = 0
    private var totalBytes = 0L
    private var expandedCss = 0L
    private var imagePixels = 0L

    fun prepare(root: SvgElement, processingInstructions: List<Map<String, String>>): SvgPreparedResources {
        processingInstructions.forEach { attrs ->
            attempt(root, true) {
                require(attrs.keys.all { it in setOf("href", "type", "media", "charset", "title", "alternate") }) {
                    "Unsupported xml-stylesheet pseudo-attribute"
                }
                require(attrs["type"] in listOf(null, "", "text/css") && unconditional(attrs["media"])) {
                    "Only unconditional text/css stylesheets are supported"
                }
                require(attrs["alternate"] in listOf(null, "no") && attrs["title"].isNullOrEmpty()) {
                    "Named or alternate xml-stylesheet sets are unsupported"
                }
                require(attrs["charset"] == null || attrs["charset"].equals("utf-8", true)) {
                    "Only UTF-8 external stylesheets are supported"
                }
                externalSheet(requireNotNull(attrs["href"]) { "Missing xml-stylesheet href" }, documentBase, root, 1)
            }
        }
        fun visit(element: SvgElement, parentBase: URI?) {
            if (element.name.startsWith('{')) return
            var base = parentBase
            element["xml:base"]?.let { value ->
                try { base = resolveBase(value, parentBase) }
                catch (failure: Exception) {
                    report("Invalid xml:base; resources in this subtree are disabled: ${failure.message}", element)
                    return
                }
            }
            if (element.name == "style") attempt(element, true) {
                require(element["type"] in listOf(null, "", "text/css") && unconditional(element["media"])) {
                    "Only unconditional text/css stylesheets are supported"
                }
                sheet(element.text, base, element, 0, false)
            }
            if (element.name == "image") attempt(element) {
                val href = element["href"] ?: element["xlink:href"] ?: throw SvgException("Missing image href")
                val key = if (href.startsWith("data:", true)) href else resolve(href, base).toString()
                val resource = if (imageResources.containsKey(key)) imageResources[key] else {
                    imageResources[key] = null // Failed resources are never retried during the same load.
                    val bytes = if (href.startsWith("data:", true)) embedded(href)
                        else fetch(URI(key), SvgResourceKind.IMAGE)
                    bytes?.let { validateImage(it); SvgImageResource(it) }.also { imageResources[key] = it }
                }
                if (resource != null) images[element] = resource
            }
            element.children.forEach { visit(it, base) }
        }
        visit(root, documentBase)
        return SvgPreparedResources(Collections.unmodifiableList(styles), Collections.unmodifiableMap(images),
            Collections.unmodifiableList(styleIssues))
    }

    private inline fun attempt(element: SvgElement, style: Boolean = false, action: () -> Unit) {
        try { action() }
        catch (failure: Exception) {
            if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw SvgException("SVG resource load interrupted", failure)
            }
            if (options.strict && failure is SvgException) throw failure
            val message = failure.message ?: "Cannot load SVG resource"
            if (style) styleIssues += message to element
            report(message, element)
        }
    }

    private fun externalSheet(href: String, base: URI?, element: SvgElement, depth: Int) {
        require(++sheetApplications <= options.maxResourceRequests) { "SVG exceeds stylesheet reference limit" }
        require(depth <= options.maxResourceDepth) { "SVG exceeds resource depth limit" }
        val uri = resolve(href, base)
        require(activeSheets.add(uri)) { "Circular CSS import: $uri" }
        try {
            val bytes = fetch(uri, SvgResourceKind.STYLESHEET) ?: return
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
                catch (failure: Exception) { throw SvgException("External CSS must be valid UTF-8", failure) }
            sheet(text, uri, element, depth, true)
        } finally { activeSheets.remove(uri) }
    }

    private fun sheet(text: String, base: URI?, element: SvgElement, depth: Int, external: Boolean) {
        var importsAllowed = true
        var first = true
        val blocks = SvgCssSyntax.rules(text) { styleIssues += it to element; report(it, element) }
        for (block in blocks) {
            when {
                block.header.startsWith("@charset", true) && block.header.getOrNull(8)?.isWhitespace() != false -> attempt(element, true) {
                    require(external && first && block.body == null &&
                        Regex("@charset\\s+\"UTF-8\"", RegexOption.IGNORE_CASE).matches(block.header)) {
                        "Only a leading UTF-8 @charset in external CSS is supported"
                    }
                }
                block.header.startsWith("@import", true) && block.header.getOrNull(7)?.isWhitespace() != false -> attempt(element, true) {
                    require(importsAllowed && block.body == null) { "CSS @import must precede all style rules" }
                    val match = IMPORT.matchEntire(block.header)
                        ?: throw SvgException("Unsupported CSS @import syntax")
                    val href = match.groupValues.drop(1).take(5).first { it.isNotEmpty() }
                    require('\\' !in href && !href.any { it == '\n' || it == '\r' }) { "Escaped CSS resource URLs are unsupported" }
                    require(unconditional(match.groupValues[6].trim())) { "Conditional CSS @import is unsupported" }
                    externalSheet(href, base, element, depth + 1)
                }
                else -> {
                    importsAllowed = false
                    val css = block.header + if (block.body == null) ";" else "{${block.body}}"
                    expandedCss += css.length
                    require(expandedCss <= options.maxTotalResourceBytes) { "SVG exceeds expanded CSS limit" }
                    styles += SvgStyleSource(css, element)
                }
            }
            first = false
        }
    }

    private fun fetch(uri: URI, kind: SvgResourceKind): ByteArray? {
        val key = Key(uri, kind)
        if (resources.containsKey(key)) return resources[key]
        require(++requests <= options.maxResourceRequests) { "SVG exceeds resource request limit" }
        resources[key] = null
        val resolver = options.resourceResolver ?: throw SvgException("External SVG resources are disabled; only embedded resources are allowed: $uri")
        require(totalBytes < options.maxTotalResourceBytes) { "SVG exceeds resource byte limit" }
        val stream = resolver.open(SvgResourceRequest(uri, kind)) ?: throw SvgException("SVG resource denied: $uri")
        return stream.use { input ->
            val remaining = (options.maxTotalResourceBytes - totalBytes).coerceAtLeast(0)
            val limit = minOf(options.maxResourceBytes.toLong(), remaining).toInt()
            val output = ByteArrayOutputStream(minOf(limit, 8192))
            val buffer = ByteArray(minOf(limit + 1, 8192))
            var count = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw SvgException("SVG resource load interrupted")
                val length = minOf(buffer.size, limit + 1 - count)
                var read = input.read(buffer, 0, length)
                if (read < 0) break
                if (read == 0) { // Defend against streams that return zero without reaching EOF.
                    val single = input.read()
                    if (single < 0) break
                    buffer[0] = single.toByte(); read = 1
                }
                count += read
                totalBytes += read // Charge bytes even if a later read throws or this resource is invalid.
                require(count <= limit) { "SVG exceeds resource byte limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray().also { resources[key] = it }
        }
    }

    private fun embedded(href: String): ByteArray {
        val match = DATA.matchEntire(href) ?: throw SvgException("Only base64 PNG/JPEG data images are supported")
        val encoded = match.groupValues[2].filterNot(Char::isWhitespace)
        require(encoded.length.toLong() <= ((options.maxResourceBytes.toLong() + 2) / 3) * 4) { "SVG exceeds resource byte limit" }
        val bytes = Base64.getDecoder().decode(encoded)
        totalBytes += bytes.size
        require(bytes.size <= options.maxResourceBytes && totalBytes <= options.maxTotalResourceBytes) { "SVG exceeds resource byte limit" }
        return bytes
    }

    private fun validateImage(bytes: ByteArray) {
        fun byte(index: Int) = bytes[index].toInt() and 255
        fun short(index: Int) = (byte(index) shl 8) or byte(index + 1)
        fun integer(index: Int) = (byte(index) shl 24) or (byte(index + 1) shl 16) or
            (byte(index + 2) shl 8) or byte(index + 3)
        val dimensions: Pair<Int, Int> = when {
            bytes.size >= 24 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> {
                require(integer(8) == 13 && integer(12) == 0x49484452) { "Invalid PNG IHDR" }
                integer(16) to integer(20)
            }
            bytes.size >= 3 && byte(0) == 255 && byte(1) == 216 -> {
                var offset = 2
                var found: Pair<Int, Int>? = null
                while (offset < bytes.size) {
                    require(byte(offset++) == 255) { "Malformed JPEG marker" }
                    while (offset < bytes.size && byte(offset) == 255) offset++
                    require(offset < bytes.size) { "Truncated JPEG marker" }
                    val marker = byte(offset++)
                    if (marker == 217 || marker == 218) break // End-of-image or compressed scan.
                    if (marker == 1 || marker in 208..216) continue // Standalone marker.
                    require(offset + 2 <= bytes.size) { "Truncated JPEG segment" }
                    val length = short(offset)
                    require(length >= 2 && offset.toLong() + length <= bytes.size) { "Invalid JPEG segment length" }
                    if (marker in JPEG_SOF) {
                        require(length >= 8) { "Truncated JPEG frame" }
                        found = short(offset + 5) to short(offset + 3)
                        break
                    }
                    offset += length
                }
                requireNotNull(found) { "JPEG image dimensions are unavailable" }
            }
            else -> throw SvgException("Only PNG/JPEG image resources are supported")
        }
        val (width, height) = dimensions
        require(width > 0 && height > 0 && width.toLong() * height <= options.maxImagePixels - imagePixels) {
            "SVG image exceeds pixel limit"
        }
        imagePixels += width.toLong() * height
    }

    private fun resolve(href: String, base: URI?): URI {
        require(href.isNotBlank()) { "Empty SVG resource URI" }
        val uri = resolveBase(href.trim(), base)
        // Registry-style authorities (for example an underscore in a hostname) have null userInfo
        // even when an explicit user:password@ prefix is present. Check before echoing any URI.
        require(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) {
            "Credentials in SVG resource URIs are forbidden"
        }
        require(uri.isAbsolute && !uri.isOpaque) { "External SVG resource needs an absolute hierarchical base URI" }
        require(uri.rawFragment == null) { "Fragments in external SVG resources are unsupported" }
        require(!uri.scheme.equals("data", true)) { "Unsupported data resource URI" }
        return uri
    }

    private fun resolveBase(value: String, base: URI?): URI {
        val reference = try { URI(value) }
            catch (_: java.net.URISyntaxException) { throw SvgException("Invalid SVG resource URI") }
        // URI.resolve treats an empty relative path as the containing directory; RFC 3986 instead
        // retains the current path for empty/query-only references (including xml:base="").
        if (base != null && !base.isOpaque && !reference.isAbsolute && reference.rawAuthority == null && reference.rawPath.isNullOrEmpty()) {
            val path = base.toString().substringBefore('#').substringBefore('?')
            val query = reference.rawQuery ?: base.rawQuery
            return normalized(URI(path + (query?.let { "?$it" } ?: "") + (reference.rawFragment?.let { "#$it" } ?: "")))
        }
        return normalized(base?.resolve(reference) ?: reference)
    }

    private fun normalized(value: URI): URI {
        val uri = value.normalize()
        var path = uri.rawPath ?: return uri
        if (!uri.isAbsolute || !path.startsWith('/')) return uri
        // Java retains leading /../ segments, while RFC 3986 removes them at the absolute root.
        while (path.startsWith("/../")) path = path.substring(3)
        if (path == "/..") path = "/"
        if (path == uri.rawPath) return uri
        return URI(uri.scheme + ":" + (uri.rawAuthority?.let { "//$it" } ?: "") + path +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: ""))
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val JPEG_SOF = setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)
        private fun unconditional(media: String?) = media.isNullOrBlank() || media.trim().equals("all", true)
        private val DATA = Regex("data:image/(png|jpeg);base64,([\\s\\S]*)", RegexOption.IGNORE_CASE)
        private val IMPORT = Regex("""@import\s+(?:"([^"]*)"|'([^']*)'|url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s'"()]+))\s*\))\s*(.*)""", RegexOption.IGNORE_CASE)
    }
}
