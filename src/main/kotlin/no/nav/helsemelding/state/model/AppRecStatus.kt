package no.nav.helsemelding.state.model

import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.AppRecStatus.REJECTED

enum class AppRecStatus {
    OK,
    OK_ERROR_IN_MESSAGE_PART,
    REJECTED
}

fun AppRecStatus?.isOk(): Boolean = this == OK

fun AppRecStatus?.isOkErrorInMessagePart(): Boolean = this == OK_ERROR_IN_MESSAGE_PART

fun AppRecStatus?.isRejected(): Boolean = this == REJECTED

fun AppRecStatus?.isNull(): Boolean = this == null

fun AppRecStatus?.isNotNull(): Boolean = this != null
