package dev.mangaflow.desktop

import dev.mangaflow.APP_DISPLAY_NAME

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.ComposeWindow
import com.sun.management.OperatingSystemMXBean
import dev.mangaflow.anime.player.AnimeNativeVideoStatistics
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.peekDesktopNativeVideoStatistics
import dev.mangaflow.reader.ReaderDecodeDiagnostics
import dev.mangaflow.ui.DesktopPerformanceMetric
import dev.mangaflow.ui.DesktopPerformancePanel
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.io.BufferedWriter
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import javax.swing.Timer
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.SkikoProperties
import kotlin.math.ceil

private const val NANOS_PER_MILLI = 1_000_000.0
private const val INTERACTION_WINDOW_NANOS = 1_000_000_000L

internal data class DesktopPerformanceSnapshot(
    val metrics: List<DesktopPerformanceMetric> = emptyList(),
    val recording: Boolean = false,
    val status: String = "仅在面板打开或主动采集时读取诊断数据。",
)

private data class Distribution(val count: Int, val p50: Double?, val p95: Double?, val p99: Double?)

/** All writes are made under the enclosing recorder's lock. Sorting happens on the IO sampler. */
private class TimingRing(private val values: LongArray = LongArray(4096)) {
    private var next = 0
    private var count = 0
    fun add(value: Long) {
        values[next] = value.coerceAtLeast(0L)
        next = (next + 1) % values.size
        count = minOf(count + 1, values.size)
    }
    fun copy() = values.copyOf(count)
    fun clear() { next = 0; count = 0 }
}

private fun LongArray.distribution(): Distribution {
    sort()
    fun at(p: Double) = if (isEmpty()) null else this[(ceil(size * p).toInt() - 1).coerceIn(indices)] / NANOS_PER_MILLI
    return Distribution(size, at(.5), at(.95), at(.99))
}

private data class FrameRecord(
    val startNanos: Long, val durationNanos: Long, val intervalNanos: Long?,
    val interactive: Boolean, val inputToRenderNanos: Long?, val budgetNanos: Long?,
)

private data class TimingSnapshot(
    val render: Distribution, val cadence: Distribution, val input: Distribution, val eventAge: Distribution,
    val edt: Distribution, val rendered: Long, val overBudget: Long, val longestOverBudgetRun: Long,
)

private class DesktopTimings {
    private enum class Capture { OFF, RECORDING, DRAINING }

    private val lock = Any()
    private val render = TimingRing()
    private val cadence = TimingRing()
    private val input = TimingRing()
    private val eventAge = TimingRing()
    private val edt = TimingRing()
    private var previousRender = 0L
    private var lastInteraction = 0L
    private var interactionStart = 0L
    private var pendingInput = 0L
    private var rendered = 0L
    private var overBudget = 0L
    private var overBudgetRun = 0L
    private var longestOverBudgetRun = 0L
    val captureFrames = ArrayBlockingQueue<FrameRecord>(16_384)
    val captureOverflow = AtomicLong()
    private var panel = false
    private var capture = Capture.OFF
    private var nextSession = 0L
    @Volatile var session = 0L
        private set

    // Normal interaction pays only the opt-in check, not the recording lock or counters.
    private inline fun record(observedSession: Long, action: () -> Unit) {
        if (observedSession == 0L) return
        synchronized(lock) { if (session == observedSession && capture != Capture.DRAINING) action() }
    }

    fun setPanelVisible(visible: Boolean, onNewSession: () -> Unit = {}): Unit = synchronized(lock) {
        panel = visible
        if (visible && session == 0L) {
            beginSession()
            onNewSession()
        } else if (!visible && capture == Capture.OFF) session = 0L
    }

    fun startCapture() = synchronized(lock) {
        capture = Capture.RECORDING
        beginSession()
    }

    // Seal the capture, but keep its generation until the writer drains the last samples.
    fun stopCapture() = synchronized(lock) { capture = Capture.DRAINING }

    fun captureDrained(): Unit = synchronized(lock) {
        capture = Capture.OFF
        if (!panel) session = 0L
    }

    fun onInput(now: Long, eventWhen: Long) = record(session) {
        val eventAgeMillis = System.currentTimeMillis() - eventWhen
        if (now - lastInteraction > INTERACTION_WINDOW_NANOS) {
            interactionStart = now
            pendingInput = 0L
        }
        lastInteraction = now
        if (pendingInput == 0L) pendingInput = now
        if (eventAgeMillis in 0..120_000) eventAge.add(eventAgeMillis * 1_000_000)
    }

