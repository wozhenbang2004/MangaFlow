import AVFoundation
import CoreMedia
import CoreVideo
import MangaFlowShared
import MetalKit
import UIKit

/** Uses the AVPlayer supplied by Mediamp; this host never opens a media URL. */
final class MangaFlowAnime4KFactory: NSObject, AppleAnimeEnhancementFactory {
    func create(player: AVPlayer, listener: AppleAnimeEnhancementListener) -> AppleAnimeEnhancementRenderer {
        if #available(iOS 15.0, *), let device = MTLCreateSystemDefaultDevice(), device.supportsFamily(.apple3) {
            return MangaFlowAnime4KRenderer(player: player, listener: listener, device: device)
        }
        return MangaFlowOriginalVideoRenderer(player: player, listener: listener,
            reason: "Anime4K 需要 iOS 15 及支持 Metal 浮点纹理的设备，继续使用原画")
    }
}

private final class Anime4KVideoHost: UIView {
    let originalLayer = AVPlayerLayer()
    var metalView: MTKView?
    var onLayoutOrVisibility: (() -> Void)?
    private var boundPlayer: AVPlayer?
    private var foreground = true
    private var attached = false
    private var appActive = UIApplication.shared.applicationState == .active
    private var notifications: [NSObjectProtocol] = []

    init(player: AVPlayer) {
        boundPlayer = player
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .black
        originalLayer.videoGravity = .resizeAspect
        layer.addSublayer(originalLayer)
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: UIApplication.willResignActiveNotification,
                                                object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.appActive = false
            self.updateNativeConnection()
        })
        notifications.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                                object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.appActive = true
            self.updateNativeConnection()
        })
    }

    required init?(coder: NSCoder) { return nil }

    deinit { notifications.forEach { NotificationCenter.default.removeObserver($0) } }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        originalLayer.frame = bounds
        CATransaction.commit()
        metalView?.frame = bounds
        updateNativeConnection()
        onLayoutOrVisibility?()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateNativeConnection()
        onLayoutOrVisibility?()
    }

    func setScale(_ scale: String) {
        originalLayer.videoGravity = scale == "FILL" ? .resize : scale == "ZOOM" ? .resizeAspectFill : .resizeAspect
    }

    func setForeground(_ foreground: Bool) {
        self.foreground = foreground
        updateNativeConnection()
    }

    func setSurfaceAttached(_ attached: Bool) {
        self.attached = attached
        updateNativeConnection()
    }

    func dispose() {
        notifications.forEach { NotificationCenter.default.removeObserver($0) }
        notifications.removeAll()
        onLayoutOrVisibility = nil
        boundPlayer = nil
        originalLayer.player = nil
    }

    private func updateNativeConnection() {
        // AVPlayer pauses background video if it remains attached to a display
        // layer. Disconnect presentation for both Metal and original-only
        // hosts; only the shared session may decide whether audio keeps playing.
        let visible = foreground && appActive && attached && window != nil && bounds.width > 0 && bounds.height > 0
        let next = visible ? boundPlayer : nil
        if originalLayer.player !== next { originalLayer.player = next }
        // Reattachment deliberately does not call play() or change rate.
    }
}

private final class MangaFlowOriginalVideoRenderer: NSObject, AppleAnimeEnhancementRenderer {
    private let host: Anime4KVideoHost
    private var listener: AppleAnimeEnhancementListener?
    private let reason: String
    var view: UIView { host }

    init(player: AVPlayer, listener: AppleAnimeEnhancementListener, reason: String) {
        host = Anime4KVideoHost(player: player)
        self.listener = listener
        self.reason = reason
        super.init()
    }

    func bind(item: AVPlayerItem?) {}
    func configure(mode: String, shaderVersion: String, shaderSources: [String]) {
        listener?.enhancementChanged(mode: mode, phase: mode == "OFF" ? "OFF" : "UNAVAILABLE",
            detail: mode == "OFF" ? nil : reason, inputWidth: 0, inputHeight: 0, outputWidth: 0, outputHeight: 0)
    }
    func setVideoScale(scale_: String) { host.setScale(scale_) }
    func setForeground(foreground: Bool) { host.setForeground(foreground) }
    func setSurfaceAttached(attached: Bool) { host.setSurfaceAttached(attached) }
    func seekStarted() {}
    func refresh() {}
    func dispose() { listener = nil; host.dispose() }
}

