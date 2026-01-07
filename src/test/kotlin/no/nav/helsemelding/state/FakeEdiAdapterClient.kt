package no.nav.helsemelding.state

import no.nav.emottak.ediadapter.client.EdiAdapterClient
import no.nav.emottak.ediadapter.model.AppRecStatus
import no.nav.emottak.ediadapter.model.ApprecInfo
import no.nav.emottak.ediadapter.model.DeliveryState
import no.nav.emottak.ediadapter.model.ErrorMessage
import no.nav.emottak.ediadapter.model.GetBusinessDocumentResponse
import no.nav.emottak.ediadapter.model.GetMessagesRequest
import no.nav.emottak.ediadapter.model.Message
import no.nav.emottak.ediadapter.model.Metadata
import no.nav.emottak.ediadapter.model.PostAppRecRequest
import no.nav.emottak.ediadapter.model.PostMessageRequest
import no.nav.emottak.ediadapter.model.StatusInfo
import kotlin.uuid.Uuid

class FakeEdiAdapterClient : EdiAdapterClient {
    private val messageStatusById = mutableMapOf<Uuid, Pair<List<StatusInfo>?, ErrorMessage?>>()
    private val messageById = mutableMapOf<Uuid, Pair<Message?, ErrorMessage?>>()
    private val businessDocumentById = mutableMapOf<Uuid, Pair<GetBusinessDocumentResponse?, ErrorMessage?>>()
    private val postApprecById = mutableMapOf<Uuid, Pair<Metadata?, ErrorMessage?>>()
    private val markAsReadById = mutableMapOf<Uuid, Pair<Boolean?, ErrorMessage?>>()
    private val postMessages = ArrayDeque<Pair<Metadata?, ErrorMessage?>>()

    fun givenStatus(
        id: Uuid,
        deliveryState: DeliveryState,
        appRecStatus: AppRecStatus?
    ) {
        messageStatusById[id] = Pair(
            listOf(
                StatusInfo(
                    transportDeliveryState = deliveryState,
                    appRecStatus = appRecStatus,
                    receiverHerId = 1,
                    sent = true
                )
            ),
            null
        )
    }

    fun givenStatusError(
        id: Uuid,
        error: ErrorMessage
    ) {
        messageStatusById[id] = Pair(null, error)
    }

    fun givenMessage(
        id: Uuid,
        message: Message
    ) {
        messageById[id] = Pair(message, null)
    }

    fun givenPostMessage(
        message: Pair<Metadata?, ErrorMessage?>
    ) {
        postMessages.add(message)
    }

    override suspend fun getMessageStatus(
        id: Uuid
    ): Pair<List<StatusInfo>?, ErrorMessage?> =
        messageStatusById[id] ?: Pair(emptyList(), null)

    override suspend fun getMessage(
        id: Uuid
    ): Pair<Message?, ErrorMessage?> =
        messageById[id] ?: Pair(null, null)

    override suspend fun getBusinessDocument(
        id: Uuid
    ): Pair<GetBusinessDocumentResponse?, ErrorMessage?> =
        businessDocumentById[id] ?: Pair(null, null)

    override suspend fun postApprec(
        id: Uuid,
        apprecSenderHerId: Int,
        postAppRecRequest: PostAppRecRequest
    ): Pair<Metadata?, ErrorMessage?> =
        postApprecById[id] ?: Pair(null, null)

    override suspend fun markMessageAsRead(
        id: Uuid,
        herId: Int
    ): Pair<Boolean?, ErrorMessage?> =
        markAsReadById[id] ?: Pair(true, null)

    override suspend fun getApprecInfo(id: Uuid) = Pair(emptyList<ApprecInfo>(), null)

    override suspend fun getMessages(getMessagesRequest: GetMessagesRequest) = Pair(emptyList<Message>(), null)

    override suspend fun postMessage(postMessagesRequest: PostMessageRequest): Pair<Metadata?, ErrorMessage?> =
        postMessages.removeFirstOrNull() ?: Pair(null, null)

    override fun close() {}
}
