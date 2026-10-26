package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.Paint
import javafx.scene.paint.Stop
import javafx.scene.shape.Shape
import javafx.scene.transform.Transform
import kotlin.math.*

/** Two-circle SVG 2 gradient evaluated in gradient coordinates. */
internal class SvgRadialSampler(
    private val inverse: Transform, private val cx: Double, private val cy: Double, private val radius: Double,
    private val fx: Double, private val fy: Double, private val focalRadius: Double,
    private val spread: String, private val stops: List<Stop>, private val linear: Boolean
) {
    private val averageColor by lazy { average() }
    fun color(x: Double, y: Double): Color {
        if (stops.isEmpty()) return Color.TRANSPARENT
        if (stops.size == 1) return stops[0].color
        if (radius == 0.0 && focalRadius == 0.0) return stops.last().color
        if (cx == fx && cy == fy && radius == focalRadius) return Color.TRANSPARENT
        val rawQx=inverse.mxx*x+inverse.mxy*y+inverse.tx-fx; val rawQy=inverse.myx*x+inverse.myy*y+inverse.ty-fy
        val rawDx=cx-fx; val rawDy=cy-fy; val rawDr=radius-focalRadius
        val scale=maxOf(abs(rawQx),abs(rawQy),abs(rawDx),abs(rawDy),abs(rawDr),focalRadius)
        require(scale.isFinite()) { "Non-finite radial gradient coordinates" }
        if(scale==0.0) return stops.last().color
        val qx=rawQx/scale; val qy=rawQy/scale
        val dx=rawDx/scale; val dy=rawDy/scale; val dr=rawDr/scale; val fr=focalRadius/scale
        val a=dx*dx+dy*dy-dr*dr
        val b=-2*(qx*dx+qy*dy+fr*dr)
        val c=qx*qx+qy*qy-fr*fr
        val tolerance=1e-12*(dx*dx+dy*dy+dr*dr)
        var t=Double.NaN
        fun accept(v: Double) {
            val rr=fr+v*dr
            if(v.isFinite() && rr>=-1e-14*max(fr,abs(v*dr)) && (t.isNaN() || v>t)) t=v
        }
        if (abs(a) <= tolerance) {
            if (b!=0.0) accept(-c/b)
        } else {
            val disc=b*b-4*a*c
            if (disc>=-1e-14*(b*b+abs(4*a*c))) {
                val root=sqrt(disc.coerceAtLeast(0.0))
                val q=-.5*(b+Math.copySign(root,b))
                if(q==0.0) accept(-b/(2*a)) else {accept(q/a);accept(c/q)}
            }
        }
        if (!t.isFinite()) {
            // SVG retains the boundary-focus pad/average behaviour outside the tangent cone.
            if (abs(a)<=tolerance && dr>0) return if(spread=="pad") stops.last().color else averageColor
            return Color.TRANSPARENT
        }
        t=when(spread) { "repeat" -> t-floor(t); "reflect" -> { val m=t-2*floor(t/2); if(m>1) 2-m else m }; else -> t.coerceIn(0.0,1.0) }
        if(t<stops.first().offset) return stops.first().color
        var low=0; var high=stops.size
        while(low<high) { val mid=(low+high) ushr 1; if(stops[mid].offset<=t) low=mid+1 else high=mid }
        if(low==stops.size) return stops.last().color
        val lo=stops[low-1]; val hi=stops[low]
        return mix(lo.color,hi.color,(t-lo.offset)/(hi.offset-lo.offset))
    }
    private fun average(): Color {
        var r=0.0; var g=0.0; var b=0.0; var a=0.0
        fun add(c: Color,w: Double) { r+=decode(c.red)*w;g+=decode(c.green)*w;b+=decode(c.blue)*w;a+=c.opacity*w }
        add(stops.first().color,stops.first().offset)
        for(i in 1 until stops.size) { val w=(stops[i].offset-stops[i-1].offset)/2;add(stops[i-1].color,w);add(stops[i].color,w) }
        add(stops.last().color,1-stops.last().offset)
        return Color(encode(r),encode(g),encode(b),a.coerceIn(0.0,1.0))
    }
    private fun decode(v: Double)=if(!linear) v else if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)
    private fun encode(v: Double)=(if(!linear) v else if(v<=.0031308) 12.92*v else 1.055*v.pow(1/2.4)-.055).coerceIn(0.0,1.0)
    private fun mix(a: Color,b: Color,t: Double): Color {
        fun channel(x: Double,y: Double): Double {
            return (if(linear) encode(decode(x)*(1-t)+decode(y)*t) else x*(1-t)+y*t).coerceIn(0.0,1.0)
        }
        return Color(channel(a.red,b.red),channel(a.green,b.green),channel(a.blue,b.blue),(a.opacity*(1-t)+b.opacity*t).coerceIn(0.0,1.0))
    }
}