@available(iOS 15.0, *)
private final class Anime4KDisplayLinkTarget: NSObject {
    weak var renderer: MangaFlowAnime4KRenderer?
    @objc func tick(_ sender: CADisplayLink) { renderer?.readFrame(sender) }
}

private final class Anime4KDecodedFrame {
    let buffer: CVPixelBuffer
    let time: CMTime
    init(buffer: CVPixelBuffer, time: CMTime) { self.buffer = buffer; self.time = time }
}

private final class Anime4KPresentationReceipt {
    let revision: UInt64
    let plan: Anime4KMetalPlan
    var completed = false
    var presented = false
    init(revision: UInt64, plan: Anime4KMetalPlan) { self.revision = revision; self.plan = plan }
}

/** All player, UIKit, ownership, and publication state is confined to Main. */
@available(iOS 15.0, *)
private final class MangaFlowAnime4KRenderer: NSObject, AppleAnimeEnhancementRenderer, MTKViewDelegate {
    private let player: AVPlayer
    private let device: MTLDevice
    private let host: Anime4KVideoHost
    private var listener: AppleAnimeEnhancementListener?
    private var item: AVPlayerItem?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [NSKeyValueObservation] = []
    private var notifications: [NSObjectProtocol] = []
    private var itemNotifications: [NSObjectProtocol] = []
    private var displayLink: CADisplayLink?
    private var commandQueue: MTLCommandQueue?
    private var textureCache: CVMetalTextureCache?
    private var program: Anime4KMetalProgram?
    private var plan: Anime4KMetalPlan?
    private var lastFrame: Anime4KDecodedFrame?
    private var pendingDraw: Anime4KDecodedFrame?
    private var inFlight = 0
    private var preparingPlan = false
    private var compiling = false
    private var metadataLoading = false
    private var metadataApproved = false
    private var metadataRevision: UInt64 = 0
    private var revision: UInt64 = 0
    private var mode = "OFF"
    private var shaderVersion = ""
    private var sources: [String] = []
    private var scale = "FIT"
    private var foreground = true
    private var attached = false
    private var appActive = UIApplication.shared.applicationState == .active
    private var disposed = false
    private var terminalFailure: (phase: String, detail: String, retryOnMediaTraits: Bool)?
    private var displayed = false
    private var missingFrameAt: CMTime?
    private var lastReport = ""
    private var currentColorSpace = CGColorSpace(name: CGColorSpace.itur_709)
    private var sourceTransfer: String?
    private var sourcePrimaries: String?

    var view: UIView { host }

