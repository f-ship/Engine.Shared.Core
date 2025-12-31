package ship.f.engine.shared.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ToastEvent")
data class ToastEvent(
    val message: String,
    val durationMs: Long? = 10000, // null for indefinite
    val actionText: String? = null,
    val actionEvent: ScopedEvent? = null,
    val toastType: ToastType = ToastType.Warning,
) : ScopedEvent() {
    override fun getScopes(): List<ScopeTo> = listOf(ScopeTo.SingleScopeTo(DefaultSingleScope::class, "UI"))
    override fun getScopes2(): List<String> = listOf()

    @Serializable
    @SerialName("ToastType")
    sealed class ToastType {
        @Serializable
        @SerialName("Success")
        object Success : ToastType()
        @Serializable
        @SerialName("Warning")
        object Warning : ToastType()
        @Serializable
        @SerialName("Error")
        object Error : ToastType()
    }
}
