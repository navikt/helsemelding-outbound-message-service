package no.nav.emottak.state.integration.ediadapter

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.emottak.state.model.ediadapter.ApprecInfo
import no.nav.emottak.state.model.ediadapter.ErrorMessage
import no.nav.emottak.state.model.ediadapter.GetBusinessDocumentResponse
import no.nav.emottak.state.model.ediadapter.GetMessagesRequest
import no.nav.emottak.state.model.ediadapter.Message
import no.nav.emottak.state.model.ediadapter.PostAppRecRequest
import no.nav.emottak.state.model.ediadapter.PostMessageRequest
import no.nav.emottak.state.model.ediadapter.StatusInfo
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class EdiAdapterClient(
    private val ediAdapterUrl: String,
    clientProvider: () -> HttpClient
) {
    val log = LoggerFactory.getLogger("no.nav.emottak.state.integration.ediadapter.EdiAdapterClient")

    private var httpClient = clientProvider.invoke()

    suspend fun getApprecInfo(id: Uuid): Pair<List<ApprecInfo>?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id/apprec"
        val response = httpClient.get(url) {
            contentType(ContentType.Application.Json)
        }

        return handleResponse(response)
    }

    suspend fun getMessages(getMessagesRequest: GetMessagesRequest): Pair<List<Message>?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages?${getMessagesRequest.toUrlParams()}"
        val response = httpClient.get(url) {
            contentType(ContentType.Application.Json)
        }

        return handleResponse(response)
    }

    suspend fun postMessage(postMessagesRequest: PostMessageRequest): Pair<String?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(postMessagesRequest)
        }

        return handleResponse(response)
    }

    suspend fun getMessage(id: Uuid): Pair<Message?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id"
        val response = httpClient.get(url) {
            contentType(ContentType.Application.Json)
        }

        return handleResponse(response)
    }

    suspend fun getBusinessDocument(id: Uuid): Pair<GetBusinessDocumentResponse?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id/document"
        val response = httpClient.get(url) {
            contentType(ContentType.Application.Json)
        }

        return handleResponse(response)
    }

    suspend fun getMessageStatus(id: Uuid): Pair<List<StatusInfo>?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id/status"
        val response = httpClient.get(url) {
            contentType(ContentType.Application.Json)
        }

        return handleResponse(response)
    }

    suspend fun postApprec(id: Uuid, apprecSenderHerId: Int, postAppRecRequest: PostAppRecRequest): Pair<String?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id/apprec/$apprecSenderHerId"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(postAppRecRequest)
        }

        return handleResponse(response)
    }

    suspend fun markMessageAsRead(id: Uuid, herId: Int): Pair<Boolean?, ErrorMessage?> {
        val url = "$ediAdapterUrl/api/v1/messages/$id/read/$herId"
        val response = httpClient.put(url) {
            contentType(ContentType.Application.Json)
        }

        return if (response.status == HttpStatusCode.NoContent) {
            Pair(true, null)
        } else {
            Pair(null, response.body())
        }
    }

    private suspend inline fun <reified T> handleResponse(httpResponse: HttpResponse): Pair<T?, ErrorMessage?> {
        return if (httpResponse.status == HttpStatusCode.OK || httpResponse.status == HttpStatusCode.Created) {
            Pair(httpResponse.body(), null)
        } else {
            Pair(null, httpResponse.body())
        }
    }
}
