package ship.f.engine.shared.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ship.f.engine.shared.ext.getAvailableCores
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST") //Should probably create extensions for safe map access
@Serializable
object Engine {

    class Queue {

        private val lock = Mutex()
        private val data = mutableListOf<suspend () -> Unit>()
        private var currentSize = 0
        private val poolSize = getAvailableCores()
        private val queueScope = CoroutineScope(Dispatchers.Default)

        suspend fun add(v: suspend () -> Unit) = lock.withLock { data.add(v) }
        suspend fun pop() : (suspend () -> Unit)? = lock.withLock {
            if (data.isNotEmpty()) {
                currentSize++
                data.removeAt(0)
            } else null
        }

        suspend fun launchRunners() {
            while(currentSize < poolSize) {
                val popped = pop()
                if (popped != null) {
                    queueScope.launch {
                        popped()
                        lock.withLock { currentSize-- }
                    }
                } else break
            }
        }
    }

    private var config = Config()
    var hasBeenInit = false

    val engineScope = CoroutineScope(Dispatchers.Default)
    fun <E : ScopedEvent> getEvent(
        event: KClass<E>,
        scope: ScopeTo
    ): E? {
        val a = config.eventMiddleWareConfig[event]!! // All events should be configured before engine is ran
        val b = a.eventConfigs[scope]
        val c = b?.event
        return c as? E
    }

    fun init(config: Config, initialEvents: List<E> = listOf()) {
        this.config = config
        config.subPubConfig.values
            .filter { it.isStartUp }
            .forEach {
                it.subPubs.values.forEach {
                    sp -> sp.tryInit()
                }
            }
        engineScope.launch {
            initialEvents.forEach { publish(it, "Initial Event") }
        }
    }

    suspend fun publish(event: E, reason: String) { // Do something with reason
        val middleWares = config.eventMiddleWareConfig[event::class]!!.middleWareConfigs.map { it.listener }
        var computedEvent = event
        middleWares.forEach { middleWare ->
            computedEvent = middleWare(computedEvent) ?: computedEvent
        }

        (computedEvent.getScopes() + listOf(defaultScope)).forEach { scope ->
            val eventConfigs = config.eventMiddleWareConfig[computedEvent::class]!!.eventConfigs
            eventConfigs[scope] = eventConfigs[scope]?.copy(event = computedEvent) ?: EventConfig(computedEvent, setOf())
            eventConfigs[scope]!!.listeners.forEach {
                if (computedEvent::class == ScopedEvent.AuthEvent::class) println("Auth Event being sent to $it")
                it.lastEvent = computedEvent
                it.executeEvent()
            }
        }
    }

    fun addScopes(subPub: SP, scope: ScopeTo, events: List<EClass>) {
        events.forEach {
            val eventConfigs = config.eventMiddleWareConfig[it]!!.eventConfigs
            val listeners = eventConfigs[scope]?.listeners ?: setOf()
            eventConfigs[scope] =
                eventConfigs[scope]?.copy(listeners = listeners + listOf(subPub)) ?: EventConfig(
                    event = null,
                    listeners = listeners
                )
        }
    }

    fun <SP : SubPub<out State>> getSubPub(subPubClass: KClass<out SP>, scope: ScopeTo): SP {
        val a = config.subPubConfig[subPubClass]!!
        val b = (a.subPubs[scope] ?: a.build()).apply { tryInit() }
        addScopes(b, scope, b.events.toList())
        return b as SP
    }

    fun <D : Dependency> getDependency(dependency: KClass<out D>, scope: ScopeTo): D {
        val a = config.dependencyConfig[dependency]!!
        val b = (a.dependencies[scope] ?: a.build(scope)).apply { init(scope) }
        return b as D
    }

    fun <D : Dependency> addDependency( // TODO this is not comprehensive yet, however scoping will be implemented fully as needed.
        dependency: KClass<out D>,
        dependencyInstance: D,
    ) {
        config = config.copy(
            dependencyConfig = config.dependencyConfig + mapOf(
                dependency to DependencyConfig(
                    build = { dependencyInstance },
                )
            )
        )
    }
}

@Serializable
data class Config(
    val subPubConfig: Map<SPClass, SubPubConfig> = mapOf(),
    val eventMiddleWareConfig: Map<EClass, EventMiddleWareConfig> = mapOf(),
    val dependencyConfig: Map<DClass, DependencyConfig> = mapOf(), // TODO Should have a way to make arbitrary independence that don't implement Dependency
)

@Serializable
data class SubPubConfig(
    val isStartUp: Boolean = false,
    val build: () -> SP,
    val subPubs: Map<ScopeTo, SP> = mapOf(), // Do I really need this one here
)

@Serializable
data class EventMiddleWareConfig(
    val eventConfigs: MutableMap<ScopeTo, EventConfig> = mutableMapOf(),
    val middleWareConfigs: List<MiddleWareConfig> = listOf(), // For now Assume all middlewares are created at init
)

@Serializable
data class EventConfig(
    val event: E?, //Can be null as someone can listen to an event that has not been published yet
    val listeners: Set<SP> = setOf(),
)

@Serializable
data class MiddleWareConfig(
    val build: () -> MW,
    val listener: MW,
)

@Serializable
data class DependencyConfig(
    val build: (ScopeTo) -> D,
    val dependencies: Map<ScopeTo, D> = mapOf(), // TODO this is currently broken, need a list of providers not explicit dependencies.
)

typealias SP = SubPub<out State>
typealias SPClass = KClass<out SP>

typealias MW = MiddleWare<out ScopedEvent>

typealias E = ScopedEvent
typealias EClass = KClass<out ScopedEvent>

typealias D = Dependency
typealias DClass = KClass<out D>

typealias Publish = (E, String?, String) -> Unit
