package club.xiaojiawei.svg

import javafx.beans.binding.Bindings
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.shape.MoveTo
import javafx.scene.text.Text
import javafx.scene.transform.Affine
import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale

/** Keeps a native Text as the CSS target; visible runs share its font and shape properties. */
internal class SvgAdjustedText(
    val styleText: Text,
    private val adjustGlyphs: Boolean,
    private val anchor: Double,
    maxGlyphs: Int
) : Group() {
    private val starts = mutableListOf<Int>()
    private val runs = mutableListOf<Text>()
    private val content = Group()
    private var x = 0.0
    private var y = 0.0
    private var targetLength = 0.0
    private var ready = false
    val glyphCount: Int get() = runs.size

    init {
        val value = styleText.text
        require('\n' !in value && '\r' !in value && '\t' !in value) { "textLength requires a single unwrapped text line" }
        if (adjustGlyphs) {
            if (value.isNotEmpty()) addRun(value, 0, maxGlyphs)
        } else {
            require(!Bidi.requiresBidi(value.toCharArray(), 0, value.length)) { "textLength spacing for bidirectional text is unsupported" }
            require(value.codePoints().allMatch { codePoint ->
                Character.UnicodeScript.of(codePoint) in SIMPLE_SCRIPTS
            }) { "textLength spacing for contextual scripts is unsupported" }
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
            val clusters = mutableListOf<Pair<Int, Int>>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                require(clusters.size < maxGlyphs) { "SVG expanded text node limit exceeded" }
                clusters += start to end
                start = end
                end = iterator.next()
            }
            clusters.forEach { (from, to) -> addRun(value.substring(from, to), from, maxGlyphs) }
        }
        styleClass += "svg-text-layout"
        styleText.isVisible = false
        styleText.isManaged = false
        content.children.setAll(runs)
        children.setAll(styleText, content)
        opacityProperty().bind(styleText.opacityProperty())
        styleText.fontProperty().addListener { _, _, _ -> if (ready) positionRuns() }
    }

    private fun addRun(value: String, start: Int, limit: Int) {
        require(runs.size < limit) { "SVG expanded text node limit exceeded" }
        starts += start
        runs += Text(value).apply {
            styleClass += "svg-text-glyph"
            fontProperty().bind(styleText.fontProperty())
            fillProperty().bind(styleText.fillProperty())
            strokeProperty().bind(styleText.strokeProperty())
            strokeWidthProperty().bind(styleText.strokeWidthProperty())
            strokeTypeProperty().bind(styleText.strokeTypeProperty())
            strokeLineCapProperty().bind(styleText.strokeLineCapProperty())
            strokeLineJoinProperty().bind(styleText.strokeLineJoinProperty())
            strokeMiterLimitProperty().bind(styleText.strokeMiterLimitProperty())
            strokeDashOffsetProperty().bind(styleText.strokeDashOffsetProperty())
            Bindings.bindContent(strokeDashArray, styleText.strokeDashArray)
            underlineProperty().bind(styleText.underlineProperty())
            strikethroughProperty().bind(styleText.strikethroughProperty())
            fontSmoothingTypeProperty().bind(styleText.fontSmoothingTypeProperty())
        }
    }

    fun position(x: Double, y: Double, textLength: Double) {
        require(textLength.isFinite() && textLength >= 0) { "Negative or non-finite textLength" }
        this.x = x
        this.y = y
        targetLength = textLength
        ready = true
        positionRuns()
    }

    private fun positionRuns() {
        val natural = styleText.layoutBounds.width
        if (adjustGlyphs) {
            val scale = if (natural > 0) targetLength / natural else 1.0
            content.transforms.setAll(Affine(scale, 0.0, x - anchor * targetLength, 0.0, 1.0, y))
        } else {
            val advance = if (runs.size > 1) targetLength else natural
            val gap = if (runs.size > 1) (targetLength - natural) / (runs.size - 1) else 0.0
            runs.forEachIndexed { index, run ->
                val caret = styleText.caretShape(starts[index], true).filterIsInstance<MoveTo>().firstOrNull()?.x ?: 0.0
                run.x = x - anchor * advance + caret + index * gap
                run.y = y
            }
        }
    }

    fun wrapRuns(wrap: (Text) -> javafx.scene.Node) {
        content.children.clear()
        content.children.setAll(runs.map(wrap))
    }

    /** Excludes the invisible style target and excludes stroke/effects. */
    fun geometryBounds(): Bounds {
        val bounds = runs.map { content.localToParentTransform.transform(it.layoutBounds) }
        if (bounds.isEmpty()) return BoundingBox(x, y, 0.0, 0.0)
        val minX = bounds.minOf { it.minX }
        val minY = bounds.minOf { it.minY }
        return BoundingBox(minX, minY, bounds.maxOf { it.maxX } - minX, bounds.maxOf { it.maxY } - minY)
    }

    companion object {
        private val SIMPLE_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK,
            Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED)
    }
}