    fun onRender(observedSession: Long, start: Long, end: Long, budget: Long?) = record(observedSession) {
        val duration = end - start
        render.add(duration)
        rendered++
        val interval = previousRender.takeIf { it != 0L }?.let { start - it }
        // Keep raw gaps in the CSV. Only an explicitly observed interaction segment
        // contributes to the live cadence histogram; an idle window owes no frames.
        val interactive = previousRender >= interactionStart && lastInteraction != 0L &&
            start - lastInteraction <= INTERACTION_WINDOW_NANOS
        if (interactive && interval != null) cadence.add(interval)
        // A modifier/click can legitimately produce no redraw. Do not associate
        // that event with an unrelated repaint seconds later.
        val inputDelay = pendingInput.takeIf {
            it != 0L && start - it in 0..INTERACTION_WINDOW_NANOS
        }?.let { end - it }
        inputDelay?.let(input::add)
        pendingInput = 0L
        previousRender = start
        if (budget != null && duration > budget) {
            overBudget++
            overBudgetRun++
            longestOverBudgetRun = maxOf(longestOverBudgetRun, overBudgetRun)
        } else overBudgetRun = 0L
        if (capture == Capture.RECORDING && !captureFrames.offer(FrameRecord(start, duration, interval, interactive, inputDelay, budget)))
            captureOverflow.incrementAndGet()
    }

    fun onEdtProbe(observedSession: Long, delay: Long) = record(observedSession) { edt.add(delay) }
    fun publishIfCurrent(observedSession: Long, publish: () -> Unit) = record(observedSession, publish)

    fun snapshot(): TimingSnapshot {
        val (arrays, counts) = synchronized(lock) {
            listOf(render.copy(), cadence.copy(), input.copy(), eventAge.copy(), edt.copy()) to
                longArrayOf(rendered, overBudget, longestOverBudgetRun)
        }
        return TimingSnapshot(arrays[0].distribution(), arrays[1].distribution(), arrays[2].distribution(),
            arrays[3].distribution(), arrays[4].distribution(), counts[0], counts[1], counts[2])
    }

    private fun beginSession() {
        listOf(render, cadence, input, eventAge, edt).forEach { it.clear() }
        previousRender = 0L; lastInteraction = 0L; interactionStart = 0L; pendingInput = 0L
        rendered = 0L; overBudget = 0L; overBudgetRun = 0L; longestOverBudgetRun = 0L
        captureFrames.clear(); captureOverflow.set(0L)
        session = ++nextSession
    }
}

private data class DesktopWindowObservation(
    val backend: String = "未获取", val refreshHz: Int? = null,
    val scaleX: Double? = null, val scaleY: Double? = null,
    val width: Int = 0, val height: Int = 0, val visible: Boolean = false,
)

private data class PerformanceCapture(
    val id: String, val startedNanos: Long, val deadlineNanos: Long,
    val metrics: BufferedWriter, val frames: BufferedWriter, val jfr: Recording?, val jfrFile: File,
)

/**
 * Measures the public Skiko onRender callback, without requesting a frame or replacing the clock.
 * This is main-window EDT scene recording inside onRender. Picture finalization,
 * rendering/submission on Skiko's render thread, Present and scanout are outside it.
 */
internal class DesktopPerformance(private val context: PlatformContext) {
    private val policy = ExperiencePolicyStore.get(context)
    private val timings = DesktopTimings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureLock = Mutex()
    private val captureActive = MutableStateFlow(false)
    val panelVisible = MutableStateFlow(false)
    val snapshot = MutableStateFlow(DesktopPerformanceSnapshot())
    private val folder = File(context.dataRoot, "diagnostics")
    private val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val gc = ManagementFactory.getGarbageCollectorMXBeans()
    private val probePending = AtomicBoolean()
    private val probeStarted = AtomicLong()
    private val disposed = AtomicBoolean()
    @Volatile private var window: ComposeWindow? = null
    @Volatile private var observation = DesktopWindowObservation()
    @Volatile private var video: AnimeNativeVideoStatistics? = null
    @Volatile private var hookInstalled = false
    @Volatile private var status = "仅在面板打开或主动采集时读取诊断数据。"
    private var capture: PerformanceCapture? = null
    private var lastInteractionNoted = 0L
    private var detach: (() -> Unit)? = null

