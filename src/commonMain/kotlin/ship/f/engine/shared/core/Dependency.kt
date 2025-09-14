package ship.f.engine.shared.core

import kotlinx.serialization.Serializable
import ship.f.engine.shared.core.State.NoState

@Serializable
abstract class Dependency : SubPub<NoState>() { // While a dependency inherits from a subpub it should not be used to listen to events, should split
    open fun init(scope: ScopeTo){

    }

    override fun initState(): NoState = NoState()

    override suspend fun onEvent() {

    }
}