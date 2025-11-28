package no.nav.emottak.state.util

// import no.nav.emottak.state.adapter.DeliveryState
// import no.nav.emottak.state.adapter.StatusInfo
// import no.nav.emottak.state.model.AppRecStatus
// import no.nav.emottak.state.model.ExternalDeliveryState
// import no.nav.emottak.state.model.ExternalStatus
// import no.nav.emottak.state.adapter.AppRecStatus as ExternalAppRecStatus

// fun StatusInfo.translate(): ExternalStatus =
//     ExternalStatus(
//         deliveryState = transportDeliveryState.translate(),
//         appRecStatus = appRecStatus?.translate()
//     )
//
// fun DeliveryState.translate(): ExternalDeliveryState = when (this) {
//     DeliveryState.UNCONFIRMED -> ExternalDeliveryState.UNCONFIRMED
//     DeliveryState.ACKNOWLEDGED -> ExternalDeliveryState.ACKNOWLEDGED
//     DeliveryState.REJECTED -> ExternalDeliveryState.REJECTED
//     DeliveryState.UNKNOWN -> ExternalDeliveryState.REJECTED
// }
//
// fun ExternalAppRecStatus.translate(): AppRecStatus =
//     when (this) {
//         ExternalAppRecStatus.OK -> AppRecStatus.OK
//         ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART -> AppRecStatus.OK_ERROR_IN_MESSAGE_PART
//         ExternalAppRecStatus.REJECTED -> AppRecStatus.REJECTED
//         ExternalAppRecStatus.UNKNOWN -> AppRecStatus.REJECTED
//     }
