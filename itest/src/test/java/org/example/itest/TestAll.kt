package org.example.itest

import com.fasterxml.uuid.EthernetAddress
import com.fasterxml.uuid.Generators
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.boot.test.web.client.postForEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import strikt.api.expectThat
import strikt.assertions.*
import java.time.Duration
import java.util.*

private const val repeats = 1000

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class TestAll {
    private val uuidGenerator = Generators.timeBasedReorderedGenerator(EthernetAddress.fromPreferredInterface())

    private fun makeMsg(discriminator: UUID? = null) = "Hello world ${discriminator?:uuidGenerator.generate()}!"

    @RepeatedTest(repeats)
    fun testCreate(@Autowired restTemplate: TestRestTemplate) {
        val uuid = uuidGenerator.generate()
        val response = restTemplate.postForEntity<String>("/create", Dto(
            uuid,
            makeMsg(uuid),
        ))
        expectThat(response)
            .describedAs("Create Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.CREATED)
                get(ResponseEntity<*>::getBody)
                    .isNull()
            }
    }

    @RepeatedTest(repeats)
    fun testMatch(@Autowired restTemplate: TestRestTemplate) {
        val response = restTemplate.exchange<List<Dto>>(
            "/match?query={query}",
            HttpMethod.GET,
            null,
            mapOf(Pair("query", "world")),
        )
        expectThat(response)
            .describedAs("Create Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.OK)
                get(ResponseEntity<*>::getBody)
                    .isA<List<Dto>>()
                    .map(Dto::payload)
                    .first()
                    .startsWith("Hello world")
            }
    }

    @RepeatedTest(repeats)
    fun testWebsocket(
        @Autowired restTemplate: TestRestTemplate,
        @Autowired wsClient: WsClient,
    ) {
        val uuid = uuidGenerator.generate().toString()
        wsClient.subscribeTo("/topic/echo/$uuid").use { drainer ->
            val response = restTemplate.postForEntity<String>("/websocket?where={where}&what={what}", null, mapOf(
                Pair("where", uuid),
                Pair("what", "Hello world!"),
            ))
            expectThat(response)
                .describedAs("WebSocket Echo Response")
                .and {
                    get(ResponseEntity<*>::getStatusCode)
                        .isEqualTo(HttpStatus.NO_CONTENT)
                    get(ResponseEntity<*>::getBody)
                        .isNull()
                }
            Thread.sleep(Duration.ofMillis(100))
            expectThat(drainer.drainPayloads())
                .describedAs("Received messages")
                .containsExactly("Hello world!")
        }
    }

    @RepeatedTest(repeats)
    fun testEcho(@Autowired restTemplate: TestRestTemplate) {
        val msg = makeMsg()
        val response = restTemplate.postForEntity<String>("/echo", msg)
        expectThat(response)
            .describedAs("Echo Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.OK)
                get(ResponseEntity<*>::getBody)
                    .isA<String>()
                    .contains(msg)
            }
    }

    @RepeatedTest(repeats)
    fun testProxy(@Autowired restTemplate: TestRestTemplate) {
        val msg = makeMsg()
        val response = restTemplate.postForEntity<String>("/proxy", msg)
        expectThat(response)
            .describedAs("Proxied Echo Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.OK)
                get(ResponseEntity<*>::getBody)
                    .isA<String>()
                    .contains(msg)
            }
    }
}

data class Dto(val id: UUID, val payload: String)