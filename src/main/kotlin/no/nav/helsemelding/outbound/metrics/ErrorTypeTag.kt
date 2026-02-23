package no.nav.helsemelding.outbound.metrics

enum class ErrorTypeTag(val value: String) {
    PAYLOAD_SIGNING_FAILED("payload_signing_failed"),
    SENDING_TO_EDI_ADAPTER_FAILED("sending_to_edi_adapter_failed"),
    STATE_INITIALIZATION_FAILED("state_initialization_failed"),
    INVALID_KAFKA_KEY("invalid_kafka_key")
}
