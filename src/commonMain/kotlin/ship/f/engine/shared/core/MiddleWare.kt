package ship.f.engine.shared.core

import kotlinx.serialization.Serializable

@Suppress("UNCHECKED_CAST")
@Serializable
abstract class MiddleWare<E : ScopedEvent> {

    private var isInitialized = false
    operator fun invoke(event: ScopedEvent): E? = process(event as E)

    // I can't remember what this method is supposed to do? Can't even be overridden
    private fun init() {

    }

    fun tryInit(){
        if (!isInitialized){
            init()
            isInitialized = true
        }
    }

    abstract fun process(event: E): E?
}