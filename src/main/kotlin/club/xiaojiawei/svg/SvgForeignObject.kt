package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeType
import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import javafx.scene.text.TextAlignment
import javafx.scene.text.TextFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Native, deliberately bounded XHTML layout, not a browser. Only div blocks, span runs and br are
 * accepted. Inline declarations are interpreted here, never passed to JavaFX CSS or an HTML engine.
 * There are no resource loads, executable attributes, controls, scripts, links or external documents.
 * SVG2's positioning rectangle is a containing block, not the auto-height of its first HTML child.
 */
internal class SvgForeignObject(
    private val owner: SvgElement,
    inherited: Map<String, String>,
    private val allocate: (Int) -> Unit,
    private val report: (String, SvgElement) -> Unit
) : Region() {
    private val contents = Group().also { allocate(1); it.isAutoSizeChildren = false }
    private val viewportClip = Rectangle().also { allocate(1) }
    private val blocks = mutableListOf<Block>()
    private val inheritedStyle = initial + inherited.filterKeys { it in inheritedProperties }
    private val styles = java.util.IdentityHashMap<SvgElement, Map<String, String>>()
    private val textIds = mutableSetOf<String>()

    init {
        isManaged = false
        isPickOnBounds = false
        children += contents
        owner.attributes.keys.filter { it !in foreignAttributes }.forEach {
            report("Unsupported foreignObject attribute: $it", owner)
        }
        owner.content.forEach { part ->
            when (part) {
                is SvgContent.Characters -> if (part.value.isNotBlank()) report("foreignObject requires XHTML div content", owner)
                is SvgContent.Child -> if (part.element.name !in metadata) {
                    if (part.element.name == XHTML + "div") {
                        prepare(part.element, inheritedStyle)
                        block(part.element)?.let { blocks += it; contents.children += it.node }
                    } else report("Unsupported foreignObject element: ${part.element.name}", part.element)
                }
            }
        }
    }

    /** Called in the renderer's geometry phase, before filters, masks and objectBoundingBox uses. */
    fun position(x: Double, y: Double, width: Double, height: Double, overflow: String) {
        require(listOf(x, y, width, height).all { it.isFinite() } && width >= 0 && height >= 0) {
            "Invalid foreignObject positioning rectangle"
        }
        require(overflow in setOf("visible", "hidden", "clip")) { "Unsupported foreignObject overflow: $overflow" }
        layoutX = x; layoutY = y
        resize(width, height)
        // Visibility is inherited by HTML paint/text; a descendant may explicitly make itself visible.
        isVisible = width > 0 && height > 0
        viewportClip.width = width; viewportClip.height = height
        clip = if (overflow == "visible") null else viewportClip
        var top = 0.0
        for (block in blocks) {
            block.node.layoutY = top
            top += block.layout(width, height)
            require(top.isFinite()) { "Non-finite foreignObject layout" }
        }
    }

    fun geometryBounds(): Bounds = BoundingBox(0.0, 0.0, width, height)

    // Child geometry is resolved synchronously above, rather than deferred to a JavaFX layout pulse.
    override fun layoutChildren() = Unit

    private data class Piece(val value: String, val style: Map<String, String>, val owner: SvgElement, val breakLine: Boolean = false)
    private interface Row {
        val node: Node
        fun layout(width: Double, height: Double?): Double
    }

    private inner class Flow(pieces: List<Piece>, private val style: Map<String, String>) : Row {
        private val owner = pieces.first().owner
        override val node = TextFlow().also { allocate(1); it.isManaged = false; it.isSnapToPixel = false }
        private val wrap = style.getValue("white-space") !in setOf("nowrap", "pre")
        private val minimumWordWidth by lazy { unbreakableWidth(node.children.filterIsInstance<Text>()) }
        init {
            node.styleClass += "svg-xhtml-flow"
            node.textAlignment = when (style.getValue("text-align")) {
                "center" -> TextAlignment.CENTER
                "right", "end" -> TextAlignment.RIGHT
                else -> TextAlignment.LEFT
            }
            normalized(pieces, style.getValue("white-space")).forEach { piece ->
                allocate(1)
                node.children += Text(piece.value).apply {
                    if (piece.owner.name == XHTML + "span") piece.owner.id?.let { if (textIds.add(it)) id = it }
                    styleClass += "svg-xhtml-text"
                    piece.owner["class"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { styleClass.addAll(it) }
                    fill = colour(piece.style.getValue("color"), Color.BLACK)
                    font = font(piece.style)
                    isVisible = piece.style.getValue("visibility") == "visible"
                }
            }
        }
        override fun layout(width: Double, height: Double?): Double {
            if (wrap && node.prefWidth(-1.0) > width && minimumWordWidth > width + 0.000001) {
                report("Unsupported foreignObject wrapping: unbreakable text exceeds the line width; use white-space:nowrap or pre", owner)
            }
            val actualWidth = if (wrap) width else max(width, node.prefWidth(-1.0))
            // JavaFX's zero-width TextFlow means unconstrained; use a nonzero width for CSS wrapping.
            val flowWidth = max(actualWidth, 0.000001)
            val measured = if (node.children.isEmpty()) 0.0 else node.prefHeight(flowWidth)
            require(measured.isFinite() && actualWidth.isFinite()) { "Non-finite foreignObject text layout" }
            node.layoutX = when (node.textAlignment) {
                TextAlignment.CENTER -> (width - actualWidth) / 2
                TextAlignment.RIGHT -> width - actualWidth
                else -> 0.0
            }
            node.resize(flowWidth, measured)
            node.requestLayout(); node.layout()
            return measured
        }
    }

    private inner class Block(private val style: Map<String, String>, element: SvgElement) : Row {
        override val node = Group().also { allocate(1); it.isAutoSizeChildren = false; it.isManaged = false }
        private val paint = Rectangle().also { allocate(1); it.strokeType = StrokeType.INSIDE }
        private val body = Group().also { allocate(1); it.isAutoSizeChildren = false }
        private val contentClip = Rectangle().also { allocate(1) }
        private val rows = mutableListOf<Row>()
        private val fontSize = style.getValue("font-size").toDouble()
        init {
            node.id = element.id
            node.styleClass += "svg-xhtml-div"
            element["class"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { node.styleClass.addAll(it) }
            paint.styleClass += "svg-xhtml-box"
            node.children.addAll(paint, body)
            val current = mutableListOf<Piece>()
            fun flush() {
                if (current.isNotEmpty()) {
                    val row = Flow(current.toList(), style)
                    if (row.node.children.isNotEmpty()) { rows += row; body.children += row.node }
                    current.clear()
                }
            }
            element.content.forEach { part ->
                when (part) {
                    is SvgContent.Characters -> current += Piece(part.value, style, element)
                    is SvgContent.Child -> when (part.element.name) {
                        XHTML + "div" -> block(part.element)?.let { flush(); rows += it; body.children += it.node }
                        else -> inline(part.element, current)
                    }
                }
            }
            flush()
        }
        override fun layout(width: Double, height: Double?): Double {
            val border = if (style.getValue("border-style") == "none") 0.0 else dimension(style.getValue("border-width"), width, fontSize)
            val top = dimension(style.getValue("padding-top"), width, fontSize)
            val right = dimension(style.getValue("padding-right"), width, fontSize)
            val bottom = dimension(style.getValue("padding-bottom"), width, fontSize)
            val left = dimension(style.getValue("padding-left"), width, fontSize)
            val horizontal = left + right + 2 * border
            val vertical = top + bottom + 2 * border
            val borderBox = style.getValue("box-sizing") == "border-box"
            val declaredWidth = style.getValue("width").takeUnless { it == "auto" }?.let { dimension(it, width, fontSize) }
            val contentWidth = max(0.0, if (declaredWidth == null) width - horizontal else declaredWidth - if (borderBox) horizontal else 0.0)
            val outerWidth = contentWidth + horizontal
            val declaredHeight = style.getValue("height").takeUnless { it == "auto" || it.endsWith('%') && height == null }
                ?.let { dimension(it, height ?: 0.0, fontSize) }
            val contentHeight = declaredHeight?.let { max(0.0, it - if (borderBox) vertical else 0.0) }
            var naturalHeight = 0.0
            for (row in rows) {
                row.node.layoutY = naturalHeight
                naturalHeight += row.layout(contentWidth, contentHeight)
            }
            val outerHeight = (contentHeight ?: naturalHeight) + vertical
            require(listOf(outerWidth, outerHeight, naturalHeight).all { it.isFinite() }) { "Non-finite foreignObject box layout" }
            val radius = min(dimension(style.getValue("border-radius"), min(outerWidth, outerHeight), fontSize), min(outerWidth, outerHeight) / 2)
            paint.width = outerWidth; paint.height = outerHeight
            paint.arcWidth = 2 * radius; paint.arcHeight = 2 * radius
            val color = colour(style.getValue("color"), Color.BLACK)
            paint.fill = colour(style.getValue("background-color"), color)
            paint.stroke = if (border > 0) colour(style.getValue("border-color"), color) else null
            paint.strokeWidth = border
            paint.isVisible = style.getValue("visibility") == "visible"
            body.layoutX = border + left; body.layoutY = border + top
            if (style.getValue("overflow") != "visible") {
                contentClip.x = -left; contentClip.y = -top
                contentClip.width = max(0.0, outerWidth - 2 * border)
                contentClip.height = max(0.0, outerHeight - 2 * border)
                contentClip.arcWidth = 2 * max(0.0, radius - border)
                contentClip.arcHeight = contentClip.arcWidth
                body.clip = contentClip
            } else body.clip = null
            return outerHeight
        }
    }

    /** Validate even display:none subtrees. Unsupported/unsafe content is never silently accepted. */
    private fun prepare(element: SvgElement, parent: Map<String, String>) {
        if (element.name !in setOf(XHTML + "div", XHTML + "span", XHTML + "br")) {
            report("Unsupported foreignObject element: ${element.name}", element)
            return
        }
        attributes(element)
        val style = style(element, parent, element.name == XHTML + "div")
        styles[element] = style
        element.children.forEach { prepare(it, style) }
    }

    private fun block(element: SvgElement): Block? {
        val style = styles.getValue(element)
        return if (style.getValue("display") == "none") null else Block(style, element)
    }

    private fun inline(element: SvgElement, pieces: MutableList<Piece>) {
        if (element.name !in setOf(XHTML + "span", XHTML + "br")) {
            report("Unsupported foreignObject element: ${element.name}", element)
            return
        }
        val style = styles.getValue(element)
        if (style.getValue("display") == "none") return
        if (element.name == XHTML + "br") {
            if (element.content.isNotEmpty()) report("foreignObject br must be empty", element)
            pieces += Piece("\n", style, element, true)
            return
        }
        element.content.forEach { part ->
            when (part) {
                is SvgContent.Characters -> pieces += Piece(part.value, style, element)
                is SvgContent.Child -> inline(part.element, pieces)
            }
        }
    }

    private fun attributes(element: SvgElement) {
        element.attributes.keys.filter { it !in htmlAttributes }.forEach {
            report("Unsupported foreignObject XHTML attribute: $it", element)
        }
    }

    private fun style(element: SvgElement, parent: Map<String, String>, block: Boolean): Map<String, String> {
        val result = (initial + parent.filterKeys { it in inheritedProperties }).toMutableMap()
        result["display"] = if (block) "block" else "inline"
        val declarations = try { SvgCssSyntax.split(SvgCssSyntax.withoutComments(element["style"] ?: ""), ';') }
        catch (e: IllegalArgumentException) { report("Invalid foreignObject CSS: ${e.message}", element); emptyList() }
        val important = mutableSetOf<String>()
        fun add(name: String, value: String, priority: Boolean) {
            if (!priority && name in important) return
            result[name] = value
            if (priority) important += name
        }
        for (declaration in declarations) {
            if (declaration.isBlank()) continue
            try {
                val colon = declaration.indexOf(':')
                require(colon > 0) { "Invalid declaration: $declaration" }
                val name = declaration.substring(0, colon).trim().lowercase()
                require(name in supportedProperties && (block || name in inlineProperties)) { "Unsupported property: $name" }
                val raw = declaration.substring(colon + 1).trim()
                val priority = importantSuffix.containsMatchIn(raw)
                val value = raw.replace(importantSuffix, "").trim()
                require(value.isNotEmpty() && '\\' !in value) { "Unsupported value for $name: $value" }
                val expanded = expand(name, value)
                // Validate the complete shorthand before installing any of its declarations.
                expanded.forEach { (key, v) -> validate(key, v, block) }
                expanded.forEach { (key, v) ->
                    val computed = when (v.lowercase()) {
                        "inherit" -> parent.getValue(key)
                        "initial" -> initial.getValue(key)
                        "unset" -> if (key in inheritedProperties) parent.getValue(key) else initial.getValue(key)
                        else -> v
                    }
                    if (key == "display") validate(key, computed, block)
                    add(key, computed, priority)
                }
            } catch (e: IllegalArgumentException) { report("Invalid foreignObject CSS: ${e.message}", element) }
        }
        val parentSize = parent.getValue("font-size").toDouble()
        result["font-size"] = if (result["font-size"] == parent["font-size"]) parent["font-size"]!!
            else dimension(result.getValue("font-size"), parentSize, parentSize).toString()
        // em lengths inherit as computed absolute lengths, not as tokens re-resolved with a
        // descendant's font. Percentages intentionally remain relative until box layout.
        val fontSize = result.getValue("font-size").toDouble()
        boxLengths.forEach { name ->
            val value = result.getValue(name)
            if (value.endsWith("em", true)) result[name] = dimension(value, 0.0, fontSize).toString() + "px"
        }
        if (result["border-style"] == "none") result["border-width"] = "0"
        if (result["color"].equals("currentcolor", true)) result["color"] = parent.getValue("color")
        return result
    }

    private fun expand(name: String, value: String): Map<String, String> {
        val lower = value.lowercase()
        val keys = when (name) {
            "background" -> listOf("background-color")
            "padding" -> listOf("padding-top", "padding-right", "padding-bottom", "padding-left")
            "border" -> listOf("border-width", "border-style", "border-color")
            "font" -> listOf("font-style", "font-weight", "font-size", "font-family")
            else -> listOf(name)
        }
        if (lower in globals) return keys.associateWith { lower }
        return when (name) {
            "background" -> mapOf("background-color" to value)
            "padding" -> {
                val parts = SvgCssSyntax.split(value, null)
                require(parts.size in 1..4) { "Unsupported padding: $value" }
                mapOf("padding-top" to parts[0], "padding-right" to parts.getOrElse(1) { parts[0] },
                    "padding-bottom" to parts.getOrElse(2) { parts[0] }, "padding-left" to parts.getOrElse(3) { parts.getOrElse(1) { parts[0] } })
            }
            "border" -> {
                val parts = SvgCssSyntax.split(value, null)
                var width = "3px"; var type = "none"; var color = "currentcolor"
                val used = mutableSetOf<String>()
                for (part in parts) {
                    val kind = when {
                        part.lowercase() in setOf("solid", "none") -> "style"
                        cssLength.matches(part) -> "width"
                        else -> "color"
                    }
                    require(used.add(kind)) { "Unsupported border: $value" }
                    when (kind) { "style" -> type = part.lowercase(); "width" -> width = part; else -> color = part }
                }
                mapOf("border-width" to width, "border-style" to type, "border-color" to color)
            }
            "font" -> {
                val parts = SvgCssSyntax.split(value, null)
                val size = parts.indexOfFirst { cssLength.matches(it) && (it.any { c -> c.isLetter() || c == '%' } || it == "0") }
                require(size >= 0 && size < parts.lastIndex) { "Unsupported font shorthand: $value" }
                var weight = "normal"; var posture = "normal"
                val used = mutableSetOf<String>()
                for (part in parts.take(size)) when (part.lowercase()) {
                    "normal" -> Unit
                    "italic", "oblique" -> { require(used.add("style")) { "Invalid font shorthand" }; posture = part.lowercase() }
                    "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900" -> {
                        require(used.add("weight")) { "Invalid font shorthand" }; weight = part.lowercase()
                    }
                    else -> throw SvgException("Unsupported font shorthand: $value")
                }
                mapOf("font-style" to posture, "font-weight" to weight, "font-size" to parts[size], "font-family" to parts.drop(size + 1).joinToString(" "))
            }
            else -> mapOf(name to if (name in keywordProperties) lower else value)
        }
    }

    private fun validate(name: String, value: String, block: Boolean) {
        if (value.lowercase() in globals) return
        when (name) {
            "background-color", "border-color", "color" -> colour(value, Color.BLACK)
            "font-size", "padding-top", "padding-right", "padding-bottom", "padding-left" -> validateLength(value)
            "border-width", "border-radius" -> { validateLength(value); require(!value.endsWith('%')) { "Percentage $name is unsupported" } }
            "width", "height" -> if (value != "auto") validateLength(value)
            "font-weight" -> require(value in setOf("normal", "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900")) { "Unsupported font-weight: $value" }
            "font-style" -> require(value in setOf("normal", "italic", "oblique")) { "Unsupported font-style: $value" }
            "font-family" -> {
                val families = SvgCssSyntax.split(value)
                require(families.isNotEmpty() && families.all { family ->
                    Regex("(?:[a-zA-Z][a-zA-Z0-9 -]*|'[^'\\\\]+'|\"[^\"\\\\]+\")").matches(family)
                }) { "Unsupported font-family: $value" }
            }
            "box-sizing" -> require(value in setOf("border-box", "content-box")) { "Unsupported box-sizing: $value" }
            "border-style" -> require(value in setOf("solid", "none")) { "Unsupported border-style: $value" }
            "text-align" -> require(value in setOf("left", "center", "right", "start", "end")) { "Unsupported text-align: $value" }
            "white-space" -> require(value in setOf("normal", "nowrap", "pre", "pre-wrap")) { "Unsupported white-space: $value" }
            "overflow" -> require(value in setOf("visible", "hidden", "clip")) { "Unsupported overflow: $value" }
            "visibility" -> require(value in setOf("visible", "hidden")) { "Unsupported visibility: $value" }
            "display" -> require(value == "none" || value == if (block) "block" else "inline") { "Unsupported display: $value" }
        }
    }

    private fun validateLength(value: String) {
        val match = cssLength.matchEntire(value) ?: throw SvgException("Unsupported CSS length: $value")
        val amount = match.groupValues[1].toDouble()
        require(amount.isFinite() && amount >= 0 && (amount == 0.0 || match.groupValues[2].isNotEmpty())) { "Invalid CSS length: $value" }
    }

    private fun dimension(value: String, reference: Double, em: Double): Double =
        (if (value.endsWith("em", true)) SvgNumbers.scalar(value.dropLast(2)) * em else SvgNumbers.length(value, reference))
            .also { require(it.isFinite() && it >= 0) { "Invalid foreignObject dimension: $value" } }

    private fun colour(value: String, current: Color): Color = if (value.equals("currentcolor", true)) current else Color.web(value)

    private fun font(style: Map<String, String>): Font {
        val candidates = SvgCssSyntax.split(style.getValue("font-family")).map { it.trim('"', '\'') }
        val chosen = candidates.firstOrNull { it.lowercase() in genericFamilies || it.lowercase() in installedFamilies } ?: candidates.last()
        val family = genericFamilies[chosen.lowercase()] ?: chosen
        val weight = when (val value = style.getValue("font-weight")) {
            "normal" -> FontWeight.NORMAL; "bold" -> FontWeight.BOLD
            else -> FontWeight.findByWeight(value.toInt())
        }
        return Font.font(family, weight, if (style.getValue("font-style") == "normal") FontPosture.REGULAR else FontPosture.ITALIC,
            max(0.000001, style.getValue("font-size").toDouble()))
    }

    /**
     * JavaFX emergency-wraps overlong words, unlike CSS normal/pre-wrap. Diagnose that boundary
     * rather than silently claiming browser line-breaking semantics. Use the platform Unicode line
     * iterator (including CJK and NBSP rules) and native rich-text metrics, not character estimates.
     * Temporary metric nodes also consume the bounded expanded-node budget, once per flow.
     */
    private fun unbreakableWidth(runs: List<Text>): Double {
        if (runs.isEmpty()) return 0.0
        allocate(1)
        val measure = TextFlow().apply { isSnapToPixel = false }
        val text = runs.joinToString("") { it.text }
        val breaks = java.text.BreakIterator.getLineInstance(java.util.Locale.ROOT).apply { setText(text) }
        val starts = IntArray(runs.size + 1)
        runs.forEachIndexed { index, run -> starts[index + 1] = starts[index] + run.text.length }
        var start = breaks.first()
        var end = breaks.next()
        var runIndex = 0
        var widest = 0.0
        while (end != java.text.BreakIterator.DONE) {
            var first = start; var last = end
            while (first < last && text[first] in " \t\r\n\u000C") first++
            while (last > first && text[last - 1] in " \t\r\n\u000C") last--
            if (first < last) {
                while (runIndex < runs.size && starts[runIndex + 1] <= first) runIndex++
                var index = runIndex
                measure.children.clear()
                while (index < runs.size && starts[index] < last) {
                    val from = max(first, starts[index]) - starts[index]
                    val to = min(last, starts[index + 1]) - starts[index]
                    if (from < to) {
                        allocate(1)
                        measure.children += Text(runs[index].text.substring(from, to)).apply { font = runs[index].font }
                    }
                    index++
                }
                widest = max(widest, measure.prefWidth(-1.0))
            }
            start = end; end = breaks.next()
        }
        return widest
    }

    private fun normalized(pieces: List<Piece>, mode: String): List<Piece> {
        if (mode == "pre" || mode == "pre-wrap") return withoutPhantomFinalLine(pieces.map {
            it.copy(value = it.value.replace("\r\n", "\n").replace('\r', '\n'))
        })
        val result = mutableListOf<Piece>()
        var atStart = true
        var space = false
        for (piece in pieces) {
            if (piece.breakLine) {
                trimTrailingSpace(result)
                result += piece
                atStart = true; space = false
                continue
            }
            val value = StringBuilder()
            for (char in piece.value) {
                if (char in " \t\r\n\u000C") {
                    if (!atStart && !space) { value.append(' '); space = true }
                } else { value.append(char); atStart = false; space = false }
            }
            if (value.isNotEmpty()) result += piece.copy(value = value.toString())
        }
        trimTrailingSpace(result)
        return withoutPhantomFinalLine(result.filter { it.value.isNotEmpty() })
    }

    private fun withoutPhantomFinalLine(pieces: List<Piece>): List<Piece> {
        if (pieces.lastOrNull()?.breakLine != true) return pieces
        // TextFlow treats a terminal newline as an additional empty line; an HTML br merely
        // terminates its current line. A lone br still needs that one (empty) line box.
        return if (pieces.size > 1) pieces.dropLast(1)
        else listOf(pieces.single().copy(value = "\u200b", breakLine = false))
    }

    private fun trimTrailingSpace(pieces: MutableList<Piece>) {
        if (pieces.isNotEmpty() && pieces.last().value.endsWith(' ')) {
            pieces[pieces.lastIndex] = pieces.last().copy(value = pieces.last().value.dropLast(1))
        }
    }

    private companion object {
        const val XHTML = "{http://www.w3.org/1999/xhtml}"
        val metadata = setOf("title", "desc", "metadata")
        val htmlAttributes = setOf("id", "class", "style", "title", "lang", "xml:lang")
        val foreignAttributes = SvgStyles.defaults.keys + setOf("id", "class", "style", "x", "y", "width", "height", "xml:lang", "lang")
        val inheritedProperties = setOf("color", "font-size", "font-family", "font-weight", "font-style", "text-align", "white-space", "visibility")
        val inlineProperties = setOf("color", "font-size", "font-family", "font-weight", "font-style", "font", "visibility", "display")
        val keywordProperties = setOf("font-weight", "font-style", "box-sizing", "border-style", "text-align", "white-space", "overflow", "display", "visibility", "width", "height")
        val initial = mapOf(
            "color" to "black", "font-size" to "16", "font-family" to "sans-serif", "font-weight" to "normal", "font-style" to "normal",
            "text-align" to "left", "white-space" to "normal", "visibility" to "visible", "display" to "inline",
            "width" to "auto", "height" to "auto", "box-sizing" to "content-box", "overflow" to "visible",
            "background-color" to "transparent", "border-width" to "3px", "border-style" to "none", "border-color" to "currentcolor", "border-radius" to "0",
            "padding-top" to "0", "padding-right" to "0", "padding-bottom" to "0", "padding-left" to "0"
        )
        val boxLengths = setOf("width", "height", "border-width", "border-radius", "padding-top", "padding-right", "padding-bottom", "padding-left")
        val supportedProperties = initial.keys + setOf("background", "border", "padding", "font")
        val globals = setOf("inherit", "initial", "unset")
        val cssLength = Regex("([+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(px|em|%|in|cm|mm|pt|pc|q)?", RegexOption.IGNORE_CASE)
        val importantSuffix = Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE)
        val genericFamilies = mapOf("sans-serif" to "SansSerif", "serif" to "Serif", "monospace" to "Monospaced", "system-ui" to "System")
        val installedFamilies by lazy { Font.getFamilies().map { it.lowercase() }.toSet() }
    }
}
