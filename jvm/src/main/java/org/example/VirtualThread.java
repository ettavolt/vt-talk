package org.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

import static java.lang.System.out;

public class VirtualThread {
	private final HttpClient httpClient = HttpClient
		.newBuilder()
		.executor(Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("VT-HttpClient-", 1).factory()
		))
		.build();

	private void doProcess(String fileName, String requestUrl) throws IOException, InterruptedException {
		out.println("Reading file: " + fileName);
		var bytes = Files.readAllBytes(Paths.get(fileName));
		out.println("Successfully read " + bytes.length + " bytes from the file.");

		var request = HttpRequest
			.newBuilder(URI.create(requestUrl))
			.POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
			.header("Content-Type", "text/plain");
		out.println("Sending HTTP request to: " + requestUrl);
		var response = httpClient.send(
			request.build(),
			HttpResponse.BodyHandlers.ofString()
		);
		out.println("Received response with status code: " + response.statusCode());
		out.println("Response body: " + response.body());
	}

	public void process(String fileName, String requestUrl) {
		var thread = Thread.startVirtualThread(() -> {
			try {
				doProcess(fileName, requestUrl);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		try {
			thread.join();
		} catch (InterruptedException ignored) {
			//Just exit.
		}
	}

	public static void main(String[] args) {
		var virtualThread = new VirtualThread();
		virtualThread.process("file.txt", "https://httpbin.org/anything");
		out.println("Process completed");
	}
}
