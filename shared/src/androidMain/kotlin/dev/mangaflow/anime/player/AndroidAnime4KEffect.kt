@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import java.util.concurrent.atomic.AtomicLong

/**
 * GLES adapter for the pinned, unmodified Anime4K CNN coefficients in Anime4KShaderLibrary.
 * These explicit x2 modes run Restore -> Upscale; they are not mpv's conditional Mode A/B/C.
 *
 * Media3 1.11.1's Factory.Builder actually defaults to WORKING_COLOR_SPACE_DEFAULT, despite
 * its older LINEAR Javadoc. PlaybackVideoGraphWrapper does not override that setting. SDR
 * therefore reaches this effect in its original non-linear RGB, matching mpv's MAIN hook
 * before pass_scale_main's optional linearization. Do not add a second gamma conversion.
 * HDR uses linear working colors and is deliberately passed through, without an SDR CNN.
 */
internal class AndroidAnime4KEffect(
    private val mode: AnimeVideoEnhancementMode,
    private val report: (AnimeVideoEnhancementState, () -> Boolean) -> Unit,
) : GlEffect {
    private val programRevision = AtomicLong(0L)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val revision = programRevision.incrementAndGet()
        val currentReport: (AnimeVideoEnhancementState) -> Unit = { observation ->
            // A format/HDR transition may instantiate this same effect again. Retired GL
            // programs cannot replace observations from its new input stream.
            if (programRevision.get() == revision) report(observation) { programRevision.get() == revision }
        }
        fun unavailable(detail: String): GlShaderProgram {
            currentReport(AnimeVideoEnhancementState(mode, AnimeVideoEnhancementPhase.UNAVAILABLE, detail))
            return PassthroughShaderProgram()
        }
        if (useHdr) return unavailable("HDR 保持原画；当前 Anime4K 组合仅处理 SDR")
        val compiled = mutableListOf<GlProgram>()
        var compiling = false
        try {
            // Media3 first attempts ES3, but can fall back to ES2 even on a recent Android OS.
            if (GlUtil.getContextMajorVersion() < 3L) {
                return unavailable("当前图形上下文不支持 OpenGL ES 3，已保留原画")
            }
            val plan = Anime4KGlPlan.create(Anime4KShaderLibrary.sources(mode))
            val textureUnits = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, textureUnits, 0)
            GlUtil.checkGlError()
            if (plan.passes.any { it.bindings.size > textureUnits[0] }) {
                return unavailable("当前 GPU 的纹理通道不足，已保留原画")
            }
            compiling = true
            val programs = plan.passes.map { pass ->
                GlProgram(ANIME4K_VERTEX, pass.fragmentSource()).also { program ->
                    compiled += program
                    program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                }
            }
            val copy = GlProgram(ANIME4K_VERTEX, ANIME4K_COPY).also { program ->
                compiled += program
                program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
            return Anime4KGlProgram(mode, plan, programs, copy, currentReport)
        } catch (failure: Exception) {
            // A failed constructor must not strand programs in Media3's shared GL context.
            compiled.forEach { runCatching { it.delete() } }
            if (compiling) {
                // GlProgram's 1.11.1 constructor can throw before returning its allocated GL
                // handles. Retire this context through the native-only recovery, instead of
                // keeping it alive behind a passthrough and leaking failed compiler objects.
                currentReport(AnimeVideoEnhancementState(mode, AnimeVideoEnhancementPhase.FAILED,
                    "GPU 未能编译 Anime4K，正在恢复原生画面"))
                throw VideoFrameProcessingException.from(failure)
            }
            return unavailable("当前 GPU 未能建立 Anime4K 处理，已保留原画")
        }
    }
}

