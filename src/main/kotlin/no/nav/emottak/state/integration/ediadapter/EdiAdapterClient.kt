package no.nav.emottak.state.integration.ediadapter

import io.ktor.client.HttpClient
import no.nav.emottak.state.model.Message

interface EdiAdapterClient {
    suspend fun postMessage(message: Message): String
}

class EdiAdapterClientImpl(
    private val ediAdapterUrl: String,
    clientProvider: () -> HttpClient
) : EdiAdapterClient {
    override suspend fun postMessage(message: Message): String {
        return ""
    }
}

class FakeEdiAdapterClient : EdiAdapterClient {
    override suspend fun postMessage(message: Message): String {
        return ""
    }
}
