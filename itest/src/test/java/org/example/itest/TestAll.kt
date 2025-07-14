package org.example.itest

import com.fasterxml.uuid.EthernetAddress
import com.fasterxml.uuid.Generators
import org.junit.jupiter.api.Test
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class TestAll {
    private val uuidGenerator = Generators.timeBasedReorderedGenerator(EthernetAddress.fromPreferredInterface())
    @Test
    fun testCreate(@Autowired restTemplate: TestRestTemplate) {
        val response = restTemplate.postForEntity<String>("/create", Dto(
            uuidGenerator.generate(),
            "Hello world!"
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

    @Test
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
                    .contains("Hello world!")
            }
    }

    @Test
    fun testWebsocket(
        @Autowired restTemplate: TestRestTemplate,
        @Autowired wsClient: WsClient,
    ) {
        wsClient.subscribeTo("/topic/echo/callback").use { drainer ->
            val response = restTemplate.postForEntity<String>("/websocket?where=callback&what={what}", null, mapOf(
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
            Thread.sleep(Duration.ofSeconds(1))
            expectThat(drainer.drainPayloads())
                .describedAs("Received messages")
                .containsExactly("Hello world!")
        }
    }

    @Test
    fun testEcho(@Autowired restTemplate: TestRestTemplate) {
        val response = restTemplate.postForEntity<String>("/echo", "Hello world!")
        expectThat(response)
            .describedAs("Echo Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.OK)
                get(ResponseEntity<*>::getBody)
                    .isA<String>()
                    .contains("Hello world!")
            }
    }

    @Test
    fun testProxy(@Autowired restTemplate: TestRestTemplate) {
        val response = restTemplate.postForEntity<String>("/proxy", "Hello world!")
        expectThat(response)
            .describedAs("Proxied Echo Response")
            .and {
                get(ResponseEntity<*>::getStatusCode)
                    .isEqualTo(HttpStatus.OK)
                get(ResponseEntity<*>::getBody)
                    .isA<String>()
                    .contains("Hello world!")
            }
    }
}

data class Dto(val id: UUID, val payload: String)