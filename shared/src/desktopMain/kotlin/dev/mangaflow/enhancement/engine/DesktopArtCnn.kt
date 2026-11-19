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
        val source = bytes.decodeToString()
        val finalPass = source.lastIndexOf("//!DESC ${model.title} (Depth-To-Space)")
        check(finalPass >= 0) { "找不到 ArtCNN 的亮度输出阶段" }
        val output = source.substring(finalPass)
        check(output.lineSequence().any { it.trim() == "//!HOOK LUMA" } &&
            output.lineSequence().none { it.startsWith("//!SAVE ") }) {
            "ArtCNN 的最终阶段必须输出到亮度平面"
        }
        // The intermediate feature textures still need four components. The final
        // hook replaces LUMA, and libmpv combines it with the chroma planes.
        val component = Regex("(?m)^//!COMPONENTS 4(?=\\r?$)")
        check(component.findAll(output).count() == 1) { "无法确认 ArtCNN 的亮度输出格式" }
        val compatible = source.substring(0, finalPass) + component.replaceFirst(output, "//!COMPONENTS 1")
        return model.path.substringAfterLast('/') to compatible.encodeToByteArray()
    }

    private fun model(name: String, size: Long, digest: String) =
        EngineModel("artcnn:$name", EnhancementEngineIds.DESKTOP_ARTCNN, "ArtCNN $name", "/artcnn/ArtCNN_$name.glsl",
            digest, 2, setOf(EngineContent.VIDEO), "MIT", VERSION, size, inputType = "MPV_GLSL_LUMA")
}
