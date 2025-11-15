package ship.f.engine.shared.ext

actual fun getAvailableCores(): Int =
    Runtime.getRuntime().availableProcessors()