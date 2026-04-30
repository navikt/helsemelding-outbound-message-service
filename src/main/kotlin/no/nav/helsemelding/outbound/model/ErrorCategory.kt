package no.nav.helsemelding.outbound.model

enum class ErrorCategory {
    VALIDATION,
    DESERIALIZATION,
    BUSINESS_RULE,
    DOWNSTREAM,
    UNKNOWN
}
