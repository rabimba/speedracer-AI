package com.trustableai.koru.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetManagerTest {
    @Test
    fun `unsupported multimodal litert markers are rejected before mediapipe warmup`() {
        val header = "LITERTLM model_type tf_lite_audio_adapter".toByteArray(Charsets.ISO_8859_1)

        assertTrue(ModelAssetManager.hasUnsupportedLiteRtLmMarkers(header))
    }

    @Test
    fun `text only litert markers remain eligible for mediapipe warmup`() {
        val header = "LITERTLM model_type tf_lite_prefill_decode model_type tf_lite_embedder"
            .toByteArray(Charsets.ISO_8859_1)

        assertFalse(ModelAssetManager.hasUnsupportedLiteRtLmMarkers(header))
    }
}
