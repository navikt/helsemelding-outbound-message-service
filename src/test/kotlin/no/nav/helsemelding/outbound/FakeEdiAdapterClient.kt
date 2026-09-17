package no.nav.helsemelding.outbound

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.common.GetBusinessDocumentResponse
import no.nav.helsemelding.ediadapter.model.v3.AppRecStatus
import no.nav.helsemelding.ediadapter.model.v3.ApprecInfo
import no.nav.helsemelding.ediadapter.model.v3.DeliveryState
import no.nav.helsemelding.ediadapter.model.v3.GetMessageResponse
import no.nav.helsemelding.ediadapter.model.v3.GetNotificationsResponse
import no.nav.helsemelding.ediadapter.model.v3.GetStatusResponse
import no.nav.helsemelding.ediadapter.model.v3.MarkAsDownloadedRequest
import no.nav.helsemelding.ediadapter.model.v3.Notification
import no.nav.helsemelding.ediadapter.model.v3.PingResponse
import no.nav.helsemelding.ediadapter.model.v3.PostAppRecRequest
import no.nav.helsemelding.ediadapter.model.v3.PostApprecResponse
import no.nav.helsemelding.ediadapter.model.v3.PostMessageRequest
import no.nav.helsemelding.ediadapter.model.v3.PostMessageResponse
import no.nav.helsemelding.ediadapter.model.v3.SetMshConfigurationsRequest
import no.nav.helsemelding.ediadapter.model.v3.StatusInfo
import kotlin.uuid.Uuid

class FakeEdiAdapterClient : EdiAdapterClient {
    private val statuses = mutableMapOf<Uuid, Either<EdiAdapterError, GetStatusResponse>>()
    private val postMessages = ArrayDeque<Either<EdiAdapterError, PostMessageResponse>>()
    val statusRequests = mutableListOf<Uuid>()
    val sentMessages = mutableListOf<PostMessageRequest>()
    val errorMessage404 = EdiAdapterError.Api(404)

    fun givenStatus(id: Uuid, deliveryState: DeliveryState, appRecStatus: AppRecStatus?) {
        givenStatusList(id, listOf(StatusInfo(8142520, deliveryState, true, appRecStatus?.let { ApprecInfo(it) })))
    }

    fun givenStatusList(id: Uuid, list: List<StatusInfo>?) {
        statuses[id] = Right(GetStatusResponse(list))
    }

    fun givenStatusError(id: Uuid, error: EdiAdapterError) {
        statuses[id] = Left(error)
    }

    fun givenPostMessage(message: Either<EdiAdapterError, PostMessageResponse>) {
        postMessages.add(message)
    }

    override suspend fun getMessageStatus(id: Uuid): Either<EdiAdapterError, GetStatusResponse> {
        statusRequests.add(id)
        return statuses[id] ?: Right(GetStatusResponse())
    }

    override suspend fun postMessage(request: PostMessageRequest): Either<EdiAdapterError, PostMessageResponse> {
        sentMessages.add(request)
        return postMessages.removeFirstOrNull() ?: Left(errorMessage404)
    }

    override suspend fun getMessage(id: Uuid): Either<EdiAdapterError, GetMessageResponse> = Left(errorMessage404)
    override suspend fun getBusinessDocument(id: Uuid): Either<EdiAdapterError, GetBusinessDocumentResponse> = Left(errorMessage404)
    override suspend fun postApprec(id: Uuid, request: PostAppRecRequest): Either<EdiAdapterError, PostApprecResponse> = Left(errorMessage404)
    override suspend fun markMessageAsDownloaded(id: Uuid, request: MarkAsDownloadedRequest): Either<EdiAdapterError, Unit> = Left(errorMessage404)
    override suspend fun getNotifications(herIds: List<Int>, offset: Long, notificationsToFetch: Int?): Either<EdiAdapterError, GetNotificationsResponse> = Right(GetNotificationsResponse(emptyList()))
    override fun streamNotifications(herIds: List<Int>, offset: Long?): Flow<Either<EdiAdapterError, Notification>> = emptyFlow()
    override suspend fun setMshConfigurations(request: SetMshConfigurationsRequest): Either<EdiAdapterError, Unit> = Left(errorMessage404)
    override suspend fun deleteMshConfigurations(herIds: List<Int>): Either<EdiAdapterError, Unit> = Left(errorMessage404)
    override suspend fun ping(): Either<EdiAdapterError, PingResponse> = Left(errorMessage404)
    override fun close() {}
}
