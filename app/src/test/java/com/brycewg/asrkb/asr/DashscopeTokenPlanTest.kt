package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashscopeTokenPlanTest {
    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.dashRegion = "token-plan-intl"
        prefs.dashApiKey = "test-token-plan-key"
    }

    @Test
    fun fileRecognitionUsesTokenPlanEndpointAndReturnsTranscript() = runTest {
        prefs.dashAsrModel = Prefs.DASH_MODEL_QWEN_AUDIO_FLASH
        prefs.uploadAudioCompressionEnabled = false
        val hosts = mapOf(
            "token-plan-cn" to "token-plan.cn-beijing.maas.aliyuncs.com",
            "token-plan-intl" to "token-plan.ap-southeast-1.maas.aliyuncs.com"
        )
        hosts.forEach { (region, host) ->
            prefs.dashRegion = region
            val requests = mutableListOf<Request>()
            val listener = RecordingListener()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"output":{"choices":[{"message":{"content":[{"text":"你好"}]}}]}}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }.build()
            val engine = DashscopeFileAsrEngine(context, this, prefs, listener, httpClient = http)

            engine.recognizeFromPcm(ByteArray(320))

            val request = requests.single()
            assertEquals(
                "https://$host/api/v1/services/aigc/multimodal-generation/generation",
                request.url.toString()
            )
            assertEquals("Bearer test-token-plan-key", request.header("Authorization"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH, JSONObject(body).getString("model"))
            assertEquals(listOf("你好"), listener.results)
            assertEquals(emptyList<String>(), listener.errors)
        }
    }

    @Test
    fun streamingModelsFailBeforeStartingPayAsYouGoConnection() = runTest {
        val models = listOf(
            Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME,
            Prefs.DASH_MODEL_FUN_ASR_REALTIME,
            Prefs.DASH_MODEL_QWEN3_REALTIME
        )
        for (region in listOf("token-plan-cn", "TOKEN-PLAN-INTL")) {
            prefs.dashRegion = region
            for (model in models) {
                prefs.dashAsrModel = model
                val listener = RecordingListener()
                val engine = DashscopeStreamAsrEngine(context, this, prefs, listener, externalPcmMode = true)

                engine.start()

                assertFalse(engine.isRunning)
                assertEquals(
                    listOf(context.getString(R.string.error_dash_token_plan_streaming_unsupported)),
                    listener.errors
                )
                assertEquals(emptyList<String>(), listener.results)
            }
        }
    }

    private class RecordingListener : StreamingAsrEngine.Listener {
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onFinal(text: String) {
            results += text
        }

        override fun onError(message: String) {
            errors += message
        }
    }
}
