package club.xiaojiawei.svg

import javafx.geometry.Bounds
import javafx.scene.Node
import kotlin.math.floor

/** Prepared once through the renderer's ordinary node/resource/security paths. */
internal class SvgFilterImageSource(val node: Node, val configure: (Bounds, Bounds, Boolean) -> Unit)

/** Repeat the input primitive's subregion, not its nontransparent-pixel bounding box. */
internal fun SvgFilterPixels.tile(left: Double, top: Double, tileWidth: Double, tileHeight: Double): SvgFilterPixels {
    require(listOf(left,top,tileWidth,tileHeight).all { it.isFinite() }) { "Non-finite feTile region" }
    val out=SvgFilterPixels(width,height,linear)
    if (tileWidth <= 0.0 || tileHeight <= 0.0) return out
    fun wrap(v: Double, origin: Double, period: Double): Double {
        val remainder=(v-origin)%period
        return origin+if(remainder < 0) remainder+period else remainder
    }
    fun index(coordinate: Double, origin: Double, period: Double, limit: Int): Int =
        floor(wrap(coordinate,origin,period)).toInt().let { if(it in 0 until limit) it else -1 }
    for(y in 0 until height) for(x in 0 until width) {
        val tx=wrap(x+.5,left,tileWidth)-.5; val ty=wrap(y+.5,top,tileHeight)-.5
        val x0=floor(tx);val y0=floor(ty);val fx=tx-x0;val fy=ty-y0
        val destination=(y*width+x)*4
        for(j in 0..1) for(i in 0..1) {
            val ix=index(x0+i+.5,left,tileWidth,width);val iy=index(y0+j+.5,top,tileHeight,height)
            if(ix < 0 || iy < 0) continue
            val weight=((if(i==0)1-fx else fx)*(if(j==0)1-fy else fy)).toFloat()
            val source=(iy*width+ix)*4
            for(c in 0..3) out.data[destination+c]+=data[source+c]*weight
        }
    }
    return out
}

/** Inherit only filter attributes; keep original child objects for CSS and prepared-resource identity. */
internal fun svgResolvedFilter(id: String, document: SvgDocument): SvgElement {
    val chain=mutableListOf<SvgElement>();val seen=mutableSetOf<String>();var next: String?=id
    while(next != null) {
        require(seen.add(next)) { "Circular filter template reference: #$next" }
        require(chain.size < document.options.maxDepth) { "Filter template depth limit exceeded" }
        val element=document.elementsById[next] ?: throw SvgException("Missing filter: #$next")
        require(element.name=="filter") { "Filter reference is not a filter: #$next" }
        require(element["filterRes"] == null) { "filterRes is unsupported" }
        chain+=element
        val href=element["href"] ?: element["xlink:href"]
        require(href == null || (href.startsWith('#') && href.length>1)) { "Only local filter templates are supported" }
        next=href?.drop(1)
    }
    val own=chain.first(); val attrs=own.attributes.toMutableMap()
    for(name in listOf("x","y","width","height","filterUnits","primitiveUnits"))
        chain.firstNotNullOfOrNull { it[name] }?.let { attrs[name]=it }
    attrs.remove("href");attrs.remove("xlink:href")
    val source=chain.firstOrNull { e-> e.children.any { !it.name.startsWith('{') && it.name !in setOf("title","desc","metadata") } } ?: own
    return SvgElement("filter",attrs,source.children,source.text,source.content)
}
