package app.lyrify.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class NpuChipsTest {
    @Test
    fun `a supported Snapdragon is found by its SoC model, alone or with a variant suffix`() {
        assertEquals("snapdragon_8_elite_gen5_for_galaxy", NpuChips.of("QTI", "SM8850")?.aiHub) // the S26 Ultra's own
        assertEquals("snapdragon_8_elite_for_galaxy", NpuChips.of("QTI", "SM8750")?.aiHub)
        assertEquals("snapdragon_8_elite_for_galaxy", NpuChips.of("qti", " sm8750-ac ")?.aiHub)
        assertEquals("snapdragon_8gen3", NpuChips.of("QTI", "SM8650")?.aiHub)
        assertEquals("snapdragon_8gen1", NpuChips.of("QTI", "SM8450")?.aiHub)
    }

    @Test
    fun `any other chip, maker or an old phone has none`() {
        assertNull(NpuChips.of("QTI", "SM8475")) // 8+ Gen 1: another part
        assertNull(NpuChips.of("QTI", "SM8550")) // 8 Gen 2: no asset enabled
        assertNull(NpuChips.of("QTI", "SM88500"))
        assertNull(NpuChips.of("Samsung", "s5e9945"))
        assertNull(NpuChips.of("Google", "Tensor G4"))
        assertNull(NpuChips.of("QTI", "unknown"))
        assertNull(NpuChips.of(null, "SM8850"))
        assertNull(NpuChips.of("QTI", null))
    }

    @Test
    fun `only the phone the pipeline ran on is marked tested`() {
        assertEquals(listOf("SM8850"), NpuChips.ALL.filter { it.tested }.map { it.socModel })
        assertEquals(listOf(81, 79, 75, 69), NpuChips.ALL.map { it.hexagon })
    }

    @Test
    fun `each chip's asset is AI Hub's precompiled_qnn_onnx w8a16 v0_65_0 zip on its S3 bucket, pinned by size and SHA-256`() {
        // release_assets.json of qualcomm/Whisper-Large-V3-Turbo-Quantized (2026-11-04) lists a w8a16 asset for every
        // supported chip. SM8850's size and SHA-256 are the bench's download.json; the others were streamed through
        // sha256sum, each size the S3 Content-Length; unpacked sizes from each zip's central directory.
        val expected = listOf(
            Triple("snapdragon_8_elite_gen5_for_galaxy", 824_020_794L, "12c4469b443b06ac6ef36b4268f7822cda798c03abadcda0c93f7b9ddbb24f16") to 983_166_696L,
            Triple("snapdragon_8_elite_for_galaxy", 823_685_788L, "a7d3eb9fd98f5deb83951bc4837f457ebf74a2f93de3abe22e0da3457403af0e") to 981_798_617L,
            Triple("snapdragon_8gen3", 823_721_740L, "7c9f289efbc999e4511b05d10e6351c101fc765f67980c2a8fee4f8127961398") to 982_003_375L,
            Triple("snapdragon_8gen1", 821_903_590L, "db3cfe3ffe37b4ff08ce6a5005e6515a1f0bc3efee152b36cf5bd8a90ac28460") to 976_301_702L,
        )
        assertEquals(expected, NpuChips.ALL.map { Triple(it.aiHub, it.zipBytes, it.zipSha256) to it.unpackedBytes })
        assertEquals(listOf(WhisperPrecision.W8A16), NpuChips.ALL.map { it.precision }.distinct())
        assertEquals(
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_large_v3_turbo_quantized/releases/v0.65.0/" +
                "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
            NpuChips.ALL.first().zipUrl,
        )
        NpuChips.ALL.forEach { chip ->
            assertTrue(chip.zipUrl, AllowedHosts.isSource(URI(chip.zipUrl)))
            assertTrue(chip.aiHub, chip.zipSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(chip.aiHub, chip.unpackedBytes > chip.zipBytes)
            assertTrue(chip.zipUrl.endsWith("/${chip.zipFolder}.zip"))
        }
        assertEquals(NpuChips.ALL.size, NpuChips.ALL.map { it.zipSha256 }.toSet().size)
    }

    @Test
    fun `a chip left on the float export gets the float asset's name, the one the app downloaded before`() {
        val chip = NpuChips.ALL.first().copy(precision = WhisperPrecision.FLOAT)
        assertEquals(
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_large_v3_turbo/releases/v0.65.0/" +
                "whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
            chip.zipUrl,
        )
        assertEquals("whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8_elite_gen5_for_galaxy", chip.zipFolder)
    }

    @Test
    fun `the AI's models are for Android 15 and later only, the version its overnight run needs`() {
        val chip = NpuChips.ALL.first()
        assertEquals(35, ModelDownloads.MIN_SDK)
        assertNull(ModelDownloads.chipFor(31, chip))
        assertNull(ModelDownloads.chipFor(34, chip))
        assertEquals(chip, ModelDownloads.chipFor(35, chip))
        assertEquals(chip, ModelDownloads.chipFor(36, chip))
        assertNull(ModelDownloads.chipFor(36, null))
    }
}
