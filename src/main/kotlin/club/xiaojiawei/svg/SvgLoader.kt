package club.xiaojiawei.svg

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
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
        load(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), options)

    @JvmStatic
    @JvmOverloads
    fun load(path: Path, options: SvgOptions = SvgOptions()): SvgDocument =
        Files.newInputStream(path).use { load(it, options) }

    /** Opening this URL is explicit; references inside the document never trigger network access. */
    @JvmStatic
    @JvmOverloads
    fun load(url: URL, options: SvgOptions = SvgOptions()): SvgDocument {
        val connection = url.openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 10_000
        }
        return connection.getInputStream().use { load(it, options) }
    }

    @JvmStatic
    @JvmOverloads
    fun load(input: InputStream, options: SvgOptions = SvgOptions()): SvgDocument {
        try {
            val bytes = input.readNBytes(options.maxBytes + 1)
            if (bytes.size > options.maxBytes) throw SvgException("SVG exceeds maxBytes")
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
                setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", options.maxDepth.toString())
            }
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            val dom = builder.parse(ByteArrayInputStream(bytes))
            val root = dom.documentElement ?: throw SvgException("Missing SVG root")
            if (root.localName != "svg" || root.namespaceURI !in listOf(null, "", SVG_NS)) {
                throw SvgException("Expected an SVG root element")
            }
            val ids = linkedMapOf<String, SvgElement>()
            val diagnostics = mutableListOf<SvgDiagnostic>()
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
                for (i in 0 until element.childNodes.length) {
                    val child = element.childNodes.item(i)
                    when (child.nodeType) {
                        Node.ELEMENT_NODE -> children += read(child as Element, depth + 1)
                        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(child.nodeValue)
                    }
                }
                val name = if (element.namespaceURI in listOf(null, "", SVG_NS)) element.localName
                    else "{${element.namespaceURI}}${element.localName}"
                val result = SvgElement(name, Collections.unmodifiableMap(attrs),
                    Collections.unmodifiableList(children), text.toString())
                result.id?.let {
                    if (ids.putIfAbsent(it, result) != null) throw SvgException("Duplicate SVG id: $it")
                }
                return result
            }
            for (i in 0 until dom.childNodes.length) {
                if (dom.childNodes.item(i).nodeType == Node.PROCESSING_INSTRUCTION_NODE) {
                    val message = "XML processing instructions are unsupported"
                    if (options.strict) throw SvgException(message)
                    diagnostics += SvgDiagnostic(message)
                }
            }
            return SvgDocument(read(root, 1), Collections.unmodifiableMap(ids),
                Collections.unmodifiableList(diagnostics), options)
        } catch (e: SvgException) {
            throw e
        } catch (e: Exception) {
            throw SvgException("Cannot parse SVG: ${e.message}", e)
        }
    }

    private const val SVG_NS = "http://www.w3.org/2000/svg"
}
