package app.lyrify.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Complex FFT of a fixed size [n], forward sign (exp(-2 pi i j k / n)), no scaling, in double precision.
 *
 * Mixed-radix Stockham autosort, decimation in frequency (OTFFT's formulation, generalised to radix p): stage by stage
 * the current length l is split as l = p * m; for every j < m and every stride offset q < s,
 * `y[q + s (p j + k)] = (sum over r of x[q + s (j + r m)] w_p^(r k)) * w_l^(j k)`, then l = m, s = s * p, and the two
 * buffers swap. Radices 8, 4, 2, 3 and 5 have hand-written butterflies; any other prime factor gets a direct DFT.
 * 7680 = 8^3 x 3 x 5 (UVR-MDX-NET-Voc_FT), 6144 = 8^3 x 4 x 3, 400 = 8 x 2 x 5^2 (Whisper). The twiddles of every
 * stage are computed once, in the order the inner loops read them; [transform] allocates nothing.
 *
 * Not thread safe (keeps a work buffer): one instance per thread.
 */
class Fft(val n: Int) {
    private val radices: IntArray
    private val twRe: Array<DoubleArray>
    private val twIm: Array<DoubleArray>
    private val workRe = DoubleArray(n)
    private val workIm = DoubleArray(n)
    // The direct DFT of a radix other than 2, 3, 4, 5: its roots w_p^k per stage (empty for the others), and inputs.
    private val rootRe: Array<DoubleArray>
    private val rootIm: Array<DoubleArray>
    private val vr: DoubleArray
    private val vi: DoubleArray

    init {
        require(n >= 1) { "FFT size must be positive" }
        radices = factor(n)
        val generic = radices.map { p -> if (p in SPECIALISED) 0 else p }
        rootRe = Array(radices.size) { DoubleArray(generic[it]) { k -> cos(-2.0 * PI * k / generic[it]) } }
        rootIm = Array(radices.size) { DoubleArray(generic[it]) { k -> sin(-2.0 * PI * k / generic[it]) } }
        vr = DoubleArray(generic.maxOrNull() ?: 0)
        vi = DoubleArray(generic.maxOrNull() ?: 0)
        var l = n
        val re = ArrayList<DoubleArray>()
        val im = ArrayList<DoubleArray>()
        for (p in radices) {
            // Stage twiddles w_l^(j r) for j < m, r = 1 .. p - 1, at [j (p - 1) + r - 1].
            val m = l / p
            val c = DoubleArray(m * (p - 1))
            val s = DoubleArray(m * (p - 1))
            for (j in 0 until m) for (r in 1 until p) {
                val angle = -2.0 * PI * ((j.toLong() * r) % l) / l
                c[j * (p - 1) + r - 1] = cos(angle)
                s[j * (p - 1) + r - 1] = sin(angle)
            }
            re += c
            im += s
            l = m
        }
        twRe = re.toTypedArray()
        twIm = im.toTypedArray()
    }

    /** Transforms ([re], [im]) in place. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == n && im.size == n) { "expected $n values" }
        var xr = re
        var xi = im
        var yr = workRe
        var yi = workIm
        var l = n
        var s = 1
        for (stage in radices.indices) {
            val p = radices[stage]
            val m = l / p
            val wr = twRe[stage]
            val wi = twIm[stage]
            when (p) {
                8 -> radix8(m, s, wr, wi, xr, xi, yr, yi)
                4 -> radix4(m, s, wr, wi, xr, xi, yr, yi)
                2 -> radix2(m, s, wr, wi, xr, xi, yr, yi)
                3 -> radix3(m, s, wr, wi, xr, xi, yr, yi)
                5 -> radix5(m, s, wr, wi, xr, xi, yr, yi)
                else -> radixAny(p, m, s, wr, wi, rootRe[stage], rootIm[stage], xr, xi, yr, yi)
            }
            val tr = xr
            val ti = xi
            xr = yr
            xi = yi
            yr = tr
            yi = ti
            l = m
            s *= p
        }
        if (xr !== re) {
            System.arraycopy(xr, 0, re, 0, n)
            System.arraycopy(xi, 0, im, 0, n)
        }
    }

    private fun radix2(m: Int, s: Int, wr: DoubleArray, wi: DoubleArray, xr: DoubleArray, xi: DoubleArray, yr: DoubleArray, yi: DoubleArray) {
        val sm = s * m
        for (j in 0 until m) {
            val w1r = wr[j]
            val w1i = wi[j]
            val src = s * j
            val dst = 2 * s * j
            for (q in 0 until s) {
                val i0 = src + q
                val i1 = i0 + sm
                val ar = xr[i0]
                val ai = xi[i0]
                val br = xr[i1]
                val bi = xi[i1]
                val o = dst + q
                yr[o] = ar + br
                yi[o] = ai + bi
                val dr = ar - br
                val di = ai - bi
                yr[o + s] = dr * w1r - di * w1i
                yi[o + s] = dr * w1i + di * w1r
            }
        }
    }

    /**
     * Radix 8 as a radix-2 step and two radix-4 butterflies: b_r = a_r + a_(r+4) gives the even outputs,
     * c_r = (a_r - a_(r+4)) w_8^r the odd ones, w_8 = (1 - i) / sqrt(2).
     */
    private fun radix8(m: Int, s: Int, wr: DoubleArray, wi: DoubleArray, xr: DoubleArray, xi: DoubleArray, yr: DoubleArray, yi: DoubleArray) {
        val sm = s * m
        for (j in 0 until m) {
            val w = 7 * j
            val src = s * j
            val dst = 8 * s * j
            for (q in 0 until s) {
                val i0 = src + q
                val a0r = xr[i0]
                val a0i = xi[i0]
                val a1r = xr[i0 + sm]
                val a1i = xi[i0 + sm]
                val a2r = xr[i0 + 2 * sm]
                val a2i = xi[i0 + 2 * sm]
                val a3r = xr[i0 + 3 * sm]
                val a3i = xi[i0 + 3 * sm]
                val a4r = xr[i0 + 4 * sm]
                val a4i = xi[i0 + 4 * sm]
                val a5r = xr[i0 + 5 * sm]
                val a5i = xi[i0 + 5 * sm]
                val a6r = xr[i0 + 6 * sm]
                val a6i = xi[i0 + 6 * sm]
                val a7r = xr[i0 + 7 * sm]
                val a7i = xi[i0 + 7 * sm]
                val b0r = a0r + a4r
                val b0i = a0i + a4i
                val b1r = a1r + a5r
                val b1i = a1i + a5i
                val b2r = a2r + a6r
                val b2i = a2i + a6i
                val b3r = a3r + a7r
                val b3i = a3i + a7i
                val c0r = a0r - a4r
                val c0i = a0i - a4i
                val d1r = a1r - a5r
                val d1i = a1i - a5i
                // c1 = d1 (1 - i) / sqrt 2, c2 = -i (a2 - a6), c3 = d3 (-1 - i) / sqrt 2.
                val c1r = (d1r + d1i) * SQRT_HALF
                val c1i = (d1i - d1r) * SQRT_HALF
                val c2r = a2i - a6i
                val c2i = a6r - a2r
                val d3r = a3r - a7r
                val d3i = a3i - a7i
                val c3r = (d3i - d3r) * SQRT_HALF
                val c3i = -(d3r + d3i) * SQRT_HALF
                // Even outputs: radix 4 of b.
                val e0r = b0r + b2r
                val e0i = b0i + b2i
                val e1r = b0r - b2r
                val e1i = b0i - b2i
                val e2r = b1r + b3r
                val e2i = b1i + b3i
                val e3r = b1r - b3r
                val e3i = b1i - b3i
                // Odd outputs: radix 4 of c.
                val f0r = c0r + c2r
                val f0i = c0i + c2i
                val f1r = c0r - c2r
                val f1i = c0i - c2i
                val f2r = c1r + c3r
                val f2i = c1i + c3i
                val f3r = c1r - c3r
                val f3i = c1i - c3i
                val o = dst + q
                yr[o] = e0r + e2r
                yi[o] = e0i + e2i
                twiddle(yr, yi, o + s, f0r + f2r, f0i + f2i, wr[w], wi[w])
                twiddle(yr, yi, o + 2 * s, e1r + e3i, e1i - e3r, wr[w + 1], wi[w + 1])
                twiddle(yr, yi, o + 3 * s, f1r + f3i, f1i - f3r, wr[w + 2], wi[w + 2])
                twiddle(yr, yi, o + 4 * s, e0r - e2r, e0i - e2i, wr[w + 3], wi[w + 3])
                twiddle(yr, yi, o + 5 * s, f0r - f2r, f0i - f2i, wr[w + 4], wi[w + 4])
                twiddle(yr, yi, o + 6 * s, e1r - e3i, e1i + e3r, wr[w + 5], wi[w + 5])
                twiddle(yr, yi, o + 7 * s, f1r - f3i, f1i + f3r, wr[w + 6], wi[w + 6])
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun twiddle(yr: DoubleArray, yi: DoubleArray, o: Int, vr: Double, vi: Double, cr: Double, ci: Double) {
        yr[o] = vr * cr - vi * ci
        yi[o] = vr * ci + vi * cr
    }

    private fun radix4(m: Int, s: Int, wr: DoubleArray, wi: DoubleArray, xr: DoubleArray, xi: DoubleArray, yr: DoubleArray, yi: DoubleArray) {
        val sm = s * m
        for (j in 0 until m) {
            val w = 3 * j
            val w1r = wr[w]
            val w1i = wi[w]
            val w2r = wr[w + 1]
            val w2i = wi[w + 1]
            val w3r = wr[w + 2]
            val w3i = wi[w + 2]
            val src = s * j
            val dst = 4 * s * j
            for (q in 0 until s) {
                val i0 = src + q
                val i1 = i0 + sm
                val i2 = i1 + sm
                val i3 = i2 + sm
                val a0r = xr[i0]
                val a0i = xi[i0]
                val a1r = xr[i1]
                val a1i = xi[i1]
                val a2r = xr[i2]
                val a2i = xi[i2]
                val a3r = xr[i3]
                val a3i = xi[i3]
                val t0r = a0r + a2r
                val t0i = a0i + a2i
                val t1r = a0r - a2r
                val t1i = a0i - a2i
                val t2r = a1r + a3r
                val t2i = a1i + a3i
                val t3r = a1r - a3r
                val t3i = a1i - a3i
                // y1 = t1 - i t3, y3 = t1 + i t3 (w_4 = -i).
                val y1r = t1r + t3i
                val y1i = t1i - t3r
                val y2r = t0r - t2r
                val y2i = t0i - t2i
                val y3r = t1r - t3i
                val y3i = t1i + t3r
                val o = dst + q
                yr[o] = t0r + t2r
                yi[o] = t0i + t2i
                yr[o + s] = y1r * w1r - y1i * w1i
                yi[o + s] = y1r * w1i + y1i * w1r
                yr[o + 2 * s] = y2r * w2r - y2i * w2i
                yi[o + 2 * s] = y2r * w2i + y2i * w2r
                yr[o + 3 * s] = y3r * w3r - y3i * w3i
                yi[o + 3 * s] = y3r * w3i + y3i * w3r
            }
        }
    }

    private fun radix3(m: Int, s: Int, wr: DoubleArray, wi: DoubleArray, xr: DoubleArray, xi: DoubleArray, yr: DoubleArray, yi: DoubleArray) {
        val sm = s * m
        for (j in 0 until m) {
            val w = 2 * j
            val w1r = wr[w]
            val w1i = wi[w]
            val w2r = wr[w + 1]
            val w2i = wi[w + 1]
            val src = s * j
            val dst = 3 * s * j
            for (q in 0 until s) {
                val i0 = src + q
                val i1 = i0 + sm
                val i2 = i1 + sm
                val a0r = xr[i0]
                val a0i = xi[i0]
                val t1r = xr[i1] + xr[i2]
                val t1i = xi[i1] + xi[i2]
                val t2r = (xr[i1] - xr[i2]) * SIN_3
                val t2i = (xi[i1] - xi[i2]) * SIN_3
                val mr = a0r - 0.5 * t1r
                val mi = a0i - 0.5 * t1i
                // y1 = m - i sin(2 pi / 3) (a1 - a2), y2 = m + i sin(2 pi / 3) (a1 - a2).
                val y1r = mr + t2i
                val y1i = mi - t2r
                val y2r = mr - t2i
                val y2i = mi + t2r
                val o = dst + q
                yr[o] = a0r + t1r
                yi[o] = a0i + t1i
                yr[o + s] = y1r * w1r - y1i * w1i
                yi[o + s] = y1r * w1i + y1i * w1r
                yr[o + 2 * s] = y2r * w2r - y2i * w2i
                yi[o + 2 * s] = y2r * w2i + y2i * w2r
            }
        }
    }

    private fun radix5(m: Int, s: Int, wr: DoubleArray, wi: DoubleArray, xr: DoubleArray, xi: DoubleArray, yr: DoubleArray, yi: DoubleArray) {
        val sm = s * m
        for (j in 0 until m) {
            val w = 4 * j
            val w1r = wr[w]
            val w1i = wi[w]
            val w2r = wr[w + 1]
            val w2i = wi[w + 1]
            val w3r = wr[w + 2]
            val w3i = wi[w + 2]
            val w4r = wr[w + 3]
            val w4i = wi[w + 3]
            val src = s * j
            val dst = 5 * s * j
            for (q in 0 until s) {
                val i0 = src + q
                val i1 = i0 + sm
                val i2 = i1 + sm
                val i3 = i2 + sm
                val i4 = i3 + sm
                val a0r = xr[i0]
                val a0i = xi[i0]
                val t1r = xr[i1] + xr[i4]
                val t1i = xi[i1] + xi[i4]
                val t2r = xr[i2] + xr[i3]
                val t2i = xi[i2] + xi[i3]
                val t3r = xr[i1] - xr[i4]
                val t3i = xi[i1] - xi[i4]
                val t4r = xr[i2] - xr[i3]
                val t4i = xi[i2] - xi[i3]
                val b1r = a0r + C1_5 * t1r + C2_5 * t2r
                val b1i = a0i + C1_5 * t1i + C2_5 * t2i
                val b2r = a0r + C2_5 * t1r + C1_5 * t2r
                val b2i = a0i + C2_5 * t1i + C1_5 * t2i
                val v1r = S1_5 * t3r + S2_5 * t4r
                val v1i = S1_5 * t3i + S2_5 * t4i
                val v2r = S2_5 * t3r - S1_5 * t4r
                val v2i = S2_5 * t3i - S1_5 * t4i
                // y1 = b1 - i v1, y4 = b1 + i v1, y2 = b2 - i v2, y3 = b2 + i v2.
                val y1r = b1r + v1i
                val y1i = b1i - v1r
                val y4r = b1r - v1i
                val y4i = b1i + v1r
                val y2r = b2r + v2i
                val y2i = b2i - v2r
                val y3r = b2r - v2i
                val y3i = b2i + v2r
                val o = dst + q
                yr[o] = a0r + t1r + t2r
                yi[o] = a0i + t1i + t2i
                yr[o + s] = y1r * w1r - y1i * w1i
                yi[o + s] = y1r * w1i + y1i * w1r
                yr[o + 2 * s] = y2r * w2r - y2i * w2i
                yi[o + 2 * s] = y2r * w2i + y2i * w2r
                yr[o + 3 * s] = y3r * w3r - y3i * w3i
                yi[o + 3 * s] = y3r * w3i + y3i * w3r
                yr[o + 4 * s] = y4r * w4r - y4i * w4i
                yi[o + 4 * s] = y4r * w4i + y4i * w4r
            }
        }
    }

    /** Any radix p by a direct DFT of p points with the roots ([cr], [ci]) = w_p^k. */
    private fun radixAny(
        p: Int,
        m: Int,
        s: Int,
        wr: DoubleArray,
        wi: DoubleArray,
        cr: DoubleArray,
        ci: DoubleArray,
        xr: DoubleArray,
        xi: DoubleArray,
        yr: DoubleArray,
        yi: DoubleArray,
    ) {
        val sm = s * m
        for (j in 0 until m) {
            val src = s * j
            val dst = p * s * j
            for (q in 0 until s) {
                for (r in 0 until p) {
                    vr[r] = xr[src + q + r * sm]
                    vi[r] = xi[src + q + r * sm]
                }
                for (k in 0 until p) {
                    var sumR = 0.0
                    var sumI = 0.0
                    var idx = 0
                    for (r in 0 until p) {
                        sumR += vr[r] * cr[idx] - vi[r] * ci[idx]
                        sumI += vr[r] * ci[idx] + vi[r] * cr[idx]
                        idx += k
                        if (idx >= p) idx -= p
                    }
                    val o = dst + q + k * s
                    if (k == 0) {
                        yr[o] = sumR
                        yi[o] = sumI
                    } else {
                        val t = j * (p - 1) + k - 1
                        yr[o] = sumR * wr[t] - sumI * wi[t]
                        yi[o] = sumR * wi[t] + sumI * wr[t]
                    }
                }
            }
        }
    }

    private companion object {
        val SIN_3 = sqrt(3.0) / 2
        val C1_5 = cos(2.0 * PI / 5)
        val C2_5 = cos(4.0 * PI / 5)
        val S1_5 = sin(2.0 * PI / 5)
        val S2_5 = sin(4.0 * PI / 5)

        val SPECIALISED = setOf(2, 3, 4, 5, 8)
        val SQRT_HALF = sqrt(0.5)

        /** 8s first, then 4, 2, 3, 5, then any other prime; the last stage, whose twiddles are all 1, gets the last. */
        fun factor(n: Int): IntArray {
            val out = ArrayList<Int>()
            var rest = n
            for (p in intArrayOf(8, 4, 2, 3, 5)) while (rest % p == 0) {
                out += p
                rest /= p
            }
            var p = 7
            while (rest > 1) {
                while (rest % p == 0) {
                    out += p
                    rest /= p
                }
                p += 2
            }
            return out.toIntArray()
        }
    }
}
