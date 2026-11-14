package club.xiaojiawei.svg

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sign

/** Filter Effects 1 section 9.7: four independent functions, evaluated on straight RGBA. */
internal class SvgComponentTransferFunction private constructor(
    private val type: String,
    private val table: DoubleArray = doubleArrayOf(),
    private val slope: Double = 1.0,
    private val intercept: Double = 0.0,
    private val amplitude: Double = 1.0,
    private val exponent: Double = 1.0,
    private val offset: Double = 0.0
) {
    fun apply(value: Double): Double {
        val c = value.coerceIn(0.0,1.0)
        val result = when(type) {
            "identity" -> c
            "table" -> if (table.isEmpty()) c else if (table.size == 1) table[0] else {
                val position=c*(table.size-1); val index=floor(position).toInt().coerceAtMost(table.size-2)
                val fraction=position-index
                // Weighted form avoids overflow of (next-current) for large opposite-sign values.
                table[index]*(1-fraction)+table[index+1]*fraction
            }
            "discrete" -> if (table.isEmpty()) c else table[floor(c*table.size).toInt().coerceAtMost(table.size-1)]
            "linear" -> {
                val scale=max(1.0,max(abs(slope),abs(intercept)))
                (slope/scale*c+intercept/scale)*scale
            }
            "gamma" -> {
                // A zero amplitude is constant even if c^exponent overflows at c=0.
                if (amplitude == 0.0) offset else {
                    val power=c.pow(exponent)
                    val term=if (power.isInfinite() && c > 0.0)
                        sign(amplitude)*exp(ln(abs(amplitude))+exponent*ln(c)) else amplitude*power
                    term+offset
                }
            }
            else -> error("Invalid component transfer type")
        }
        // All parameters are finite; overflow has a definite sign and clamps normally.
        return result.coerceIn(0.0,1.0)
    }

    companion object {
        val IDENTITY = SvgComponentTransferFunction("identity")
        fun parse(element: SvgElement): SvgComponentTransferFunction {
            val type=element["type"] ?: "identity"
            require(type in setOf("identity","table","discrete","linear","gamma")) { "Invalid component transfer type: $type" }
            fun scalar(name: String, default: Double): Double {
                val raw=element[name] ?: return default
                val values=SvgNumbers.list(raw)
                require(values.size == 1) { "Invalid component transfer $name" }
                return values.single()
            }
            return when(type) {
                "table","discrete" -> SvgComponentTransferFunction(type, SvgNumbers.list(element["tableValues"] ?: "").toDoubleArray())
                "linear" -> SvgComponentTransferFunction(type, slope=scalar("slope",1.0), intercept=scalar("intercept",0.0))
                "gamma" -> SvgComponentTransferFunction(type, amplitude=scalar("amplitude",1.0), exponent=scalar("exponent",1.0), offset=scalar("offset",0.0))
                else -> IDENTITY
            }
        }
    }
}

internal object SvgFilterComponentTransfer {
    val FUNCTION_NAMES = listOf("feFuncR","feFuncG","feFuncB","feFuncA")
    fun parse(primitive: SvgElement): List<SvgComponentTransferFunction> {
        val functions=MutableList(4) { SvgComponentTransferFunction.IDENTITY }
        for (child in primitive.children) {
            if (child.name.startsWith('{') || child.name in setOf("title","desc","metadata")) continue
            val channel=FUNCTION_NAMES.indexOf(child.name)
            require(channel >= 0) { "Unsupported feComponentTransfer child: ${child.name}" }
            require(child.children.all { it.name.startsWith('{') || it.name in setOf("title","desc","metadata") }) {
                "Animated component transfer functions are unsupported"
            }
            functions[channel]=SvgComponentTransferFunction.parse(child)
        }
        return functions
    }
}

internal fun SvgFilterPixels.componentTransfer(functions: List<SvgComponentTransferFunction>): SvgFilterPixels {
    require(functions.size == 4) { "Component transfer needs four channel functions" }
    val result=SvgFilterPixels(width,height,linear)
    for (i in data.indices step 4) {
        val alpha=data[i+3].toDouble()
        val outAlpha=functions[3].apply(alpha)
        result.data[i+3]=outAlpha.toFloat()
        for (c in 0..2) {
            val straight=if (alpha > 0.0) (data[i+c]/alpha).coerceIn(0.0,1.0) else 0.0
            result.data[i+c]=(functions[c].apply(straight)*outAlpha).toFloat()
        }
    }
    return result
}
