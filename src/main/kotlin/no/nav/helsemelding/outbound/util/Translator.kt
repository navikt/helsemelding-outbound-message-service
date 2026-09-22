package no.nav.helsemelding.outbound.util

import no.nav.helsemelding.ediadapter.model.v3.AppRecError
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
import no.nav.helsemelding.outbound.model.AppRecErrorMessage
import no.nav.helsemelding.outbound.model.AppRecPayload
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus as ExternalAppRecStatus

fun StatusInfo.translate(): ExternalStatus =
    ExternalStatus(
        deliveryState = transportDeliveryState.translate(),
        appRecStatus = apprecInfo?.appRecStatus?.translate(),
        apprec = apprecInfo?.let { info ->
            AppRecPayload(
                receiverHerId = receiverHerId,
                status = info.appRecStatus?.name,
                errorList = info.appRecErrorList.orEmpty().map { it.toAppRecErrorMessage() }
            )
        }
    )

private fun DeliveryState.translate(): ExternalDeliveryState = when (this) {
    DeliveryState.UNCONFIRMED -> ExternalDeliveryState.UNCONFIRMED
    DeliveryState.ACKNOWLEDGED -> ExternalDeliveryState.ACKNOWLEDGED
    DeliveryState.REJECTED -> ExternalDeliveryState.REJECTED
    DeliveryState.ABANDONED -> ExternalDeliveryState.ABANDONED
}

private fun ExternalAppRecStatus.translate(): AppRecStatus =
    when (this) {
        ExternalAppRecStatus.OK -> AppRecStatus.OK
        ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART -> AppRecStatus.OK_ERROR_IN_MESSAGE_PART
        ExternalAppRecStatus.REJECTED -> AppRecStatus.REJECTED
    }

fun AppRecError.toAppRecErrorMessage(): AppRecErrorMessage = AppRecErrorMessage(
    code = errorCode,
    description = description,
    oid = oid,
    details = details
)