    init(player: AVPlayer, listener: AppleAnimeEnhancementListener, device: MTLDevice) {
        self.player = player
        self.listener = listener
        self.device = device
        host = Anime4KVideoHost(player: player)
        super.init()
        host.onLayoutOrVisibility = { [weak self] in self?.surfaceChanged() }
        observations = [
            player.observe(\.rate, options: [.new]) { [weak self] _, _ in self?.onMain { $0.updateDisplayLink() } },
            player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in self?.onMain { $0.updateDisplayLink() } },
            player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in self?.onMain { $0.evaluate() } },
            player.observe(\.isExternalPlaybackActive, options: [.new]) { [weak self] _, _ in self?.onMain { $0.evaluate() } },
        ]
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.appActive = false
            self.evaluate()
        })
        notifications.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.appActive = true
            self.evaluate()
        })
        notifications.append(center.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { [weak self] _ in
            self?.fallback(Anime4KMetalError.unavailable("系统内存紧张，Anime4K 已回到原画；可稍后重新开启"))
        })
    }

    func bind(item: AVPlayerItem?) {
        guard !disposed else { return }
        invalidateProcessing(removeOutput: true)
        itemObservations.removeAll()
        itemNotifications.forEach { NotificationCenter.default.removeObserver($0) }
        itemNotifications.removeAll()
        self.item = item
        terminalFailure = nil
        metadataRevision &+= 1
        metadataLoading = false
        metadataApproved = false
        sourceTransfer = nil
        sourcePrimaries = nil
        currentColorSpace = CGColorSpace(name: CGColorSpace.itur_709)
        if let item = item {
            itemObservations = [
                item.observe(\.status, options: [.new]) { [weak self] observed, _ in
                    self?.onMain { if $0.item === observed { $0.evaluate() } }
                },
                item.observe(\.tracks, options: [.new]) { [weak self] observed, _ in
                    self?.onMain { if $0.item === observed { $0.mediaTraitsChanged() } }
                },
            ]
            let center = NotificationCenter.default
            itemNotifications.append(center.addObserver(forName: AVPlayerItem.mediaSelectionDidChangeNotification,
                                                        object: item, queue: .main) { [weak self] event in
                guard let self = self, self.item === (event.object as? AVPlayerItem) else { return }
                self.mediaTraitsChanged()
            })
            itemNotifications.append(center.addObserver(forName: AVPlayerItem.timeJumpedNotification,
                                                        object: item, queue: .main) { [weak self] event in
                guard let self = self, self.item === (event.object as? AVPlayerItem) else { return }
                self.seekStarted()
            })
        }
        evaluate()
    }

    func configure(mode: String, shaderVersion: String, shaderSources: [String]) {
        guard !disposed else { return }
        guard self.mode != mode || self.shaderVersion != shaderVersion || sources != shaderSources else { return }
        invalidateProcessing(removeOutput: true)
        self.mode = mode
        self.shaderVersion = shaderVersion
        sources = shaderSources
        program = nil
        terminalFailure = nil
        evaluate()
    }

    func setVideoScale(scale_: String) {
        guard !disposed, self.scale != scale_ else { return }
        self.scale = scale_
        host.setScale(scale_)
        invalidateProcessing(removeOutput: false, retainFrame: true)
        evaluate()
    }

    func setForeground(foreground: Bool) {
        guard !disposed, self.foreground != foreground else { return }
        self.foreground = foreground
        host.setForeground(foreground)
        evaluate()
    }

    func setSurfaceAttached(attached: Bool) {
        guard !disposed, self.attached != attached else { return }
        self.attached = attached
        host.setSurfaceAttached(attached)
        evaluate()
    }

    func seekStarted() {
        guard !disposed, mode != "OFF" else { return }
        invalidateProcessing(removeOutput: false)
        evaluate()
    }

    func refresh() { evaluate() }

    func dispose() {
        guard !disposed else { return }
        disposed = true
        metadataRevision &+= 1
        invalidateProcessing(removeOutput: true)
        observations.removeAll()
        itemObservations.removeAll()
        notifications.forEach { NotificationCenter.default.removeObserver($0) }
        itemNotifications.forEach { NotificationCenter.default.removeObserver($0) }
        notifications.removeAll()
        itemNotifications.removeAll()
        host.dispose()
        host.metalView?.delegate = nil
        host.metalView?.removeFromSuperview()
        host.metalView = nil
        item = nil
        program = nil
        sources.removeAll()
        listener = nil
    }

    private var canRender: Bool {
        !disposed && mode != "OFF" && foreground && appActive && attached && host.window != nil &&
            host.bounds.width > 0 && host.bounds.height > 0 &&
            !player.isExternalPlaybackActive && item != nil && player.currentItem === item && terminalFailure == nil
    }

    private func evaluate() {
        guard !disposed else { return }
        if mode == "OFF" {
            suspend()
            report("OFF", nil)
            return
        }
        guard mode == "FAST" || mode == "QUALITY" else {
            fallback(Anime4KMetalError.failed("未识别的 Anime4K 档位，继续使用原画"))
            return
        }
        if let failure = terminalFailure { report(failure.phase, failure.detail); return }
        guard foreground, appActive, attached, host.window != nil, host.bounds.width > 0, host.bounds.height > 0 else {
            suspend()
            report("WAITING", "画面重新可见时恢复 Anime4K；音频仍由系统播放器管理")
            return
        }
        guard !player.isExternalPlaybackActive else {
            suspend()
            report("UNAVAILABLE", "外部播放由系统输出原画，Anime4K 不会冒充接收端增强")
            return
        }
        guard let item = item, player.currentItem === item, item.status == .readyToPlay else {
            report("WAITING", "等待系统播放器准备视频")
            return
        }
        guard metadataApproved else { inspectMetadata(item); return }
        guard let program = program else {
            if !compiling {
                compiling = true
                let expected = revision
                report("WAITING", "正在准备 Anime4K Metal，暂时显示原画")
                Anime4KMetalCompiler.shared.compile(device: device, sources: sources) { [weak self] result in
                    guard let self = self, !self.disposed, self.revision == expected else { return }
                    self.compiling = false
                    switch result {
                    case .success(let program): self.program = program; self.evaluate()
                    case .failure(let error): self.fallback(error)
                    }
                }
            }
            return
        }
        _ = program
        do {
            try prepareOutput(item)
            if !displayed { report("CONFIGURED", "Anime4K 已配置，等待实际增强画面") }
            updateDisplayLink()
            if let frame = lastFrame, !displayed { schedule(frame) }
        } catch { fallback(error) }
    }

    private func suspend() {
        // Keep only compiled programs, whose two-entry cache is independent of
        // media. Decoded buffers, float textures, video output, and display work
        // are released on background / surface removal / OFF.
        if videoOutput != nil || plan != nil || displayLink != nil || compiling {
            invalidateProcessing(removeOutput: true)
        }
    }

    private func invalidateProcessing(removeOutput: Bool, retainFrame: Bool = false) {
        revision &+= 1
        compiling = false
        preparingPlan = false
        displayed = false
        missingFrameAt = nil
        pendingDraw = nil
        if !retainFrame { lastFrame = nil }
        plan = nil
        displayLink?.invalidate()
        displayLink = nil
        host.metalView?.alpha = 0
        videoOutput?.suppressesPlayerRendering = false
        if removeOutput {
            let retiringOutput = videoOutput
            videoOutput = nil
            if let output = retiringOutput { item?.remove(output) }
            commandQueue = nil
            if let cache = textureCache { CVMetalTextureCacheFlush(cache, 0) }
            textureCache = nil
            host.metalView?.releaseDrawables()
        }
        // In-flight command buffers retain their own plan/buffer until GPU
        // completion. Do not reset inFlight here or reuse their arena early.
    }

    private func fallback(_ error: Error, retryOnMediaTraits: Bool = false) {
        guard !disposed, mode != "OFF" else { return }
        let phase: String
        let message: String
        if let known = error as? Anime4KMetalError {
            switch known {
            case .unavailable(let detail): phase = "UNAVAILABLE"; message = detail
            case .failed(let detail): phase = "FAILED"; message = detail
            }
        } else {
            // Native compiler/GPU diagnostics can be huge. Keep user-facing
            // state bounded and never include source URLs or secret headers.
            phase = "FAILED"; message = "Anime4K Metal 处理失败，已恢复原画"
        }
        terminalFailure = (phase, message, retryOnMediaTraits)
        invalidateProcessing(removeOutput: true)
        report(phase, message)
    }

    private func mediaTraitsChanged() {
        guard !disposed else { return }
        metadataRevision &+= 1
        metadataLoading = false
        metadataApproved = false
        sourceTransfer = nil
        sourcePrimaries = nil
        if terminalFailure?.retryOnMediaTraits == true { terminalFailure = nil }
        // Recheck HLS track changes before another GPU frame can cover a newly
        // selected legible track or an HDR representation.
        invalidateProcessing(removeOutput: true)
        evaluate()
    }

    private func inspectMetadata(_ item: AVPlayerItem) {
        guard !metadataLoading else { return }
        metadataLoading = true
        let expected = metadataRevision
        let asset = item.asset
        let keys = ["tracks", "hasProtectedContent", "availableMediaCharacteristicsWithMediaSelectionOptions"]
        report("WAITING", "正在确认视频色彩与字幕，暂时显示原画")
        asset.loadValuesAsynchronously(forKeys: keys) { [weak self, weak item] in
            DispatchQueue.main.async {
                guard let self = self, let item = item, !self.disposed,
                      self.item === item, self.metadataRevision == expected else { return }
                guard keys.allSatisfy({ asset.statusOfValue(forKey: $0, error: nil) == .loaded }) else {
                    self.metadataLoading = false
                    self.fallback(Anime4KMetalError.unavailable("无法确认该线路的视频属性，继续使用原画"), retryOnMediaTraits: true)
                    return
                }
                if asset.hasProtectedContent {
                    self.metadataLoading = false
                    self.fallback(Anime4KMetalError.unavailable("受保护的视频继续使用系统原画"), retryOnMediaTraits: true)
                    return
                }
                // Conservatively preserve all embedded/forced subtitle tracks.
                // A video-only MTKView cannot promise AVPlayerLayer's captions.
                let legible = asset.mediaSelectionGroup(forMediaCharacteristic: .legible)
                let tracks = item.tracks.compactMap { $0.assetTrack }
                if legible?.options.isEmpty == false || tracks.contains(where: {
                    $0.mediaType == .subtitle || $0.mediaType == .text || $0.mediaType == .closedCaption
                }) {
                    self.metadataLoading = false
                    self.fallback(Anime4KMetalError.unavailable("这条线路含内嵌软字幕，已保留系统原画和字幕"), retryOnMediaTraits: true)
                    return
                }
                let videoTracks = tracks.filter { $0.mediaType == .video }
                guard !videoTracks.isEmpty else {
                    self.metadataLoading = false
                    self.fallback(Anime4KMetalError.unavailable("该线路未提供可确认的视频轨道，继续使用原画"), retryOnMediaTraits: true)
                    return
                }
                self.loadVideoTraits(videoTracks, item: item, expected: expected)
            }
        }
    }

    private func loadVideoTraits(_ tracks: [AVAssetTrack], item: AVPlayerItem, expected: UInt64) {
        let keys = ["formatDescriptions", "preferredTransform", "naturalSize"]
        let group = DispatchGroup()
        for track in tracks {
            group.enter()
            track.loadValuesAsynchronously(forKeys: keys) { group.leave() }
        }
        group.notify(queue: .main) { [weak self, weak item] in
            guard let self = self, let item = item, !self.disposed,
                  self.item === item, self.metadataRevision == expected else { return }
            self.metadataLoading = false
            var transfers: Set<String> = []
            var primaries: Set<String> = []
            for track in tracks {
                guard keys.allSatisfy({ track.statusOfValue(forKey: $0, error: nil) == .loaded }) else {
                    self.fallback(Anime4KMetalError.unavailable("无法确认视频轨道属性，继续使用原画"), retryOnMediaTraits: true); return
                }
                if track.hasMediaCharacteristic(.containsHDRVideo) || track.hasMediaCharacteristic(.usesWideGamutColorSpace) {
                    self.fallback(Anime4KMetalError.unavailable("HDR / 广色域保留系统原画，当前 Anime4K 仅增强 SDR"), retryOnMediaTraits: true); return
                }
                // The initial path explicitly preserves native orientation and
                // aperture for media it cannot map exactly; no stretched guess.
                if !track.preferredTransform.isIdentity {
                    self.fallback(Anime4KMetalError.unavailable("该视频含画面旋转或变换，已保留系统原画"), retryOnMediaTraits: true); return
                }
                guard let descriptions = track.formatDescriptions as? [CMFormatDescription], !descriptions.isEmpty else {
                    self.fallback(Anime4KMetalError.unavailable("无法确认视频像素格式，继续使用原画"), retryOnMediaTraits: true); return
                }
                for description in descriptions {
                    let extensions = CMFormatDescriptionGetExtensions(description) as? [String: Any] ?? [:]
                    let transfer = extensions[kCMFormatDescriptionExtension_TransferFunction as String] as? String
                    let primary = extensions[kCMFormatDescriptionExtension_ColorPrimaries as String] as? String
                    if !self.acceptsColorTags(transfer: transfer, primaries: primary) {
                        self.fallback(Anime4KMetalError.unavailable("该视频的色彩空间暂不支持增强，已保留原画"), retryOnMediaTraits: true); return
                    }
                    if let transfer = transfer { transfers.insert(transfer) }
                    if let primary = primary { primaries.insert(primary) }
                }
            }
            // Ambiguous/absent track tags cannot become a guessed 709 source.
            // The decoded frame must supply any missing or conflicting value.
            self.sourceTransfer = transfers.count == 1 ? transfers.first : nil
            self.sourcePrimaries = primaries.count == 1 ? primaries.first : nil
            self.metadataApproved = true
            self.evaluate()
        }
    }

    private func acceptsColorTags(transfer: String?, primaries: String?) -> Bool {
        if let transfer = transfer,
           transfer != (kCVImageBufferTransferFunction_ITU_R_709_2 as String) &&
           transfer != (kCVImageBufferTransferFunction_sRGB as String) { return false }
        if let primaries = primaries, primaries != (kCVImageBufferColorPrimaries_ITU_R_709_2 as String) { return false }
        return true
    }

    private func prepareOutput(_ item: AVPlayerItem) throws {
        if commandQueue == nil {
            guard let queue = device.makeCommandQueue() else {
                throw Anime4KMetalError.unavailable("当前 GPU 无法创建 Anime4K 队列")
            }
            commandQueue = queue
        }
        if textureCache == nil {
            var cache: CVMetalTextureCache?
            guard CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache) == kCVReturnSuccess,
                  let result = cache else { throw Anime4KMetalError.unavailable("系统无法共享视频纹理，继续使用原画") }
            textureCache = result
        }
        if host.metalView == nil {
            let metal = MTKView(frame: host.bounds, device: device)
            metal.isUserInteractionEnabled = false
            metal.isPaused = true
            metal.enableSetNeedsDisplay = false
            metal.framebufferOnly = false
            metal.colorPixelFormat = .bgra8Unorm
            metal.autoResizeDrawable = true
            metal.clearColor = MTLClearColorMake(0, 0, 0, 1)
            metal.alpha = 0
            metal.delegate = self
            (metal.layer as? CAMetalLayer)?.colorspace = currentColorSpace
            host.metalView = metal
            host.addSubview(metal)
        }
        if videoOutput == nil {
            let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
                kCVPixelBufferMetalCompatibilityKey as String: true,
            ])
            output.suppressesPlayerRendering = false
            item.add(output)
            videoOutput = output
        }
    }

    private func surfaceChanged() {
        guard !disposed else { return }
        if let plan = plan, let size = drawableSize(), plan.outputSize != size {
            invalidateProcessing(removeOutput: false, retainFrame: true)
        }
        evaluate()
    }

    private func drawableSize() -> Anime4KTextureSize? {
        guard let size = host.metalView?.drawableSize, size.width.isFinite, size.height.isFinite,
              size.width >= 1, size.height >= 1, size.width <= 16_384, size.height <= 16_384 else { return nil }
        return Anime4KTextureSize(width: Int(size.width), height: Int(size.height))
    }

    private func updateDisplayLink() {
        guard canRender, metadataApproved, program != nil, videoOutput != nil else {
            displayLink?.isPaused = true
            return
        }
        if displayLink == nil {
            let target = Anime4KDisplayLinkTarget()
            target.renderer = self
            let link = CADisplayLink(target: target, selector: #selector(Anime4KDisplayLinkTarget.tick(_:)))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
        // A paused video may still need its first frame or a layout redraw.
        displayLink?.isPaused = displayed && player.timeControlStatus != .playing
    }

    fileprivate func readFrame(_ sender: CADisplayLink) {
        guard canRender, metadataApproved, !preparingPlan, inFlight == 0, let output = videoOutput else { return }
        let time = output.itemTime(forHostTime: sender.targetTimestamp)
        guard output.hasNewPixelBuffer(forItemTime: time) else {
            if let frame = lastFrame, !displayed { schedule(frame) }
            if player.timeControlStatus == .playing {
                let now = player.currentTime()
                if let start = missingFrameAt, CMTimeGetSeconds(CMTimeSubtract(now, start)) > 4 {
                    fallback(Anime4KMetalError.unavailable("该线路无法提供可增强的视频帧，已恢复系统原画"), retryOnMediaTraits: true)
                } else if missingFrameAt == nil { missingFrameAt = now }
            }
            return
        }
        var displayTime = CMTime.invalid
        guard let buffer = output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: &displayTime) else { return }
        missingFrameAt = nil
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA else {
            fallback(Anime4KMetalError.unavailable("该视频帧格式不支持 Anime4K SDR 增强，已保留原画"), retryOnMediaTraits: true); return
        }
        let frameTransfer = CVBufferCopyAttachment(buffer, kCVImageBufferTransferFunctionKey, nil) as? String
        let framePrimaries = CVBufferCopyAttachment(buffer, kCVImageBufferColorPrimariesKey, nil) as? String
        guard let transfer = frameTransfer ?? sourceTransfer, let primaries = framePrimaries ?? sourcePrimaries else {
            fallback(Anime4KMetalError.unavailable("缺少可确认的 SDR 色彩信息，已保留系统原画"), retryOnMediaTraits: true); return
        }
        guard acceptsColorTags(transfer: transfer, primaries: primaries) else {
            fallback(Anime4KMetalError.unavailable("当前视频切换到不支持的色彩空间，已恢复原画"), retryOnMediaTraits: true); return
        }
        currentColorSpace = CGColorSpace(name: transfer == (kCVImageBufferTransferFunction_sRGB as String)
            ? CGColorSpace.sRGB : CGColorSpace.itur_709)
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let aperture = CVImageBufferGetCleanRect(buffer)
        let display = CVImageBufferGetDisplaySize(buffer)
        guard abs(aperture.origin.x) < 0.5, abs(aperture.origin.y) < 0.5,
              abs(aperture.width - CGFloat(width)) < 0.5, abs(aperture.height - CGFloat(height)) < 0.5,
              abs(display.width / max(1, display.height) - CGFloat(width) / CGFloat(max(1, height))) < 0.002 else {
            fallback(Anime4KMetalError.unavailable("该视频含裁切或非方形像素，已保留系统原画"), retryOnMediaTraits: true); return
        }
        (host.metalView?.layer as? CAMetalLayer)?.colorspace = currentColorSpace
        let frame = Anime4KDecodedFrame(buffer: buffer, time: displayTime.isValid ? displayTime : time)
        lastFrame = frame
        schedule(frame)
    }

    private func schedule(_ frame: Anime4KDecodedFrame) {
        guard canRender, inFlight == 0, !preparingPlan, let program = program, let output = drawableSize() else { return }
        let input = Anime4KTextureSize(width: CVPixelBufferGetWidth(frame.buffer), height: CVPixelBufferGetHeight(frame.buffer))
        if plan?.inputSize != input || plan?.outputSize != output || plan?.scale != scale {
            if plan != nil {
                // HLS can change decoded dimensions without replacing its item.
                // Show current native video while a new arena is allocated.
                invalidateProcessing(removeOutput: false, retainFrame: true)
            }
            preparingPlan = true
            updateDisplayLink()
            let expected = revision
            let requestedScale = scale
            let device = self.device
            Anime4KMetalCompiler.shared.queue.async { [weak self] in
                let result = Result { try program.makePlan(device: device, input: input, output: output, scale: requestedScale) }
                DispatchQueue.main.async {
                    guard let self = self, !self.disposed, self.revision == expected else { return }
                    self.preparingPlan = false
                    switch result {
                    case .success(let plan):
                        self.plan = plan
                        self.updateDisplayLink()
                        let age = abs(CMTimeGetSeconds(CMTimeSubtract(self.player.currentTime(), frame.time)))
                        if self.player.timeControlStatus != .playing || (age.isFinite && age < 0.25) {
                            self.schedule(frame)
                        }
                    case .failure(let error): self.fallback(error)
                    }
                }
            }
            return
        }
        pendingDraw = frame
        host.metalView?.draw()
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        guard !disposed else { return }
        if plan != nil { invalidateProcessing(removeOutput: false, retainFrame: true) }
        // MTKView invokes this before its new drawable size is fully installed.
        DispatchQueue.main.async { [weak self] in self?.evaluate() }
    }

    func draw(in view: MTKView) {
        guard canRender, inFlight == 0, let frame = pendingDraw, let plan = plan,
              let textureCache = textureCache, let queue = commandQueue, let drawable = view.currentDrawable else { return }
        pendingDraw = nil
        guard drawable.texture.width == plan.outputSize.width, drawable.texture.height == plan.outputSize.height else {
            // A screen/rotation layout transition is not a shader failure.
            invalidateProcessing(removeOutput: false, retainFrame: true)
            DispatchQueue.main.async { [weak self] in self?.evaluate() }
            return
        }
        var texture: CVMetalTexture?
        let result = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, textureCache, frame.buffer,
            nil, .bgra8Unorm, plan.inputSize.width, plan.inputSize.height, 0, &texture)
        guard result == kCVReturnSuccess, let cvTexture = texture, let input = CVMetalTextureGetTexture(cvTexture),
              let command = queue.makeCommandBuffer() else {
            fallback(Anime4KMetalError.unavailable("无法共享当前视频纹理，已恢复原画")); return
        }
        do { try plan.encode(commandBuffer: command, input: input, output: drawable.texture) }
        catch { fallback(error); return }
        let receipt = Anime4KPresentationReceipt(revision: revision, plan: plan)
        inFlight += 1
        drawable.addPresentedHandler { [weak self, receipt] presented in
            let shown = presented.presentedTime > 0
            DispatchQueue.main.async {
                guard let self = self, shown else { return }
                receipt.presented = true
                self.finish(receipt)
            }
        }
        command.addCompletedHandler { [weak self, frame, cvTexture, plan, receipt] completed in
            withExtendedLifetime((frame, cvTexture, plan)) {
                let succeeded = completed.status == .completed
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.inFlight = max(0, self.inFlight - 1)
                    guard !self.disposed, self.revision == receipt.revision else { return }
                    guard succeeded else {
                        self.fallback(Anime4KMetalError.failed("GPU 未完成 Anime4K 画面，已恢复原画")); return
                    }
                    receipt.completed = true
                    self.finish(receipt)
                }
            }
        }
        command.present(drawable)
        command.commit()
    }

    private func finish(_ receipt: Anime4KPresentationReceipt) {
        guard canRender, revision == receipt.revision, receipt.completed else { return }
        // Compilation/encoding alone never produces ACTIVE. A successful GPU
        // result may become visible first; the actual drawable presentation
        // acknowledgement is additionally required to publish ACTIVE.
        host.metalView?.alpha = 1
        guard receipt.presented else { return }
        displayed = true
        videoOutput?.suppressesPlayerRendering = true
        let detail = receipt.plan.upscaled ? "Anime4K · Metal CNN 修复与 2× 超分"
            : "Anime4K · Metal CNN 修复；当前显示尺寸无需 2× 超分"
        report("ACTIVE", detail, plan: receipt.plan)
        updateDisplayLink()
    }

    private func report(_ phase: String, _ detail: String?, plan: Anime4KMetalPlan? = nil) {
        let input = plan?.inputSize
        let output = plan?.processedSize
        let key = "\(mode)|\(phase)|\(detail ?? "")|\(input?.width ?? 0)|\(input?.height ?? 0)|\(output?.width ?? 0)|\(output?.height ?? 0)"
        guard key != lastReport else { return }
        lastReport = key
        listener?.enhancementChanged(mode: mode, phase: phase, detail: detail,
            inputWidth: Int32(input?.width ?? 0), inputHeight: Int32(input?.height ?? 0),
            outputWidth: Int32(output?.width ?? 0), outputHeight: Int32(output?.height ?? 0))
    }

    private func onMain(_ action: @escaping (MangaFlowAnime4KRenderer) -> Void) {
        // AVFoundation KVO can arrive during add/remove/replace. Queue the
        // observation so it cannot re-enter a partially retired output.
        DispatchQueue.main.async { [weak self] in if let self = self { action(self) } }
    }
}
