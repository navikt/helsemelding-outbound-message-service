package no.nav.helsemelding.state

import arrow.core.Either
import no.nav.helsemelding.payloadsigning.client.PayloadSigningClient
import no.nav.helsemelding.payloadsigning.model.MessageSigningError
import no.nav.helsemelding.payloadsigning.model.PayloadRequest
import no.nav.helsemelding.payloadsigning.model.PayloadResponse

class FakePayloadSigningClient : PayloadSigningClient {

    private var response: Either<MessageSigningError, PayloadResponse> =
        Either.Right(PayloadResponse("data".toByteArray()))

    fun givenSignPayload(response: Either<MessageSigningError, PayloadResponse>) {
        this.response = response
    }

    override suspend fun signPayload(payloadRequest: PayloadRequest): Either<MessageSigningError, PayloadResponse> {
        return response
    }

    override fun close() = Unit
}
