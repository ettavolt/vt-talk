package org.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.invoke
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths

val client = HttpClient(CIO)

suspend fun process(fileName: String, requestUrl: String) = coroutineScope {
    val bytes = Dispatchers.IO.invoke {
        println("Reading file: $fileName")
        Files.readAllBytes(Paths.get(fileName))
    }
    println("Successfully read ${bytes.size} bytes from the file.")

    println("Sending HTTP request to: $requestUrl")
    val response = client.post(requestUrl) {
        header(HttpHeaders.ContentType, "text/plain")
        header(HttpHeaders.ContentLength, bytes.size)
        setBody(bytes)
    }
    println("Received response with status code: ${response.status.value}")
    println("Response body: ${response.bodyAsText()}")
}

fun main() {
    runBlocking {
        process("file.txt", "https://httpbin.org/anything")
    }
    println("Process completed")
}