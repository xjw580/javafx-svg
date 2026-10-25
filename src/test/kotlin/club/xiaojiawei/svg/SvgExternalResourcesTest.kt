package club.xiaojiawei.svg

import javafx.application.Platform
import javafx.scene.Group
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SvgExternalResourcesTest {
    @TempDir lateinit var directory: Path
    private val base = URI("https://assets.example.test/icons/icon.svg")
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=")
    private fun css(doc: SvgDocument, id: String = "p") = SvgStyles(doc) { _, _ -> }
        .compute(doc.elementsById.getValue(id), listOf(doc.root), emptyMap())
    private fun resolver(resources: Map<String, ByteArray>, requests: MutableList<SvgResourceRequest> = mutableListOf()) =
        SvgResourceResolver { request -> requests += request; resources[request.uri.toString()]?.inputStream() }

    @Test fun defaultsDenyNetworkAndFileReferencesWithoutOpeningThem() {
        val doc = SvgLoader.parse("""<?xml-stylesheet href="http://127.0.0.1:9/x.css" type="text/css"?>
            <svg><style>@import "file:///etc/passwd";</style>
            <image href="http://169.254.169.254/latest/meta-data"/><rect id="p"/></svg>""", base)
        assertEquals(3, doc.diagnostics.size)
        assertTrue(doc.diagnostics.all { it.message.contains("disabled") })
        assertTrue(doc.resources.images.isEmpty())
        assertEquals("black", css(doc)["fill"])
        assertFailsWith<SvgException> {
            SvgLoader.parse("""<svg><image href="https://example.test/image.png"/></svg>""", SvgOptions(strict = true))
        }
    }

    @Test fun piImportsAndInlineSheetsRespectOrderAndResolveRelativeToTheirOwnOrigin() {
        val requests = mutableListOf<SvgResourceRequest>()
        val options = SvgOptions(strict = true, resourceResolver = resolver(mapOf(
            "https://assets.example.test/icons/css/main.css" to """@charset "UTF-8";
                @import '../shared.css'; rect { fill: blue; }""".toByteArray(),
            "https://assets.example.test/icons/shared.css" to "rect { fill: red; stroke: purple; }".toByteArray(),
            "https://assets.example.test/icons/inline.css" to "rect { fill: green; }".toByteArray()
        ), requests))
        val doc = SvgLoader.parse("""<?xml-stylesheet href="css/main.css" type="text/css"?>
            <svg><style>@import url(inline.css) all; rect { opacity: .5; }</style><rect id="p"/></svg>""", base, options)
        assertEquals("green", css(doc)["fill"])
        assertEquals("purple", css(doc)["stroke"])
        assertEquals(".5", css(doc)["opacity"])
        assertEquals(listOf("/icons/css/main.css", "/icons/shared.css", "/icons/inline.css"), requests.map { it.uri.path })
        assertEquals(base, doc.baseUri)
    }

    @Test fun xmlBaseIsInheritedForImagesAndEmbeddedImports() {
        val requests = mutableListOf<SvgResourceRequest>()
        val doc = SvgLoader.parse("""<svg xml:base="../assets/"><g xml:base="nested/">
            <style>@import '../color.css';</style><image id="a" href="../pixel.png"/>
            <image id="b" href="../pixel.png"/></g><rect id="p"/></svg>""", base,
            SvgOptions(strict = true, resourceResolver = resolver(mapOf(
                "https://assets.example.test/assets/color.css" to "rect { fill: gold }".toByteArray(),
                "https://assets.example.test/assets/pixel.png" to png), requests)))
        assertEquals("gold", css(doc)["fill"])
        assertEquals(2, requests.size)
        assertSame(doc.resources.images[doc.elementsById["a"]], doc.resources.images[doc.elementsById["b"]])
    }

    @Test fun repeatedImportsReuseBytesButReapplyRulesAtEveryImportPosition() {
        val requests = mutableListOf<SvgResourceRequest>()
        val doc = SvgLoader.parse("""<svg><style>@import 'a.css'; @import 'b.css'; @import 'a.css';</style><rect id="p"/></svg>""", base,
            SvgOptions(strict = true, resourceResolver = resolver(mapOf(
                base.resolve("a.css").toString() to "rect { fill: red }".toByteArray(),
                base.resolve("b.css").toString() to "rect { fill: blue }".toByteArray()), requests)))
        assertEquals(2, requests.size)
        assertEquals("red", css(doc)["fill"])
    }

    @Test fun cyclesAreDiagnosedAndIndependentRulesSurvive() {
        val doc = SvgLoader.parse("""<svg><style>@import 'a.css'; rect { fill: green }</style><rect id="p"/></svg>""", base,
            SvgOptions(resourceResolver = resolver(mapOf(
                base.resolve("a.css").toString() to "@import 'b.css'; rect { stroke: red }".toByteArray(),
                base.resolve("b.css").toString() to "@import 'a.css';".toByteArray()))))
        assertTrue(doc.diagnostics.any { it.message.contains("Circular") })
        assertEquals("green", css(doc)["fill"])
        assertEquals("red", css(doc)["stroke"])
    }

    @Test fun unsupportedImportsAndPiVariantsDoNotCallResolver() {
        val requests = mutableListOf<SvgResourceRequest>()
        val doc = SvgLoader.parse("""<?xml-stylesheet href="alternate.css" title="Night" alternate="yes"?>
            <svg><style>@import 'print.css' print; @import 'layer.css' layer(foo);
            @import 'escaped\2e css'; rect { fill: green } @import 'late.css';</style><rect id="p"/></svg>""", base,
            SvgOptions(resourceResolver = resolver(emptyMap(), requests)))
        assertEquals(0, requests.size)
        assertEquals(5, doc.diagnostics.size)
        assertEquals("green", css(doc)["fill"])
    }

    @Test fun piPseudoAttributesDecodeEntitiesAndRejectMalformedOrNonPrologInstructions() {
        val requests = mutableListOf<SvgResourceRequest>()
        val opts = SvgOptions(resourceResolver = resolver(mapOf(
            base.resolve("a.css?x=1&y=2").toString() to "rect { fill: red }".toByteArray()), requests))
        val doc = SvgLoader.parse("""<?xml-stylesheet type='text/css' href='a.css?x=1&amp;y=2'?>
            <svg><rect id="p"/></svg>""", base, opts)
        assertEquals("red", css(doc)["fill"])
        assertEquals("x=1&y=2", requests.single().uri.query)
        for (pi in listOf("href='a.css' href='b.css'", "href='a.css' garbage", "href='&evil;'")) {
            assertFailsWith<SvgException> { SvgLoader.parse("<?xml-stylesheet $pi?><svg/>", base, opts.copy(strict = true)) }
        }
        assertTrue(SvgLoader.parse("<svg/><?xml-stylesheet href='a.css'?>", base, opts).diagnostics.isNotEmpty())
    }

    @Test fun budgetsBoundRequestsDepthBytesExpandedCssAndImagePixels() {
        val bodies = mapOf(
            base.resolve("a.css").toString() to "@import 'b.css'; rect { fill: red }".toByteArray(),
            base.resolve("b.css").toString() to "rect { stroke: blue }".toByteArray(),
            base.resolve("p.png").toString() to png)
        val opts = SvgOptions(strict = true, resourceResolver = resolver(bodies))
        val xml = "<svg><style>@import 'a.css';</style></svg>"
        for (limits in listOf(opts.copy(maxResourceRequests = 1), opts.copy(maxResourceDepth = 1),
            opts.copy(maxResourceBytes = 3), opts.copy(maxTotalResourceBytes = 4))) {
            assertFailsWith<SvgException> { SvgLoader.parse(xml, base, limits) }
        }
        val repeated = "<svg><style>" + "@import 'b.css';".repeat(40) + "</style></svg>"
        assertFailsWith<SvgException> { SvgLoader.parse(repeated, base, opts.copy(maxTotalResourceBytes = 100)) }
        val big = png.clone().apply { this[19] = 100; this[23] = 100 }
        val images = opts.copy(resourceResolver = resolver(mapOf(base.resolve("p.png").toString() to big)), maxImagePixels = 99)
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><image href='p.png'/></svg>", base, images) }
    }

    @Test fun deniedFailedAndOversizedResourcesAreClosedAndNeverRetriedWithinLoad() {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val opts = SvgOptions(maxResourceBytes = 8, resourceResolver = SvgResourceResolver {
            opens.incrementAndGet()
            object : ByteArrayInputStream(ByteArray(100)) { override fun close() { closes.incrementAndGet() } }
        })
        val doc = SvgLoader.parse("<svg><style>@import 'a.css'; @import 'a.css';</style></svg>", base, opts)
        assertEquals(1, opens.get()); assertEquals(1, closes.get())
        assertTrue(doc.diagnostics.any { it.message.contains("byte limit") })
        val deny = SvgLoader.parse("<svg><image href='p.png'/></svg>", base, opts.copy(resourceResolver = SvgResourceResolver { null }))
        assertTrue(deny.diagnostics.single().message.contains("denied"))
    }

    @Test fun totalByteBudgetChargesReadsBeforeStreamFailures() {
        val opens = AtomicInteger(); val reads = AtomicInteger()
        val opts = SvgOptions(maxTotalResourceBytes = 6, resourceResolver = SvgResourceResolver {
            opens.incrementAndGet()
            object : java.io.InputStream() {
                var delivered = false
                override fun read(): Int = throw java.io.IOException("read failed")
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    if (delivered) throw java.io.IOException("read failed")
                    delivered = true
                    val size = minOf(5, length)
                    target.fill(32, offset, offset + size); reads.addAndGet(size)
                    return size
                }
            }
        })
        val doc = SvgLoader.parse("<svg><style>@import 'a.css'; @import 'b.css'; @import 'c.css';</style></svg>", base, opts)
        assertEquals(2, opens.get())
        assertEquals(7, reads.get()) // One excess byte is the bounded EOF/limit probe.
        assertEquals(3, doc.diagnostics.size)
    }

    @Test fun relativeReferenceRequiresOriginAndCredentialOrFragmentUrisNeverReachResolver() {
        val requests = mutableListOf<SvgResourceRequest>()
        val opts = SvgOptions(resourceResolver = resolver(emptyMap(), requests))
        for (href in listOf("relative.png", "https://user:password@host.test/x.png", "https://host.test/x.png#fragment",
            "https://test:secret@host_name.test/x.png", "https://test:secret@host.test:bad/x.png")) {
            val doc = SvgLoader.parse("<svg><image href='$href'/></svg>", opts)
            assertTrue(doc.diagnostics.isNotEmpty())
            assertTrue(doc.diagnostics.none { "password" in it.message || "secret" in it.message })
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun invalidXmlBaseDoesNotFallBackToParentAndDecodePixelBudgetIsAggregate() {
        val requests = mutableListOf<SvgResourceRequest>()
        val opts = SvgOptions(resourceResolver = resolver(mapOf(
            base.resolve("p.png").toString() to png, base.resolve("q.png").toString() to png), requests))
        val invalid = SvgLoader.parse("<svg><g xml:base='[bad'><image href='p.png'/></g></svg>", base, opts)
        assertTrue(invalid.diagnostics.single().message.contains("Invalid xml:base"))
        assertTrue(requests.isEmpty())
        val limited = SvgLoader.parse("<svg><image href='p.png'/><image href='q.png'/></svg>", base, opts.copy(maxImagePixels = 1))
        assertEquals(1, limited.resources.images.size)
        assertTrue(limited.diagnostics.single().message.contains("pixel limit"))
    }

    @Test fun parentSegmentsAreClampedAtTheAbsoluteUriRoot() {
        val requests = mutableListOf<SvgResourceRequest>()
        val doc = SvgLoader.parse("<svg><image href='../../../p.png'/></svg>", base,
            SvgOptions(strict = true, resourceResolver = resolver(mapOf("https://assets.example.test/p.png" to png), requests)))
        assertEquals("/p.png", requests.single().uri.path)
        assertEquals(1, doc.resources.images.size)
    }

    @Test fun emptyXmlBaseAndQueryOnlyReferencesKeepCurrentOriginPath() {
        val requests = mutableListOf<SvgResourceRequest>()
        val doc = SvgLoader.parse("<svg xml:base=''><image href='?variant=1'/></svg>", base,
            SvgOptions(strict = true, resourceResolver = resolver(mapOf(
                "https://assets.example.test/icons/icon.svg?variant=1" to png), requests)))
        assertEquals("/icons/icon.svg", requests.single().uri.path)
        assertEquals(1, doc.resources.images.size)
    }

    @Test fun queryOnlyImportsRetainTheStylesheetFileAndPiParsingIsBounded() {
        val requests = mutableListOf<SvgResourceRequest>()
        val opts = SvgOptions(strict = true, resourceResolver = resolver(mapOf(
            base.resolve("main.css").toString() to "@import '?v=2';".toByteArray(),
            "https://assets.example.test/icons/main.css?v=2" to "rect { fill: red }".toByteArray()), requests))
        val doc = SvgLoader.parse("<svg><style>@import 'main.css';</style><rect id='p'/></svg>", base, opts)
        assertEquals("red", css(doc)["fill"])
        assertEquals("/icons/main.css", requests.last().uri.path)
        val limited = SvgLoader.parse("<?xml-stylesheet href='a.css'?>".repeat(10) + "<svg/>", base,
            SvgOptions(maxResourceRequests = 0))
        assertEquals(1, limited.diagnostics.size)
        assertTrue(limited.diagnostics.single().message.contains("reference limit"))
    }

    @Test fun invalidEncodingsImageTypesAndMalformedJpegAreDiagnosed() {
        val opts = SvgOptions(resourceResolver = resolver(mapOf(
            base.resolve("bad.css").toString() to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            base.resolve("gif").toString() to "GIF89a".toByteArray(),
            base.resolve("jpeg").toString() to byteArrayOf(-1, -40, -1, -32, 0, 0))))
        val doc = SvgLoader.parse("<svg><style>@import 'bad.css';</style><image href='gif'/><image href='jpeg'/></svg>", base, opts)
        assertEquals(3, doc.diagnostics.size)
        assertTrue(doc.diagnostics.any { it.message.contains("UTF-8") })
        assertTrue(doc.resources.images.isEmpty())
    }

    @Test fun directoryResolverRejectsTraversalSymlinksAndEveryNetworkScheme() {
        val allowed = Files.createDirectory(directory.resolve("allowed"))
        Files.write(allowed.resolve("pixel.png"), png)
        val secret = Files.write(directory.resolve("secret.png"), png)
        val policy = SvgResourceResolvers.directory(allowed)
        assertNotNull(policy.open(SvgResourceRequest(allowed.resolve("pixel.png").toUri(), SvgResourceKind.IMAGE))).close()
        assertNull(policy.open(SvgResourceRequest(secret.toUri(), SvgResourceKind.IMAGE)))
        Files.createSymbolicLink(allowed.resolve("escape.png"), secret)
        assertNull(policy.open(SvgResourceRequest(allowed.resolve("escape.png").toUri(), SvgResourceKind.IMAGE)))
        for (uri in listOf("http://127.0.0.1/x", "http://169.254.169.254/x", "https://example.test/x",
            "file://server/share/x", "jar:file:/tmp/a.jar!/x", "ftp://host/x")) {
            assertNull(policy.open(SvgResourceRequest(URI(uri), SvgResourceKind.IMAGE)))
        }
    }

    @Test fun pathAndUrlLoadsCarryOriginAndExternalDependenciesAreNotRetainedByDocumentCache() {
        Files.write(directory.resolve("pixel.png"), png)
        val cssPath = directory.resolve("color.css")
        Files.writeString(cssPath, "rect { fill: red }")
        val svg = Files.writeString(directory.resolve("icon.svg"), """<svg><style>@import 'color.css';</style>
            <rect id="p"/><image id="i" href="pixel.png"/></svg>""")
        val opts = SvgOptions(strict = true, resourceResolver = SvgResourceResolvers.directory(directory))
        val cache = SvgResourceCache()
        val first = cache.load(svg, opts)
        Files.writeString(cssPath, "rect { fill: blue }")
        val second = cache.load(svg.toUri().toURL(), opts)
        assertEquals(svg.toUri(), first.baseUri)
        assertEquals("red", css(first)["fill"]); assertEquals("blue", css(second)["fill"])
        assertNotSame(first, second); assertEquals(0, cache.size)
    }

    @Test fun preparedImagesRenderWithoutFurtherIoAndReuseDecodedImageWithinView() {
        val opens = AtomicInteger()
        val doc = SvgLoader.parse("""<svg width="20" height="10"><image id="a" href="p.png" width="10" height="10"/>
            <image id="b" href="p.png" x="10" width="10" height="10"/></svg>""", base,
            SvgOptions(strict = true, resourceResolver = SvgResourceResolver { opens.incrementAndGet(); png.inputStream() }))
        assertEquals(1, opens.get())
        FxTest.run {
            val first = SvgView(doc); val second = SvgView(doc)
            fun image(view: SvgView, id: String) = (view.lookup("#$id") as Group).children.single() as ImageView
            assertSame(image(first, "a").image, image(first, "b").image)
            assertNotSame(image(first, "a"), image(second, "a"))
            assertEquals(1, opens.get())
        }
    }

    @Test fun jpegDimensionsAreValidatedBeforeNativeDecodingAndRespectPixelLimit() {
        // AWT/ImageIO is test-fixture generation only; production validates bounded JPEG headers.
        val fixture = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until fixture.height) for (x in 0 until fixture.width) fixture.setRGB(x, y, 0xCC3300)
        val jpeg = java.io.ByteArrayOutputStream().use { output ->
            assertTrue(javax.imageio.ImageIO.write(fixture, "jpeg", output))
            output.toByteArray()
        }
        val opts = SvgOptions(strict = true, maxImagePixels = 6,
            resourceResolver = resolver(mapOf(base.resolve("fixture.jpg").toString() to jpeg)))
        val xml = "<svg width='3' height='2'><image id='jpeg' href='fixture.jpg' width='3' height='2'/></svg>"
        val doc = SvgLoader.parse(xml, base, opts)
        assertEquals(1, doc.resources.images.size)
        FxTest.run {
            val view = SvgView(doc)
            val image = ((view.lookup("#jpeg") as Group).children.single() as ImageView).image
            assertFalse(image.isError)
            assertEquals(3.0, image.width)
            assertEquals(2.0, image.height)
        }
        val failure = assertFailsWith<SvgException> { SvgLoader.parse(xml, base, opts.copy(maxImagePixels = 5)) }
        assertTrue(failure.message!!.contains("pixel limit"))
    }

    @Test fun truncatedJpegSofMarkersAreRejectedWithoutDecoding() {
        val malformed = listOf(
            byteArrayOf(-1, -40, -1, -64), // Missing frame-segment length.
            byteArrayOf(-1, -40, -1, -64, 0, 8, 8, 0), // Length exceeds available bytes.
            byteArrayOf(-1, -40, -1, -64, 0, 7, 8, 0, 1, 0, 1) // Too short for SOF fields.
        )
        for (jpeg in malformed) {
            val opts = SvgOptions(strict = true,
                resourceResolver = resolver(mapOf(base.resolve("truncated.jpg").toString() to jpeg)))
            val failure = assertFailsWith<SvgException> {
                SvgLoader.parse("<svg><image href='truncated.jpg'/></svg>", base, opts)
            }
            assertTrue(failure.message!!.contains("JPEG"))
        }
    }

    @Test fun sourceOptionsSnapshotResolvesLocalSourceOffFxThread() {
        val file = Files.writeString(directory.resolve("async.svg"), "<svg><style>@import 'a.css';</style><rect id='p'/></svg>")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val complete = CountDownLatch(1)
        lateinit var view: SvgView
        try {
            FxTest.run {
                view = SvgView().apply {
                    sourceOptions = SvgOptions(strict = true, resourceResolver = SvgResourceResolver {
                        assertFalse(Platform.isFxApplicationThread()); entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        "rect { fill: red }".byteInputStream()
                    })
                    documentProperty().addListener { _, _, value -> if (value != null) complete.countDown() }
                    source = file.toUri().toString()
                    sourceOptions = SvgOptions()
                }
                assertTrue(view.isLoading)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS)); release.countDown()
            assertTrue(complete.await(10, TimeUnit.SECONDS))
            FxTest.run {
                assertEquals(Color.RED, (view.lookup("#p") as Rectangle).fill)
                assertNull(view.loadException)
                assertEquals(file.toUri(), view.document!!.baseUri)
            }
        } finally { release.countDown() }
    }
}
