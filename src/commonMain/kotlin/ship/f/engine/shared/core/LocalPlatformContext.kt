package ship.f.engine.shared.core

import androidx.compose.runtime.staticCompositionLocalOf

expect class PlatformContext

val LocalPlatformContext = staticCompositionLocalOf<PlatformContext> {
    error("PlatformContext not provided")
}