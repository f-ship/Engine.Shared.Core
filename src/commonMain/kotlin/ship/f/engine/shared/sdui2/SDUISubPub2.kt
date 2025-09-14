package ship.f.engine.shared.sdui2

import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Resource
import ship.f.engine.shared.core.ExpectationBuilder
import ship.f.engine.shared.core.State
import ship.f.engine.shared.core.SubPub
import ship.f.engine.shared.sdui2.SDUISubPub2.SDUIState2
import ship.f.engine.shared.utils.serverdrivenui2.config.meta.models.NavigationConfig2

class SDUISubPub2 : SubPub<SDUIState2>(
    requiredEvents = setOf(SDUIConfig2::class),
    nonRequiredEvents = setOf(SDUIInput2::class),
) {
    data class SDUIState2(
        val projectName: String? = null,
        val resources: Map<String, Resource> = mapOf(),
    ) : State()

    override fun initState() = SDUIState2()
    override fun postInit() {
        val client = getDependency(CommonClientDependency2::class).client
        client.emitSideEffect = { populatedSideEffect ->
            coroutineScope.launch { // TODO check to see if this is really necessary
                publish(SDUISideEffect2(populatedSideEffect)) {
                    onceAny(
                        ExpectationBuilder(
                            expectedEvent = SDUIInput2::class,
                            onCheck = {
                                populatedSideEffect.onExpected.contains(id) ||
                                        states.any { state -> populatedSideEffect.onExpected.contains(state.id) } ||
                                        metas.any { meta -> populatedSideEffect.onExpected.contains(meta.metaId) }
                            },
                            on = {
                                /* TODO this is not where the main event is handled, but where we can notify sdui a call is complete */
                                val id = populatedSideEffect.onExpected[id]?.let { id }
                                    ?: states.firstOrNull { state -> populatedSideEffect.onExpected.contains(state.id) }?.id
                                    ?: metas.first { meta -> populatedSideEffect.onExpected.contains(meta.metaId) }.metaId

                                populatedSideEffect.onExpected[id]?.forEach {
                                    it.second.run(
                                        state = client.get(it.first),
                                        client = client,
                                    )
                                }
                            }
                        )
                    )
                }
            }
        }
    }

    override suspend fun onEvent() {
        ge<SDUIConfig2> {
            state.value = state.value.copy(
                projectName = it.projectName,
                resources = it.resources
            )
        }

        le<SDUIInput2> {
            getDependency(CommonClientDependency2::class).client.run {
                it.states.forEach { state -> update(state) }
                it.metas.forEach { meta -> update(meta) }
                it.metas.filterIsInstance<NavigationConfig2>().forEach { nav -> navigate(nav) }
            }
        }
    }
}