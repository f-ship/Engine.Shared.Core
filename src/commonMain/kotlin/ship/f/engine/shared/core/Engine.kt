package ship.f.engine.shared.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ship.f.engine.shared.ext.getAvailableCores
import ship.f.engine.shared.utils.serverdrivenui2.ext.sduiLog
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST") //Should probably create extensions for safe map access
@Serializable
object Engine {

    class Queue {

        private val lock = Mutex()
        private val data = mutableListOf<suspend () -> Unit>()
        private var currentSize = 0
        private val availableCores = getAvailableCores()
        private val poolMultiplier = 1
        var poolSize = maxOf(availableCores * poolMultiplier, 8)
        private val queueScope = CoroutineScope(Dispatchers.Default.limitedParallelism(poolSize))

        suspend fun add(v: suspend () -> Unit) = lock.withLock { data.add(v) }
        suspend fun pop(): (suspend () -> Unit)? = lock.withLock {
            if (data.isNotEmpty()) {
                currentSize++
                data.removeAt(0)
            } else null
        }

        suspend fun launchRunners() {
            sduiLog("Launching runners with current pool $currentSize with $availableCores and $poolMultiplier poolMultiplier", tag = "EngineX")
            while (currentSize < poolSize) {
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
    val queue = Queue()

    /**
     * Typically, it is inefficient to allow more coroutines than logical cores available at runtime
     * as this will require costly context switches if processing is CPU-bound.
     * Client operations will typically be a mix of cpu intensive work and io intensive work.
     * We could manage a separate pool for io operations, but using a simple multiplier will be okay for now
     */
    fun updatePoolMultiplier(multiplier: Int) {
        queue.poolSize = multiplier
    }

    fun <E : ScopedEvent> getEvent(
        event: KClass<E>,
        scope: ScopeTo
    ): E? {
        val a = config.eventMiddleWareConfig[event]!! // All events should be configured before engine is ran
        val b = a.eventConfigs[scope]
        val c = b?.event
        return c as? E
    }

    fun <E : ScopedEvent> getEventV2(
        event: KClass<E>,
        scope: List<String>,
    ): E? {
        val a = config.eventMiddleWareConfig[event]!!
        val b = a.eventConfigs2[scope.joinToString("/")]
        val c = b?.event
        return c as? E
    }

    fun <E : ScopedEvent> getEventsV2(
        event: KClass<E>,
        scope: List<String>,
    ): List<E> {
        val regex = scope.joinToString("/").replace("*", "[^/]+").toRegex()
        val a = config.eventMiddleWareConfig[event] ?: error("$event does not exist in event middle ware config")
        val b = a.eventConfigs2.filter { regex.matches(it.key) }.mapNotNull { it.value.event }
        return b as? List<E> ?: emptyList()
    }

    fun init(config: Config, initialEvents: List<E> = listOf()) {
        this.config = config
        config.subPubConfig.values
            .filter { it.isStartUp }
            .forEach {
                it.subPubs.values.forEach { sp ->
                    sp.tryInit()
                }
            }
        engineScope.launch {
            initialEvents.forEach { publish(it, "Initial Event", true) }
        }
    }

    fun runInterceptor(event: EClass, scope: ScopeTo): E? {
        return config.eventMiddleWareConfig[event]?.interceptor(this, scope)
    }

    suspend fun publish(event: E, reason: String, blocking: Boolean = false, send: Boolean = true) { // Do something with reason
        sduiLog("Publishing $event because $reason", tag = "Engine")
        val middleWares = config.eventMiddleWareConfig[event::class]!!.middleWareConfigs.map { it.listener }
        var computedEvent = event
        middleWares.forEach { middleWare ->
            computedEvent = middleWare(computedEvent) ?: computedEvent
        }

        // Scopes Version 2
        val scope = computedEvent.getScopes2().joinToString("/")
        val eventConfigs = config.eventMiddleWareConfig[computedEvent::class]!!.eventConfigs2

        eventConfigs[scope] = eventConfigs[scope]?.copy(event = computedEvent)
            ?: EventConfig(computedEvent, eventConfigs[defaultScope2]!!.listeners) // TODO a bit hacky but subpubs should automatically get all scopes

        eventConfigs[scope]!!.listeners.forEach {
            sduiLog("Now Sending ${computedEvent::class} to $scope", tag = "EngineX")
            if (send) {
                if (blocking) {
                    it.lastEvent = computedEvent
                    it.executeEvent(computedEvent)
                } else {
                    queue.add {
                        it.lastEvent = computedEvent
                        it.executeEvent(computedEvent)
                    }
                }
            }
        }

        queue.launchRunners()
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
        val b = a.dependencies[scope] ?: a.build(scope).apply {
            init(scope)
            val c = a.copy(dependencies = a.dependencies + mapOf(scope to this))
            val d = config.dependencyConfig + mapOf(dependency to c)
            config.copy(dependencyConfig = d)
        }
        return b as D
    }

    inline fun <reified D : Dependency> get(scope: ScopeTo = defaultScope): D = getDependency(D::class, scope)

    fun <D : Dependency> addDependency(
        // TODO this is not comprehensive yet, however scoping will be implemented fully as needed.
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
    val eventConfigs2: MutableMap<String, EventConfig> = mutableMapOf(),
    val interceptor: Engine.(ScopeTo) -> E? = { null }, // TODO implement in the same way as middleWareConfigs, always run on ge and le
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
