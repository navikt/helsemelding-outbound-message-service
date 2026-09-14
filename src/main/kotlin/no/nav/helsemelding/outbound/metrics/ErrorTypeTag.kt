package no.nav.helsemelding.outbound.metrics

enum class ErrorTypeTag(val value: String) {
    SENDING_TO_EDI_ADAPTER_FAILED("sending_to_edi_adapter_failed"),
    STATE_INITIALIZATION_FAILED("state_initialization_failed")
}
