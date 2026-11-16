import SwiftUI
import UIKit
import MangaFlowShared

@main
struct MangaFlowApp: App {
    init() {
        AppleAnimeEnhancementBridge.shared.registerFactory(factory: MangaFlowAnime4KFactory())
    }

    var body: some Scene {
        WindowGroup {
            MangaFlowView()
                .ignoresSafeArea()
        }
    }
}

struct MangaFlowView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
