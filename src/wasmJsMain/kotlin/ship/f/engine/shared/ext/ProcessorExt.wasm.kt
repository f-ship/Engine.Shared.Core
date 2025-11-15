package ship.f.engine.shared.ext

@Suppress("UNREACHABLE_CODE")
actual fun getAvailableCores(): Int {
    val cores = js("navigator?.hardwareConcurrency") as? Int?
    return cores ?: 1
}
