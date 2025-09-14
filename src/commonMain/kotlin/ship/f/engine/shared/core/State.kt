package ship.f.engine.shared.core

import kotlinx.serialization.Serializable

@Serializable
abstract class State {
    @Serializable
    class NoState : State()
}