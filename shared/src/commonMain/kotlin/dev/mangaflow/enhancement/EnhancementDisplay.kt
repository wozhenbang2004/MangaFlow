package dev.mangaflow.enhancement

/** Display choices are independent of processing policy and never create a session override. */
enum class EnhancementMetric(val reader: Boolean, val anime: Boolean, val resource: Boolean = false) {
    QUALITY(true, true),
    DIMENSIONS(true, true),
    PREPARED(true, true),
    TASKS(true, false),
    PAGE_LATENCY(true, false),
    CACHE_HITS(true, false),
    PRESENTED_FPS(false, true),
    DROPPED_FRAMES(false, true),
    MOTION_STATUS(false, true),
    MOTION_THROUGHPUT(false, true),
    MOTION_LATENCY(false, true),
    GPU_TIME(false, true),
    BUFFER(false, true),
    BUFFER_BYTES(false, true),
    NEXT_EPISODE(false, true),
    PROCESS_CPU(true, true, true),
    PROCESS_MEMORY(true, true, true),
    THERMAL(true, true, true);

    fun supports(content: EnhancementContent): Boolean = if (content == EnhancementContent.ANIME) anime else reader

    fun label(content: EnhancementContent): String = when (this) {
        QUALITY -> if (content == EnhancementContent.ANIME) "画质状态" else "本页增强"
        DIMENSIONS -> if (content == EnhancementContent.ANIME) "视频与增强尺寸" else "本页增强文件尺寸"
        PREPARED -> if (content == EnhancementContent.ANIME) "前方预处理片段" else "前方增强准备"
        TASKS -> "增强任务"
        PAGE_LATENCY -> "整页处理耗时"
        CACHE_HITS -> "增强缓存命中"
        PRESENTED_FPS -> "当前帧率"
        DROPPED_FRAMES -> "近期丢帧"
        MOTION_STATUS -> "补帧状态"
        MOTION_THROUGHPUT -> "补帧处理速度"
        MOTION_LATENCY -> "单次补帧耗时"
        GPU_TIME -> "增强 GPU 耗时"
        BUFFER -> "前方已缓冲时长"
        BUFFER_BYTES -> "原生缓冲字节"
        NEXT_EPISODE -> "下一集准备"
        PROCESS_CPU -> "进程 CPU"
        PROCESS_MEMORY -> "进程驻留内存"
        THERMAL -> "设备热压力"
    }

    companion object {
        fun defaults(content: EnhancementContent): Set<EnhancementMetric> = if (content == EnhancementContent.ANIME)
            setOf(QUALITY, PRESENTED_FPS, DROPPED_FRAMES, BUFFER, NEXT_EPISODE, PREPARED) else setOf(QUALITY, PREPARED)
    }
}