private class Anime4KGlProgram(
    private val mode: AnimeVideoEnhancementMode,
    private val plan: Anime4KGlPlan,
    private val programs: List<GlProgram>,
    private val copy: GlProgram,
    private val report: (AnimeVideoEnhancementState) -> Unit,
) : BaseGlShaderProgram(false, 1) {
    private data class Scratch(val texture: Int, val framebuffer: Int)

    private val scratch = mutableListOf<Scratch>()
    private var inputWidth = 0
    private var inputHeight = 0
    private var enhancing = false
    private var reportedActive = false
    private val dimensions = FloatArray(2)
    private val savedFramebuffer = IntArray(1)
    private val savedViewport = IntArray(4)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseScratch()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        dimensions[0] = inputWidth.toFloat()
        dimensions[1] = inputHeight.toFloat()
        enhancing = false
        reportedActive = false
        try {
            val maximum = IntArray(1)
            val viewport = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0)
            GlUtil.checkGlError()
            if (inputWidth <= 0 || inputHeight <= 0 || inputWidth.toLong() * 2 > maximum[0] ||
                inputHeight.toLong() * 2 > maximum[0] || inputWidth.toLong() * 2 > viewport[0] ||
                inputHeight.toLong() * 2 > viewport[1]) {
                throw Anime4KUnavailable("2 倍输出超出当前 GPU 的纹理尺寸，已保留原画")
            }
            repeat(plan.scratchCount) {
                // Signed CNN activations MUST survive. RGBA8 would clamp negative features and
                // silently turn the upstream neural network into a different, broken algorithm.
                val texture = GlUtil.createTexture(inputWidth, inputHeight, true)
                var framebuffer = 0
                try {
                    framebuffer = GlUtil.createFboForTexture(texture)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                        throw Anime4KUnavailable("当前 GPU 不支持所需浮点帧缓冲，已保留原画")
                    }
                    GlUtil.checkGlError()
                    scratch += Scratch(texture, framebuffer)
                } catch (failure: Exception) {
                    if (framebuffer != 0) runCatching { GlUtil.deleteFbo(framebuffer) }
                    runCatching { GlUtil.deleteTexture(texture) }
                    throw failure
                }
            }
            enhancing = true
            report(observation(AnimeVideoEnhancementPhase.CONFIGURED, "Anime4K 已配置，等待视频帧"))
            return Size(inputWidth * 2, inputHeight * 2)
        } catch (failure: Exception) {
            // configure runs before BaseGlShaderProgram allocates/binds its output. A same-size
            // copy is safe for a known capability limit with fully owned/released resources.
            releaseScratch()
            if (failure is Anime4KUnavailable) {
                report(observation(AnimeVideoEnhancementPhase.UNAVAILABLE, failure.message))
                return Size(inputWidth, inputHeight)
            }
            // GlUtil allocation helpers, like GlProgram, can fail before returning a newly
            // allocated handle. A GL/OOM failure retires the complete context, rather than
            // leaving unknown allocations behind or assuming that the driver is still healthy.
            report(observation(AnimeVideoEnhancementPhase.FAILED,
                "GPU 无法建立浮点处理资源，正在恢复原生画面"))
            throw VideoFrameProcessingException.from(failure)
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)
        val blend = GLES20.glIsEnabled(GLES20.GL_BLEND)
        val scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
        try {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            if (!enhancing) {
                copy.use()
                copy.setSamplerTexIdUniform("uTexture", inputTexId, 0)
                copy.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                return
            }
            plan.passes.forEachIndexed { index, pass ->
                val destination = pass.outputSlot
                if (destination == null) restoreOutput()
                else GlUtil.focusFramebufferUsingCurrentContext(
                    scratch[destination].framebuffer, inputWidth, inputHeight,
                )
                val program = programs[index]
                program.use()
                program.setFloatsUniformIfPresent("uInputSize", dimensions)
                pass.bindings.forEachIndexed { unit, binding ->
                    val texture = binding.slot?.let { scratch[it].texture } ?: inputTexId
                    program.setSamplerTexIdUniform("uTexture$unit", texture, unit)
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            }
            // Only a completed, error-free frame submission can advance past CONFIGURED.
            // This is pipeline activity, not a claim of display quality, FPS or presentation.
            if (!reportedActive) {
                reportedActive = true
                report(observation(AnimeVideoEnhancementPhase.ACTIVE, "Anime4K 正在处理视频帧"))
            }
        } catch (failure: Exception) {
            report(observation(AnimeVideoEnhancementPhase.FAILED, "Anime4K 处理失败，正在恢复原画"))
            throw VideoFrameProcessingException.from(failure)
        } finally {
            restoreOutput()
            if (blend) GLES20.glEnable(GLES20.GL_BLEND) else GLES20.glDisable(GLES20.GL_BLEND)
            if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST) else GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    override fun flush() {
        reportedActive = false
        super.flush()
    }

    override fun release() {
        // Every GL allocation belongs to this program/context, never the UI or a later session.
        var failure: Exception? = null
        fun releasePart(action: () -> Unit) {
            try { action() } catch (error: Exception) { if (failure == null) failure = error }
        }
        releasePart { releaseScratch() }
        programs.forEach { program -> releasePart { program.delete() } }
        releasePart { copy.delete() }
        releasePart { super.release() }
        failure?.let { throw VideoFrameProcessingException.from(it) }
    }

    private fun releaseScratch() {
        // Keep releasing after one driver error; a partially created chain must not leak all
        // its remaining textures. A subsequent draw/processor error owns playback recovery.
        scratch.forEach { target ->
            runCatching { GlUtil.deleteFbo(target.framebuffer) }
            runCatching { GlUtil.deleteTexture(target.texture) }
        }
        scratch.clear()
    }

    private fun restoreOutput() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0])
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
    }

    private fun observation(phase: AnimeVideoEnhancementPhase, detail: String?) = AnimeVideoEnhancementState(
        mode = mode, phase = phase, detail = detail,
        inputWidth = inputWidth, inputHeight = inputHeight,
        outputWidth = if (enhancing) inputWidth * 2 else inputWidth,
        outputHeight = if (enhancing) inputHeight * 2 else inputHeight,
    )
}

