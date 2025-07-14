package org.example.itest

import org.eclipse.jetty.client.HttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.autoconfigure.http.client.ClientHttpRequestFactoryBuilderCustomizer
import org.springframework.boot.autoconfigure.http.client.HttpClientProperties
import org.springframework.boot.autoconfigure.thread.Threading
import org.springframework.boot.http.client.JettyClientHttpRequestFactoryBuilder
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

@Component
@ConditionalOnThreading(Threading.VIRTUAL)
class VtEnabler(
    private val props: HttpClientProperties,
) : ClientHttpRequestFactoryBuilderCustomizer<JettyClientHttpRequestFactoryBuilder> {
    lateinit var httpClient: HttpClient
    override fun customize(builder: JettyClientHttpRequestFactoryBuilder): JettyClientHttpRequestFactoryBuilder {
        return builder.withHttpClientCustomizer {
            httpClient = it
            it.idleTimeout = props.readTimeout.toMillis()
            it.executor = Thread
                .ofVirtual()
                .name("HttpClient-", 1)
                .factory()
                .let(Executors::newThreadPerTaskExecutor)
        }
    }
}