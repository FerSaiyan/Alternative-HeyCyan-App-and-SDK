package com.fersaiyan.cyanbridge.plugins.walkingaid

data class WalkingAidModelCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val sourceUrl: String,
    val sourcePageUrl: String,
    val expectedFilename: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseNote: String,
)

/** Public LiteRT/TFLite artifacts installed directly into Walking Aid's model directory. */
object WalkingAidModelCatalog {
    val entries = listOf(
        WalkingAidModelCatalogEntry(
            id = "yolo11n-tflite",
            displayName = "YOLO11n (LiteRT/TFLite)",
            description = "Fast on-device recognition of common people, animals, vehicles, and objects.",
            sourceUrl = "https://huggingface.co/mehmetkeremturkcan/RubikPi.YOLO11x.Detection/resolve/8eddca915fdcd0e64d56846bf3f895980b526848/yolo11n.tflite",
            sourcePageUrl = "https://huggingface.co/mehmetkeremturkcan/RubikPi.YOLO11x.Detection",
            expectedFilename = "yolo11n_float16.tflite",
            sizeBytes = 2_971_416L,
            sha256 = "3bf2169140efb4f960132f57b454d4836e421a7eaa6e6f0b11748b8791dca8de",
            licenseNote = "Ultralytics YOLO11 is distributed under AGPL-3.0 terms.",
        ),
        WalkingAidModelCatalogEntry(
            id = "depth-anything-3-small-litert",
            displayName = "Depth Anything 3 Small (LiteRT)",
            description = "On-device monocular relative-depth estimation for walking hazards.",
            sourceUrl = "https://huggingface.co/litert-community/Depth-Anything-3-Small/resolve/5cd25d936e3fde2edef68f53b4123401454ac9c8/da3_small_gpu_fp16.tflite",
            sourcePageUrl = "https://huggingface.co/litert-community/Depth-Anything-3-Small",
            expectedFilename = "depth_anything_3_small.tflite",
            sizeBytes = 55_035_456L,
            sha256 = "e170369a72ba1bba7486a4d2de555639fccd0595a9bb5b5349f7733ed4aebd1f",
            licenseNote = "Depth Anything 3 Small and this LiteRT conversion are Apache-2.0.",
        ),
        WalkingAidModelCatalogEntry(
            id = "yolo-world-tflite",
            displayName = "YOLO-World (LiteRT/TFLite)",
            description = "Larger on-device detector with a built-in vocabulary of common objects.",
            sourceUrl = "https://huggingface.co/wondervictor/YOLO-World/resolve/4340b03f4f59f46279a6581bbb818e0f77765d4d/yolo_world_x_coco_zeroshot_rep_integer_quant.tflite",
            sourcePageUrl = "https://huggingface.co/wondervictor/YOLO-World",
            expectedFilename = "yolo_world.tflite",
            sizeBytes = 73_490_408L,
            sha256 = "cbd5806fbecd5b33664597b9c8e9bb264f7ea0e295ddbf1f80560a131c48bfe0",
            licenseNote = "This YOLO-World export is distributed under GPL-3.0 terms.",
        ),
    )

    fun findById(id: String): WalkingAidModelCatalogEntry? = entries.firstOrNull { it.id == id }

    fun detectorFor(type: String): WalkingAidModelCatalogEntry =
        if (type == WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD) entries[2] else entries[0]

    val depth: WalkingAidModelCatalogEntry
        get() = entries[1]
}
