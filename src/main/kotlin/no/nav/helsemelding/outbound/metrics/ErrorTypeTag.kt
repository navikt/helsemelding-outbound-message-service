package no.nav.helsemelding.outbound.metrics

enum class ErrorTypeTag(val value: String) {
    INVALID_EDI_VERSION("invalid_edi_version"),
    METADATA_EXTRACTION_FAILED("metadata_extraction_failed"),
    SENDING_TO_EDI_ADAPTER_FAILED("sending_to_edi_adapter_failed"),
    SENDING_TO_EDI_FAILED("sending_to_edi_failed"),
    EXTERNAL_REFERENCE_VALIDATION_FAILED("external_reference_validation_failed"),
    STATE_LOOKUP_FAILED("state_lookup_failed"),
    STATE_INITIALIZATION_FAILED("state_initialization_failed")
}
