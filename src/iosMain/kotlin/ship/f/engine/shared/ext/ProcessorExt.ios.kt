package ship.f.engine.shared.ext

import platform.Foundation.NSProcessInfo

actual fun getAvailableCores(): Int =
    NSProcessInfo.processInfo.processorCount.toInt()