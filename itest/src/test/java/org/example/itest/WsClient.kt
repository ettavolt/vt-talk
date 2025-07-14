package org.example.itest

import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainer
import org.eclipse.jetty.websocket.core.WebSocketComponents
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.AutoCloseable
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
class WsClient(
    @Autowired vtEnabler: VtEnabler,
    @Autowired restTemplate: TestRestTemplate
) {
    private val stompClient: WebSocketStompClient
    private val container: JakartaWebSocketClientContainer
    private val wsUri: URI

    init {
        val httpClient = vtEnabler.httpClient
        val executor = httpClient.executor
        container = JakartaWebSocketClientContainer(
            WebSocketComponents(
                null,
                null,
                null,
                null,
                null,
                executor
            )
        ) { components: WebSocketComponents? -> WebSocketCoreClient(httpClient, components) }
        val socketClient = StandardWebSocketClient(container)
        stompClient = WebSocketStompClient(socketClient)
        stompClient.messageConverter = StringMessageConverter()
        SimpleAsyncTaskScheduler()
            .apply {
                threadNamePrefix = "Stomp-"
                setVirtualThreads(true)
            }
            .let(stompClient::setTaskScheduler)
        wsUri = URI.create(restTemplate.getRootUri().replace(Regex("^http"), "ws") + "/ws")
    }

    fun subscribeTo(topic: String): MessageDrainer {
        if (!container.isStarted) container.start()

        val receiver = Receiver()
        val session = stompClient
            .connectAsync(wsUri, WebSocketHttpHeaders(), StompHeaders(), receiver)
            .get(10, TimeUnit.SECONDS)
        //We collect to the session handler.
        session.subscribe(topic, receiver)
        return receiver
    }

}

interface MessageDrainer : AutoCloseable {
    fun drainPayloads(): MutableList<Any?>
}

private class Receiver : StompSessionHandlerAdapter(), MessageDrainer {
    private val payloads = AtomicReference<MutableList<Any?>>(ArrayList<Any?>())
    private var session: StompSession? = null

    override fun handleFrame(headers: StompHeaders, payload: Any?) {
        //Strictly speaking, we might still add to here after it has been drained.
        payloads.get().add(payload ?: headers.getFirst("message"))
    }

    override fun afterConnected(session: StompSession, connectedHeaders: StompHeaders) {
        this.session = session
    }

    override fun drainPayloads(): MutableList<Any?> {
        return payloads.getAndSet(ArrayList<Any?>())
    }

    override fun close() {
        if (session != null) {
            session!!.disconnect()
            session = null
        }
    }

    override fun handleException(
        session: StompSession,
        command: StompCommand?,
        headers: StompHeaders,
        payload: ByteArray,
        exception: Throwable
    ) {
        addException(exception)
    }

    override fun handleTransportError(session: StompSession, exception: Throwable) {
        addException(exception)
    }

    fun addException(exception: Throwable) {
        payloads.get().add(exception.message ?: exception::class.simpleName)
    }
}
