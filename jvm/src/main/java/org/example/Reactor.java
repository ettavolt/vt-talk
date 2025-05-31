package org.example;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.lang.System.out;

public class Reactor {
	private final HttpClient httpClient = HttpClient.create();

	public Mono<Void> process(String fileName, String requestUrl) {
		return Mono
			.fromCallable(() -> {
				try {
					out.println("Reading file: " + fileName);
					return Files.readAllBytes(Paths.get(fileName));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			})
			.flatMap(bytes -> {
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
			})
			.flatMap(response -> {
				out.println("Response body: " + response);
				return Mono.empty();
			});
	}

	public static void main(String[] args) {
		Reactor reactor = new Reactor();
		reactor
			.process("file.txt", "https://httpbin.org/anything")
			.block();
		out.println("Process completed");
	}
}
