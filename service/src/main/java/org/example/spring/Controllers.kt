package org.example.spring

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.*
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

private val proxiedHeaders = setOf(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH)

@Controller
class HttpControllers(
    private val restClient: RestClient,
) {
    @RequestMapping(path = ["/echo"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun echo(req: HttpServletRequest): ResponseEntity<ByteArray> {
        val buf = ByteArrayOutputStream()
        val writer = PrintWriter(buf, true, StandardCharsets.UTF_8)
        writer.append("\nmethod: ").append(req.method).append("\n")
        writer.append("url: ").append(req.contextPath).append("\n")
        writer.append("origin: ").append(req.remoteAddr).append("\n")
        writer.append("headers:")
        for (header in req.headerNames) {
            writer.append("\n\t").append(header).append(": ")
            for (value in req.getHeaders(header)) {
                writer.append(value).append(", ")
            }
        }
        writer.append("\ndata: ")
        writer.flush()
        req.inputStream.use { it.transferTo(buf) }
        return ResponseEntity.ok(buf.toByteArray())
    }

    @RequestMapping("/proxy")
    fun proxy(req: HttpServletRequest, res: HttpServletResponse) {
        restClient
            .method(HttpMethod.valueOf(req.method))
            .apply {
                for (headerName in proxiedHeaders) {
                    for (value in req.getHeaders(headerName)) {
                        header(headerName, value)
                    }
                }
            }
            .uri("http://localhost:8080/")
            .body { oS -> req.inputStream.use { iS -> iS.transferTo(oS) } }
            .exchange { pReq, pRes ->
                for (headerName in proxiedHeaders) {
                    for (value in pRes.headers.getOrEmpty(headerName)) {
                        res.addHeader(headerName, value)
                    }
                }
                res.status = pRes.statusCode.value()
                pRes.body.use { iS -> res.outputStream.use { oS -> iS.transferTo(oS) } }
            }
    }
}


@RestController
class RestControllers(
    private val notifier: Notifier,
    private val creator: Creator,
    private val matcher: Matcher,
) {
    @PostMapping("/websocket")
    fun proxy(
        @RequestParam
        where: String,
        @RequestParam
        what: String,
    ): ResponseEntity<String> {
        try {
            notifier.notify(where, what)
            return ResponseEntity.noContent().build()
        } catch (ex: IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.message)
        }
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody what: Persisted) = creator.create(what)

    @GetMapping("/match")
    fun match(@RequestParam query: String) = matcher.match(query)
}