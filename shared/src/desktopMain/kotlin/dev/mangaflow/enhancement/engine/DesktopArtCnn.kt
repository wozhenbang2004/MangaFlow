package dev.mangaflow.enhancement.engine

import dev.mangaflow.platform.sha256

/** Original ArtCNN v1.6.2 release shaders, bundled unchanged with their upstream MIT notice. */
internal object DesktopArtCnn {
    const val VERSION = "1.6.2"
    const val SOURCE = "https://github.com/Artoriuz/ArtCNN/releases/tag/v1.6.2"

    val models = listOf(
        model("C4F16", 212_905, "03d0b3d31cb82c898a94a46663021a3e8f02c5a21d69c5cfdf0208de4bfd453e"),
        model("C4F32", 761_029, "f773bce6cf5fe7e5e5d599a695edd40df5cd7a20c3d08c4d164d07591d5bead3"),
    )

    fun verifiedShader(model: EngineModel): Pair<String, ByteArray> {
        require(model in models) { "只接受已固定摘要的 ArtCNN 模型" }
        val bytes = DesktopArtCnn::class.java.getResourceAsStream(model.path)?.use { it.readBytes() }
            ?: error("此构建缺少 ArtCNN 模型")
        require(bytes.size.toLong() == model.bytes && sha256(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == model.sha256) {
            "ArtCNN 模型完整性校验失败"
        }
        return model.path.substringAfterLast('/') to bytes
    }

    private fun model(name: String, size: Long, digest: String) =
        EngineModel("artcnn:$name", EnhancementEngineIds.DESKTOP_ARTCNN, "ArtCNN $name", "/artcnn/ArtCNN_$name.glsl",
            digest, 2, setOf(EngineContent.VIDEO), "MIT", VERSION, size, inputType = "MPV_GLSL_LUMA")
}
