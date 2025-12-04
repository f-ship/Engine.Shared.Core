package ship.f.engine.shared.core

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ship.f.engine.shared.core.Engine.getEventsV2
import ship.f.engine.shared.core.ScopeTo.SingleScopeTo
import ship.f.engine.shared.core.ScopedEvent.AuthEvent
import kotlin.reflect.KClass

@Serializable
abstract class SubPub<S : State>(
    private val requiredEvents: Set<EClass> = setOf(),
    val nonRequiredEvents: Set<EClass> = setOf()
) {
    private val uid = "${this::class.simpleName}:${Clock.System.now()}"

    private var scopes: List<Pair<ScopeTo, List<EClass>?>> = listOf(Pair(SingleScopeTo(), null)) // Change to
    val engine: Engine = Engine
    val events = requiredEvents + nonRequiredEvents
    var lastEvent: E = ScopedEvent.InitialEvent(uid)
    lateinit var state: MutableState<S>
    val isReady: MutableState<Boolean> = mutableStateOf(false)
    private val idempotentMap: MutableMap<EClass, MutableSet<String>> = mutableMapOf()
    val coroutineScope: CoroutineScope = engine.engineScope
    val subPubScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    val linkedExpectations = mutableMapOf<Pair<EClass, String?>, LinkedExpectation>()
    val queuedEvents = mutableListOf<E>()

    abstract fun initState(): S
    abstract suspend fun ScopedEvent.onEvent()

    // TODO really is another awful method right here, that being said this single handedly enables me to handle sync work
    suspend fun executeEvent(computedEvent: ScopedEvent) {
        val event = lastEvent // TODO I think I was screwing up here big time before
        event.let { lastEvent ->
            val expectationsToRemove: MutableList<Pair<EClass, String?>> = mutableListOf()
            linkedExpectations.forEach { linkedExpectation ->
                var currentExpectation = linkedExpectation.value

                /**
                 * Crude way of ensure all events are true below
                 */
                val allList = linkedExpectation.value.all
                val updatedAllList = allList.map {
                    if (it.first.expectedEvent == lastEvent::class && it.first.runOnCheck(lastEvent)) {
                        Pair(it.first, true)
                    } else {
                        it
                    }
                }

                /**
                 * Block to execute expectations for all
                 */
                if (updatedAllList.all { it.second }) {
                    updatedAllList.forEach { expectation ->
                        val event = getEvent(expectation.first.expectedEvent)!!
                        expectation.first.runOn(event)
                        queuedEvents.add(event)
                    }
                    currentExpectation = currentExpectation.copy(all = emptyList())
                }

                /**
                 * Block to execute expectations for any
                 */
                val anyList = linkedExpectation.value.any
                for (any in anyList) {
                    if (any.expectedEvent == lastEvent::class && any.runOnCheck(
                            lastEvent
                        )
                    ) {
                        any.runOn(lastEvent)
                        queuedEvents.add(lastEvent)
                        currentExpectation = currentExpectation.copy(any = emptyList())
                        break
                    }
                }

                /**
                 * Block to update linkedExpectations
                 */
                if (currentExpectation.all.isEmpty() && currentExpectation.any.isEmpty()) {
                    expectationsToRemove.add(linkedExpectation.key)
                } else {
                    linkedExpectations[linkedExpectation.key] = currentExpectation
                }
            }
            expectationsToRemove.forEach { linkedExpectations.remove(it) } // TODO to avoid ConcurrentModificationException
        }
        computedEvent.apply { onEvent() }

        if (!isReady.value) isReady.value = checkIfReady()
    }

    @Serializable
    @SerialName("Exp")
    data class Exp<T : ScopedEvent>(
        val type: ExpType,
        val on: (T) -> T?,
        val eventClass: KClass<T>,
        val flow: FlowCollector<T>? = null
    )

    fun <K, V> MutableMap<K, List<V>>.safeAdd(key: K, value: V) {
        if (this[key] == null) listOf(value)
        else this[key] = this[key]!! + listOf(value)
    }

    @Serializable
    @SerialName("ExpType")
    sealed class ExpType {
        abstract val key: String

        @Serializable
        @SerialName("Any")
        data class Any(override val key: String) : ExpType()

        @Serializable
        @SerialName("All")
        data class All(override val key: String) : ExpType()
    }

    private var isInitialized = false //Can probably remove

    /**
     * Cannot perform publications from this method as SubPub is not currently set up.
     * In the next version of engine it will be better to add all these events to a cache and then trigger them after the subpub is ready.
     * This will remove the need to have an init and postInit method which can be confusing to navigate.
     */
    open fun init() {

    }

    // Can safely run as much as needed as idempotent
    open fun postInit() {

    }

    fun tryInit() {
        if (!isInitialized) {
            init()
            println("SubPub $uid is initialized")
            state = mutableStateOf(initState())
            isInitialized = true
        }
        postInit()
        isReady.value = checkIfReady()
    }

    suspend fun publish(
        event: E,
        key: String? = null,
        reason: String = "Please Give a Reason for readability",
        expectationBuilder: Expectation.() -> Unit = { }
    ): Expectation {
        val expectation = Expectation(emittedEvent = event, key = key, expectedEvent = null)
        idempotentMap[event::class]?.contains(key) ?: let {
            idempotentMap.smartAdd(event::class, key)
            expectationBuilder(expectation)
            engine.publish(event, reason)
        }
        return expectation
    }

    // TODO like publish but does not call handlers, should be used for intermediary values but publish should always be called afterwards
    suspend fun store(
        events: List<E>,
        reason: String = "Please Give a Reason for readability"
    ) {
        events.forEach { engine.publish(it, reason, blocking = false, send = false) }
    }

    // Pair<EClass, String?> is only used to stop multiple of the same item being added.
    // Ultimately, we will still iterate through the entire list

    fun Expectation.onceAny(vararg expectationBuilders: ExpectationBuilder<out ScopedEvent>) {
        val currentExpectation = linkedExpectations[Pair(emittedEvent::class, key)]
        val any = mutableListOf<ExpectationBuilder<out ScopedEvent>>()

        expectationBuilders.forEach {
            any.add(it)
        }

        val linkedExpectation = LinkedExpectation(
            any = currentExpectation?.any.orEmpty() + any,
            all = currentExpectation?.all
                ?: listOf(), // TODO This is a bug as it means we can't have both any and all on the same event
        )

        linkedExpectations[Pair(emittedEvent::class, key)] = linkedExpectation
    }

    fun Expectation.onceAll(vararg expectationBuilders: ExpectationBuilder<out ScopedEvent>) {
        val currentExpectation = linkedExpectations[Pair(emittedEvent::class, key)]
        val all = mutableListOf<Pair<ExpectationBuilder<out ScopedEvent>, Boolean>>()

        expectationBuilders.forEach {
            all.add(Pair(it, false))
        }

        val linkedExpectation = LinkedExpectation(
            any = currentExpectation?.any ?: listOf(),
            all = currentExpectation?.all.orEmpty() + all,
        )

        linkedExpectations[Pair(emittedEvent::class, key)] = linkedExpectation
    }

    fun getEvent(event: EClass): ScopedEvent? = getEventsV2(event, listOf(defaultScope2)).firstOrNull()

    fun <E : ScopedEvent> getScopedEvents(event: KClass<out E>, scope: List<String> = listOf(defaultScope2)): List<E> =
        getEventsV2(event, scope)

    fun <D : Dependency> getDependency( // TODO should make this inline
        dependency: KClass<out D>,
        scope: ScopeTo = defaultScope
    ): D {
        if (!isInitialized) {
            init()
        } // TODO this is currently done because of layout inspector destroying state randomly
        return engine.getDependency(dependency, scope)
    }

    inline fun <reified D : Dependency> getDependency(
        scope: ScopeTo = defaultScope
    ): D = getDependency(D::class, scope)

    private fun checkIfReady(runIfNotReady: () -> Unit = {}) = requiredEvents.none {
        getOrRun(klass = it).firstOrNull() == null
    }.also {
        if (!it) {
            runIfNotReady()
        }
    } //Add a method that enables work to be done to mitigate this to get the subpub up and running

    inline fun <reified E1 : E> SubPub<S>.runOrRun(nFunc: () -> Unit = {}, func: (E1) -> Unit) {
        getEvent(E1::class)?.also { func(it as E1) } ?: nFunc()
    }

    fun <E1 : E> SubPub<S>.getOrRun(
        klass: KClass<E1>,
        scopes: List<String> = listOf(defaultScope2),
        nFunc: () -> Unit = {},
    ) = getScopedEvents(klass, scopes).also { if (it.isEmpty()) nFunc() }

    inline fun <reified E1 : E> SubPub<S>.getOrRun(
        scopes: List<String> = listOf(defaultScope2),
        nFunc: () -> Unit = {},
    ) = getScopedEvents(E1::class, scopes).also { if (it.isEmpty()) nFunc() }

    inline fun <reified E1 : E> SubPub<S>.getOrReturn(
        scopes: List<String> = listOf(defaultScope2),
        nFunc: () -> E1 = { error("Not implemented the nFunc and no events found") },
    ) = getScopedEvents(E1::class, scopes).let { it.ifEmpty { listOf(nFunc()) } }



    inline fun <reified E1 : E> SubPub<S>.getOrComputeScopedEvent(
        scopes: List<String> = listOf(defaultScope2),
        nFunc: () -> E1 = { error("Not implemented the nFunc and no events found") },
    ) = getScopedEvents(E1::class, scopes).let { scopedEvents ->
        (scopedEvents.ifEmpty { emptyList() }.firstOrNull() ?: nFunc()).also {
            coroutineScope.launch { publish(it) }
        }
    }

    inline fun <reified E1 : E> ScopedEvent.le(func: (E1) -> Unit) {
        if (E1::class == AuthEvent::class && this::class.simpleName == "CommSubPub") println("Now calling le E for $lastEvent")
        val le = this
        if (le is E1) func(le)
    }
}
