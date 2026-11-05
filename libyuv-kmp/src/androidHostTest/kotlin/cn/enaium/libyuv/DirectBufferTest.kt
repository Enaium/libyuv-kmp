package cn.enaium.libyuv

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectBufferTest {
    @Test
    fun i420CopyUsesDirectBuffers() {
        val srcY = direct(10, 20, 30, 40)
        val srcU = direct(100)
        val srcV = direct(200)
        val dstY = directBuffer(4)
        val dstU = directBuffer(1)
        val dstV = directBuffer(1)

        val result = JniLibYuv.nativeCallDirect(
            Op.I420Copy.code,
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
            intArrayOf(2, 2, 2, 1, 1, 2, 1, 1),
        )

        assertEquals(0, result)
        assertEquals(listOf(10, 20, 30, 40), dstY.bytes(4))
        assertEquals(listOf(100), dstU.bytes(1))
        assertEquals(listOf(200), dstV.bytes(1))
    }

    @Test
    fun android420ToI420UsesDirectBuffers() {
        val srcY = direct(10, 20, 30, 40)
        val srcU = direct(100, 200)
        val srcV = direct(200, 100)
        val dstY = directBuffer(4)
        val dstU = directBuffer(1)
        val dstV = directBuffer(1)

        val result = JniLibYuv.nativeCallDirect(
            Op.Android420ToI420.code,
            arrayOf(srcY, srcU, srcV, dstY, dstU, dstV),
            intArrayOf(2, 2, 2, 2, 2, 2, 2, 1, 1),
        )

        assertEquals(0, result)
        assertEquals(listOf(10, 20, 30, 40), dstY.bytes(4))
        assertEquals(listOf(100), dstU.bytes(1))
        assertEquals(listOf(200), dstV.bytes(1))
    }
}

private fun direct(vararg values: Int): ByteBuffer =
    directBuffer(values.size).apply {
        values.forEachIndexed { index, value -> put(index, value.toByte()) }
    }

private fun directBuffer(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)

private fun ByteBuffer.bytes(size: Int): List<Int> =
    List(size) { index -> get(index).toInt() and 0xFF }
