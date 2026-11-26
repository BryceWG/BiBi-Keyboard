package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.jaredmdobson.concentus.OpusApplication
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DoubaoImeAndWeTypeProtocolTest {

    @Test
    fun opusEncoderProducesPacketsFor10msAnd20msFrames() {
        val encoder10ms = RawOpusFrameEncoder(
            sampleRate = 16000,
            channels = 1,
            application = OpusApplication.OPUS_APPLICATION_AUDIO,
            bitrate = 32000,
            useVbr = false,
            frameSamples = 160
        )
        val packets10ms = mutableListOf<ByteArray>()
        encoder10ms.feed(ByteArray(320 * 2 + 100)) { packets10ms += it }
        assertEquals(2, packets10ms.size)
        encoder10ms.flush { packets10ms += it }
        assertEquals(3, packets10ms.size)
        assertTrue(packets10ms.all { it.isNotEmpty() })

        val encoder20ms = RawOpusFrameEncoder(
            sampleRate = 16000,
            channels = 1,
            application = OpusApplication.OPUS_APPLICATION_VOIP,
            bitrate = 64000,
            useVbr = true,
            complexity = 9,
            frameSamples = 320
        )
        val packets20ms = mutableListOf<ByteArray>()
        encoder20ms.feed(ByteArray(640 * 3 + 50)) { packets20ms += it }
        assertEquals(3, packets20ms.size)
        encoder20ms.flush { packets20ms += it }
        assertEquals(4, packets20ms.size)
        assertTrue(packets20ms.all { it.isNotEmpty() })
    }

    @Test
    fun doubaoImeProtobufRoundtripAndTtEncryptHeader() {
        val encoded = DoubaoImeProtocol.encodeRequest(
            namespace = "ASR",
            event = "TaskRequest",
            taskId = "task-123",
            appkey = "",
            payload = "{\"extra\":{}}",
            audio = byteArrayOf(1, 2, 3, 4),
            seqId = 7L
        )
        val fields = LiteProtobuf.parseFields(encoded)
        assertEquals("ASR", LiteProtobuf.lastString(fields, 3))
        assertEquals("TaskRequest", LiteProtobuf.lastString(fields, 5))
        assertEquals("{\"extra\":{}}", LiteProtobuf.lastString(fields, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), LiteProtobuf.lastBytes(fields, 7))
        assertEquals("task-123", LiteProtobuf.lastString(fields, 8))
        assertEquals(7L, LiteProtobuf.lastInt(fields, 9))

        val respBytes = ByteArrayOutputStream().apply {
            write(LiteProtobuf.encodeString(1, "task-123"))
            write(LiteProtobuf.encodeString(4, "SessionFinished"))
            write(LiteProtobuf.encodeInt(5, 200L))
            write(LiteProtobuf.encodeString(6, "ok"))
            write(LiteProtobuf.encodeString(7, "{\"results\":[{\"text\":\"你好\"}]}"))
            write(LiteProtobuf.encodeInt(9, 12L))
        }.toByteArray()
        val resp = DoubaoImeProtocol.decodeResponse(respBytes)
        assertEquals("task-123", resp.taskId)
        assertEquals("SessionFinished", resp.event)
        assertEquals(200, resp.statusCode)
        assertEquals("ok", resp.statusText)
        assertEquals("{\"results\":[{\"text\":\"你好\"}]}", resp.payload)
        assertEquals(12L, resp.seqId)

        val salt = ByteArray(32) { it.toByte() }
        val ttEncrypted = DoubaoImeIdentityStore.encodeTtEncrypt(byteArrayOf(0x1F, 0x8B.toByte(), 0x08), salt)
        assertArrayEquals(
            byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00),
            ttEncrypted.copyOfRange(0, 6)
        )
        assertArrayEquals(salt, ttEncrypted.copyOfRange(6, 38))
        assertTrue(ttEncrypted.size > 38)
    }

    @Test
    fun weTypeCryptoEcdhAesAndSnappyRoundtrip() {
        val privA = WeTypeCrypto.randomScalar()
        val privB = WeTypeCrypto.randomScalar()
        val pubA = WeTypeCrypto.encodeUncompressedPoint(WeTypeCrypto.pointMul(privA)!!)
        val pubB = WeTypeCrypto.encodeUncompressedPoint(WeTypeCrypto.pointMul(privB)!!)
        val sharedA = WeTypeCrypto.sharedKey(privA, pubB)
        val sharedB = WeTypeCrypto.sharedKey(privB, pubA)
        assertEquals(32, sharedA.size)
        assertArrayEquals(sharedA, sharedB)

        val payload = "WeType Snappy + AES-256-ECB test payload 中文测试 1234567890".repeat(3)
            .toByteArray(Charsets.UTF_8)
        val compressed = WeTypeCrypto.snappyCompress(payload)
        val encrypted = WeTypeCrypto.aesCrypt(sharedA, compressed, encrypt = true)
        val decrypted = WeTypeCrypto.aesCrypt(sharedB, encrypted, encrypt = false)
        val decompressed = WeTypeCrypto.snappyDecompress(decrypted)
        assertArrayEquals(payload, decompressed)

        // 验证 ECDH 乘法满足交换律且标量 1 返回基点
        val gPoint = WeTypeCrypto.pointMul(BigInteger.ONE)!!
        assertEquals(130, WeTypeCrypto.encodeUncompressedPoint(gPoint).length)
    }

    @Test
    fun weTypeRpcClientCompletesMockHandshakeAndVoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WeTypeIdentityStore.resetIdentity(context)
        val serverPriv = WeTypeCrypto.randomScalar()
        val serverPub = WeTypeCrypto.encodeUncompressedPoint(WeTypeCrypto.pointMul(serverPriv)!!)
        var negotiatedKey: ByteArray? = null

        val rpc = WeTypeRpcClient { frame ->
            val outer = LiteProtobuf.parseFields(frame)
            val httpBytes = LiteProtobuf.lastBytes(outer, 5)!!
            val httpFields = LiteProtobuf.parseFields(httpBytes)
            val path = LiteProtobuf.lastString(httpFields, 3)
            val body = LiteProtobuf.lastBytes(httpFields, 6) ?: ByteArray(0)

            val (respBody, respHeaders) = when (path) {
                "/timestamp" -> LiteProtobuf.encodeInt(1, 1_760_000_000_000L) to emptyList<Pair<String, String>>()
                "/oauth_pubkey_v2" -> {
                    val req = LiteProtobuf.parseFields(WeTypeCrypto.aesCrypt(WeTypeCrypto.BOOT_KEY, body, false))
                    val clientPub = LiteProtobuf.lastString(req, 1)
                    negotiatedKey = WeTypeCrypto.sharedKey(serverPriv, clientPub)
                    val reply = LiteProtobuf.encodeString(2, serverPub) + LiteProtobuf.encodeString(4, "tok-1")
                    WeTypeCrypto.aesCrypt(WeTypeCrypto.BOOT_KEY, reply, true) to emptyList()
                }
                "/gen_uin_v2" -> {
                    val reply = LiteProtobuf.encodeInt(2, 987654321L)
                    WeTypeCrypto.aesCrypt(negotiatedKey!!, reply, true) to emptyList()
                }
                "/api_v2" -> {
                    val plain = WeTypeCrypto.aesCrypt(negotiatedKey!!, body, false)
                    if (plain.isEmpty()) {
                        WeTypeCrypto.aesCrypt(negotiatedKey!!, ByteArray(0), true) to emptyList()
                    } else {
                        val innerReply = LiteProtobuf.encodeString(4, "测试") + LiteProtobuf.encodeString(14, "测试。")
                        val respCompressed = WeTypeCrypto.snappyCompress(LiteProtobuf.encodeBytes(1, innerReply))
                        WeTypeCrypto.aesCrypt(negotiatedKey!!, respCompressed, true) to listOf("Kb-CompressionType" to "2")
                    }
                }
                else -> error("Unexpected path: $path")
            }

            val httpResp = ByteArrayOutputStream().apply {
                write(LiteProtobuf.encodeInt(2, 200L))
                respHeaders.forEach { (k, v) ->
                    write(LiteProtobuf.encodeBytes(4, LiteProtobuf.encodeString(1, k) + LiteProtobuf.encodeString(2, v)))
                }
                write(LiteProtobuf.encodeBytes(5, respBody))
            }.toByteArray()
            LiteProtobuf.encodeBytes(4, httpResp)
        }

        rpc.handshake(context)
        val (raw, finalText) = rpc.voice(
            voiceId = "vid-1",
            opus = byteArrayOf(1, 2, 3),
            seq = 1,
            total = 3,
            end = true
        )
        assertEquals("测试", raw)
        assertEquals("测试。", finalText)
        WeTypeIdentityStore.resetIdentity(context)
    }

    @Test
    fun registryModeResolverAndFactoriesWireDoubaoImeAndWeType() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.brycewg.asrkb.store.Prefs(context)

        for (vendor in listOf(AsrVendor.DoubaoIme, AsrVendor.WeType)) {
            val descriptor = AsrVendorRegistry.descriptorFor(vendor)
            assertTrue(AsrVendorCapability.StreamingRecognition in descriptor.capabilities)
            assertTrue(AsrVendorCapability.NativePushPcmInput in descriptor.capabilities)
            assertFalse(AsrVendorCapability.FileRecognition in descriptor.capabilities)
            assertTrue(prefs.hasVendorKeys(vendor))
            assertTrue(isAsrVendorConfigured(context, prefs, vendor))

            val direct = AsrEngineModeResolver.resolve(vendor, AsrEngineInvocationMode.DirectMicrophoneCapture)
            val push = AsrEngineModeResolver.resolve(vendor, AsrEngineInvocationMode.PushPcm)
            assertSame(AsrResolvedEngineMode.DirectStream, direct.mode)
            assertSame(AsrResolvedEngineMode.PushPcmNativeStream, push.mode)

            val route = AsrRecordedAudioRouteResolver.resolve(context, prefs, vendor)
            assertSame(AsrRecordedAudioRouteKind.ReplayStream, route.kind)
            assertTrue(route.canContinue)
        }
    }
}
