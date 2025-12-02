package no.nav.emottak.state.integration.ediadapter

import io.ktor.client.*
import no.nav.emottak.state.model.DialogMessage
import no.nav.emottak.state.model.PostMessageResponse
import kotlin.uuid.Uuid

interface EdiAdapterClient {
    suspend fun postMessage(dialogMessage: DialogMessage): PostMessageResponse
}

class EdiAdapterClientImpl(
    private val ediAdapterUrl: String,
    clientProvider: () -> HttpClient
) : EdiAdapterClient {
    override suspend fun postMessage(dialogMessage: DialogMessage): PostMessageResponse {
        return PostMessageResponse(Uuid.random(), "")
    }
}

open class FakeEdiAdapterClient : EdiAdapterClient {
    override suspend fun postMessage(dialogMessage: DialogMessage): PostMessageResponse {
        return PostMessageResponse(Uuid.random(), "")
    }
}
