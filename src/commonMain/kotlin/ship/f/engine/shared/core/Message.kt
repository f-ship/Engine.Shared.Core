package ship.f.engine.shared.core

sealed class Message {
    abstract val requestId: String

    data class Data(
        override val requestId: String,
        val data: ScopedEvent,
        val binary: ByteArray? = null,
    ) : Message() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Data

            if (requestId != other.requestId) return false
            if (data != other.data) return false
            if (!binary.contentEquals(other.binary)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = requestId.hashCode()
            result = 31 * result + data.hashCode()
            result = 31 * result + (binary?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Ack(
        override val requestId: String,
    ) : Message()

    data class Init(
        override val requestId: String,
        val map: Map<String, String>,
    ) : Message()
}