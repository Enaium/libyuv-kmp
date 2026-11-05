/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.libyuv

/**
 * Scaling filter passed to the libyuv scaler.
 *
 * [NONE] is the fastest (nearest neighbour), [BILINEAR] is the default quality
 * mode, and [BOX] is used for large downscales.
 */
enum class FilterMode(val value: Int) {
    NONE(0),
    LINEAR(1),
    BILINEAR(2),
    BOX(3),
}

/** Rotation applied by the rotate functions. */
enum class Rotation(val degrees: Int) {
    NONE(0),
    CLOCKWISE_90(90),
    ROTATE_180(180),
    CLOCKWISE_270(270),
}

/**
 * FourCC values accepted by [LibYuv.convertToI420] and
 * [LibYuv.convertFromI420], matching libyuv's `FOURCC` macro.
 */
enum class FourCC(val value: Int) {
    I420(fourCC('I', '4', '2', '0')),
    J420(fourCC('J', '4', '2', '0')),
    H420(fourCC('H', '4', '2', '0')),
    U420(fourCC('U', '4', '2', '0')),
    I422(fourCC('I', '4', '2', '2')),
    I444(fourCC('I', '4', '4', '4')),
    NV12(fourCC('N', 'V', '1', '2')),
    NV21(fourCC('N', 'V', '2', '1')),
    YUY2(fourCC('Y', 'U', 'Y', '2')),
    UYVY(fourCC('U', 'Y', 'V', 'Y')),
    RGB24(fourCC('R', 'G', 'B', '3')),
    RAW(fourCC('R', 'A', 'W', ' ')),
    ARGB(fourCC('A', 'R', 'G', 'B')),
    BGRA(fourCC('B', 'G', 'R', 'A')),
    ABGR(fourCC('A', 'B', 'G', 'R')),
    RGBA(fourCC('R', 'G', 'B', 'A')),
    RGB565(fourCC('R', 'G', 'B', 'P')),
    I400(fourCC('I', '4', '0', '0')),
    J400(fourCC('J', '4', '0', '0')),
    I010(fourCC('I', '0', '1', '0')),
    I210(fourCC('I', '2', '1', '0')),
    I410(fourCC('I', '4', '1', '0')),
    I012(fourCC('I', '0', '1', '2')),
    I212(fourCC('I', '2', '1', '2')),
    I412(fourCC('I', '4', '1', '2')),
    P010(fourCC('P', '0', '1', '0')),
    P210(fourCC('P', '2', '1', '0')),
    P410(fourCC('P', '4', '1', '0')),
    P012(fourCC('P', '0', '1', '2')),
    P212(fourCC('P', '2', '1', '2')),
    P412(fourCC('P', '4', '1', '2')),

    /** Row stride, in bytes, of a packed image of [width] pixels. */
    ;

    internal fun defaultStride(width: Int): Int = when (this) {
        I420, J420, H420, U420, I422, I444, I400, J400 -> width
        NV12, NV21 -> width
        YUY2, UYVY, RGB565 -> width * 2
        RGB24 -> width * 3
        ARGB, BGRA, ABGR, RGBA -> width * 4
        RAW -> width
        I010, I210, I410, I012, I212, I412,
        P010, P210, P410, P012, P212, P412,
        -> width * 2
    }
}

/** libyuv CPU feature flags. See `libyuv/cpu_id.h`. */
object CpuFlag {
    const val HAS_ARM = 0x2
    const val HAS_NEON = 0x100
    const val HAS_NEON_DOT_PROD = 0x200
    const val HAS_NEON_I8MM = 0x400
    const val HAS_SVE = 0x800
    const val HAS_SVE2 = 0x1000
    const val HAS_SME = 0x2000
    const val HAS_SME2 = 0x4000

    const val HAS_X86 = 0x8
    const val HAS_SSE2 = 0x100
    const val HAS_SSSE3 = 0x200
    const val HAS_SSE41 = 0x400
    const val HAS_SSE42 = 0x800
    const val HAS_AVX = 0x1000
    const val HAS_AVX2 = 0x2000
    const val HAS_ERMS = 0x4000
    const val HAS_FMA3 = 0x10000
    const val HAS_F16C = 0x20000
    const val HAS_AVX512BW = 0x40000
    const val HAS_AVX512VL = 0x80000
    const val HAS_AVX512VNNI = 0x100000
    const val HAS_AVX512VBMI = 0x200000
    const val HAS_AVX512VBMI2 = 0x400000
    const val HAS_AVX512VBITALG = 0x800000

    const val HAS_WASM = 0x40
    const val HAS_WASM_SIMD = 0x100
}

/** Thrown when libyuv rejects an operation after the Kotlin-side checks pass. */
class LibYuvException(
    val operation: String,
    val code: Int,
) : RuntimeException("libyuv $operation failed with code $code")

/** Error code used when the platform bridge itself is unavailable. */
internal const val LIBYUV_BRIDGE_ERROR = -1_000_000