private class Anime4KUnavailable(message: String) : Exception(message)

/** A bounded adapter for the four pinned Restore/Upscale S/M shaders, not an arbitrary shader host. */
private data class Anime4KGlPlan(val passes: List<Pass>, val scratchCount: Int) {
    data class Binding(val name: String, val slot: Int?)
    data class Pass(val bindings: List<Binding>, val outputSlot: Int?, val savesMain: Boolean, val body: String) {
        fun fragmentSource(): String = buildString {
            append("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n")
            append("in vec2 vTexCoord;\nuniform vec2 uInputSize;\nout vec4 outColor;\n")
            bindings.forEachIndexed { unit, binding ->
                val name = binding.name
                append("uniform sampler2D uTexture$unit;\n")
                append("#define ${name}_pos vTexCoord\n")
                append("#define ${name}_size uInputSize\n")
                append("#define ${name}_pt (vec2(1.0) / uInputSize)\n")
                append("#define ${name}_tex(pos) texture(uTexture$unit, (pos))\n")
                append("#define ${name}_texOff(off) texture(uTexture$unit, vTexCoord + (off) / uInputSize)\n")
            }
            append(body)
            append("\nvoid main() {\n    vec4 color = hook();\n")
            // mpv's opaque-video alpha is not another neural feature in Media3's compositor.
            append(if (savesMain) "    outColor = vec4(color.rgb, MAIN_tex(MAIN_pos).a);\n"
                else "    outColor = color;\n")
            append("}\n")
        }
    }

    private data class PendingPass(
        val bindings: List<Pair<String, String>>, val key: String, val savesMain: Boolean,
        val scale: Int, val body: String,
    )

