package org.example;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.lang.System.out;

public class Reactor {
	private final HttpClient httpClient = HttpClient.create();

	@NotNull
	private Mono<byte[]> readFile(String fileName) {
		try {
			out.println("Reading file: " + fileName);
			return Mono.just(Files.readAllBytes(Paths.get(fileName)));
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	@NotNull
	private Mono<String> postBytes(String requestUrl, byte[] bytes) {
		out.println("Successfully read " + bytes.length + " bytes from the file.");

		out.println("Sending HTTP request to: " + requestUrl);
		return httpClient
			.headers(headers -> {
				headers.set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
				headers.set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
			})
			.post()
			.uri(requestUrl)
			.send(Mono.just(Unpooled.wrappedBuffer(bytes)))
			.responseSingle((response, body) -> {
				out.println("Received response with status code: " + response.status().code());
				return body.asString();
			});
	}

	@NotNull
	private Mono<Void> acknowledge(String response) {
		out.println("Response body: " + response);
		return Mono.empty();
	}

	public Mono<Void> process(String fileName, String requestUrl) {
		return readFile(fileName)
			.flatMap(bytes -> postBytes(requestUrl, bytes))
			.flatMap(this::acknowledge);
	}

	public void processOne() {
		process("file.txt", "http://localhost:8080/").block();
	}

	public void processMany() {
		Flux
			.range(0, 100_000)
			.flatMap(i -> process("file.txt", "http://localhost:8080/"))
			.blockLast();
	}


	public static void main(String[] args) {
		Reactor reactor = new Reactor();
//		reactor.processOne();
		reactor.processMany();
		out.println("Process completed");
	}

}
