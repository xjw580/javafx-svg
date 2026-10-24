package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class SvgResourceCacheTest {
    @TempDir lateinit var directory: Path
    private val original = """<svg><rect id="first" width="10" height="10"/></svg>"""

    @Test fun pathAndUrlShareNormalizedDocumentsIncludingEscapedNames() {
        val file = Files.writeString(directory.resolve("icon space %.svg"), original)
        val cache = SvgResourceCache()
        val doc = cache.load(file)
        assertSame(doc, cache.load(file.toUri().toURL()))
        assertSame(doc, cache.load(directory.resolve("unused/../icon space %.svg")))
        assertEquals(1, cache.size)
        assertEquals(original.toByteArray().size.toLong(), cache.sourceBytes)
        assertNotSame(doc, SvgLoader.load(file))
    }

    @Test fun changedAndDeletedFilesCannotReturnStaleDocuments() {
        val file = Files.writeString(directory.resolve("icon.svg"), original)
        val cache = SvgResourceCache()
        val before = cache.load(file)
        Files.writeString(file, """<svg><circle id="second" r="5"/></svg>""")
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000))
        val after = cache.load(file)
        assertNotSame(before, after)
        assertTrue("second" in after.elementsById)
        Files.delete(file)
        assertFails { cache.load(file) }
    }

    @Test fun optionsHaveSeparateKeysAndCannotBypassInputLimitsOrStrictParsing() {
        val file = Files.writeString(directory.resolve("limits.svg"), """<?custom data?><svg><g/></svg>""")
        val cache = SvgResourceCache()
        val loose = cache.load(file)
        assertTrue(loose.diagnostics.isNotEmpty())
        assertFailsWith<SvgException> { cache.load(file, SvgOptions(strict = true)) }
        assertFailsWith<SvgException> { cache.load(file, SvgOptions(maxBytes = 4)) }
        assertFailsWith<SvgException> { cache.load(file, SvgOptions(maxElements = 1)) }
        val other = cache.load(file, SvgOptions(maxDepth = 2))
        assertNotSame(loose, other)
        assertSame(loose, cache.load(file))
    }

    @Test fun entryAndByteBudgetsEvictLeastRecentlyUsedDocuments() {
        val a = Files.writeString(directory.resolve("a.svg"), original)
        val b = Files.writeString(directory.resolve("b.svg"), original)
        val c = Files.writeString(directory.resolve("c.svg"), original)
        val cache = SvgResourceCache(maxEntries = 2)
        val firstA = cache.load(a)
        val firstB = cache.load(b)
        assertSame(firstA, cache.load(a))
        cache.load(c)
        assertSame(firstA, cache.load(a))
        assertNotSame(firstB, cache.load(b))
        assertEquals(2, cache.size)
        val weighted = SvgResourceCache(maxSourceBytes = original.toByteArray().size.toLong())
        weighted.load(a); weighted.load(b)
        assertEquals(1, weighted.size)
        assertEquals(original.toByteArray().size.toLong(), weighted.sourceBytes)
        val disabled = SvgResourceCache(maxEntries = 0)
        assertNotSame(disabled.load(a), disabled.load(a))
        assertEquals(0, disabled.size)
    }

    @Test fun expiryIsMeasuredFromInsertionAndClearInvalidatesAllOptions() {
        var clock = 0L
        val cache = SvgResourceCache(128, 1024, Duration.ofNanos(10)) { clock }
        val file = Files.writeString(directory.resolve("expiry.svg"), original)
        val before = cache.load(file)
        clock = 9
        assertSame(before, cache.load(file))
        clock = 10
        assertEquals(0, cache.size)
        assertEquals(0, cache.sourceBytes)
        val after = cache.load(file)
        assertNotSame(before, after)
        cache.load(file, SvgOptions(strict = true))
        cache.invalidate(file.toUri().toURL())
        assertEquals(0, cache.size)
        val fresh = cache.load(file)
        cache.clear()
        assertEquals(0, cache.sourceBytes)
        assertNotSame(fresh, cache.load(file))
    }

    @Test fun malformedFailuresAreNotRetainedAndOversizedDocumentsAreNotStored() {
        val file = Files.writeString(directory.resolve("invalid.svg"), "<svg>")
        val cache = SvgResourceCache()
        assertFailsWith<SvgException> { cache.load(file) }
        assertEquals(0, cache.size)
        Files.writeString(file, original)
        assertNotNull(cache.load(file))
        val small = SvgResourceCache(maxSourceBytes = 1)
        assertNotSame(small.load(file), small.load(file))
        assertEquals(0, small.size)
        assertEquals(0, small.sourceBytes)
    }

    @Test fun replacingLocalJarInvalidatesItsEntriesAndDoesNotKeepItLocked() {
        val jar = directory.resolve("icons space.jar")
        fun write(value: String) = JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("icon.svg"))
            it.write(value.toByteArray())
            it.closeEntry()
        }
        write(original)
        val url = URL("jar:" + jar.toUri().toASCIIString() + "!/icon.svg")
        val cache = SvgResourceCache()
        val first = cache.load(url)
        assertSame(first, cache.load(url))
        write("""<svg><circle id="changed" r="4"/></svg>""")
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 2000))
        val next = cache.load(url)
        assertNotSame(first, next)
        assertTrue("changed" in next.elementsById)
    }

    @Test fun concurrentRequestsShareOneParseButCompletedRemoteLoadsStayFresh() {
        Resource(original).use { resource ->
            val cache = SvgResourceCache()
            val first = caller { cache.load(resource.url) }
            await(resource.entered)
            val second = caller { cache.load(resource.url) }
            awaitWaiting(second.thread)
            assertEquals(1, resource.reads.get())
            resource.release.countDown()
            assertSame(first.result.get(5, TimeUnit.SECONDS), second.result.get(5, TimeUnit.SECONDS))
            assertEquals(0, cache.size)
            cache.load(resource.url)
            assertEquals(2, resource.reads.get())
        }
    }

    @Test fun cancellingOneReaderDoesNotInterruptAnotherReader() {
        Resource(original).use { resource ->
            val cache = SvgResourceCache()
            val first = caller { cache.load(resource.url) }
            await(resource.entered)
            val second = caller { cache.load(resource.url) }
            awaitWaiting(second.thread)
            first.thread.interrupt()
            assertFails { first.result.get(5, TimeUnit.SECONDS) }
            assertEquals(1, resource.interrupted.count)
            resource.release.countDown()
            assertTrue("first" in second.result.get(5, TimeUnit.SECONDS).elementsById)
            assertEquals(1, resource.reads.get())
        }
    }

    @Test fun cancellingAllReadersInterruptsWorkerAndAllowsFreshRetry() {
        Resource(original).use { resource ->
            val cache = SvgResourceCache()
            val first = caller { cache.load(resource.url) }
            await(resource.entered)
            first.thread.interrupt()
            assertFails { first.result.get(5, TimeUnit.SECONDS) }
            await(resource.interrupted)
            resource.release.countDown()
            assertNotNull(cache.load(resource.url))
            assertEquals(2, resource.reads.get())
        }
    }

    @Test fun clearAndInvalidateDoNotCancelExistingReadersOrMergeNewOnes() {
        for (invalidate in listOf(false, true)) Resource(original).use { resource ->
            val cache = SvgResourceCache()
            val first = caller { cache.load(resource.url) }
            await(resource.entered)
            if (invalidate) cache.invalidate(resource.url) else cache.clear()
            val second = caller { cache.load(resource.url) }
            awaitWaiting(second.thread)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (resource.reads.get() < 2) {
                assertTrue(System.nanoTime() < deadline)
                Thread.sleep(2)
            }
            assertEquals(2, resource.reads.get())
            resource.release.countDown()
            assertNotSame(first.result.get(5, TimeUnit.SECONDS), second.result.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failedConcurrentRequestIsSharedAndCanRetry() {
        Resource("<svg>").use { resource ->
            val cache = SvgResourceCache()
            val first = caller { cache.load(resource.url) }
            await(resource.entered)
            val second = caller { cache.load(resource.url) }
            awaitWaiting(second.thread)
            resource.release.countDown()
            assertFails { first.result.get(5, TimeUnit.SECONDS) }
            assertFails { second.result.get(5, TimeUnit.SECONDS) }
            assertEquals(1, resource.reads.get())
            resource.body = original
            assertNotNull(cache.load(resource.url))
            assertEquals(2, resource.reads.get())
        }
    }

    @Test fun clearingPendingLocalLoadPreventsItFromRepopulatingRetention() {
        val file = Files.writeString(directory.resolve("pending.svg"), original)
        Resource(original, file.toUri().toASCIIString()).use { resource ->
            val cache = SvgResourceCache()
            val caller = caller { cache.load(resource.url) }
            await(resource.entered)
            cache.clear()
            resource.release.countDown()
            val before = caller.result.get(5, TimeUnit.SECONDS)
            assertEquals(0, cache.size)
            assertNotSame(before, cache.load(file))
        }
    }

    @Test fun fileChangingDuringReadIsReturnedButNotRetained() {
        val file = Files.writeString(directory.resolve("changing.svg"), original)
        Resource(original, file.toUri().toASCIIString()).use { resource ->
            val cache = SvgResourceCache()
            val caller = caller { cache.load(resource.url) }
            await(resource.entered)
            Files.writeString(file, """<svg><circle id="changed" r="4"/></svg>""")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000))
            resource.release.countDown()
            assertTrue("first" in caller.result.get(5, TimeUnit.SECONDS).elementsById)
            assertEquals(0, cache.size)
            assertTrue("changed" in cache.load(file).elementsById)
        }
    }

    private data class Caller(val thread: Thread, val result: FutureTask<SvgDocument>)
    private fun caller(action: () -> SvgDocument): Caller {
        val task = FutureTask(action)
        return Caller(Thread.ofVirtual().start(task), task)
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.stackTrace.none { it.className == "java.util.concurrent.CompletableFuture" }) {
            assertTrue(thread.isAlive && System.nanoTime() < deadline, "Reader did not wait for shared resource")
            Thread.sleep(2)
        }
    }

    private class Resource(@Volatile var body: String, location: String = "testsvg://resource/icon.svg") : AutoCloseable {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val url = URL(null, location, object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : URLConnection(url) {
                override fun connect() {}
                override fun getInputStream(): ByteArrayInputStream {
                    reads.incrementAndGet()
                    entered.countDown()
                    try { check(release.await(5, TimeUnit.SECONDS)) }
                    catch (e: InterruptedException) { interrupted.countDown(); throw e }
                    return ByteArrayInputStream(body.toByteArray())
                }
            }
        })
        override fun close() { release.countDown() }
    }
}
