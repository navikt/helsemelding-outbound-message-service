package no.nav.helsemelding.outbound.model

enum class MessageTransport(val value: String) {
    EDI_1_0("EDI_1.0"),
    EDI_2_0("EDI_2.0");

    fun isEdi10(): Boolean = this == EDI_1_0

    fun isEdi20(): Boolean = this == EDI_2_0

    companion object {
        fun fromValue(value: String): MessageTransport =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown message transport: $value")
    }
}
