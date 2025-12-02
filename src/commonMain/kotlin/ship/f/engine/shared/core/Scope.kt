package ship.f.engine.shared.core

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

val defaultScope = ScopeTo.SingleScopeTo()
const val defaultScope2 = ""

fun ScopeTo.with(value: String = "") = when (this) {
    is ScopeTo.CompositeScopeTo -> copy(value = value)
    is ScopeTo.SingleScopeTo -> copy(value = value)
}

annotation class SingleScope(val scope: KClass<out SingleScopeType> = DefaultSingleScope::class)
annotation class CompositeScope(val scope: KClass<out CompositeScopeType> = DefaultCompositeScope::class)
@Serializable
abstract class ScopeType
@Serializable
abstract class SingleScopeType : ScopeType()
@Serializable
class DefaultSingleScope : SingleScopeType()
@Serializable
class IdSingleScope : SingleScopeType()
@Serializable
abstract class CompositeScopeType : ScopeType()
@Serializable
class DefaultCompositeScope : CompositeScopeType()
@Serializable
class IdCompositeScope : CompositeScopeType()
@Serializable
sealed class ScopeMode {
    @Serializable
    data object Static : ScopeMode()
    @Serializable
    data object Temp : ScopeMode()
    @Serializable
    data object Dynamic : ScopeMode()
    @Serializable
    data object Instance : ScopeMode()
}

@Serializable
sealed class ScopeTo {
    abstract val value: String
    abstract val scope: KClass<out ScopeType>
    abstract val mode: ScopeMode

    @Serializable
    data class SingleScopeTo(
        override val scope: KClass<out SingleScopeType> = DefaultSingleScope::class,
        override val value: String = "",
        override val mode: ScopeMode = ScopeMode.Dynamic,
    ) : ScopeTo()

    @Serializable
    data class CompositeScopeTo(
        override val scope: KClass<out CompositeScopeType> = DefaultCompositeScope::class,
        override val value: String = "",
        override val mode: ScopeMode = ScopeMode.Dynamic,
    ) : ScopeTo()
}

@Serializable
data class Expectation(
    val emittedEvent: ScopedEvent,
    val key: String? = null,
    val expectedEvent: KClass<out ScopedEvent>? = null,
)

@Suppress("UNCHECKED_CAST")
@Serializable
data class ExpectationBuilder<T : ScopedEvent>(
    val expectedEvent: KClass<T>,
    val on: T.() -> Unit,
    val onCheck: T.() -> Boolean = { true },
) {
    fun runOn(event: ScopedEvent) = on(event as T)
    fun runOnCheck(event: ScopedEvent) = onCheck(event as T)
}

@Serializable
data class LinkedExpectation(
    val any: List<ExpectationBuilder<out ScopedEvent>>,
    val all: List<Pair<ExpectationBuilder<out ScopedEvent>, Boolean>>,
)


/**
 * The first step is always to register the relevant scope.
 * Scopes can be temp, static, dynamic or instance.
 * Temp scopes register and deregister automatically when the event consumes it.
 * Static scopes register and prevent the subpub from getting updates for events outside the scope.
 * Dynamic scopes register and allow for updates.
 * Instance scopes register, allows for updates but also determines when a slice should reuse or create a new subpub.
 *
 * Traditional Command operations in an event driven manner
 * OR Use a temp Scope registered to two events
 * AND Sequential Use a temp registered to first event, within that event use a temp scope registered to the next event
 * AND Parallel Use multiple temp scopes registered to each of the events
 *
 * If a subpub is not ready it means that it can still be used for loading screens but not ready for a general display
 * Subpubs will try to get ready when found to not be ready
 * Slice and Subpubs have a many to one relationship, each subpub can be used in multiple places but a slice can only use one subpub
 * Slices are simple UI blocks that can reused and composed at will, Engine will intelligently ensure that slices either share or create subpubs based on their instance scopes
 * Slices are simple isolated UI blocks that
 */



@Serializable
data class MockEvent(
    @CompositeScope(IdCompositeScope::class) @SingleScope(IdSingleScope::class) val id: String,

    @CompositeScope(IdCompositeScope::class) @SingleScope(IdSingleScope::class) val id2: String,
) : ScopedEvent() {
    override fun getScopes(): List<ScopeTo> = listOf(
        ScopeTo.CompositeScopeTo(IdCompositeScope::class, id + id2),
        ScopeTo.SingleScopeTo(IdSingleScope::class, id),
        ScopeTo.SingleScopeTo(IdSingleScope::class, id2),
    )

    override fun getScopes2(): List<String> = listOf(id, id2)
}

/**
 * Use KSP to generate the necessary scope bindings
 * Use binding for the scope based on addScopeOrModify to determine what events should come back
 * By default all events are scoped to default, careful when overriding that behaviour by adding scopes
 * There is the normal event access that will only return a single event based on the instance scope with the default being default
 * There is scoped event access that will give a list of events based on what ever scope criteria the block was using
 * Careful when adding instance scopes from within a subpub, as this can be prone to bugs
 * Imagine a slice calls a subpub with a scope to Jenny, however that subpub a few seconds later adds a scope for John
 * Then a few seconds later a slice calls for a subpub with a scope for Jenny, it will have to create a new subpub as the existing subpub is scoped to Jenny + John
 * If this cycle repeats every few seconds then in a few minutes you will end up with loads of subpubs that are effectively the same.
 * In the future there will developer debug tools put in place prevent you from accidentally doing this.
 * Another issue with adding runtime instance scopes is that when using normal event access methods you could receive any one of the instance scoped events
 * In some sense the order of events will determine which one will be displayed
 * Scopes are very powerful but are also very dangerous
 * NOTICE I might just change this implementation to you can only have one instance scope after all, it's most definitely not a good idea to have more than one.
 * In documentation I can tell people to open up issues if that can't solve a problem without it
 * When a slice requires a subpub it can specify a instance scope with only a few select events that should override the default scope
 * In other words subpubs are somewhat interesting in how they are semi hive mind and semi independent
 * When a event's scope has been overridden from default to a scope, it must then be handled by the proper event access that will accept that scope
 * You can get very creative with how you handle scopes, but it's recommended to use them sparingly as it can greatly add complexity very quickly,
 * It's best to design your system in a way that requires few scopes and even less compound scopes
 * Expect can be used to ensure there is an event that is received in a timely manner, can be useful for network request etc,
 * It has a retry method which takes into account how many retries have gone, a util can be used for exponential backoff
 * Can I just replace le with ge and just make it run only if it's updated to make things more efficient and reactive
 * I can keep lastEvent for a helper function that can check if an event is a last event, but it's use should be discouraged
 * It's very declarative as time is not a factor,
 * Simply by reading the ges you should know what state you are in based on the last events that entered independent of order
 * This can of course be subverted but this is not encouraged
 * Most slice
 */
