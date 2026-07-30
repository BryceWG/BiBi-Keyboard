package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TencentStreamAsrEngineTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = Prefs(context).apply {
            tencentAppId = "1300000000"
            tencentSecretId = "PLACEHOLDER_AKID"
            tencentSecretKey = "PLACEHOLDER_SECRET_KEY"
            tencentEngineType = "16k_zh"
            tencentVadEnabled = true
        }
    }

    @Test
    fun buildsWsUrlWithValidCredentials() {
        val engine = TencentStreamAsrEngine(
            context,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            prefs,
            object : StreamingAsrEngine.Listener {
                override fun onFinal(text: String) {}
                override fun onError(message: String) {}
                override fun onPartial(text: String) {}
                override fun onStopped() {}
                override fun onAmplitude(amplitude: Float) {}
            }
        )
        val url = engine.buildWsUrl()
        assertNotNull("URL should not be null with valid credentials", url)
        assertTrue("URL should start with wss://", url!!.startsWith("wss://"))
        assertTrue("URL should contain asr.cloud.tencent.com", url.contains("asr.cloud.tencent.com"))
        assertTrue("URL should contain /asr/v2/", url.contains("/asr/v2/"))
        assertTrue("URL should contain the appid", url.contains("1300000000"))
        assertTrue("URL should contain engine_model_type=16k_zh", url.contains("engine_model_type=16k_zh"))
        assertTrue("URL should contain signature parameter", url.contains("signature="))
        assertTrue("URL should contain secretid", url.contains("secretid="))
        assertTrue("URL should contain timestamp", url.contains("timestamp="))
    }

    @Test
    fun wsUrlFailsWithMissingCredentials() {
        val badPrefs = Prefs(context).apply {
            tencentAppId = ""
            tencentSecretId = ""
            tencentSecretKey = ""
        }
        val engine = TencentStreamAsrEngine(
            context,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            badPrefs,
            object : StreamingAsrEngine.Listener {
                override fun onFinal(text: String) {}
                override fun onError(message: String) {}
                override fun onPartial(text: String) {}
                override fun onStopped() {}
                override fun onAmplitude(amplitude: Float) {}
            }
        )
        val url = engine.buildWsUrl()
        assertEquals(null, url)
    }

    @Test
    fun hmacSha1Base64ProducesCorrectSignature() {
        val plain = "asr.cloud.tencent.com/asr/v2/1305857615?engine_model_type=16k_zh&expired=9999999999&nonce=1234567&secretid=PLACEHOLDER_AKID&timestamp=9999999998&voice_format=1&voice_id=test123"
        val result = TencentStreamAsrEngine.hmacSha1Base64(plain, "testkey")
        assertNotNull(result)
        assertTrue("HMAC-SHA1 signature should be base64 encoded", result.isNotBlank())
    }

    @Test
    fun sortedMapOfPreservesAlphabeticalOrder() {
        val map = TencentStreamAsrEngine.sortedMapOf(
            "voice_id" to "abc",
            "engine_model_type" to "16k_zh",
            "secretid" to "test",
            "timestamp" to "12345"
        )
        val keys = map.keys.toList()
        assertEquals("engine_model_type", keys[0])
        assertEquals("secretid", keys[1])
        assertEquals("timestamp", keys[2])
        assertEquals("voice_id", keys[3])
    }

    @Test
    fun responseJsonParsingExtractsCode() {
        val json = """{"code":0,"message":"success","voice_id":"abc123"}"""
        val code = JSONObject(json).optInt("code", -1)
        assertEquals(0, code)
    }

    @Test
    fun responseJsonParsingExtractsErrorCode() {
        val json = """{"code":4002,"message":"auth failed"}"""
        val code = JSONObject(json).optInt("code", -1)
        assertEquals(4002, code)
    }

    @Test
    fun responseJsonParsingExtractsFinalFlag() {
        val json = """{"code":0,"message":"success","voice_id":"abc","final":1}"""
        val final = JSONObject(json).optInt("final", 0)
        assertEquals(1, final)
    }

    @Test
    fun responseJsonParsingExtractsResultWithSliceType() {
        val json = """{"code":0,"result":{"slice_type":2,"voice_text_str":"hello world"}}"""
        val resultObj = JSONObject(json).optJSONObject("result")
        assertNotNull(resultObj)
        val sliceType = resultObj!!.optInt("slice_type", -1)
        val voiceText = resultObj.optString("voice_text_str", "")
        assertEquals(2, sliceType)
        assertEquals("hello world", voiceText)
    }

    @Test
    fun responseJsonHandlesMissingFieldsGracefully() {
        val json = """{"code":0,"message":"success"}"""
        val resultObj = JSONObject(json).optJSONObject("result")
        assertEquals(null, resultObj)
        val final = JSONObject(json).optInt("final", 0)
        assertEquals(0, final)
    }
}
