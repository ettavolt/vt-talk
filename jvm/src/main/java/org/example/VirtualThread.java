package org.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.out;

public class VirtualThread {
	private final boolean logProcess;
	public VirtualThread(boolean logProcess) {this.logProcess = logProcess;}

	private final HttpClient httpClient = HttpClient
		.newBuilder()
		.executor(Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("VT-HttpClient-", 1).factory()
		))
		.build();

	private void doProcess(String fileName, String requestUrl) throws IOException, InterruptedException {
		if (logProcess) out.println("Reading file: " + fileName);
		var bytes = Files.readAllBytes(Paths.get(fileName));
		if (logProcess) out.println("Successfully read " + bytes.length + " bytes from the file.");

		var request = HttpRequest
			.newBuilder(URI.create(requestUrl))
			.POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
			.header("Content-Type", "text/plain");
		if (logProcess) out.println("Sending HTTP request to: " + requestUrl);
		var response = httpClient.send(
			request.build(),
			HttpResponse.BodyHandlers.ofString()
		);
		if (logProcess) out.println("Received response with status code: " + response.statusCode());
		if (logProcess) out.println("Response body: " + response.body());
	}

	public void processOne(String fileName, String requestUrl) {
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

	public void processMany(String fileName, String requestUrl) {
		final var successes = new AtomicInteger(0);
		var wanted = 100_000;
		try (var scope = new StructuredTaskScope<Void>()) {
			var semaphore = new Semaphore(200);
			for (int i = 0; i < wanted; i++) {
				scope.fork(() -> {
					semaphore.acquire();
					try {
						doProcess(fileName, requestUrl);
						successes.incrementAndGet();
					} catch (Exception ignored) {
						//No rethrowing, so that the stack traces don't accumulate on the heap.
					} finally {
						semaphore.release();
					}
					return null;
				});
			}
			scope.join();
		} catch (InterruptedException ignored) {
			//Just exit.
		}
		out.println("S:" + successes.get() + " F:" + (wanted - successes.get()));
	}

	public static void main(String[] args) {
		var virtualThread = new VirtualThread(false);
//		virtualThread.processOne("file.txt", "http://localhost:8080/");
		virtualThread.processMany("file.txt", "http://localhost:8080/");
		out.println("Process completed");
	}
}