    companion object {
        fun create(shaders: List<Anime4KShader>): Anime4KGlPlan {
            require(shaders.size == 2) { "Expected the pinned Restore/Upscale pair" }
            val pending = mutableListOf<PendingPass>()
            var main = "input"
            shaders.forEachIndexed { stage, shader ->
                val known = mutableMapOf("MAIN" to main)
                val sections = shader.source.split(Regex("(?m)^//!DESC ")).drop(1)
                require(sections.isNotEmpty()) { "Missing upstream shader passes" }
                sections.forEachIndexed { index, section ->
                    val lines = section.lineSequence().drop(1).toList()
                    val directives = lines.filter { it.startsWith("//!") }.map { line ->
                        val directive = line.removePrefix("//!")
                        directive.substringBefore(' ') to directive.substringAfter(' ', "").trim()
                    }
                    require(directives.all { it.first in setOf("HOOK", "BIND", "SAVE", "WIDTH", "HEIGHT", "COMPONENTS", "WHEN") })
                    fun directive(name: String): String = directives.single { it.first == name }.second
                    require(directive("HOOK") == "MAIN")
                    val names = directives.filter { it.first == "BIND" }.map { it.second }
                    require(names.isNotEmpty() && names.distinct().size == names.size)
                    val bindings = names.map { name -> name to requireNotNull(known[name]) }
                    val save = directive("SAVE")
                    require(save.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                    fun scale(dimension: String, suffix: String): Int {
                        val match = requireNotNull(Regex("([A-Za-z_][A-Za-z_0-9]*)\\.$suffix( 2 \\*)?").matchEntire(dimension))
                        require(known.containsKey(match.groupValues[1]))
                        return if (match.groupValues[2].isEmpty()) 1 else 2
                    }
                    val widthScale = scale(directive("WIDTH"), "w")
                    require(widthScale == scale(directive("HEIGHT"), "h"))
                    require(directives.filter { it.first == "COMPONENTS" }.all { it.second == "4" })
                    // These are explicit x2 modes. mpv's OUTPUT-dependent >1.2 WHEN is replaced
                    // by the user's mode selection; no hidden display-size quality promise.
                    require(directives.filter { it.first == "WHEN" }.all {
                        it.second == "OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *"
                    })
                    require(save != "MAIN" || (index == sections.lastIndex && "MAIN" in names))
                    val key = "$stage:$index"
                    pending += PendingPass(bindings, key, save == "MAIN", widthScale,
                        lines.filterNot { it.startsWith("//!") }.joinToString("\n"))
                    known[save] = key
                }
                require(pending.last().savesMain)
                main = requireNotNull(known["MAIN"])
            }
            require(pending.last().scale == 2 && pending.dropLast(1).all { it.scale == 1 })
            val lastUse = mutableMapOf<String, Int>()
            pending.forEachIndexed { index, pass -> pass.bindings.forEach { lastUse[it.second] = index } }
            val slots = mutableMapOf<String, Int>()
            val owners = mutableListOf<String>()
            val passes = pending.mapIndexed { index, pass ->
                val bindings = pass.bindings.map { (name, key) ->
                    Binding(name, if (key == "input") null else requireNotNull(slots[key]))
                }
                val output = if (index == pending.lastIndex) null else {
                    // M combines all seven earlier feature maps. Keep them live until their
                    // actual last reader; reusing a two-texture S-style ping-pong would corrupt M.
                    val free = owners.indexOfFirst { (lastUse[it] ?: -1) < index }
                    val slot = if (free >= 0) free else owners.size.also { owners += pass.key }
                    owners[slot] = pass.key
                    slots[pass.key] = slot
                    slot
                }
                Pass(bindings, output, pass.savesMain, pass.body)
            }
            return Anime4KGlPlan(passes, owners.size)
        }
    }
}

private const val ANIME4K_VERTEX = """#version 300 es
in vec4 aFramePosition;
out vec2 vTexCoord;
void main() {
    gl_Position = aFramePosition;
    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

private const val ANIME4K_COPY = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vTexCoord;
uniform sampler2D uTexture;
out vec4 outColor;
void main() {
    outColor = texture(uTexture, vTexCoord);
}
"""
