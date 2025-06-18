package org.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

val client = HttpClient(CIO)
const val logProcess = false

suspend fun process(fileName: String, requestUrl: String) = coroutineScope {
    val bytes = Dispatchers.IO.invoke {
        if (logProcess) println("Reading file: $fileName")
        Files.readAllBytes(Paths.get(fileName))
    }
    if (logProcess) println("Successfully read ${bytes.size} bytes from the file.")

    if (logProcess) println("Sending HTTP request to: $requestUrl")
    val response = client.post(requestUrl) {
        header(HttpHeaders.ContentType, "text/plain")
        header(HttpHeaders.ContentLength, bytes.size)
        setBody(bytes)
    }
    if (logProcess) println("Received response with status code: ${response.status.value}")
    val body = response.bodyAsText()
    if (logProcess) println("Response body: $body")
}

@OptIn(ExperimentalAtomicApi::class)
fun main() {
    val wanted = 1_000_000
    val successes = AtomicInt(0)
    val semaphore = Semaphore(200)
    runBlocking {
//        process("file.txt", "http://localhost:8080/")
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(wanted) {
                    semaphore.acquire()
                    launch {
                        try {
                            process("file.txt", "http://localhost:8080/")
                            successes.addAndFetch(1)
                        } catch (ignored: Exception) {
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }
        }
        println("S:${successes.load()} F:${wanted - successes.load()}")
    }
    println("Process completed")
}