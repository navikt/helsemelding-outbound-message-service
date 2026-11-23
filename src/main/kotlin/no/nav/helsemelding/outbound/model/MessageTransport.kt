package no.nav.helsemelding.outbound.model

enum class MessageTransport(val value: String) {
    EDI_1_0("EDI_1.0"),
    EDI_2_0("EDI_2.0");

    companion object {
        fun fromEdiVersion(ediVersion: String?): MessageTransport? {
            val version = ediVersion ?: return EDI_2_0
            return entries.firstOrNull { it.value == version }
        }

        internal fun fromValue(value: String): MessageTransport =
            fromEdiVersion(value) ?: error("Unknown message transport: $value")
    }
}
