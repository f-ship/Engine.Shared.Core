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
            while (currentSize < poolSize) {
                val popped = pop()
                if (popped != null) {
                    queueScope.launch {
                        try {
                            popped()
                        } catch (e: Exception) {
                            sduiLog("Error in runner", e, e.printStackTrace(), tag = "EngineX > While > Popped > Launch > Error")
                        }
                        lock.withLock { currentSize-- }
                    }
                } else break
            }
        }
    }

    private var config = Config()

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

    fun <E : ScopedEvent> getEventV2(
        eventClass: KClass<E>,
        scope: List<String>,
    ): E? {
        val eventMiddleWareConfig = config.eventMiddleWareConfigs[eventClass]!!
        val eventConfig = eventMiddleWareConfig.eventConfigs2[scope.joinToString("/")]
        val event = eventConfig?.event
        return event as? E
    }

    fun <E : ScopedEvent> getEventsV2(
        event: KClass<E>,
        scope: List<String>,
    ): List<E> {
        val regex = scope.joinToString("/").replace("*", "[^/]+").toRegex()
        val eventMiddleWareConfig = config.eventMiddleWareConfigs[event] ?: error("$event does not exist in event middle ware config")
        val eventConfig = eventMiddleWareConfig.eventConfigs2.filter { regex.matches(it.key) }.mapNotNull { it.value.event }
        return eventConfig as? List<E> ?: emptyList()
    }

    fun init(
        config: Config,
        initialEvents: List<E> = listOf()
    ) {
        this.config = config
        config.subPubConfigs.values
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

    suspend fun publish(event: E, reason: String, blocking: Boolean = false, send: Boolean = true) { // Do something with reason
        engineScope.launch {
            sduiLog("Publishing ${event::class} because $reason", tag = "Engine")
        }
        val middleWares = config.eventMiddleWareConfigs[event::class]!!.middleWareConfigs.map { it.listener }
        var computedEvent = event
        middleWares.forEach { middleWare ->
            computedEvent = middleWare(computedEvent) ?: computedEvent
        }

        val scope = computedEvent.getScopes2().joinToString("/")
        val eventConfigs = config.eventMiddleWareConfigs[computedEvent::class]!!.eventConfigs2

        eventConfigs[scope] = eventConfigs[scope]?.copy(event = computedEvent)
            ?: EventConfig(computedEvent, eventConfigs[defaultScope2]!!.listeners) // TODO a bit hacky but subpubs should automatically get all scopes

        eventConfigs[scope]!!.listeners.forEach {
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

    fun <SP : SubPub<out State>> getSubPub(subPubClass: KClass<out SP>, scope: ScopeTo): SP {
        val subPubConfig = config.subPubConfigs[subPubClass]!!
        val subPub = (subPubConfig.subPubs[scope] ?: subPubConfig.build()).apply { tryInit() }
        return subPub as SP
    }

    fun <D : Dependency> getDependency(dependencyClass: KClass<out D>, scope: ScopeTo): D {
        val dependencyConfig = config.dependencyConfigs[dependencyClass]!!
        val dependency = dependencyConfig.dependencies[scope] ?: dependencyConfig.build(scope).apply {
            init(scope)
            val updatedDependencyConfig = dependencyConfig.copy(dependencies = dependencyConfig.dependencies + mapOf(scope to this))
            val updatedDependencyConfigs = config.dependencyConfigs + mapOf(dependencyClass to updatedDependencyConfig)
            config.copy(dependencyConfigs = updatedDependencyConfigs)
        }
        return dependency as D
    }

    inline fun <reified D : Dependency> get(scope: ScopeTo = defaultScope): D = getDependency(D::class, scope)

    fun <D : Dependency> addDependency(
        // TODO this is not comprehensive yet, however scoping will be implemented fully as needed.
        dependency: KClass<out D>,
        dependencyInstance: D,
    ) {
        config = config.copy(
            dependencyConfigs = config.dependencyConfigs + mapOf(
                dependency to DependencyConfig(
                    build = { dependencyInstance },
                )
            )
        )
    }
}

@Serializable
data class Config(
    val subPubConfigs: Map<SPClass, SubPubConfig> = mapOf(),
    val eventMiddleWareConfigs: Map<EClass, EventMiddleWareConfig> = mapOf(),
    val dependencyConfigs: Map<DClass, DependencyConfig> = mapOf(), // TODO Should have a way to make arbitrary independence that don't implement Dependency
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