internal fun fourCC(a: Char, b: Char, c: Char, d: Char): Int =
    (a.code and 0xFF) or
        ((b.code and 0xFF) shl 8) or
        ((c.code and 0xFF) shl 16) or
        ((d.code and 0xFF) shl 24)

/**
 * YUV conversion, scaling, rotation and mirroring backed by libyuv.
 *
 * Every function writes into caller-provided [ByteArray]s. Strides are in
 * bytes and may be larger than the visible row width; the default values
 * describe a tightly packed image. Source and destination buffers must not
 * overlap unless the underlying libyuv operation explicitly supports it.
 */
object LibYuv {
    /** libyuv version (`LIBYUV_VERSION`). */
    val version: Int
        get() = libyuvVersion()

    // =====================================================================
    // Copies
    // =====================================================================

    fun i420Copy(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.I420Copy,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun i422Copy(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV, chromaHeight = height)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV, chromaHeight = height)
        invoke(
            Op.I422Copy,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun i444Copy(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = width,
        srcStrideV: Int = width,
        dstStrideY: Int = width,
        dstStrideU: Int = width,
        dstStrideV: Int = width,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV, width, height)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV, width, height)
        invoke(
            Op.I444Copy,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun nv12Copy(
        srcY: ByteArray, srcUv: ByteArray,
        dstY: ByteArray, dstUv: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideUv: Int = width,
        dstStrideY: Int = width,
        dstStrideUv: Int = width,
    ) {
        requireNv("src", srcY, srcUv, width, height, srcStrideY, srcStrideUv)
        requireNv("dst", dstY, dstUv, width, height, dstStrideY, dstStrideUv)
        invoke(
            Op.Nv12Copy,
            intArrayOf(width, height, srcStrideY, srcStrideUv, dstStrideY, dstStrideUv),
            arrayOf(srcY, srcUv, dstY, dstUv),
        )
    }

    fun nv21Copy(
        srcY: ByteArray, srcVu: ByteArray,
        dstY: ByteArray, dstVu: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideVu: Int = width,
        dstStrideY: Int = width,
        dstStrideVu: Int = width,
    ) {
        requireNv("src", srcY, srcVu, width, height, srcStrideY, srcStrideVu)
        requireNv("dst", dstY, dstVu, width, height, dstStrideY, dstStrideVu)
        invoke(
            Op.Nv21Copy,
            intArrayOf(width, height, srcStrideY, srcStrideVu, dstStrideY, dstStrideVu),
            arrayOf(srcY, srcVu, dstY, dstVu),
        )
    }

    // =====================================================================
    // I420 -> packed / planar
    // =====================================================================

    fun i420ToArgb(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideArgb: Int = width * 4,
    ) = i420ToPacked(
        Op.I420ToArgb, "ARGB", srcY, srcU, srcV, dstArgb, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideArgb, width * 4,
    )

    fun i420ToBgra(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstBgra: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideBgra: Int = width * 4,
    ) = i420ToPacked(
        Op.I420ToBgra, "BGRA", srcY, srcU, srcV, dstBgra, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideBgra, width * 4,
    )

    fun i420ToAbgr(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstAbgr: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideAbgr: Int = width * 4,
    ) = i420ToPacked(
        Op.I420ToAbgr, "ABGR", srcY, srcU, srcV, dstAbgr, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideAbgr, width * 4,
    )

    fun i420ToRgba(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstRgba: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideRgba: Int = width * 4,
    ) = i420ToPacked(
        Op.I420ToRgba, "RGBA", srcY, srcU, srcV, dstRgba, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideRgba, width * 4,
    )

    fun i420ToRgb24(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstRgb24: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideRgb24: Int = width * 3,
    ) = i420ToPacked(
        Op.I420ToRgb24, "RGB24", srcY, srcU, srcV, dstRgb24, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideRgb24, width * 3,
    )

    fun i420ToRgb565(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstRgb565: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideRgb565: Int = width * 2,
    ) = i420ToPacked(
        Op.I420ToRgb565, "RGB565", srcY, srcU, srcV, dstRgb565, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideRgb565, width * 2,
    )

    fun i420ToYuy2(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstYuy2: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideYuy2: Int = width * 2,
    ) = i420ToPacked(
        Op.I420ToYuy2, "YUY2", srcY, srcU, srcV, dstYuy2, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideYuy2, width * 2,
    )

    fun i420ToUyvy(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstUyvy: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideUyvy: Int = width * 2,
    ) = i420ToPacked(
        Op.I420ToUyvy, "UYVY", srcY, srcU, srcV, dstUyvy, width, height,
        srcStrideY, srcStrideU, srcStrideV, dstStrideUyvy, width * 2,
    )

    private fun i420ToPacked(
        op: Op, name: String,
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int, srcStrideU: Int, srcStrideV: Int,
        dstStride: Int, packedRowBytes: Int,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requirePacked("dst $name", dst, height, dstStride, packedRowBytes)
        invoke(
            op,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStride),
            arrayOf(srcY, srcU, srcV, dst),
        )
    }