/** Per-target raster, sharing the existing pattern pixel budget. No document-global images. */
internal class SvgRadialPaint(private val target: Node,private val viewport: SvgViewport,private val budget: SvgPatternBudget,
    private val sampler: (Double,Bounds)->SvgRadialSampler, private val invalidate: ()->Unit) {
    private var key: List<Double>?=null
    private var cached: Paint=Color.TRANSPARENT
    private var retainedPixels=0L
    private val changed=InvalidationListener { invalidate() }
    init { target.localToSceneTransformProperty().addListener(changed) }
    fun dispose() { budget.release(this); target.localToSceneTransformProperty().removeListener(changed); cached=Color.TRANSPARENT; key=null; retainedPixels=0 }
    fun paint(opacity: Double): Paint {
        val bounds=svgGeometryBounds(target)
        val sx=hypot(target.localToSceneTransform.mxx,target.localToSceneTransform.myx)*(target.scene?.window?.outputScaleX ?: 1.0)
        val sy=hypot(target.localToSceneTransform.mxy,target.localToSceneTransform.myy)*(target.scene?.window?.outputScaleY ?: 1.0)
        val extra=if(target is Shape && target.stroke != null) target.strokeWidth*max(1.0,target.strokeMiterLimit) else 0.0
        val x=bounds.minX-extra; val y=bounds.minY-extra; val w=bounds.width+2*extra; val h=bounds.height+2*extra
        if(w<=0 || h<=0 || sx==0.0 || sy==0.0) { budget.release(this); key=null; cached=Color.TRANSPARENT; retainedPixels=0; return cached }
        val pw=ceil(w*sx); val ph=ceil(h*sy)
        require(pw.isFinite() && ph.isFinite() && pw in 1.0..4096.0 && ph in 1.0..4096.0) { "SVG radial gradient raster exceeds 4096 pixels per axis" }
        val next=listOf(x,y,w,h,pw,ph,opacity,bounds.minX,bounds.minY,bounds.width,bounds.height,viewport.width,viewport.height)
        if(key==next) return cached
        val evaluator=sampler(opacity,bounds)
        budget.reserve(this,retainedPixels+pw.toLong()*ph.toLong())
        try {
        val image=WritableImage(pw.toInt(),ph.toInt())
        for(j in 0 until ph.toInt()) for(i in 0 until pw.toInt()) image.pixelWriter.setColor(i,j,evaluator.color(x+(i+.5)*w/pw,y+(j+.5)*h/ph))
        cached=ImagePattern(image,x,y,w,h,false); key=next
        retainedPixels=pw.toLong()*ph.toLong(); budget.reserve(this,retainedPixels)
        return cached
        } finally { budget.reserve(this,retainedPixels) }
    }
}

internal fun svgPaintInverse(mapping: Transform): Transform {
    val inverse=try {mapping.createInverse()} catch (_: javafx.scene.transform.NonInvertibleTransformException) {throw SvgException("Singular paint transform")}
    require(listOf(inverse.mxx,inverse.mxy,inverse.myx,inverse.myy,inverse.tx,inverse.ty).all {it.isFinite()}) {"Non-finite inverse paint transform"}
    return inverse
}