    init {
        scope.launch {
            combine(panelVisible, captureActive) { panel, active -> panel || active }
                .distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                val stopObserving = policy.startObserving()
                try { while (isActive) {
                    val observedSession = timings.session
                    if (observedSession == 0L) { delay(1_000); continue }
                    requestEdtObservation(observedSession)
                    val (data, rows) = sample()
                    captureLock.withLock {
                        // A snapshot made while viewing the panel must not become the first
                        // sample of a newly started recording after acquiring the writer lock.
                        if (observedSession != timings.session) return@withLock
                        capture?.let { current ->
                            try {
                                current.metrics.appendLine(data.toString())
                                drainFrames(current)
                                current.metrics.flush()
                                current.frames.flush()
                                if (System.nanoTime() >= current.deadlineNanos) finishCapture()
                            } catch (_: Exception) {
                                finishCapture()
                                status = "采集写入失败；请检查诊断目录的空间和权限。"
                            }
                        }
                    }
                    timings.publishIfCurrent(observedSession) {
                        snapshot.value = DesktopPerformanceSnapshot(rows, captureActive.value, status)
                    }
                    delay(1_000)
                } } finally { stopObserving() }
            }
        }
    }

    fun attach(target: ComposeWindow, onRenderApi: (GraphicsApi) -> Unit): () -> Unit {
        check(EventQueue.isDispatchThread())
        window = target
        var layer: SkiaLayer? = null
        var original: SkikoRenderDelegate? = null
        var wrapper: SkikoRenderDelegate? = null
        var reportRenderer: ((GraphicsApi) -> Unit)? = onRenderApi
        var attempts = 0
        fun installHook() {
            if (disposed.get() || layer != null) return
            val candidate = findSkiaLayer(target) ?: return
            val delegate = candidate.renderDelegate ?: return
            val observer = SkikoRenderDelegate { canvas, width, height, time ->
                val observedSession = timings.session
                val start = System.nanoTime()
                try { delegate.onRender(canvas, width, height, time) }
                finally {
                    val end = System.nanoTime()
                    val budget = observation.refreshHz?.let { 1_000_000_000L / it }
                    if (observedSession != 0L) timings.onRender(observedSession, start, end, budget)
                    budget?.let { policy.recordDesktopFrameWork(end - start, it) }
                }
            }
            original = delegate; wrapper = observer; layer = candidate
            candidate.renderDelegate = observer
            reportRenderer?.invoke(candidate.renderApi)
            // Reuse the already discovered window layer. A live GPU fallback is an event,
            // not something the video material should poll on every frame.
            candidate.onStateChanged(SkiaLayer.PropertyKind.Renderer) { changed ->
                EventQueue.invokeLater {
                    if (layer === changed) reportRenderer?.invoke(changed.renderApi)
                }
            }
            hookInstalled = true
        }
        // Initial composition can precede the layer's attachment. A bounded startup
        // retry never forces drawing and stops completely once attached.
        val startup = Timer(100) { event ->
            installHook()
            updateWindowObservation(target)
            if (layer != null || ++attempts >= 20) (event.source as Timer).stop()
        }.apply { isRepeats = true; start() }
        val monitor = PropertyChangeListener { updateWindowObservation(target) }
        target.addPropertyChangeListener("graphicsConfiguration", monitor)
        val visibility = object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent?) { updateWindowObservation(target) }
            override fun componentHidden(e: ComponentEvent?) { updateWindowObservation(target) }
            // Native caption/resize gestures do not necessarily dispatch client-area
            // InputEvents. Reserve UI capacity without inventing input-latency samples
            // or querying the native display mode on every move/resize event.
            override fun componentMoved(e: ComponentEvent?) { if (target.isShowing) noteInteractiveWork(System.nanoTime()) }
            override fun componentResized(e: ComponentEvent?) { if (target.isShowing) noteInteractiveWork(System.nanoTime()) }
        }
        target.addComponentListener(visibility)
        val input = AWTEventListener input@{ event ->
            // Plain pointer moves do not own an interaction lease or a latency sample.
            if (event !is InputEvent || !(event is KeyEvent || event.id == MouseEvent.MOUSE_PRESSED ||
                    event.id == MouseEvent.MOUSE_RELEASED || event.id == MouseEvent.MOUSE_DRAGGED ||
                    event.id == MouseEvent.MOUSE_WHEEL)) return@input
            val component = event.source as? Component ?: return@input
            if (SwingUtilities.getWindowAncestor(component) !== target) return@input
            val now = System.nanoTime()
            timings.onInput(now, event.`when`)
            noteInteractiveWork(now)
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(input,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        installHook()
        updateWindowObservation(target)
        val release: () -> Unit = {
            startup.stop()
            Toolkit.getDefaultToolkit().removeAWTEventListener(input)
            target.removePropertyChangeListener("graphicsConfiguration", monitor)
            target.removeComponentListener(visibility)
            layer?.let { if (it.renderDelegate === wrapper) it.renderDelegate = original }
            reportRenderer = null
            layer = null
            hookInstalled = false
            window = null
        }
        detach = release
        if (System.getenv("MANGAFLOW_PROFILE_START") == "1") toggleCapture()
        return { release(); detach = null }
    }

    fun togglePanel() {
        if (panelVisible.value) { hidePanel(); return }
        timings.setPanelVisible(true) { snapshot.value = DesktopPerformanceSnapshot(status = status) }
        panelVisible.value = true
    }

    fun hidePanel() {
        timings.setPanelVisible(false)
        panelVisible.value = false
    }

    /** EDT-only input/geometry notifications share one bounded policy lease update. */
    private fun noteInteractiveWork(now: Long) {
        if (now - lastInteractionNoted >= 100_000_000L) {
            lastInteractionNoted = now
            policy.noteInteraction()
        }
    }

    fun toggleCapture() {
        scope.launch {
            captureLock.withLock {
                if (capture != null) finishCapture() else startCapture()
                snapshot.value = snapshot.value.copy(recording = captureActive.value, status = status)
            }
        }
    }

    fun openFolder() {
        scope.launch {
            runCatching {
                check(folder.isDirectory || folder.mkdirs())
                Desktop.getDesktop().open(folder)
            }.onFailure {
                status = "无法打开诊断文件夹：%LOCALAPPDATA%\\MangaFlow\\diagnostics"
                snapshot.value = snapshot.value.copy(status = status)
            }
        }
    }

    private fun updateWindowObservation(target: ComposeWindow) {
        val config = target.graphicsConfiguration
        observation = DesktopWindowObservation(
            backend = runCatching { target.renderApi.name }.getOrDefault("未获取"),
            refreshHz = runCatching { config?.device?.displayMode?.refreshRate?.takeIf { it > 0 } }.getOrNull(),
            scaleX = config?.defaultTransform?.scaleX, scaleY = config?.defaultTransform?.scaleY,
            width = target.width, height = target.height,
            visible = target.isShowing && target.extendedState and Frame.ICONIFIED == 0,
        )
    }

    private fun requestEdtObservation(observedSession: Long) {
        if (!probePending.compareAndSet(false, true)) return
        val sent = System.nanoTime()
        probeStarted.set(sent)
        EventQueue.invokeLater {
            try {
                if (!disposed.get() && observedSession == timings.session) {
                    timings.onEdtProbe(observedSession, System.nanoTime() - sent)
                    window?.let(::updateWindowObservation)
                    video = peekDesktopNativeVideoStatistics(context)
                }
            } finally { probeStarted.set(0L); probePending.set(false) }
        }
    }

    private fun sample(): Pair<JsonObject, List<DesktopPerformanceMetric>> {
        val sample = timings.snapshot()
        val observed = observation
        val usage = policy.usage.value
        val tile = ReaderDecodeDiagnostics.snapshot()
        val runtime = Runtime.getRuntime()
        val cpu = runCatching { os?.processCpuLoad?.takeIf { it.isFinite() && it in 0.0..1.0 } }.getOrNull()
        val heap = runtime.totalMemory() - runtime.freeMemory()
        val collections = gc.map { it.collectionCount }.filter { it >= 0 }.takeIf { it.isNotEmpty() }?.sum()
        val collectionTime = gc.map { it.collectionTime }.filter { it >= 0 }.takeIf { it.isNotEmpty() }?.sum()
        val currentVideo = video
        val resourceAge = usage.resources.sampledAtMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        val videoAge = currentVideo?.sampledAtMillis?.let { (monotonicTimeMillis() - it).coerceAtLeast(0L) }
        val data = buildJsonObject {
            put("schema", 1); put("timestamp", Instant.now().toString()); put("process_id", ProcessHandle.current().pid())
            put("monotonic_nanos", System.nanoTime())
            put("jvm_uptime_ms", ManagementFactory.getRuntimeMXBean().uptime)
            put("application_version", System.getProperty("mangaflow.version", "development"))
            put("java_version", System.getProperty("java.version")); put("renderer", observed.backend)
            put("configured_vsync_enabled", SkikoProperties.vsyncEnabled)
            put("configured_frame_buffering", SkikoProperties.frameBuffering.name)
            put("gc_names", JsonArray(gc.map { JsonPrimitive(it.name) }))
            put("cpu_render_scope", "main_window_skiko_onRender_EDT_scene_recording")
            put("input_scope", "dispatch_to_next_scene_callback_end_within_1s_not_pixel_latency")
            put("render_hook", hookInstalled); put("refresh_hz_awt", observed.refreshHz?.let(::JsonPrimitive) ?: JsonNull)
            put("dpi_scale_x", observed.scaleX?.let(::JsonPrimitive) ?: JsonNull)
            put("dpi_scale_y", observed.scaleY?.let(::JsonPrimitive) ?: JsonNull)
            put("window_width", observed.width); put("window_height", observed.height); put("window_visible", observed.visible)
            put("cpu_render_ms", sample.render.json()); put("interactive_callback_interval_ms", sample.cadence.json())
            put("input_dispatch_to_cpu_render_ms", sample.input.json()); put("awt_event_age_ms", sample.eventAge.json())
            put("edt_dispatch_delay_ms", sample.edt.json()); put("cpu_rendered_callbacks", sample.rendered)
            put("cpu_budget_overruns", sample.overBudget); put("cpu_longest_overrun_run", sample.longestOverBudgetRun)
            put("edt_pending_ms", probeStarted.get().takeIf { it != 0L }?.let { JsonPrimitive((System.nanoTime() - it) / NANOS_PER_MILLI) } ?: JsonNull)
            put("process_cpu_fraction", cpu?.let(::JsonPrimitive) ?: JsonNull)
            put("heap_bytes", heap); put("heap_limit_bytes", runtime.maxMemory())
            put("process_resident_bytes", usage.resources.processResidentBytes?.let(::JsonPrimitive) ?: JsonNull)
            put("resource_sampled_at_epoch_ms", usage.resources.sampledAtMillis?.let(::JsonPrimitive) ?: JsonNull)
            put("resource_sample_age_ms", resourceAge?.let(::JsonPrimitive) ?: JsonNull)
            put("gc_collections", collections?.let(::JsonPrimitive) ?: JsonNull)
            put("gc_collection_time_ms", collectionTime?.let(::JsonPrimitive) ?: JsonNull)
            put("coil_decode_p95_ms", usage.decodeP95Millis?.let(::JsonPrimitive) ?: JsonNull)
            put("image_requests", usage.imageRequests); put("memory_hits", usage.memoryHits); put("disk_hits", usage.diskHits)
            put("active_prefetch", usage.activePrefetch); put("active_preparations", usage.activePreparations)
            put("active_decodes", usage.activeDecodes); put("active_background_pages", usage.activeBackgroundPages)
            put("active_enhancements", usage.activeEnhancements); put("active_realtime_renderers", usage.activeRealtimeRenderers)
            put("tile_decode_p50_ms", tile.p50Millis?.let(::JsonPrimitive) ?: JsonNull)
            put("tile_decode_p95_ms", tile.p95Millis?.let(::JsonPrimitive) ?: JsonNull)
            put("tile_decode_p99_ms", tile.p99Millis?.let(::JsonPrimitive) ?: JsonNull)
            put("tile_decode_active", tile.active); put("tile_decode_completed", tile.completed)
            put("tile_decode_cancelled", tile.cancelled); put("tile_decode_failures", tile.failures)
            put("tile_decode_samples", tile.samples); put("tile_reader_opened", tile.openedReaders)
            put("tile_reader_reuse", tile.reusedReaders)
            put("tile_retained_encoded_bytes", tile.retainedEncodedBytes)
            put("mpv_decoder", currentVideo?.hardwareDecoder?.let(::JsonPrimitive) ?: JsonNull)
            put("mpv_video_output", currentVideo?.videoOutput?.let(::JsonPrimitive) ?: JsonNull)
            put("mpv_decoder_drops", currentVideo?.decoderDroppedFrames?.let(::JsonPrimitive) ?: JsonNull)
            put("mpv_output_drops", currentVideo?.outputDroppedFrames?.let(::JsonPrimitive) ?: JsonNull)
            put("mpv_sampled_at_monotonic_ms", currentVideo?.sampledAtMillis?.let(::JsonPrimitive) ?: JsonNull)
            put("mpv_sample_age_ms", videoAge?.let(::JsonPrimitive) ?: JsonNull)
            put("present_ms", JsonNull); put("gpu_utilization", JsonNull); put("vram_used_bytes", JsonNull)
            put("capture_queue_overflow", timings.captureOverflow.get())
        }
        val rows = listOf(
            DesktopPerformanceMetric("实际后端 / CPU 钩子", "${observed.backend} / ${if (hookInstalled) "已接入" else "不可用"}"),
            DesktopPerformanceMetric("显示模式 / DPI", "${observed.refreshHz ?: "未知"} Hz · ${number(observed.scaleX)}×"),
            DesktopPerformanceMetric("主窗口 CPU 场景 P50 / P95 / P99", sample.render.display()),
            DesktopPerformanceMetric("交互回调间隔 P50 / P95 / P99", sample.cadence.display()),
            DesktopPerformanceMetric("CPU 超预算 / 最长连续", "${sample.overBudget} / ${sample.longestOverBudgetRun}"),
            DesktopPerformanceMetric("输入关联 P50 / P95 / P99", sample.input.display()),
            DesktopPerformanceMetric("AWT 事件年龄 P95 / EDT 等待 P95", "${number(sample.eventAge.p95)} / ${number(sample.edt.p95)} ms"),
            DesktopPerformanceMetric("进程 CPU / RSS", "${number(cpu?.times(100))}% / ${mib(usage.resources.processResidentBytes)}"),
            DesktopPerformanceMetric("JVM Heap / 上限", "${mib(heap)} / ${mib(runtime.maxMemory())}"),
            DesktopPerformanceMetric("GC 次数 / 累计收集耗时", "${collections ?: "—"} / ${collectionTime ?: "—"} ms（暂停见 JFR）"),
            DesktopPerformanceMetric("分块解码 P50 / P95 / P99", "${number(tile.p50Millis)} / ${number(tile.p95Millis)} / ${number(tile.p99Millis)} ms"),
            DesktopPerformanceMetric("分块运行 / 取消 / 解码器复用", "${tile.active} / ${tile.cancelled} / ${tile.reusedReaders}"),
            DesktopPerformanceMetric("Coil 解码 P95 / 请求", "${usage.decodeP95Millis ?: "—"} ms / ${usage.imageRequests}"),
            DesktopPerformanceMetric("内存命中 / 磁盘命中", "${usage.memoryHits} / ${usage.diskHits}"),
            DesktopPerformanceMetric("预取 / 预处理 / 后台页", "${usage.activePrefetch} / ${usage.activePreparations} / ${usage.activeBackgroundPages}"),
            DesktopPerformanceMetric("增强任务 / 实时渲染预留", "${usage.activeEnhancements} / ${usage.activeRealtimeRenderers}"),
            DesktopPerformanceMetric("MPV 解码器 / 输出", "${currentVideo?.hardwareDecoder ?: "—"} / ${currentVideo?.videoOutput ?: "—"}"),
            DesktopPerformanceMetric("MPV 解码 / 输出丢帧", "${currentVideo?.decoderDroppedFrames ?: "—"} / ${currentVideo?.outputDroppedFrames ?: "—"}"),
            DesktopPerformanceMetric("资源 / MPV 样本年龄", "${resourceAge ?: "—"} / ${videoAge ?: "—"} ms"),
            DesktopPerformanceMetric("GPU Present / GPU / 显存", "不可用 · 外部 ETW 采集"),
        )
        return data to rows
    }

    private fun startCapture() {
        var metrics: BufferedWriter? = null
        var frames: BufferedWriter? = null
        var recording: Recording? = null
        try {
            check(folder.isDirectory || folder.mkdirs())
            val requested = System.getenv("MANGAFLOW_PROFILE_ID")
            val id = requested?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
                ?: "windows-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())}"
            val seconds = System.getenv("MANGAFLOW_PROFILE_SECONDS")?.toLongOrNull()?.coerceIn(10, 600) ?: 120L
            // A repeated script id must never overwrite earlier measurements.
            val stem = File(folder, "$id-${System.currentTimeMillis()}")
            metrics = File("${stem.path}.jsonl").bufferedWriter()
            frames = File("${stem.path}-cpu-frames.csv").bufferedWriter()
            frames.appendLine("start_nanos,cpu_render_ms,callback_interval_ms,interactive_interval,input_dispatch_to_render_ms,display_budget_ms")
            recording = runCatching {
                val candidate = Recording(Configuration.getConfiguration("profile"))
                try { candidate.apply {
                    name = "$APP_DISPLAY_NAME Windows performance"
                    maxSize = 64L * 1024 * 1024
                    maxAge = Duration.ofSeconds(seconds + 5)
                    start()
                } } catch (failure: Exception) { candidate.close(); throw failure }
            }.getOrNull()
            val now = System.nanoTime()
            capture = PerformanceCapture(stem.name, now, now + seconds * 1_000_000_000L,
                metrics, frames, recording, File("${stem.path}.jfr"))
            timings.startCapture()
            status = "正在采集 ${seconds} 秒 · ${stem.name}${if (recording == null) " · JFR 不可用，仍保存 CPU/资源日志" else ""}"
            // Do not present the open panel's older rows as samples of this new capture.
            snapshot.value = DesktopPerformanceSnapshot(recording = true, status = status)
            captureActive.value = true
        } catch (_: Exception) {
            runCatching { metrics?.close() }; runCatching { frames?.close() }; runCatching { recording?.close() }
            status = "无法创建性能日志；请检查诊断目录的空间和权限。"
        }
    }

    private fun drainFrames(current: PerformanceCapture) {
        while (true) {
            val frame = timings.captureFrames.poll() ?: break
            current.frames.appendLine(listOf(frame.startNanos,
                frame.durationNanos / NANOS_PER_MILLI, frame.intervalNanos?.div(NANOS_PER_MILLI) ?: "",
                frame.interactive, frame.inputToRenderNanos?.div(NANOS_PER_MILLI) ?: "",
                frame.budgetNanos?.div(NANOS_PER_MILLI) ?: "").joinToString(","))
        }
    }

    private fun finishCapture() {
        val current = capture ?: return
        timings.stopCapture()
        capture = null
        val saved = try {
            runCatching {
                drainFrames(current)
                current.metrics.appendLine(buildJsonObject {
                    sample().first.forEach { (key, value) -> put(key, value) }
                    put("capture_finished", true)
                }.toString())
                current.metrics.flush(); current.frames.flush()
            }.isSuccess
        } finally { timings.captureDrained() }
        runCatching { current.metrics.close() }; runCatching { current.frames.close() }
        val jfrSaved = current.jfr?.let { recording ->
            try { recording.stop(); recording.dump(current.jfrFile.toPath()); true }
            catch (_: Exception) { false }
            finally { recording.close() }
        }
        status = if (saved) "采集已保存 · ${current.id}${if (jfrSaved == false) " · JFR 保存失败" else ""}"
            else "采集未完整保存；请检查诊断目录。"
        captureActive.value = false
    }

    suspend fun close() {
        disposed.set(true)
        withContext(Dispatchers.Main) { detach?.invoke(); detach = null }
        captureLock.withLock { finishCapture() }
        scope.cancel()
    }
}

private fun findSkiaLayer(component: Component): SkiaLayer? {
    if (component is SkiaLayer) return component
    if (component is Container) for (child in component.components) findSkiaLayer(child)?.let { return it }
    return null
}

private fun Distribution.json() = buildJsonObject {
    put("samples", count)
    put("p50", p50?.let(::JsonPrimitive) ?: JsonNull)
    put("p95", p95?.let(::JsonPrimitive) ?: JsonNull)
    put("p99", p99?.let(::JsonPrimitive) ?: JsonNull)
}
private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"
private fun mib(value: Long?) = value?.let { "${number(it / (1024.0 * 1024))} MiB" } ?: "—"
private fun Distribution.display() = "${number(p50)} / ${number(p95)} / ${number(p99)} ms ($count)"

@Composable
internal fun DesktopPerformanceSurface(performance: DesktopPerformance) {
    val visible by performance.panelVisible.collectAsState()
    val snapshot by performance.snapshot.collectAsState()
    DesktopPerformancePanel(visible, snapshot.metrics, snapshot.recording, snapshot.status,
        performance::hidePanel, performance::toggleCapture, performance::openFolder)
}
