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

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { cosmoRelay(Relay()) }.start(wait = true)
}

fun Application.cosmoRelay(relay: Relay = Relay()) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 128L * 1024
    }
    routing {
        get("/") { call.respondText("Cosmo server. Chat: WebSocket /chat.") }
        webSocket("/chat") { relay.handle(this) }
    }
}
