package org.example;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.lang.System.out;

public class Reactor {
	private final boolean logProcess;
	public Reactor(boolean logProcess) {this.logProcess = logProcess;}
	private final HttpClient httpClient = HttpClient.create();

	@NotNull
	private Mono<byte[]> readFile(String fileName) {
		try {
			if (logProcess) out.println("Reading file: " + fileName);
			return Mono.just(Files.readAllBytes(Paths.get(fileName)));
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	@NotNull
	private Mono<String> postBytes(String requestUrl, byte[] bytes) {
		if (logProcess) out.println("Successfully read " + bytes.length + " bytes from the file.");

		if (logProcess) out.println("Sending HTTP request to: " + requestUrl);
		return httpClient
			.headers(headers -> {
				headers.set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
				headers.set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
			})
			.post()
			.uri(requestUrl)
			.send(Mono.just(Unpooled.wrappedBuffer(bytes)))
			.responseSingle((response, body) -> {
				if (logProcess) out.println("Received response with status code: " + response.status().code());
				return body.asString();
			});
	}

	@NotNull
	private Mono<Integer> acknowledge(String response) {
		if (logProcess) out.println("Response body: " + response);
		return Mono.just(1);
	}

	public Mono<Integer> process(String fileName, String requestUrl) {
		return readFile(fileName)
			.flatMap(bytes -> postBytes(requestUrl, bytes))
			.flatMap(this::acknowledge);
	}

	public void processOne() {
		process("file.txt", "http://localhost:8080/").block();
	}

	public void processMany() {
		var wanted = 1_000_000;
		var successes = Flux
			.range(0, wanted)
			.flatMap(i -> process("file.txt", "http://localhost:8080/"), 100)
			.onErrorResume(e -> Mono.just(0))
			.subscribeOn(Schedulers.boundedElastic())
			.reduce(0, Integer::sum)
			.block();
		out.println("S:" + successes + " F:" + (wanted - successes));
	}


	public static void main(String[] args) {
//		System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true");
		Reactor reactor = new Reactor(false);
//		reactor.processOne();
		reactor.processMany();
		out.println("Process completed");
	}

}
