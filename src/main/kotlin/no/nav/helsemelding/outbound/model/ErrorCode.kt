package no.nav.helsemelding.outbound.model

enum class ErrorCode {
    INVALID_KAFKA_KEY,
    INVALID_XML,
    MISSING_REQUIRED_FIELD,
    UNEXPECTED_ERROR
}
