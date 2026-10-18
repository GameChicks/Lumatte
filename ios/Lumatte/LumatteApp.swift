import SwiftUI

@main
struct LumatteApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(LumaTheme.amber)
        }
    }
}
