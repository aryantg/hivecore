package com.hivecore.ui.qr

/**
 * A small QR encoder for the pairing invite (spec section 6.2, 17 "Show QR"): byte mode, versions 1–10, error
 * correction M (L when M does not fit), ISO/IEC 18004 masks and format / version information. Pure Kotlin, no
 * dependency: the catalog has no barcode library and the invite link (`swarm://join?g=…&k=…&t=…`, about 120–150
 * bytes) fits version 7–9 at level M. Returns the module matrix; the screen draws it with a Compose Canvas.
 */
object QrCode {
    enum class Ecl(val formatBits: Int) { L(1), M(0) }

    /** The encoded symbol: [size] × [size] modules, `modules[row][col]`, true = dark. */
    class Symbol(val version: Int, val ecl: Ecl, val mask: Int, val modules: Array<BooleanArray>) {
        val size: Int get() = modules.size
        fun get(row: Int, col: Int): Boolean = modules[row][col]
    }

    private class Block(val ecPerBlock: Int, val blocks1: Int, val data1: Int, val blocks2: Int, val data2: Int) {
        val dataCodewords: Int get() = blocks1 * data1 + blocks2 * data2
        val totalCodewords: Int get() = dataCodewords + ecPerBlock * (blocks1 + blocks2)
    }

    /** ISO/IEC 18004 table 9, versions 1–10, levels L and M. */
    private val TABLE: Map<Ecl, List<Block>> = mapOf(
        Ecl.L to listOf(
            Block(7, 1, 19, 0, 0), Block(10, 1, 34, 0, 0), Block(15, 1, 55, 0, 0), Block(20, 1, 80, 0, 0), Block(26, 1, 108, 0, 0),
            Block(18, 2, 68, 0, 0), Block(20, 2, 78, 0, 0), Block(24, 2, 97, 0, 0), Block(30, 2, 116, 0, 0), Block(18, 2, 68, 2, 69),
        ),
        Ecl.M to listOf(
            Block(10, 1, 16, 0, 0), Block(16, 1, 28, 0, 0), Block(26, 1, 44, 0, 0), Block(18, 2, 32, 0, 0), Block(24, 2, 43, 0, 0),
            Block(16, 4, 27, 0, 0), Block(18, 4, 31, 0, 0), Block(22, 2, 38, 2, 39), Block(22, 3, 36, 2, 37), Block(26, 4, 43, 1, 44),
        ),
    )

    const val MAX_VERSION = 10

    fun sizeOf(version: Int): Int = 17 + 4 * version

    /** Data codewords available at [version] / [ecl]. */
    fun capacity(version: Int, ecl: Ecl): Int = TABLE.getValue(ecl)[version - 1].dataCodewords
    fun totalCodewords(version: Int, ecl: Ecl): Int = TABLE.getValue(ecl)[version - 1].totalCodewords

