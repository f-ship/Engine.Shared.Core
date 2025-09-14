package ship.f.engine.shared.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import kotlin.reflect.KClass

abstract class Slice<S : State, SP : SubPub<S>>(
    subPubClass: KClass<out SP>,
) {
    private var scope: ScopeTo = defaultScope
    private val engine: Engine = Engine

    fun withScope(scope: ScopeTo) = this.apply { this.scope = scope }

    @Composable
    abstract fun EntryPoint(state: MutableState<S>)

    @Composable
    abstract fun notReadyEntryPoint(state: MutableState<S>): @Composable () -> Unit //Probably not needed anymore as should be handled on the sub pub itself

    private val subPub: SP = engine.getSubPub(subPubClass, scope)

    private val publish = subPub::publish

    suspend fun publishOnce(event: E, reason: String) {
        publish(event, "", reason, {})
    }

    @Composable
    fun Show() {
        EntryPointWrapper(
            state = subPub.state,
            isReady = subPub.isReady,
        )
    }

    @Composable
    private fun EntryPointWrapper(
        state: MutableState<S>,
        isReady: MutableState<Boolean>,
    ) {
        if (isReady.value) {
            EntryPoint(state)
        } else {
            notReadyEntryPoint(state)()
        }
    }
}
