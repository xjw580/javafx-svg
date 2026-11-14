package rc.consumer

import club.xiaojiawei.svg.*
import java.net.URI
import java.nio.file.Files

/** Compile in a separate compiler invocation against the packaged JAR, not this source module. */
fun main() {
    val xml = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><rect id="box" width="16" height="16"/></svg>"""
    val defaults = SvgOptions()
    val options = defaults.copy(strict = true, maxBytes = 4096)
    check(!defaults.strict && options.strict)
    val document = SvgLoader.parse(xml, URI("file:/icons/example.svg"), options)
    check(document.root.name == "svg" && document.elementsById.getValue("box")["width"] == "16")
    check(document.baseUri == URI("file:/icons/example.svg") && document.diagnostics.isEmpty())
    val (message, id) = SvgDiagnostic("diagnostic", "box")
    check(message == "diagnostic" && id == "box")
    check(SvgException("failure").cause == null)
    val resolver = SvgResourceResolver { null }
    check(resolver.open(SvgResourceRequest(URI("https://example.invalid/no-network"), SvgResourceKind.STYLESHEET)) == null)
    val file = Files.createTempFile("javafx-svg-kotlin-consumer-", ".svg")
    try {
        Files.writeString(file, xml)
        val cache = SvgResourceCache(maxEntries = 2)
        val first = cache.load(file, options)
        check(cache.load(file, options) === first)
        cache.invalidate(file)
        check(cache.load(file, options) !== first)
        cache.clear()
        check(cache.size == 0 && cache.sourceBytes == 0L)
    } finally { Files.deleteIfExists(file) }
    println("KOTLIN_CONSUMER_OK")
}