    /** Encodes [text] as UTF-8 bytes; null when it does not fit version 10 at level L. */
    fun encode(text: String, preferred: Ecl = Ecl.M): Symbol? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (ecl in listOf(preferred, Ecl.L).distinct()) {
            for (v in 1..MAX_VERSION) {
                val headerBits = 4 + if (v <= 9) 8 else 16
                if (headerBits + bytes.size * 8 <= capacity(v, ecl) * 8) return encodeAt(bytes, v, ecl)
            }
        }
        return null
    }

    fun encodeAt(bytes: ByteArray, version: Int, ecl: Ecl): Symbol {
        require(version in 1..MAX_VERSION)
        val block = TABLE.getValue(ecl)[version - 1]
        val data = dataCodewords(bytes, version, block.dataCodewords)
        val all = interleave(data, block)
        val size = sizeOf(version)
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, function, version)
        placeData(modules, function, all)
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            applyMask(modules, function, m)
            drawFormat(modules, function, ecl, m)
            val p = penalty(modules)
            if (p < bestPenalty) { bestPenalty = p; best = m }
            applyMask(modules, function, m)   // XOR is its own inverse
        }
        applyMask(modules, function, best)
        drawFormat(modules, function, ecl, best)
        return Symbol(version, ecl, best, modules)
    }

    // ---- segments and codewords ----

    /** Byte-mode segment, terminator, pad to a byte, pad codewords 0xEC / 0x11. */
    fun dataCodewords(bytes: ByteArray, version: Int, capacity: Int): ByteArray {
        val bits = BitBuffer()
        bits.append(0b0100, 4)
        bits.append(bytes.size, if (version <= 9) 8 else 16)
        for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
        val cap = capacity * 8
        require(bits.length <= cap) { "data does not fit" }
        bits.append(0, minOf(4, cap - bits.length))
        bits.append(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < cap) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
        return bits.toBytes()
    }

    /** Splits into blocks, computes the Reed-Solomon codewords per block, interleaves (ISO 18004 section 7.6). */
    private fun interleave(data: ByteArray, b: Block): ByteArray {
        val blocks = ArrayList<ByteArray>()
        val ecs = ArrayList<ByteArray>()
        var k = 0
        val gen = rsGenerator(b.ecPerBlock)
        repeat(b.blocks1) { blocks += data.copyOfRange(k, k + b.data1); k += b.data1 }
        repeat(b.blocks2) { blocks += data.copyOfRange(k, k + b.data2); k += b.data2 }
        for (blk in blocks) ecs += rsRemainder(blk, gen)
        val out = ByteArray(b.totalCodewords)
        var o = 0
        val maxData = maxOf(b.data1, b.data2)
        for (i in 0 until maxData) for (blk in blocks) if (i < blk.size) out[o++] = blk[i]
        for (i in 0 until b.ecPerBlock) for (ec in ecs) out[o++] = ec[i]
        check(o == out.size)
        return out
    }

    // ---- GF(256) Reed-Solomon (primitive polynomial 0x11D) ----

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** The generator polynomial of degree [ec]: Π (x − α^i), coefficients highest degree first, leading 1 omitted. */
    fun rsGenerator(ec: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until ec) {
            val n = IntArray(g.size + 1)
            for (j in g.indices) { n[j] = n[j] xor g[j]; n[j + 1] = n[j + 1] xor gfMul(g[j], EXP[i]) }
            g = n
        }
        return g.copyOfRange(1, g.size)
    }

    /** The [gen].size EC codewords of [data]: the remainder of data·x^ec divided by the generator. */
    fun rsRemainder(data: ByteArray, gen: IntArray): ByteArray {
        val r = IntArray(gen.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in gen.indices) r[i] = r[i] xor gfMul(gen[i], factor)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    /** Evaluates the codeword polynomial (data then ec, highest degree first) at α^[power]; zero for a valid codeword. */
    fun evaluate(codeword: ByteArray, power: Int): Int {
        var acc = 0
        for (b in codeword) acc = gfMul(acc, EXP[power]) xor (b.toInt() and 0xFF)
        return acc
    }

    // ---- matrix ----

    fun alignmentPositions(version: Int): List<Int> {
        if (version == 1) return emptyList()
        val n = version / 7 + 2
        val step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
        val out = ArrayList<Int>()
        var pos = sizeOf(version) - 7
        repeat(n - 1) { out += pos; pos -= step }
        out += 6
        return out.sorted()
    }

    /** Finder patterns, separators, timing, alignment, the dark module, the reserved format and version areas. */
    private fun drawFunctionPatterns(m: Array<BooleanArray>, f: Array<BooleanArray>, version: Int) {
        val size = m.size
        fun set(row: Int, col: Int, dark: Boolean) { if (row in 0 until size && col in 0 until size) { m[row][col] = dark; f[row][col] = true } }
        fun finder(r0: Int, c0: Int) {
            for (dr in -4..4) for (dc in -4..4) {
                val d = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                set(r0 + dr, c0 + dc, d != 2 && d != 4)
            }
        }
        for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        finder(3, 3); finder(3, size - 4); finder(size - 4, 3)
        val al = alignmentPositions(version)
        for (i in al.indices) for (j in al.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.size - 1) || (i == al.size - 1 && j == 0)) continue
            for (dr in -2..2) for (dc in -2..2) set(al[i] + dr, al[j] + dc, maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1)
        }
        // reserve the format areas (drawn later) and the dark module
        for (i in 0..8) if (i != 6) { set(8, i, false); set(i, 8, false) }
        for (i in 0 until 8) { set(size - 1 - i, 8, false); set(8, size - 1 - i, false) }
        set(size - 8, 8, true)
        if (version >= 7) {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(b, a, bit); set(a, b, bit)
            }
        }
    }

    private fun drawFormat(m: Array<BooleanArray>, f: Array<BooleanArray>, ecl: Ecl, mask: Int) {
        val size = m.size
        val data = (ecl.formatBits shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        fun set(row: Int, col: Int, dark: Boolean) { m[row][col] = dark; f[row][col] = true }
        for (i in 0..5) set(i, 8, bit(i))
        set(7, 8, bit(6)); set(8, 8, bit(7)); set(8, 7, bit(8))
        for (i in 9..14) set(8, 14 - i, bit(i))
        for (i in 0..7) set(8, size - 1 - i, bit(i))
        for (i in 8..14) set(size - 15 + i, 8, bit(i))
        set(size - 8, 8, true)
    }

    /** Zigzag placement, two columns at a time from the right, skipping the timing column (ISO 18004 section 7.7.3). */
    private fun placeData(m: Array<BooleanArray>, f: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val col = right - j
                val upward = ((right + 1) and 2) == 0
                val row = if (upward) size - 1 - vert else vert
                if (!f[row][col] && i < data.size * 8) {
                    m[row][col] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                    i++
                }
            }
            right -= 2
        }
    }

    fun maskBit(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        else -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
    }

    private fun applyMask(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        for (r in m.indices) for (c in m.indices) if (!f[r][c] && maskBit(mask, r, c)) m[r][c] = !m[r][c]
    }

    /** ISO 18004 section 7.8.3 penalty rules N1–N4 (only the mask choice depends on it, never decodability). */
    private fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        var p = 0
        fun line(get: (Int) -> Boolean) {
            var run = 1
            for (i in 1 until size) {
                if (get(i) == get(i - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p += 1 } else run = 1
            }
            for (i in 0..size - 11) {
                var a = true; var b = true
                for (k in 0 until 11) {
                    val v = get(i + k)
                    if (v != FINDER_A[k]) a = false
                    if (v != FINDER_B[k]) b = false
                }
                if (a) p += 40
                if (b) p += 40
            }
        }
        for (r in 0 until size) line { c -> m[r][c] }
        for (c in 0 until size) line { r -> m[r][c] }
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val v = m[r][c]
            if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) p += 3
        }
        var dark = 0
        for (row in m) for (v in row) if (v) dark++
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        p += k.coerceAtLeast(0) * 10
        return p
    }

    private val FINDER_A = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
    private val FINDER_B = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size
        fun append(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun toBytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            return out
        }
    }
}
