package club.xiaojiawei.svg

import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.concurrent.Task
import javafx.scene.layout.Region
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path

/** A resizable native scene-graph SVG. Construct and modify on the JavaFX application thread. */
class SvgView @JvmOverloads constructor(document: SvgDocument? = null) : Region() {
    private var renderer: FxSvgRenderer? = null
    private val documentValue = ReadOnlyObjectWrapper<SvgDocument?>(this, "document")
    private val sourceValue = ReadOnlyStringWrapper(this, "source")
    private val loadingValue = ReadOnlyBooleanWrapper(this, "loading", false)
    private val loadExceptionValue = ReadOnlyObjectWrapper<SvgException?>(this, "loadException")
    private var pendingLoad: Task<SvgDocument>? = null
    private var loadGeneration = 0L
    private val fitWidthValue = SimpleDoubleProperty(this, "fitWidth", 0.0)
    private val fitHeightValue = SimpleDoubleProperty(this, "fitHeight", 0.0)
    private val preserveRatioValue = SimpleBooleanProperty(this, "preserveRatio", true)

    var document: SvgDocument?
        get() = documentValue.get()
        set(value) { replaceDocument(value, null) }
    fun documentProperty(): ReadOnlyObjectProperty<SvgDocument?> = documentValue.readOnlyProperty

    /**
     * Absolute URL loaded using default [SvgOptions]. Network I/O and parsing run in background;
     * file and jar:file resources load synchronously. Nodes are always created on the FX thread.
     * In FXML, use `source="@icon.svg"` to resolve relative to the FXML location.
     * Null/blank clears the view and cancels pending work. Async failures preserve the old document
     * and nodes, retain the requested source and report [loadException]. Local failures throw.
     * Assigning [document] directly clears source; [sourceProperty] is for observation only.
     */
    var source: String?
        get() = sourceValue.get()
        set(value) {
            checkFxThread()
            val location = value?.takeUnless { it.isBlank() }
            if (location == null) {
                replaceDocument(null, null)
                return
            }
            val url = try { URI(location).toURL() }
                catch (e: Exception) { throw sourceException(location, e) }
            if (url.protocol == "file" || (url.protocol == "jar" && url.path.startsWith("file:"))) {
                val loaded = try { SvgLoader.load(url) }
                    catch (e: Exception) { throw sourceException(location, e) }
                replaceDocument(loaded, location)
            } else {
                loadInBackground(location, url)
            }
        }
    fun sourceProperty(): ReadOnlyStringProperty = sourceValue.readOnlyProperty

    /** True while the current remote source is being downloaded, parsed or installed. */
    val isLoading: Boolean get() = loadingValue.get()
    fun loadingProperty(): ReadOnlyBooleanProperty = loadingValue.readOnlyProperty

    /** Last asynchronous load/render failure; cleared when a new source or document is accepted. */
    val loadException: SvgException? get() = loadExceptionValue.get()
    fun loadExceptionProperty(): ReadOnlyObjectProperty<SvgException?> = loadExceptionValue.readOnlyProperty

    private fun cancelPendingLoad(): Long {
        val previous = pendingLoad
        pendingLoad = null
        val generation = ++loadGeneration
        previous?.cancel(true)
        return generation
    }

    private fun loadInBackground(location: String, url: URL) {
        val generation = cancelPendingLoad()
        val task = object : Task<SvgDocument>() {
            override fun call(): SvgDocument = SvgLoader.load(url)
        }
        pendingLoad = task
        task.setOnSucceeded {
            if (generation == loadGeneration) {
                try { installDocument(task.value, createRenderer(task.value)) }
                catch (e: Exception) { failLoad(generation, location, e) }
                finishLoad(generation)
            }
        }
        task.setOnFailed { failLoad(generation, location, task.exception) }
        sourceValue.set(location)
        if (generation != loadGeneration) return
        loadExceptionValue.set(null)
        if (generation != loadGeneration) return
        loadingValue.set(true)
        if (generation != loadGeneration) return
        // Java 21 virtual threads are daemon threads; cancellation interrupts their blocking I/O.
        try { Thread.ofVirtual().name("javafx-svg-loader").start(task) }
        catch (e: Exception) { failLoad(generation, location, e) }
    }

    private fun failLoad(generation: Long, location: String, cause: Throwable) {
        if (generation != loadGeneration) return
        loadExceptionValue.set(sourceException(location, cause))
        finishLoad(generation)
    }

    private fun finishLoad(generation: Long) {
        if (generation != loadGeneration) return
        pendingLoad = null
        loadingValue.set(false)
    }