    fun i420ToNv12(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstY: ByteArray, dstUv: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideUv: Int = width,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireNv("dst", dstY, dstUv, width, height, dstStrideY, dstStrideUv)
        invoke(
            Op.I420ToNv12,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideUv),
            arrayOf(srcY, srcU, srcV, dstY, dstUv),
        )
    }

    fun i420ToNv21(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dstY: ByteArray, dstVu: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideVu: Int = width,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireNv("dst", dstY, dstVu, width, height, dstStrideY, dstStrideVu)
        invoke(
            Op.I420ToNv21,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideVu),
            arrayOf(srcY, srcU, srcV, dstY, dstVu),
        )
    }

    fun i420ToI422(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV, chromaHeight = height)
        invoke(
            Op.I420ToI422,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun i420ToI444(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideU: Int = width,
        dstStrideV: Int = width,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV, width, height)
        invoke(
            Op.I420ToI444,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    // =====================================================================
    // NV12 / NV21 -> planar / packed
    // =====================================================================

    fun nv12ToI420(
        srcY: ByteArray, srcUv: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideUv: Int = width,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireNv("src", srcY, srcUv, width, height, srcStrideY, srcStrideUv)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.Nv12ToI420,
            intArrayOf(width, height, srcStrideY, srcStrideUv, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcUv, dstY, dstU, dstV),
        )
    }

    fun nv21ToI420(
        srcY: ByteArray, srcVu: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideVu: Int = width,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireNv("src", srcY, srcVu, width, height, srcStrideY, srcStrideVu)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.Nv21ToI420,
            intArrayOf(width, height, srcStrideY, srcStrideVu, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcVu, dstY, dstU, dstV),
        )
    }

    /**
     * Converts the three planes of an Android `YUV_420_888` image to I420.
     *
     * Android exposes the chroma planes either planar ([srcPixelStrideUv] = 1)
     * or interleaved ([srcPixelStrideUv] = 2); [srcU] and [srcV] may point at the
     * same allocation with an offset between them. libyuv's Android420ToI420
     * handles both layouts.
     */
    fun android420ToI420(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        srcPixelStrideUv: Int = 1,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requirePositive("width", width)
        requirePositive("height", height)
        require(srcPixelStrideUv == 1 || srcPixelStrideUv == 2) {
            "srcPixelStrideUv must be 1 or 2, was $srcPixelStrideUv"
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        requirePacked("src Y", srcY, height, srcStrideY, width)
        requirePacked("src U", srcU, chromaHeight, srcStrideU, chromaWidth * srcPixelStrideUv)
        requirePacked("src V", srcV, chromaHeight, srcStrideV, chromaWidth * srcPixelStrideUv)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.Android420ToI420,
            intArrayOf(
                width, height,
                srcStrideY, srcStrideU, srcStrideV, srcPixelStrideUv,
                dstStrideY, dstStrideU, dstStrideV,
            ),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun nv12ToArgb(
        srcY: ByteArray, srcUv: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideUv: Int = width,
        dstStrideArgb: Int = width * 4,
    ) = nvToPacked(
        Op.Nv12ToArgb, "ARGB", srcY, srcUv, dstArgb, width, height,
        srcStrideY, srcStrideUv, dstStrideArgb, width * 4,
    )

    fun nv21ToArgb(
        srcY: ByteArray, srcVu: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideVu: Int = width,
        dstStrideArgb: Int = width * 4,
    ) = nvToPacked(
        Op.Nv21ToArgb, "ARGB", srcY, srcVu, dstArgb, width, height,
        srcStrideY, srcStrideVu, dstStrideArgb, width * 4,
    )

    fun nv12ToRgb24(
        srcY: ByteArray, srcUv: ByteArray, dstRgb24: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideUv: Int = width,
        dstStrideRgb24: Int = width * 3,
    ) = nvToPacked(
        Op.Nv12ToRgb24, "RGB24", srcY, srcUv, dstRgb24, width, height,
        srcStrideY, srcStrideUv, dstStrideRgb24, width * 3,
    )

    fun nv21ToRgb24(
        srcY: ByteArray, srcVu: ByteArray, dstRgb24: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideVu: Int = width,
        dstStrideRgb24: Int = width * 3,
    ) = nvToPacked(
        Op.Nv21ToRgb24, "RGB24", srcY, srcVu, dstRgb24, width, height,
        srcStrideY, srcStrideVu, dstStrideRgb24, width * 3,
    )

    private fun nvToPacked(
        op: Op, name: String,
        srcY: ByteArray, srcUv: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int, srcStrideUv: Int, dstStride: Int, packedRowBytes: Int,
    ) {
        requireNv("src", srcY, srcUv, width, height, srcStrideY, srcStrideUv)
        requirePacked("dst $name", dst, height, dstStride, packedRowBytes)
        invoke(
            op,
            intArrayOf(width, height, srcStrideY, srcStrideUv, dstStride),
            arrayOf(srcY, srcUv, dst),
        )
    }

    // =====================================================================
    // Packed -> I420 / packed
    // =====================================================================

    fun argbToI420(
        srcArgb: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.ArgbToI420, "ARGB", srcArgb, dstY, dstU, dstV, width, height,
        srcStrideArgb, width * 4, dstStrideY, dstStrideU, dstStrideV,
    )

    fun bgraToI420(
        srcBgra: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideBgra: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.BgraToI420, "BGRA", srcBgra, dstY, dstU, dstV, width, height,
        srcStrideBgra, width * 4, dstStrideY, dstStrideU, dstStrideV,
    )

    fun abgrToI420(
        srcAbgr: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideAbgr: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.AbgrToI420, "ABGR", srcAbgr, dstY, dstU, dstV, width, height,
        srcStrideAbgr, width * 4, dstStrideY, dstStrideU, dstStrideV,
    )

    fun rgbaToI420(
        srcRgba: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideRgba: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.RgbaToI420, "RGBA", srcRgba, dstY, dstU, dstV, width, height,
        srcStrideRgba, width * 4, dstStrideY, dstStrideU, dstStrideV,
    )

    fun rgb24ToI420(
        srcRgb24: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideRgb24: Int = width * 3,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.Rgb24ToI420, "RGB24", srcRgb24, dstY, dstU, dstV, width, height,
        srcStrideRgb24, width * 3, dstStrideY, dstStrideU, dstStrideV,
    )

    fun yuy2ToI420(
        srcYuy2: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideYuy2: Int = width * 2,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.Yuy2ToI420, "YUY2", srcYuy2, dstY, dstU, dstV, width, height,
        srcStrideYuy2, width * 2, dstStrideY, dstStrideU, dstStrideV,
    )

    fun uyvyToI420(
        srcUyvy: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideUyvy: Int = width * 2,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) = packedToI420(
        Op.UyvyToI420, "UYVY", srcUyvy, dstY, dstU, dstV, width, height,
        srcStrideUyvy, width * 2, dstStrideY, dstStrideU, dstStrideV,
    )

    private fun packedToI420(
        op: Op, name: String,
        src: ByteArray, dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStride: Int, srcRowBytes: Int,
        dstStrideY: Int, dstStrideU: Int, dstStrideV: Int,
    ) {
        requirePacked("src $name", src, height, srcStride, srcRowBytes)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            op,
            intArrayOf(width, height, srcStride, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(src, dstY, dstU, dstV),
        )
    }

    fun argbToRgb24(
        srcArgb: ByteArray, dstRgb24: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideRgb24: Int = width * 3,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        requirePacked("dst RGB24", dstRgb24, height, dstStrideRgb24, width * 3)
        invoke(
            Op.ArgbToRgb24,
            intArrayOf(width, height, srcStrideArgb, dstStrideRgb24),
            arrayOf(srcArgb, dstRgb24),
        )
    }

    fun argbToRgb565(
        srcArgb: ByteArray, dstRgb565: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideRgb565: Int = width * 2,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        requirePacked("dst RGB565", dstRgb565, height, dstStrideRgb565, width * 2)
        invoke(
            Op.ArgbToRgb565,
            intArrayOf(width, height, srcStrideArgb, dstStrideRgb565),
            arrayOf(srcArgb, dstRgb565),
        )
    }

    fun argbToNv12(
        srcArgb: ByteArray, dstY: ByteArray, dstUv: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideUv: Int = width,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        requireNv("dst", dstY, dstUv, width, height, dstStrideY, dstStrideUv)
        invoke(
            Op.ArgbToNv12,
            intArrayOf(width, height, srcStrideArgb, dstStrideY, dstStrideUv),
            arrayOf(srcArgb, dstY, dstUv),
        )
    }

    fun argbToNv21(
        srcArgb: ByteArray, dstY: ByteArray, dstVu: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideY: Int = width,
        dstStrideVu: Int = width,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        requireNv("dst", dstY, dstVu, width, height, dstStrideY, dstStrideVu)
        invoke(
            Op.ArgbToNv21,
            intArrayOf(width, height, srcStrideArgb, dstStrideY, dstStrideVu),
            arrayOf(srcArgb, dstY, dstVu),
        )
    }

    fun rgb24ToArgb(
        srcRgb24: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideRgb24: Int = width * 3,
        dstStrideArgb: Int = width * 4,
    ) {
        requirePacked("src RGB24", srcRgb24, height, srcStrideRgb24, width * 3)
        requirePacked("dst ARGB", dstArgb, height, dstStrideArgb, width * 4)
        invoke(
            Op.Rgb24ToArgb,
            intArrayOf(width, height, srcStrideRgb24, dstStrideArgb),
            arrayOf(srcRgb24, dstArgb),
        )
    }

    fun rgb565ToArgb(
        srcRgb565: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideRgb565: Int = width * 2,
        dstStrideArgb: Int = width * 4,
    ) {
        requirePacked("src RGB565", srcRgb565, height, srcStrideRgb565, width * 2)
        requirePacked("dst ARGB", dstArgb, height, dstStrideArgb, width * 4)
        invoke(
            Op.Rgb565ToArgb,
            intArrayOf(width, height, srcStrideRgb565, dstStrideArgb),
            arrayOf(srcRgb565, dstArgb),
        )
    }

    // =====================================================================
    // FourCC conversions
    // =====================================================================

    /**
     * Converts any libyuv-supported packed/planar input to I420.
     *
     * @param srcWidth width of the input image before cropping.
     * @param srcHeight height of the input image before cropping.
     */
    fun convertToI420(
        src: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        srcWidth: Int, srcHeight: Int,
        dstStrideY: Int = srcWidth,
        dstStrideU: Int = (srcWidth + 1) / 2,
        dstStrideV: Int = dstStrideU,
        cropX: Int = 0,
        cropY: Int = 0,
        cropWidth: Int = srcWidth,
        cropHeight: Int = srcHeight,
        rotation: Rotation = Rotation.NONE,
        fourCC: FourCC = FourCC.I420,
    ) {
        requirePositive("srcWidth", srcWidth)
        requirePositive("srcHeight", srcHeight)
        requirePositive("cropWidth", cropWidth)
        requirePositive("cropHeight", cropHeight)
        require(cropX >= 0 && cropY >= 0) { "crop offsets must not be negative" }
        require(cropX + cropWidth <= srcWidth) { "crop rectangle exceeds srcWidth" }
        require(cropY + cropHeight <= srcHeight) { "crop rectangle exceeds srcHeight" }
        require(cropWidth % 2 == 0 && cropHeight % 2 == 0) { "I420 dimensions must be even" }
        requireI420("dst", dstY, dstU, dstV, cropWidth, cropHeight, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.ConvertToI420,
            intArrayOf(
                dstStrideY, dstStrideU, dstStrideV,
                cropX, cropY, srcWidth, srcHeight, cropWidth, cropHeight,
                rotation.degrees, fourCC.value,
            ),
            arrayOf(src, dstY, dstU, dstV),
        )
    }

    /**
     * Converts I420 to a packed/planar format selected by [fourCC].
     * [dstStride] defaults to the tightly packed row size for that format.
     */
    fun convertFromI420(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        fourCC: FourCC = FourCC.NV12,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStride: Int = fourCC.defaultStride(width),
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        require(dstStride > 0) { "dstStride must be > 0" }
        require(dst.size.toLong() >= dstStride.toLong() * height) {
            "dst is ${dst.size} bytes, needs ${dstStride.toLong() * height} (stride=$dstStride, height=$height)"
        }
        invoke(
            Op.ConvertFromI420,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStride, fourCC.value),
            arrayOf(srcY, srcU, srcV, dst),
        )
    }

    // =====================================================================
    // Scaling
    // =====================================================================

    fun i420Scale(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int,
        srcStrideY: Int = srcWidth,
        srcStrideU: Int = (srcWidth + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = dstWidth,
        dstStrideU: Int = (dstWidth + 1) / 2,
        dstStrideV: Int = dstStrideU,
        filterMode: FilterMode = FilterMode.BILINEAR,
    ) {
        requirePositive("srcWidth", srcWidth)
        requirePositive("srcHeight", srcHeight)
        requirePositive("dstWidth", dstWidth)
        requirePositive("dstHeight", dstHeight)
        requireI420("src", srcY, srcU, srcV, srcWidth, srcHeight, srcStrideY, srcStrideU, srcStrideV)
        requireI420("dst", dstY, dstU, dstV, dstWidth, dstHeight, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.I420Scale,
            intArrayOf(
                srcWidth, srcHeight, dstWidth, dstHeight,
                srcStrideY, srcStrideU, srcStrideV,
                dstStrideY, dstStrideU, dstStrideV,
                filterMode.value,
            ),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun argbScale(
        srcArgb: ByteArray, dstArgb: ByteArray,
        srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int,
        srcStrideArgb: Int = srcWidth * 4,
        dstStrideArgb: Int = dstWidth * 4,
        filterMode: FilterMode = FilterMode.BILINEAR,
    ) {
        requirePacked("src ARGB", srcArgb, srcHeight, srcStrideArgb, srcWidth * 4)
        requirePacked("dst ARGB", dstArgb, dstHeight, dstStrideArgb, dstWidth * 4)
        invoke(
            Op.ArgbScale,
            intArrayOf(
                srcWidth, srcHeight, dstWidth, dstHeight,
                srcStrideArgb, dstStrideArgb, filterMode.value,
            ),
            arrayOf(srcArgb, dstArgb),
        )
    }

    fun scalePlane(
        src: ByteArray, dst: ByteArray,
        srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int,
        srcStride: Int = srcWidth,
        dstStride: Int = dstWidth,
        filterMode: FilterMode = FilterMode.BILINEAR,
    ) {
        requirePacked("src", src, srcHeight, srcStride, srcWidth)
        requirePacked("dst", dst, dstHeight, dstStride, dstWidth)
        invoke(
            Op.ScalePlane,
            intArrayOf(srcWidth, srcHeight, dstWidth, dstHeight, srcStride, dstStride, filterMode.value),
            arrayOf(src, dst),
        )
    }

    fun nv12Scale(
        srcY: ByteArray, srcUv: ByteArray, dstY: ByteArray, dstUv: ByteArray,
        srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int,
        srcStrideY: Int = srcWidth,
        srcStrideUv: Int = srcWidth,
        dstStrideY: Int = dstWidth,
        dstStrideUv: Int = dstWidth,
        filterMode: FilterMode = FilterMode.BILINEAR,
    ) {
        requireNv("src", srcY, srcUv, srcWidth, srcHeight, srcStrideY, srcStrideUv)
        requireNv("dst", dstY, dstUv, dstWidth, dstHeight, dstStrideY, dstStrideUv)
        invoke(
            Op.Nv12Scale,
            intArrayOf(
                srcWidth, srcHeight, dstWidth, dstHeight,
                srcStrideY, srcStrideUv, dstStrideY, dstStrideUv,
                filterMode.value,
            ),
            arrayOf(srcY, srcUv, dstY, dstUv),
        )
    }

    // =====================================================================
    // Rotation
    // =====================================================================

    fun i420Rotate(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        rotation: Rotation = Rotation.NONE,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = 0,
        dstStrideU: Int = 0,
        dstStrideV: Int = 0,
    ) {
        requirePositive("width", width)
        requirePositive("height", height)
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        val dstWidth = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) height else width
        val dstHeight = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) width else height
        val dsY = if (dstStrideY > 0) dstStrideY else dstWidth
        val dsU = if (dstStrideU > 0) dstStrideU else (dstWidth + 1) / 2
        val dsV = if (dstStrideV > 0) dstStrideV else dsU
        requireI420("dst", dstY, dstU, dstV, dstWidth, dstHeight, dsY, dsU, dsV)
        invoke(
            Op.I420Rotate,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dsY, dsU, dsV, rotation.degrees),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun argbRotate(
        srcArgb: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        rotation: Rotation = Rotation.NONE,
        srcStrideArgb: Int = width * 4,
        dstStrideArgb: Int = 0,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        val dstWidth = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) height else width
        val dstHeight = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) width else height
        val ds = if (dstStrideArgb > 0) dstStrideArgb else dstWidth * 4
        requirePacked("dst ARGB", dstArgb, dstHeight, ds, dstWidth * 4)
        invoke(
            Op.ArgbRotate,
            intArrayOf(width, height, srcStrideArgb, ds, rotation.degrees),
            arrayOf(srcArgb, dstArgb),
        )
    }

    fun rotatePlane(
        src: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        rotation: Rotation = Rotation.NONE,
        srcStride: Int = width,
        dstStride: Int = 0,
    ) {
        requirePacked("src", src, height, srcStride, width)
        val dstWidth = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) height else width
        val dstHeight = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) width else height
        val ds = if (dstStride > 0) dstStride else dstWidth
        requirePacked("dst", dst, dstHeight, ds, dstWidth)
        invoke(
            Op.RotatePlane,
            intArrayOf(width, height, srcStride, ds, rotation.degrees),
            arrayOf(src, dst),
        )
    }

    // =====================================================================
    // Mirroring
    // =====================================================================

    fun i420Mirror(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        width: Int, height: Int,
        srcStrideY: Int = width,
        srcStrideU: Int = (width + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = width,
        dstStrideU: Int = (width + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireI420("src", srcY, srcU, srcV, width, height, srcStrideY, srcStrideU, srcStrideV)
        requireI420("dst", dstY, dstU, dstV, width, height, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.I420Mirror,
            intArrayOf(width, height, srcStrideY, srcStrideU, srcStrideV, dstStrideY, dstStrideU, dstStrideV),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    fun argbMirror(
        srcArgb: ByteArray, dstArgb: ByteArray,
        width: Int, height: Int,
        srcStrideArgb: Int = width * 4,
        dstStrideArgb: Int = width * 4,
    ) {
        requirePacked("src ARGB", srcArgb, height, srcStrideArgb, width * 4)
        requirePacked("dst ARGB", dstArgb, height, dstStrideArgb, width * 4)
        invoke(
            Op.ArgbMirror,
            intArrayOf(width, height, srcStrideArgb, dstStrideArgb),
            arrayOf(srcArgb, dstArgb),
        )
    }

    fun planeMirror(
        src: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        srcStride: Int = width,
        dstStride: Int = width,
    ) {
        requirePacked("src", src, height, srcStride, width)
        requirePacked("dst", dst, height, dstStride, width)
        invoke(
            Op.PlaneMirror,
            intArrayOf(width, height, srcStride, dstStride),
            arrayOf(src, dst),
        )
    }

    fun rgb24Mirror(
        srcRgb24: ByteArray, dstRgb24: ByteArray,
        width: Int, height: Int,
        srcStrideRgb24: Int = width * 3,
        dstStrideRgb24: Int = width * 3,
    ) {
        requirePacked("src RGB24", srcRgb24, height, srcStrideRgb24, width * 3)
        requirePacked("dst RGB24", dstRgb24, height, dstStrideRgb24, width * 3)
        invoke(
            Op.Rgb24Mirror,
            intArrayOf(width, height, srcStrideRgb24, dstStrideRgb24),
            arrayOf(srcRgb24, dstRgb24),
        )
    }

    // =====================================================================
    // Plane utilities
    // =====================================================================

    fun i420Rect(
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        x: Int, y: Int, width: Int, height: Int,
        valueY: Int, valueU: Int, valueV: Int,
        dstStrideY: Int,
        dstStrideU: Int = (dstStrideY + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        require(x >= 0 && y >= 0) { "x and y must not be negative" }
        require(x % 2 == 0 && y % 2 == 0) { "x and y must be even" }
        require(width > 0 && height > 0) { "width and height must be > 0" }
        require(width % 2 == 0 && height % 2 == 0) { "width and height must be even" }
        require(valueY in 0..255 && valueU in 0..255 && valueV in 0..255) { "values must be in 0..255" }
        val endX = x + width
        val endY = y + height
        requirePacked("dst Y", dstY, endY, dstStrideY, endX)
        requirePacked("dst U", dstU, endY / 2, dstStrideU, endX / 2)
        requirePacked("dst V", dstV, endY / 2, dstStrideV, endX / 2)
        invoke(
            Op.I420Rect,
            intArrayOf(x, y, width, height, dstStrideY, dstStrideU, dstStrideV, valueY, valueU, valueV),
            arrayOf(dstY, dstU, dstV),
        )
    }

    fun setPlane(
        dst: ByteArray,
        width: Int, height: Int,
        value: Int,
        dstStride: Int = width,
    ) {
        requirePositive("width", width)
        requirePositive("height", height)
        require(value in 0..255) { "value must be in 0..255" }
        requirePacked("dst", dst, height, dstStride, width)
        invoke(
            Op.SetPlane,
            intArrayOf(width, height, dstStride, value),
            arrayOf(dst),
        )
    }

    fun copyPlane(
        src: ByteArray, dst: ByteArray,
        width: Int, height: Int,
        srcStride: Int = width,
        dstStride: Int = width,
    ) {
        requirePacked("src", src, height, srcStride, width)
        requirePacked("dst", dst, height, dstStride, width)
        invoke(
            Op.CopyPlane,
            intArrayOf(width, height, srcStride, dstStride),
            arrayOf(src, dst),
        )
    }

    fun i420Crop(
        srcY: ByteArray, srcU: ByteArray, srcV: ByteArray,
        dstY: ByteArray, dstU: ByteArray, dstV: ByteArray,
        srcWidth: Int, srcHeight: Int,
        cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int,
        srcStrideY: Int = srcWidth,
        srcStrideU: Int = (srcWidth + 1) / 2,
        srcStrideV: Int = srcStrideU,
        dstStrideY: Int = cropWidth,
        dstStrideU: Int = (cropWidth + 1) / 2,
        dstStrideV: Int = dstStrideU,
    ) {
        requireI420("src", srcY, srcU, srcV, srcWidth, srcHeight, srcStrideY, srcStrideU, srcStrideV)
        require(cropX % 2 == 0 && cropY % 2 == 0) { "cropX and cropY must be even" }
        require(cropWidth > 0 && cropHeight > 0) { "crop dimensions must be > 0" }
        require(cropX + cropWidth <= srcWidth) { "crop rectangle exceeds srcWidth" }
        require(cropY + cropHeight <= srcHeight) { "crop rectangle exceeds srcHeight" }
        requireI420("dst", dstY, dstU, dstV, cropWidth, cropHeight, dstStrideY, dstStrideU, dstStrideV)
        invoke(
            Op.I420Crop,
            intArrayOf(
                cropX, cropY, cropWidth, cropHeight,
                srcStrideY, srcStrideU, srcStrideV,
                dstStrideY, dstStrideU, dstStrideV,
            ),
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
        )
    }

    // =====================================================================
    // CPU dispatch
    // =====================================================================

    /** Applies [flags] to libyuv's runtime dispatch and returns the previous mask. */
    fun maskCpuFlags(flags: Int): Int =
        callRaw(Op.MaskCpuFlags, intArrayOf(flags), emptyArray())

    /** Re-detects the host CPU features. */
    fun initCpuFlags() {
        callRaw(Op.InitCpuFlags, IntArray(0), emptyArray())
    }

    /** Whether the CPU implements the instruction set selected by [flag]. */
    fun testCpuFlag(flag: Int): Boolean =
        callRaw(Op.TestCpuFlag, intArrayOf(flag), emptyArray()) > 0
}

// =========================================================================
// Validation helpers
// =========================================================================

internal const val MAX_PACKET_SIZE = Int.MAX_VALUE - 16

private fun requirePositive(name: String, value: Int) {
    require(value > 0) { "$name must be > 0, was $value" }
}

private fun requireI420(
    name: String,
    y: ByteArray, u: ByteArray, v: ByteArray,
    width: Int, height: Int,
    strideY: Int, strideU: Int, strideV: Int,
    chromaWidth: Int = (width + 1) / 2,
    chromaHeight: Int = (height + 1) / 2,
) {
    requirePositive("width", width)
    requirePositive("height", height)
    requirePacked("$name Y", y, height, strideY, width)
    requirePacked("$name U", u, chromaHeight, strideU, chromaWidth)
    requirePacked("$name V", v, chromaHeight, strideV, chromaWidth)
}

private fun requireNv(
    name: String,
    y: ByteArray, uv: ByteArray,
    width: Int, height: Int,
    strideY: Int, strideUv: Int,
) {
    requirePositive("width", width)
    requirePositive("height", height)
    requirePacked("$name Y", y, height, strideY, width)
    requirePacked("$name UV", uv, (height + 1) / 2, strideUv, width)
}

private fun requirePacked(name: String, buffer: ByteArray, rows: Int, stride: Int, rowBytes: Int) {
    require(rows > 0) { "rows must be > 0, was $rows" }
    require(rowBytes > 0) { "rowBytes must be > 0, was $rowBytes" }
    require(stride >= rowBytes) { "$name stride must be >= $rowBytes, was $stride" }
    val needed = stride.toLong() * (rows - 1) + rowBytes
    require(buffer.size.toLong() >= needed) {
        "$name is ${buffer.size} bytes, needs $needed (stride=$stride, rows=$rows, rowBytes=$rowBytes)"
    }
}

// =========================================================================
// Dispatch
// =========================================================================

internal enum class Op(val code: Int) {
    I420Copy(1),
    I422Copy(2),
    I444Copy(3),
    Nv12Copy(4),
    Nv21Copy(5),
    I420ToArgb(6),
    I420ToBgra(7),
    I420ToAbgr(8),
    I420ToRgba(9),
    I420ToRgb24(10),
    I420ToRgb565(11),
    I420ToNv12(12),
    I420ToNv21(13),
    I420ToYuy2(14),
    I420ToUyvy(15),
    I420ToI422(16),
    I420ToI444(17),
    Nv12ToI420(18),
    Nv21ToI420(19),
    Nv12ToArgb(20),
    Nv21ToArgb(21),
    Nv12ToRgb24(22),
    Nv21ToRgb24(23),
    ArgbToI420(24),
    BgraToI420(25),
    AbgrToI420(26),
    RgbaToI420(27),
    ArgbToRgb24(28),
    ArgbToRgb565(29),
    ArgbToNv12(30),
    ArgbToNv21(31),
    Rgb24ToI420(32),
    Rgb24ToArgb(33),
    Rgb565ToArgb(34),
    Yuy2ToI420(35),
    UyvyToI420(36),
    ConvertToI420(37),
    ConvertFromI420(38),
    I420Scale(39),
    ArgbScale(40),
    ScalePlane(41),
    Nv12Scale(42),
    I420Rotate(43),
    ArgbRotate(44),
    RotatePlane(45),
    I420Mirror(46),
    ArgbMirror(47),
    PlaneMirror(48),
    Rgb24Mirror(49),
    I420Rect(50),
    SetPlane(51),
    CopyPlane(52),
    I420Crop(53),
    MaskCpuFlags(54),
    InitCpuFlags(55),
    TestCpuFlag(56),
    Android420ToI420(57),
}

private fun invoke(op: Op, args: IntArray, regions: Array<ByteArray>) {
    val code = libyuvCall(op.code, regions, args)
    if (code != 0) throw LibYuvException(op.name, code)
}

private fun callRaw(op: Op, args: IntArray, regions: Array<ByteArray>): Int =
    libyuvCall(op.code, regions, args)

// =========================================================================
// Platform bridge
// =========================================================================

internal expect fun libyuvVersion(): Int

internal expect fun libyuvCall(op: Int, regions: Array<ByteArray>, args: IntArray): Int
