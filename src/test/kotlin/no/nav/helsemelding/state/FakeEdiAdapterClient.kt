package no.nav.helsemelding.state

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.AppRecStatus
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.ediadapter.model.DeliveryState
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.GetBusinessDocumentResponse
import no.nav.helsemelding.ediadapter.model.GetMessagesRequest
import no.nav.helsemelding.ediadapter.model.Message
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.ediadapter.model.PostAppRecRequest
import no.nav.helsemelding.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.ediadapter.model.StatusInfo
import kotlin.uuid.Uuid

class FakeEdiAdapterClient : EdiAdapterClient {
    private val messageStatusById = mutableMapOf<Uuid, Either<ErrorMessage, List<StatusInfo>>>()
    private val messageById = mutableMapOf<Uuid, Either<ErrorMessage, Message>>()
    private val businessDocumentById = mutableMapOf<Uuid, Either<ErrorMessage, GetBusinessDocumentResponse>>()
    private val postApprecById = mutableMapOf<Uuid, Either<ErrorMessage, Metadata>>()
    private val markAsReadById = mutableMapOf<Uuid, Either<ErrorMessage, Boolean>>()
    private val apprecInfoById = mutableMapOf<Uuid, Either<ErrorMessage, List<ApprecInfo>>>()
    private val postMessages = ArrayDeque<Either<ErrorMessage, Metadata>>()

    val errorMessage404 = ErrorMessage(
        error = "Not Found",
        errorCode = 1000,
        requestId = Uuid.random().toString()
    )

    fun givenStatus(
        id: Uuid,
        deliveryState: DeliveryState,
        appRecStatus: AppRecStatus?
    ) {
        messageStatusById[id] = Right(
            listOf(
                StatusInfo(
                    transportDeliveryState = deliveryState,
                    appRecStatus = appRecStatus,
                    receiverHerId = 1,
                    sent = true
                )
            )
        )
    }

    fun givenStatusList(
        id: Uuid,
        list: List<StatusInfo>?
    ) {
        messageStatusById[id] = Right(list ?: emptyList())
    }

    fun givenStatusError(
        id: Uuid,
        error: ErrorMessage
    ) {
        messageStatusById[id] = Left(error)
    }

    fun givenMessage(
        id: Uuid,
        message: Message
    ) {
        messageById[id] = Right(message)
    }

    fun givenPostMessage(
        message: Either<ErrorMessage, Metadata>
    ) {
        postMessages.add(message)
    }

    fun givenApprecInfo(
        id: Uuid,
        info: List<ApprecInfo>
    ) {
        apprecInfoById[id] = Right(info)
    }

    fun givenApprecInfoSingle(
        id: Uuid,
        info: ApprecInfo
    ) {
        apprecInfoById[id] = Right(listOf(info))
    }

    fun givenApprecInfoEmpty(
        id: Uuid
    ) {
        apprecInfoById[id] = Right(emptyList())
    }

    fun givenApprecInfoError(
        id: Uuid,
        error: ErrorMessage
    ) {
        apprecInfoById[id] = Left(error)
    }

    override suspend fun getMessageStatus(
        id: Uuid
    ): Either<ErrorMessage, List<StatusInfo>> =
        messageStatusById[id] ?: Right(emptyList())

    override suspend fun getMessage(
        id: Uuid
    ): Either<ErrorMessage, Message> =
        messageById[id] ?: Left(errorMessage404)

    override suspend fun getBusinessDocument(
        id: Uuid
    ): Either<ErrorMessage, GetBusinessDocumentResponse> =
        businessDocumentById[id] ?: Left(errorMessage404)

    override suspend fun postApprec(
        id: Uuid,
        apprecSenderHerId: Int,
        postAppRecRequest: PostAppRecRequest
    ): Either<ErrorMessage, Metadata> =
        postApprecById[id] ?: Left(errorMessage404)

    override suspend fun markMessageAsRead(
        id: Uuid,
        herId: Int
    ): Either<ErrorMessage, Boolean> =
        markAsReadById[id] ?: Right(true)

    override suspend fun getApprecInfo(id: Uuid): Either<ErrorMessage, List<ApprecInfo>> =
        apprecInfoById[id] ?: Right(emptyList())

    override suspend fun getMessages(getMessagesRequest: GetMessagesRequest) = Right(emptyList<Message>())

    override suspend fun postMessage(postMessagesRequest: PostMessageRequest): Either<ErrorMessage, Metadata> =
        postMessages.removeFirstOrNull() ?: Left(errorMessage404)

    override fun close() {}
}
