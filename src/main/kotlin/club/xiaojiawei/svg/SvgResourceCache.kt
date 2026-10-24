package club.xiaojiawei.svg

import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Bounded, thread-safe local resource cache with in-flight request sharing for all URL protocols.
 * Contains documents only. Loading is blocking; call remote loads on a background thread.
 * HTTP/other remote documents are shared only while loading, never retained after completion.
 */
class SvgResourceCache internal constructor(
    val maxEntries: Int,
    val maxSourceBytes: Long,
    val expireAfter: Duration,
    private val nanoTime: () -> Long
) {
    @JvmOverloads
    constructor(
        maxEntries: Int = 128,
        maxSourceBytes: Long = 16L * 1024 * 1024,
        expireAfter: Duration = Duration.ofMinutes(5)
    ) : this(maxEntries, maxSourceBytes, expireAfter, System::nanoTime)

    init {
        require(maxEntries >= 0)
        require(maxSourceBytes >= 0)
        require(!expireAfter.isNegative)
    }

    private val ttlNanos = expireAfter.toNanos()
    private val lock = Any()
    private data class Key(val location: String, val options: SvgOptions)
    private data class Version(val size: Long, val modified: FileTime, val fileKey: Any?)
    private data class Entry(val document: SvgDocument, val version: Version, val created: Long)
    private class Flight(val version: Version?) {
        val result = CompletableFuture<SvgDocument>()
        lateinit var worker: Thread
        var readers = 0
    }
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private val flights = mutableMapOf<Key, Flight>()
    private var bytes = 0L

    /** Retained documents; expired entries are removed before reporting. */
    val size: Int get() = synchronized(lock) { expire(); entries.size }

    /** Sum of original SVG input bytes, not the parsed documents' JVM heap size. */
    val sourceBytes: Long get() = synchronized(lock) { expire(); bytes }

    @JvmOverloads
    fun load(path: Path, options: SvgOptions = SvgOptions()): SvgDocument =
        load(path.toAbsolutePath().normalize().toUri().toURL(), options)

    @JvmOverloads
    fun load(url: URL, options: SvgOptions = SvgOptions()): SvgDocument {
        val localFile = localFile(url)
        val location = if (url.protocol == "file" && localFile != null)
            localFile.toUri().toASCIIString() else url.toExternalForm()
        val key = Key(location, options)
        // Metadata I/O happens outside the cache lock. URL.equals/hashCode are never used.
        val version = localFile?.let(::version)
        val flight = synchronized(lock) {
            expire()
            val cached = entries[key]
            if (cached != null && cached.version == version) return cached.document
            if (cached != null) remove(key)
            val current = flights[key]
            val selected = if (current != null && current.version == version) current else {
                Flight(version).also { created ->
                    created.worker = Thread.ofVirtual().name("javafx-svg-resource").unstarted {
                        complete(key, created, url, localFile)
                    }
                    flights[key] = created
                    try { created.worker.start() }
                    catch (failure: Throwable) {
                        flights.remove(key)
                        created.result.completeExceptionally(failure)
                    }
                }
            }
            selected.readers++
            selected
        }
        try {
            return flight.result.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SvgException("SVG resource load interrupted", failure)
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        } finally {
            synchronized(lock) {
                flight.readers--
                if (flight.readers == 0 && !flight.result.isDone) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.result.cancel(false)
                    flight.worker.interrupt()
                }
            }
        }
    }

    private fun complete(key: Key, flight: Flight, url: URL, localFile: Path?) {
        try {
            val document = SvgLoader.load(url, key.options)
            // Don't retain a document if its file changed while being read.
            val after = if (localFile != null) try { version(localFile) } catch (_: Exception) { null } else null
            val unchanged = after != null && flight.version == after
            synchronized(lock) {
                if (flights[key] === flight) {
                    flights.remove(key)
                    if (unchanged && maxEntries > 0 && ttlNanos > 0 && document.sourceBytes <= maxSourceBytes) {
                        remove(key)
                        entries[key] = Entry(document, checkNotNull(flight.version), nanoTime())
                        bytes += document.sourceBytes
                        while (entries.size > maxEntries || bytes > maxSourceBytes) remove(entries.keys.first())
                    }
                }
                flight.result.complete(document)
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (flights[key] === flight) flights.remove(key)
                flight.result.completeExceptionally(failure)
            }
        }
    }

    /** Clear retention and prevent pending loads from repopulating the cache. Existing readers continue. */
    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0
        flights.clear()
    }

    fun invalidate(path: Path) = invalidate(path.toAbsolutePath().normalize().toUri().toURL())

    /** Evict every options variant. Pending readers continue, but new loads won't join their requests. */
    fun invalidate(url: URL) {
        val file = localFile(url)
        val location = if (url.protocol == "file" && file != null) file.toUri().toASCIIString() else url.toExternalForm()
        synchronized(lock) {
            entries.keys.filter { it.location == location }.forEach(::remove)
            flights.keys.removeAll { it.location == location }
        }
    }

    private fun expire() {
        val now = nanoTime()
        entries.entries.removeIf { (_, entry) ->
            (now - entry.created >= ttlNanos).also { expired ->
                if (expired) bytes -= entry.document.sourceBytes
            }
        }
    }

    private fun remove(key: Key) {
        entries.remove(key)?.let { bytes -= it.document.sourceBytes }
    }

    private fun version(path: Path): Version {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        return Version(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
    }

    private fun localFile(url: URL): Path? = when {
        url.protocol == "file" -> {
            val uri = url.toURI()
            Path.of(URI(uri.scheme, uri.authority, uri.path, null, null)).toAbsolutePath().normalize()
        }
        url.protocol == "jar" -> {
            val archive = (url.openConnection() as? JarURLConnection)?.jarFileURL
            if (archive?.protocol == "file") localFile(archive) else null
        }
        else -> null
    }

    companion object {
        /** Shared instance; caches may also be scoped to an application or component. */
        @JvmField val shared = SvgResourceCache()
    }
}
