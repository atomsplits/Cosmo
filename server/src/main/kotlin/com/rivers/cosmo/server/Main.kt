package com.rivers.cosmo.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val relay = Relay()
    val sumsub = SumsubConfig.fromEnvironment()
    if (sumsub == null) {
        LoggerFactory.getLogger("Main").info("Identity verification is off: set SUMSUB_APP_TOKEN and SUMSUB_SECRET_KEY to enable it.")
    }
    val kyc = Kyc(relay.registry, sumsub?.let(::HttpSumsubApi), sumsub?.webhookSecret)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { cosmoRelay(relay, kyc) }.start(wait = true)
}

fun Application.cosmoRelay(relay: Relay = Relay(), kyc: Kyc = Kyc(relay.registry, api = null, webhookSecret = null)) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 128L * 1024
    }
    routing {
        get("/") { call.respondText("Cosmo server. Chat: WebSocket /chat. Identity verification: /kyc/*.") }
        webSocket("/chat") { relay.handle(this) }
        kyc.routes(this)
    }
}
