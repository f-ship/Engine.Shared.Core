package ship.f.engine.shared.sdui2

import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.Resource
import ship.f.engine.shared.core.Event
import ship.f.engine.shared.utils.serverdrivenui2.config.meta.models.Meta2
import ship.f.engine.shared.utils.serverdrivenui2.config.meta.models.PopulatedSideEffectMeta2
import ship.f.engine.shared.utils.serverdrivenui2.config.state.models.Id2.MetaId2
import ship.f.engine.shared.utils.serverdrivenui2.config.state.models.Id2.MetaId2.Companion.none
import ship.f.engine.shared.utils.serverdrivenui2.config.state.models.Id2.StateId2
import ship.f.engine.shared.utils.serverdrivenui2.state.State2

// Events Accepted by SDUISubPub2
@Serializable
data class SDUIInput2(
    val id: MetaId2 = none,
    val states: List<State2> = listOf(),
    val metas: List<Meta2> = listOf(),
) : Event()

@Serializable
data class SDUIReactiveInput2(
    val id: MetaId2 = none,
    val states: List<StateId2> = listOf(),
    val metas: List<Meta2> = listOf(),
) : Event()

data class SDUIConfig2(
    val projectName: String,
    val resources: Map<String, Resource> = mapOf(),
) : Event()

// Events Emitted by SDUISubPub2
@Serializable
data class SDUISideEffect2(
    val sideEffect: PopulatedSideEffectMeta2
) : Event()