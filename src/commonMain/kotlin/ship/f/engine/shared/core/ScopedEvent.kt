package ship.f.engine.shared.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    data class ViewRequest6(
        override val requesterId: String,
        val name: String,
        val scope: String? = null,
    ) : RequesterScopedEvent() {
        override fun getScopes2(): List<String> = listOf(defaultScope2)
    }

    abstract class DomainEvent6 : ScopedEvent() {
        abstract val viewRequest: ViewRequest6
        override fun getScopes2(): List<String> = listOf(defaultScope2)
    }

    data class StaticVoid6(
        override val viewRequest: ViewRequest6
    ) : DomainEvent6()

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
