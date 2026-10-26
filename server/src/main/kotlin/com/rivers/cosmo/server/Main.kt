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
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    printConnectionHelp(port)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { cosmoRelay(Relay()) }.start(wait = true)
}

/** Tells the developer what to type into the app's Settings → Cosmo server. */
private fun printConnectionHelp(port: Int) {
    val lan = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
    println(
        buildString {
            appendLine()
            appendLine("Starting the Cosmo server on port $port. In the app, Settings > Cosmo server:")
            appendLine("  Android emulator:     ws://10.0.2.2:$port/chat")
            appendLine("  Phone on USB:         ws://localhost:$port/chat   (after: adb reverse tcp:$port tcp:$port)")
            lan.forEach { appendLine("  Phone on same Wi-Fi:  ws://$it:$port/chat") }
            if (lan.isEmpty()) appendLine("  (No Wi-Fi/LAN address found; use the emulator or USB.)")
        }
    )
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
