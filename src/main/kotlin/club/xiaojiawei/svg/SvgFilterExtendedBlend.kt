package club.xiaojiawei.svg

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/** W3C Compositing and Blending 1, sections 10.1 and 10.2. Straight channels in current RGB space. */
internal fun svgBlendStraight(backdrop: DoubleArray, source: DoubleArray, mode: String, out: DoubleArray) {
    if (mode == "hue" || mode == "saturation" || mode == "color" || mode == "luminosity") {
        when (mode) {
            "hue" -> { source.copyInto(out); setSaturation(out, saturation(backdrop)); setLuminosity(out, luminosity(backdrop)) }
            "saturation" -> { backdrop.copyInto(out); setSaturation(out, saturation(source)); setLuminosity(out, luminosity(backdrop)) }
            "color" -> { source.copyInto(out); setLuminosity(out, luminosity(backdrop)) }
            "luminosity" -> { backdrop.copyInto(out); setLuminosity(out, luminosity(source)) }
        }
        return
    }
    for (c in 0..2) {
        val b = backdrop[c]; val s = source[c]
        out[c] = when (mode) {
            "overlay" -> if (b <= .5) 2*b*s else 1-2*(1-b)*(1-s)
            "hard-light" -> if (s <= .5) 2*b*s else 1-2*(1-b)*(1-s)
            "soft-light" -> if (s <= .5) b-(1-2*s)*b*(1-b) else {
                val d = if (b <= .25) ((16*b-12)*b+4)*b else sqrt(b)
                b+(2*s-1)*(d-b)
            }
            "color-dodge" -> if (b == 0.0) 0.0 else if (s == 1.0) 1.0 else min(1.0,b/(1-s))
            "color-burn" -> if (b == 1.0) 1.0 else if (s == 0.0) 0.0 else 1-min(1.0,(1-b)/s)
            "difference" -> abs(b-s)
            "exclusion" -> b+s-2*b*s
            else -> error("Unsupported extended blend mode: $mode")
        }.coerceIn(0.0,1.0)
    }
}

private fun luminosity(c: DoubleArray) = .3*c[0]+.59*c[1]+.11*c[2]
private fun saturation(c: DoubleArray) = max(c[0],max(c[1],c[2]))-min(c[0],min(c[1],c[2]))
private fun setLuminosity(c: DoubleArray, lum: Double) {
    val delta = lum-luminosity(c)
    for (i in 0..2) c[i] += delta
    val l = luminosity(c); val n = min(c[0],min(c[1],c[2])); val x = max(c[0],max(c[1],c[2]))
    if (n < 0) for (i in 0..2) c[i] = l+(c[i]-l)*l/(l-n)
    if (x > 1) for (i in 0..2) c[i] = l+(c[i]-l)*(1-l)/(x-l)
    for (i in 0..2) c[i] = c[i].coerceIn(0.0,1.0)
}
private fun setSaturation(c: DoubleArray, sat: Double) {
    var lo = 0; var hi = 0
    for (i in 1..2) { if (c[i] < c[lo]) lo=i; if (c[i] > c[hi]) hi=i }
    if (hi == lo) { c.fill(0.0); return }
    val mid = 3-lo-hi
    c[mid] = (c[mid]-c[lo])*sat/(c[hi]-c[lo])
    c[hi] = sat
    c[lo] = 0.0
}
