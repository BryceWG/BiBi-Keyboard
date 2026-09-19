package com.brycewg.asrkb.store

import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsAudioSourceCacheTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun cacheKeepsDevicesIndependentAndCanInvalidateOneDevice() {
        prefs.setAudioSourceCache("xiaomi|model-a", MediaRecorder.AudioSource.MIC)
        prefs.setAudioSourceCache(
            "google|model-b",
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        assertEquals(MediaRecorder.AudioSource.MIC, prefs.getAudioSourceCache("xiaomi|model-a"))
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            prefs.getAudioSourceCache("google|model-b")
        )

        prefs.clearAudioSourceCache("xiaomi|model-a")

        assertNull(prefs.getAudioSourceCache("xiaomi|model-a"))
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            prefs.getAudioSourceCache("google|model-b")
        )
    }

    @Test
    fun unsupportedSourceIsNotPersisted() {
        prefs.setAudioSourceCache("device", MediaRecorder.AudioSource.CAMCORDER)

        assertNull(prefs.getAudioSourceCache("device"))
    }
}
