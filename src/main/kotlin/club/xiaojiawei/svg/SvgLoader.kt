package club.xiaojiawei.svg

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object SvgLoader {
    @JvmStatic
    @JvmOverloads
    fun parse(xml: String, options: SvgOptions = SvgOptions()): SvgDocument =
        loadBytes(xml.toByteArray(Charsets.UTF_8), options, null)

    /** Parse with an explicit origin. External resources still require an opt-in resolver. */
    @JvmStatic
    @JvmOverloads
    fun parse(xml: String, baseUri: URI, options: SvgOptions = SvgOptions()): SvgDocument =
        loadBytes(xml.toByteArray(Charsets.UTF_8), options, baseUri)

    @JvmStatic
    @JvmOverloads
    fun load(path: Path, options: SvgOptions = SvgOptions()): SvgDocument =
        Files.newInputStream(path).use { load(it, path.toAbsolutePath().normalize().toUri(), options) }

    /** Opening this URL is explicit; references are denied unless [SvgOptions.resourceResolver] allows them. */
    @JvmStatic
    @JvmOverloads
    fun load(url: URL, options: SvgOptions = SvgOptions()): SvgDocument {
        val connection = url.openConnection().apply {
            useCaches = false
            connectTimeout = 5_000
            readTimeout = 10_000
        }
        return connection.getInputStream().use { load(it, connection.url.toURI(), options) }
    }

    @JvmStatic
    @JvmOverloads
    fun load(input: InputStream, options: SvgOptions = SvgOptions()): SvgDocument =
        loadStream(input, options, null)

    /** The caller owns the SVG stream; resolver-created resource streams are closed after reading. */
    @JvmStatic
    @JvmOverloads
    fun load(input: InputStream, baseUri: URI, options: SvgOptions = SvgOptions()): SvgDocument =
        loadStream(input, options, baseUri)

    private fun loadStream(input: InputStream, options: SvgOptions, baseUri: URI?): SvgDocument =
        try { loadBytes(input.readNBytes(options.maxBytes + 1), options, baseUri) }
        catch (e: SvgException) { throw e }
        catch (e: Exception) { throw SvgException("Cannot parse SVG: ${e.message}", e) }

    private fun loadBytes(bytes: ByteArray, options: SvgOptions, baseUri: URI?): SvgDocument {
        try {
            if (bytes.size > options.maxBytes) throw SvgException("SVG exceeds maxBytes")
            require(baseUri == null || baseUri.isAbsolute) { "SVG base URI must be absolute" }
            val dom = parseDom(bytes, options.maxDepth)
            val root = dom.documentElement ?: throw SvgException("Missing SVG root")
            if (root.localName != "svg" || root.namespaceURI !in listOf(null, "", SVG_NS)) {
                throw SvgException("Expected an SVG root element")
            }
            val ids = linkedMapOf<String, SvgElement>()
            val diagnostics = mutableListOf<SvgDiagnostic>()
            fun report(message: String, element: SvgElement? = null) {
                if (options.strict) throw SvgException(message)
                diagnostics += SvgDiagnostic(message, element?.id)
            }
            var count = 0
            fun read(element: Element, depth: Int): SvgElement {
                if (depth > options.maxDepth || ++count > options.maxElements) {
                    throw SvgException("SVG exceeds element/depth limit")
                }
                val attrs = linkedMapOf<String, String>()
                for (i in 0 until element.attributes.length) {
                    val attr = element.attributes.item(i)
                    when (attr.namespaceURI) {
                        null, "" -> attrs[attr.nodeName] = attr.nodeValue
                        "http://www.w3.org/1999/xlink" -> attrs["xlink:" + attr.localName] = attr.nodeValue
                        XMLConstants.XML_NS_URI -> attrs["xml:" + attr.localName] = attr.nodeValue
                    }
                }
                val children = mutableListOf<SvgElement>()
                val text = StringBuilder()
                val content = mutableListOf<SvgContent>()
                val pending = StringBuilder()
                fun flushText() {
                    if (pending.isNotEmpty()) {
                        content += SvgContent.Characters(pending.toString())
                        pending.setLength(0)
                    }
                }
                for (i in 0 until element.childNodes.length) {
                    val child = element.childNodes.item(i)
                    when (child.nodeType) {
                        Node.ELEMENT_NODE -> {
                            flushText()
                            val parsed = read(child as Element, depth + 1)
                            children += parsed
                            content += SvgContent.Child(parsed)
                        }
                        Node.PROCESSING_INSTRUCTION_NODE -> report("XML processing instructions inside SVG elements are unsupported")
                        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                            text.append(child.nodeValue)
                            pending.append(child.nodeValue)
                        }
                    }
                }
                flushText()
                val name = if (element.namespaceURI in listOf(null, "", SVG_NS)) element.localName
                    else "{${element.namespaceURI}}${element.localName}"
                val result = SvgElement(name, Collections.unmodifiableMap(attrs),
                    Collections.unmodifiableList(children), text.toString(), Collections.unmodifiableList(content))
                result.id?.let {
                    if (ids.putIfAbsent(it, result) != null) throw SvgException("Duplicate SVG id: $it")
                }
                return result
            }
            val processingInstructions = mutableListOf<Map<String, String>>()
            var inProlog = true
            var stylesheetInstructions = 0
            for (i in 0 until dom.childNodes.length) {
                val child = dom.childNodes.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) inProlog = false
                if (child.nodeType == Node.PROCESSING_INSTRUCTION_NODE) {
                    if (inProlog && child.nodeName == "xml-stylesheet") {
                        // Bound pseudo-attribute parsing too, rather than just the eventual I/O.
                        if (++stylesheetInstructions > options.maxResourceRequests) {
                            if (stylesheetInstructions == options.maxResourceRequests + 1) report("SVG exceeds stylesheet reference limit")
                            continue
                        }
                        try { processingInstructions += stylesheetAttributes(child.nodeValue) }
                        catch (failure: Exception) { report(failure.message ?: "Malformed xml-stylesheet") }
                    } else report("XML processing instructions outside stylesheet prolog are unsupported")
                }
            }
            val parsedRoot = read(root, 1)
            val resources = SvgResourceSession(options, baseUri, ::report).prepare(parsedRoot, processingInstructions)
            return SvgDocument(parsedRoot, Collections.unmodifiableMap(ids),
                Collections.unmodifiableList(diagnostics), options, bytes.size, baseUri, resources)
        } catch (e: SvgException) {
            throw e
        } catch (e: Exception) {
            throw SvgException("Cannot parse SVG: ${e.message}", e)
        }
    }


    private fun stylesheetAttributes(text: String): Map<String, String> {
        // Match the complete pseudo-attribute grammar before using the hardened XML parser for
        // character/entity decoding and duplicate-attribute validation. Never parse arbitrary markup.
        require(PI_ATTRIBUTES.matches(text)) { "Malformed xml-stylesheet pseudo-attributes" }
        val element = parseDom(("<svg " + text + "/>").toByteArray(Charsets.UTF_8), 1).documentElement
        return (0 until element.attributes.length).associate { index ->
            val attr = element.attributes.item(index)
            attr.nodeName to attr.nodeValue
        }
    }

    private val PI_ATTRIBUTES = Regex("""\s*(?:[A-Za-z_:][\w:.-]*\s*=\s*(?:"[^"<]*"|'[^'<]*')(?:\s+|$))*""")


    private class Parser(val depth: Int, val builder: javax.xml.parsers.DocumentBuilder, var uses: Int = 0, var bytes: Int = 0)
    private val parsers = ThreadLocal<Parser>()
    private val errors = object : DefaultHandler() {
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
    }

    /** Confined to one thread; rotate parsers to bound provider symbol/buffer retention. */
    private fun parseDom(bytes: ByteArray, depth: Int): org.w3c.dom.Document {
        val cached = parsers.get()
        val parser = if (cached != null && cached.depth == depth && cached.uses < 64 &&
            cached.bytes.toLong() + bytes.size <= 1024 * 1024) cached else createParser(depth)
        if (bytes.size <= 128 * 1024) parsers.set(parser) else parsers.remove()
        parser.uses++
        parser.bytes += bytes.size
        try {
            parser.builder.setErrorHandler(errors)
            return parser.builder.parse(ByteArrayInputStream(bytes))
        } catch (failure: Exception) {
            parsers.remove()
            throw failure
        } finally {
            try { parser.builder.reset() } catch (_: UnsupportedOperationException) { parsers.remove() }
        }
    }

    private fun createParser(depth: Int): Parser {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", depth.toString())
        }
        return Parser(depth, factory.newDocumentBuilder())
    }

    private const val SVG_NS = "http://www.w3.org/2000/svg"
}
