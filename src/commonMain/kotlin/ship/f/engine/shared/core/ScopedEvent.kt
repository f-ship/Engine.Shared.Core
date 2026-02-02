package ship.f.engine.shared.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ship.f.engine.shared.utils.serverdrivenui2.config.state.models.Id2

@Serializable
@SerialName("ScopedEvent")
abstract class ScopedEvent {
    @Serializable
    @SerialName("InitialEvent")
    data class InitialEvent(val name: String) : ScopedEvent() { //I may be making an error with this scope implementation as well... Actually, I might be okay
        override fun getScopes2(): List<String> = listOf(defaultScope2) // TODO no one should care about the name for now
    }

    @Serializable
    @SerialName("AuthEvent")
    data class AuthEvent(
        val userId: String? = null,
        val deviceId: String? = null,
        val fcmToken: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val eventResources: List<String> = listOf()
    ) : ScopedEvent() {
        override fun getScopes2(): List<String> = listOf(defaultScope2) // TODO no one should care about the name for now
    }

    @Serializable
    @SerialName("RequesterScopedEvent")
    abstract class RequesterScopedEvent : ScopedEvent()  {
        abstract val requesterId: String
    }

    @Serializable
    @SerialName("ViewRequest5")
    sealed class ViewRequest6 : RequesterScopedEvent() {
        abstract val id: Id2.MetaId2
        abstract val ctx: Map<String, String> // TODO to merge into something better at some point
        abstract val listCtx: Map<String, List<String>>
        override fun getScopes2(): List<String> = listOf(defaultScope2)
        abstract fun plus(key: String, value: String): ViewRequest6
        abstract fun plus(key: String, value: List<String>): ViewRequest6
    }

    @Serializable
    @SerialName("InitiatedViewRequest6")
    data class InitiatedViewRequest6(
        override val id: Id2.MetaId2,
        override val ctx: Map<String, String>,
        override val listCtx: Map<String, List<String>>,
        override val requesterId: String,
        val requestId: String,
        val domainId: String,
        val domainIds: List<String>,
    ) : ViewRequest6() {
        override fun plus(key: String, value: String) = copy(ctx = ctx + (key to value))
        override fun plus(key: String, value: List<String>) = copy(listCtx = listCtx + (key to value))
    }

    @Serializable
    @SerialName("UninitiatedViewRequest6")
    data class UninitiatedViewRequest6(
        override val id: Id2.MetaId2,
        override val ctx: Map<String, String> = mapOf(),
        override val listCtx: Map<String, List<String>> = mapOf(),
        override val requesterId: String,
    ) : ViewRequest6() {
        override fun plus(key: String, value: String) = copy(ctx = ctx + (key to value))
        override fun plus(key: String, value: List<String>) = copy(listCtx = listCtx + (key to value))
    }

    abstract class DomainEvent6 : ScopedEvent() {
        abstract val viewRequest: InitiatedViewRequest6
        abstract val domainId: String
        override fun getScopes2(): List<String> = listOf(defaultScope2)
    }

    abstract class FailedDomainEvent6 : ScopedEvent() {
        abstract val viewRequest: InitiatedViewRequest6
        abstract val domainId: String
        override fun getScopes2(): List<String> = listOf(defaultScope2)
    }

    data class StaticVoid6(
        override val viewRequest: InitiatedViewRequest6
    ) : DomainEvent6() {
        override val domainId: String = viewRequest.id.name + viewRequest.id.scope
    }

    @Serializable
    @SerialName("NetworkConnectivityEvent")
    data class NetworkConnectivityEvent(
        val isConnected: Boolean
    ) : ScopedEvent() {
        override fun getScopes2(): List<String> = listOf()
    }

    abstract fun getScopes2(): List<String>
}

@Serializable
@SerialName("Event")
abstract class Event : ScopedEvent() {
    override fun getScopes2(): List<String> = listOf(defaultScope2) // TODO no one should care about the name for now
}
