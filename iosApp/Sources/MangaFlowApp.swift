import SwiftUI
import UIKit
import MangaFlowShared

@main
struct MangaFlowApp: App {
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
