package app.lyrify.models

import android.os.Build

/**
 * Which of Qualcomm AI Hub's Whisper-Large-V3-Turbo exports a chip gets (`precompiled_qnn_onnx`, release v0.65.0, QAIRT
 * 2.50.0.260828221209): [model] is the AI Hub model id, [id] the precision in its asset names. The engine runs either,
 * reading the I/O contract from the zip's metadata.json.
 */
enum class WhisperPrecision(val model: String, val id: String) {
    /**
     * qualcomm/Whisper-Large-V3-Turbo-Quantized: 8-bit weights, 16-bit activations; uint16 mel, mask and logits, uint8
     * caches. About 0.98 GB unpacked. The bench's model size test (2026-11-04) found it as good as float on the phone:
     * WER 19% / 17% against 20% / 18%, the same 10 of 12 songs timed, 59% of transcript lines within 1 s against 63%.
     */
    W8A16("whisper_large_v3_turbo_quantized", "w8a16"),

    /** qualcomm/Whisper-Large-V3-Turbo: float16 I/O, about 2.2 GB unpacked. For a chip without a w8a16 asset. */
    FLOAT("whisper_large_v3_turbo", "float"),
}

/**
 * A Snapdragon chip whose NPU runs the AI, and the AI Hub Whisper built for it in [precision], as listed in AI Hub's
 * release_assets.json (https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized/raw/main/release_assets.json for
 * w8a16, .../Whisper-Large-V3-Turbo/... for float). Every supported chip has a w8a16 asset (checked 2026-11-04, repo
 * revision 93014065).
 *
 * [socModel] is what `Build.SOC_MODEL` reports ([NpuChips.of]); [aiHub] AI Hub's name for the chip; [hexagon] its HTP
 * version (from the zip's metadata.json, which also gives the reference phone); [zipBytes] and [zipSha256] the asset's
 * size and SHA-256 (the download is checked against them); [unpackedBytes] its five files' total, from the zip's
 * central directory. [tested]: the whole pipeline ran on a phone with this chip.
 */
data class NpuChip(
    val name: String,
    val socModel: String,
    val aiHub: String,
    val hexagon: Int,
    val precision: WhisperPrecision,
    val zipBytes: Long,
    val zipSha256: String,
    val unpackedBytes: Long,
    val tested: Boolean,
) {
    /** The zip's one folder, and its name without `.zip`. */
    val zipFolder: String get() = "${precision.model}-precompiled_qnn_onnx-${precision.id}-qualcomm_$aiHub"

    val zipUrl: String get() = "$BUCKET/${precision.model}/releases/$RELEASE/$zipFolder.zip"

    private companion object {
        const val BUCKET = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
        const val RELEASE = "v0.65.0"
    }
}

/** The chips the AI runs on. Any other phone has no AI lyrics; the rest of Lyrify works everywhere. */
object NpuChips {
    /** `Build.SOC_MANUFACTURER` of Qualcomm chips. */
    const val MANUFACTURER: String = "QTI"

    val ALL: List<NpuChip> = listOf(
        // VERIFIED: Galaxy S26 Ultra (SM-S948B) reported QTI / SM8850; the float pipeline ran there (npu-full-run1) and
        // this w8a16 zip in the model size test (model-size-run1). Size and SHA-256 from the bench's download.json.
        NpuChip(
            name = "Snapdragon 8 Elite Gen 5",
            socModel = "SM8850",
            aiHub = "snapdragon_8_elite_gen5_for_galaxy",
            hexagon = 81,
            precision = WhisperPrecision.W8A16,
            zipBytes = 824_020_794,
            zipSha256 = "12c4469b443b06ac6ef36b4268f7822cda798c03abadcda0c93f7b9ddbb24f16",
            unpackedBytes = 983_166_696,
            tested = true,
        ),
        // UNTESTED: Galaxy S25 (AI Hub's reference phone for this asset). SM8750 (-AC "for Galaxy"). The w8a16 zip was
        // measured on 2026-11-04 by streaming it through sha256sum, its size matching the S3 Content-Length.
        NpuChip(
            name = "Snapdragon 8 Elite",
            socModel = "SM8750",
            aiHub = "snapdragon_8_elite_for_galaxy",
            hexagon = 79,
            precision = WhisperPrecision.W8A16,
            zipBytes = 823_685_788,
            zipSha256 = "a7d3eb9fd98f5deb83951bc4837f457ebf74a2f93de3abe22e0da3457403af0e",
            unpackedBytes = 981_798_617,
            tested = false,
        ),
        // UNTESTED: Galaxy S24 (AI Hub's reference phone). SM8650. Measured as SM8750's.
        NpuChip(
            name = "Snapdragon 8 Gen 3",
            socModel = "SM8650",
            aiHub = "snapdragon_8gen3",
            hexagon = 75,
            precision = WhisperPrecision.W8A16,
            zipBytes = 823_721_740,
            zipSha256 = "7c9f289efbc999e4511b05d10e6351c101fc765f67980c2a8fee4f8127961398",
            unpackedBytes = 982_003_375,
            tested = false,
        ),
        // UNTESTED: Galaxy S22 (AI Hub's reference phone). SM8450; the 8+ Gen 1 is another part (SM8475), not included.
        // Measured as SM8750's.
        NpuChip(
            name = "Snapdragon 8 Gen 1",
            socModel = "SM8450",
            aiHub = "snapdragon_8gen1",
            hexagon = 69,
            precision = WhisperPrecision.W8A16,
            zipBytes = 821_903_590,
            zipSha256 = "db3cfe3ffe37b4ff08ce6a5005e6515a1f0bc3efee152b36cf5bd8a90ac28460",
            unpackedBytes = 976_301_702,
            tested = false,
        ),
    )

    /**
     * The chip of a phone whose `Build.SOC_MANUFACTURER` is [manufacturer] and `Build.SOC_MODEL` is [model]: a Qualcomm
     * part number as listed, alone or with a variant suffix ("SM8750-AC"); null for any other chip.
     */
    fun of(manufacturer: String?, model: String?): NpuChip? {
        if (!manufacturer.orEmpty().trim().equals(MANUFACTURER, ignoreCase = true)) return null
        val part = model.orEmpty().trim().uppercase()
        return ALL.firstOrNull { part == it.socModel || part.startsWith(it.socModel + "-") }
    }

    /** This phone's chip, from `Build.SOC_MANUFACTURER` and `Build.SOC_MODEL` (Android 12+); null before Android 12. */
    fun device(): NpuChip? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) of(Build.SOC_MANUFACTURER, Build.SOC_MODEL) else null
}