    private fun sourceException(location: String, cause: Throwable) =
        SvgException("Cannot load SVG source '$location': ${cause.message}", cause)

    private fun createRenderer(value: SvgDocument?): FxSvgRenderer? =
        try { value?.let(::FxSvgRenderer) }
        catch (e: IllegalArgumentException) { throw SvgException("Cannot render SVG: ${e.message}", e) }

    private fun replaceDocument(value: SvgDocument?, source: String?) {
        checkFxThread()
        val replacement = createRenderer(value)
        val generation = cancelPendingLoad()
        sourceValue.set(source)
        if (generation != loadGeneration) return
        loadExceptionValue.set(null)
        if (generation != loadGeneration) return
        installDocument(value, replacement)
        finishLoad(generation)
    }

    private fun installDocument(value: SvgDocument?, replacement: FxSvgRenderer?) {
        renderer = replacement
        children.setAll(listOfNotNull(replacement?.root))
        requestLayout()
        documentValue.set(value)
    }

    /** Zero uses the document's natural size. Parent layout may still resize this Region. */
    var fitWidth: Double
        get() = fitWidthValue.get()
        set(value) { require(value.isFinite() && value >= 0); fitWidthValue.set(value) }
    fun fitWidthProperty(): DoubleProperty = fitWidthValue

    var fitHeight: Double
        get() = fitHeightValue.get()
        set(value) { require(value.isFinite() && value >= 0); fitHeightValue.set(value) }
    fun fitHeightProperty(): DoubleProperty = fitHeightValue

    var preserveRatio: Boolean
        get() = preserveRatioValue.get()
        set(value) { preserveRatioValue.set(value) }
    fun preserveRatioProperty(): BooleanProperty = preserveRatioValue

    val diagnostics: List<SvgDiagnostic> get() = renderer?.diagnostics ?: emptyList()

    init {
        checkFxThread()
        styleClass += "svg-view"
        isPickOnBounds = true
        fitWidthValue.addListener { _ -> requestLayout() }
        fitHeightValue.addListener { _ -> requestLayout() }
        preserveRatioValue.addListener { _ -> requestLayout() }
        this.document = document
    }

    override fun computeMinWidth(height: Double): Double = insets.left + insets.right
    override fun computeMinHeight(width: Double): Double = insets.top + insets.bottom
    override fun computePrefWidth(height: Double): Double {
        val naturalWidth = renderer?.intrinsicWidth ?: 0.0
        val naturalHeight = renderer?.intrinsicHeight ?: 0.0
        val value = if (fitWidth > 0) fitWidth
            else if (fitHeight > 0 && preserveRatio && naturalHeight > 0) fitHeight * naturalWidth / naturalHeight
            else naturalWidth
        return value + insets.left + insets.right
    }
    override fun computePrefHeight(width: Double): Double {
        val naturalWidth = renderer?.intrinsicWidth ?: 0.0
        val naturalHeight = renderer?.intrinsicHeight ?: 0.0
        val value = if (fitHeight > 0) fitHeight
            else if (fitWidth > 0 && preserveRatio && naturalWidth > 0) fitWidth * naturalHeight / naturalWidth
            else naturalHeight
        return value + insets.top + insets.bottom
    }

    override fun layoutChildren() {
        val render = renderer ?: return
        render.root.layoutX = insets.left
        render.root.layoutY = insets.top
        render.layout((width - insets.left - insets.right).coerceAtLeast(0.0),
            (height - insets.top - insets.bottom).coerceAtLeast(0.0), preserveRatio)
    }

    companion object {
        private fun checkFxThread() {
            check(Platform.isFxApplicationThread()) { "SvgView must be used on the JavaFX application thread; parse with SvgLoader in background threads" }
        }
        @JvmStatic @JvmOverloads
        fun parse(xml: String, options: SvgOptions = SvgOptions()): SvgView = SvgView(SvgLoader.parse(xml, options))
        @JvmStatic @JvmOverloads
        fun load(path: Path, options: SvgOptions = SvgOptions()): SvgView = SvgView(SvgLoader.load(path, options))
        @JvmStatic @JvmOverloads
        fun load(url: URL, options: SvgOptions = SvgOptions()): SvgView = SvgView(SvgLoader.load(url, options))
        @JvmStatic @JvmOverloads
        fun load(input: InputStream, options: SvgOptions = SvgOptions()): SvgView = SvgView(SvgLoader.load(input, options))
    }
}
