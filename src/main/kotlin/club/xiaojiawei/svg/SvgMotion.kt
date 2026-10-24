package club.xiaojiawei.svg

import javafx.scene.transform.Affine

internal class SvgMotion private constructor(
    val timing: SvgAnimation, private val curve: SvgAnimation.Curve, private val path: SvgPathMeasure,
    private val rotation: String, val additive: Boolean
) {
    fun append(time: Double, output: Affine): Boolean {
        val progress = timing.progress(time) ?: return false
        val fraction = (curve.sample(progress, 1.0, null) as SvgAnimation.Value.Number).value
        val point = path.at(fraction * path.length)
        output.appendTranslation(point.x, point.y)
        output.appendRotation(when (rotation) {
            "auto" -> point.angle
            "auto-reverse" -> point.angle + 180
            else -> SvgNumbers.scalar(rotation)
        })
        return true
    }

    companion object {
        fun create(element: SvgElement, document: SvgDocument, order: Int): SvgMotion {
            require(element.children.all { it.name in setOf("mpath", "title", "desc", "metadata") }) { "Unsupported animateMotion child" }
            val links = element.children.filter { it.name == "mpath" }
            require(links.size <= 1) { "animateMotion requires at most one mpath" }
            val linked = links.singleOrNull()?.let { link ->
                val href = link["href"] ?: link["xlink:href"] ?: throw SvgException("Missing mpath href")
                require(href.startsWith('#')) { "Only local mpath references are supported" }
                val target = document.elementsById[href.drop(1)] ?: throw SvgException("Missing motion path")
                require(target.name == "path") { "mpath must reference a path" }
                require(SvgTransforms.parse(target["transform"]).isIdentity) { "Transformed mpath references are unsupported" }
                target["d"] ?: ""
            }
            val data = linked ?: element["path"] ?: run {
                fun pair(value: String): String {
                    require('%' !in value) { "Percentage motion coordinates require path data" }
                    val words = value.trim().split(Regex("[,\\s]+"))
                    require(words.size == 2) { "Motion coordinate needs two lengths" }
                    return words.joinToString(" ") { document.lengths.resolve(it, 1.0).toString() }
                }
                val values = element["values"]?.split(';')?.map(::pair)
                    ?: listOfNotNull(element["from"]?.let(::pair), element["to"]?.let(::pair))
                require(values.size >= 2 && element["by"] == null) { "Motion requires path, mpath, values or from/to" }
                values.mapIndexed { index, value -> (if (index == 0) "M" else "L") + value }.joinToString(" ")
            }
            val path = document.geometry.measure(data)
            val mode = element["calcMode"] ?: "paced"
            val keyPoints = element["keyPoints"]?.split(';')?.map { SvgNumbers.scalar(it.trim()).also { n -> require(n in 0.0..1.0) } }
            require(keyPoints == null || mode != "paced") { "Motion keyPoints require linear/discrete/spline mode" }
            val fractions = keyPoints ?: if (mode == "paced") listOf(0.0, 1.0) else path.fractions
            require(fractions.size >= 2)
            val attrs = element.attributes.toMutableMap()
            for (name in listOf("path", "rotate", "keyPoints", "from", "to", "by", "values", "additive")) attrs.remove(name)
            attrs["attributeName"] = "stroke-dashoffset"
            attrs["calcMode"] = if (mode == "paced") "linear" else mode
            if (mode == "paced") attrs.remove("keyTimes")
            attrs["values"] = fractions.joinToString(";")
            val timing = SvgAnimation.parse(SvgElement("animate", attrs, emptyList(), "", emptyList()), order)
            val rotation = element["rotate"] ?: "0"
            if (rotation !in setOf("auto", "auto-reverse")) SvgNumbers.scalar(rotation)
            val additive = element["additive"] ?: "replace"
            require(additive in setOf("replace", "sum"))
            return SvgMotion(timing, timing.Curve(null, false), path, rotation, additive == "sum")
        }
    }
}
