package ship.f.engine.shared.core

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    protected val coroutineScope: CoroutineScope = engine.engineScope
    val subPubScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    val linkedExpectations = mutableMapOf<Pair<EClass, String?>, LinkedExpectation>()
    val queuedEvents = mutableListOf<E>()

    abstract fun initState(): S
    abstract suspend fun onEvent()

    // TODO really is another awful method right here, that being said this single handedly enables me to handle sync work
    suspend fun executeEvent() {
        val event = getEvent(lastEvent::class)
        event?.let { lastEvent ->
            val expectationsToRemove: MutableList<Pair<EClass, String?>> = mutableListOf()
            linkedExpectations.forEach { linkedExpectation ->
                val blockedEvents = mutableSetOf<EClass>()
                val allList = linkedExpectation.value.all
                val updatedAllList = allList.map {
                    blockedEvents.add(it.first.expectedEvent)
                    if (it.first.expectedEvent == lastEvent::class && it.first.runOnCheck(lastEvent)) {
                        Pair(it.first, true)
                    } else {
                        it
                    }
                }
                if (updatedAllList.all { it.second }) {
                    updatedAllList.forEach { expectation ->
                        val event = getEvent(expectation.first.expectedEvent)!!
                        expectation.first.runOn(event)
                        queuedEvents.add(event)
                        blockedEvents.remove(expectation.first.expectedEvent)
                    }
                    expectationsToRemove.add(linkedExpectation.key)
                }

                val anyList = linkedExpectation.value.any
                for (any in anyList) {
                    if (any.expectedEvent == lastEvent::class && !blockedEvents.contains(any.expectedEvent) && any.runOnCheck(
                            lastEvent
                        )
                    ) {
                        any.runOn(lastEvent)
                        queuedEvents.add(lastEvent)
                        expectationsToRemove.add(linkedExpectation.key)
                        break
                    }
                }
            }
            expectationsToRemove.forEach { linkedExpectations.remove(it) } // TODO to avoid ConcurrentModificationException
        }
        event?.let { executeExpectation(it) }
        onEvent()
        if (!isReady.value) isReady.value = checkIfReady()
    }

    // Pretty clean and neat solution compared to before, could even wrap in a flow
    suspend fun executeExpectation(event: ScopedEvent) {
        expectationMap[event::class.simpleName]?.distinctBy { it.type }?.forEach { exp ->
            val expectedEvent = getEvent(exp.eventClass)!!
            val expectations = typeExpectationMap[exp.type]!!
            val success = if (exp.type is ExpType.Any) {
                expectations.any { it.on(expectedEvent) != null }
            } else {
                expectations.all { it.on(expectedEvent) != null }
            }
            if (success) typeExpectationMap.remove(exp.type)
            exp.flow?.emit(expectedEvent)
        }

    }

    val expectationMap = mutableMapOf<String, List<Exp<ScopedEvent>>>()
    val typeExpectationMap = mutableMapOf<ExpType, List<Exp<ScopedEvent>>>()

    @Serializable
    @SerialName("Exp")
    data class Exp<T : ScopedEvent>(
        val type: ExpType,
        val on: (T) -> T?,
        val eventClass: KClass<T>,
        val flow: FlowCollector<T>? = null
    )

    inline fun <reified T : ScopedEvent> expectation2(type: ExpType = ExpType.Any("Default"), noinline on: (T) -> T?) {
        val exp = Exp(type, on as (ScopedEvent) -> ScopedEvent?, T::class as KClass<ScopedEvent>)
        expectationMap.safeAdd(T::class.simpleName!!, exp)
        typeExpectationMap.safeAdd(type, exp)
    }

    suspend inline fun <reified T : ScopedEvent> expectation3(
        type: ExpType = ExpType.Any("Default"),
        vararg on: (T) -> T?
    ): T {
        // TODO handle vararg here then job done
        return flow {
            val exp = Exp(type, on as (ScopedEvent) -> ScopedEvent?, T::class as KClass<ScopedEvent>, this)
            expectationMap.safeAdd(T::class.simpleName!!, exp)
            typeExpectationMap.safeAdd(type, exp)
        }.first() as T
    }

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

    fun mock() {
        expectation2<AuthEvent> {
            if (it.userId == "mockId") {
                it
            } else {
                it
            }
        }
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

    // Pair<EClass, String?> is only used to stop multiple of the same item being added.
    // Ultimately, we will still iterate through the entire list

    fun Expectation.onceAny(vararg expectationBuilders: ExpectationBuilder<out ScopedEvent>) {
        if (linkedExpectations.contains(Pair(emittedEvent::class, key))) return
        val any = mutableListOf<ExpectationBuilder<out ScopedEvent>>()
        expectationBuilders.forEach {
            any.add(it)
        }
        val linkedExpectation = LinkedExpectation(
            any = any,
            all = listOf(), // TODO This is a bug as it means we can't have both any and all on the same event
        )
        linkedExpectations[Pair(emittedEvent::class, key)] = linkedExpectation
    }

    suspend fun Expectation.onceAnySync(vararg expectationBuilders: ExpectationBuilder<out ScopedEvent>): ScopedEvent {
        val any = mutableListOf<ExpectationBuilder<out ScopedEvent>>()
        expectationBuilders.forEach {
            any.add(it)
        }
        val linkedExpectation = LinkedExpectation(
            any = any,
            all = listOf(),
        )
        linkedExpectations[Pair(emittedEvent::class, key)] = linkedExpectation

        while (expectationBuilders.any { it.expectedEvent != queuedEvents.lastOrNull()?.let { e -> e::class } }) {
            delay(10)
        }
        return queuedEvents.last() as ScopedEvent
    }

    fun Expectation.onceAll(vararg expectationBuilders: ExpectationBuilder<out ScopedEvent>) {
        if (linkedExpectations.contains(Pair(emittedEvent::class, key))) return
        val all = mutableListOf<Pair<ExpectationBuilder<out ScopedEvent>, Boolean>>()
        expectationBuilders.forEach {
            all.add(Pair(it, false))
        }
        val linkedExpectation = LinkedExpectation(
            any = listOf(),
            all = all,
        )
        linkedExpectations[Pair(emittedEvent::class, key)] = linkedExpectation
    }

    fun getEvent(event: EClass): ScopedEvent? =
        getScopedEvent(event, scopes.lastOrNull { it.first.mode == ScopeMode.Instance }?.first ?: defaultScope)

    fun <E : ScopedEvent> getScopedEvents(event: KClass<out E>, scope: ScopeTo? = null): List<E> =
        scopes.filter { //Should probably be a set...
            (it.second?.contains(event) == true) && (scope == null || scope == it.first)
        }.mapNotNull {
            getScopedEvent(event, it.first)
        }

    private fun <E : ScopedEvent> getScopedEvent(event: KClass<out E>, scope: ScopeTo): E? =
        engine.getEvent(event, scope)

    private fun addScopeOrModify(
        scope: ScopeTo,
        events: List<EClass> = this.events.toList()
    ) { //I think I need to somehow think it through, I don't understand what null is? All events? nah that is stupid should just default to all events
        engine.addScopes(this, scope, events) //This is done to modify the event config at runtime
        scopes = scopes.map {
            if (it.first == scope) {
                it.copy(second = events)
            } else {
                it
            }
        }
    }

    fun <D : Dependency> getDependency( // TODO should make this inline
        dependency: KClass<out D>,
        scope: ScopeTo = defaultScope
    ): D {
        if (!isInitialized) {
            init()
        } // TODO this is currently done because of layout inspector destroying state randomly
        return engine.getDependency(dependency, scope)
    }

    private fun checkIfReady(runIfNotReady: () -> Unit = {}) = requiredEvents.none {
        getEvent(it) == null
    }.also {
        if (!it) {
            runIfNotReady()
        }
    } //Add a method that enables work to be done to mitigate this to get the subpub up and running

    inline fun <reified E1 : E> SubPub<S>.ge(nFunc: () -> Unit = {}, func: (E1) -> Unit) {
        getEvent(E1::class)?.also { func(it as E1) } ?: nFunc()
    }

    inline fun <reified E1 : E> SubPub<S>.ges(
        func: (List<E>) -> Unit,
        nFunc: () -> Unit = {},
        scopeTo: ScopeTo? = null
    ) {
        getScopedEvents(E1::class, scopeTo).also {
            if (it.isNotEmpty()) {
                func(it)
            } else {
                nFunc()
            }
        }
    }

    inline fun <reified E1 : E, reified E2 : E> SubPub<S>.ge2(nFunc: () -> Unit = {}, func: (E1?, E2?) -> Unit) {
        val e1 = getEvent(E1::class)
        val e2 = getEvent(E2::class)
        if (e1 != null || e2 != null) {
            func(e1 as? E1, e2 as? E2)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E> SubPub<S>.ges2(
        nFunc: () -> Unit = {},
        scopeTo: ScopeTo? = null,
        func: (List<E1>, List<E2>) -> Unit,
    ) {
        val e1 = getScopedEvents(E1::class, scopeTo)
        val e2 = getScopedEvents(E2::class, scopeTo)
        if (e1.isNotEmpty() || e2.isNotEmpty()) {
            func(e1, e2)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E> SubPub<S>.gae2(
        nFunc: () -> Unit = {},
        func: (E1, E2) -> Unit,
    ) {
        val e1 = getEvent(E1::class)
        val e2 = getEvent(E2::class)
        if (e1 is E1 && e2 is E2) {
            func(e1, e2)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E> SubPub<S>.gaes2(
        nFunc: () -> Unit = {},
        scopeTo: ScopeTo? = null,
        func: (List<E1>, List<E2>) -> Unit,
    ) {
        val e1 = getScopedEvents(E1::class, scopeTo)
        val e2 = getScopedEvents(E2::class, scopeTo)
        if (e1.isNotEmpty() && e2.isNotEmpty()) {
            func(e1, e2)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E, reified E3 : E> SubPub<S>.ge3(
        nFunc: () -> Unit = {},
        func: (E1?, E2?, E3?) -> Unit,
    ) {
        val e1 = getEvent(E1::class)
        val e2 = getEvent(E2::class)
        val e3 = getEvent(E2::class)
        if (e1 != null || e2 != null || e3 != null) {
            func(e1 as? E1, e2 as? E2, e3 as? E3)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E, reified E3 : E> SubPub<S>.ges3(
        nFunc: () -> Unit = {},
        scopeTo: ScopeTo? = null,
        func: (List<E1>, List<E2>, List<E3>) -> Unit,
    ) {
        val e1 = getScopedEvents(E1::class, scopeTo)
        val e2 = getScopedEvents(E2::class, scopeTo)
        val e3 = getScopedEvents(E3::class, scopeTo)
        if (e1.isNotEmpty() || e2.isNotEmpty() || e3.isNotEmpty()) {
            func(e1, e2, e3)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E, reified E3 : E> SubPub<S>.gea3(
        nFunc: () -> Unit = {},
        func: (E1, E2, E3) -> Unit,
    ) {
        val e1 = getEvent(E1::class)
        val e2 = getEvent(E2::class)
        val e3 = getEvent(E2::class)
        if (e1 is E1 && e2 is E2 && e3 is E3) {
            func(e1, e2, e3)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E, reified E2 : E, reified E3 : E> SubPub<S>.geas3(
        nFunc: () -> Unit = {},
        scopeTo: ScopeTo? = null,
        func: (List<E1>, List<E2>, List<E3>) -> Unit,
    ) {
        val e1 = getScopedEvents(E1::class, scopeTo)
        val e2 = getScopedEvents(E2::class, scopeTo)
        val e3 = getScopedEvents(E3::class, scopeTo)
        if (e1.isNotEmpty() && e2.isNotEmpty() && e3.isNotEmpty()) {
            func(e1, e2, e3)
        } else {
            nFunc()
        }
    }

    inline fun <reified E1 : E> le(func: (E1) -> Unit) {
        if (E1::class == AuthEvent::class && this::class.simpleName == "CommSubPub") println("Now calling le E for $lastEvent")
        val le = lastEvent
        if (le is E1) func(le)
    }

    inline fun <reified E1 : E, reified E2 : E> le2(func: (E1?, E2?) -> Unit) {
        when (val le = lastEvent) {
            is E1 -> func(le, null)
            is E2 -> func(null, le)
        }
    }

    inline fun <reified E1 : E, reified E2 : E, reified E3 : E> le3(func: (E1?, E2?, E3?) -> Unit) {
        when (val le = lastEvent) {
            is E1 -> func(le, null, null)
            is E2 -> func(null, le, null)
            is E3 -> func(null, null, le)
        }
    }
}
