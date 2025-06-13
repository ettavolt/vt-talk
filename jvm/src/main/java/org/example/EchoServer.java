package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EchoServer {

	public static void main(String[] args) throws IOException {
		new EchoServer().start();
	}

	private void doHandle(HttpExchange exchange) throws IOException {
		try {
			var buf = new ByteArrayOutputStream();
			var writer = new PrintWriter(buf, true, StandardCharsets.UTF_8);
			writer.append("\nmethod: ").append(exchange.getRequestMethod()).append("\n");
			writer.append("url: ").append(exchange.getRequestURI().toString()).append("\n");
			writer.append("origin: ").append(exchange.getRemoteAddress().toString()).append("\n");
			writer.append("headers:");
			for (var header : exchange.getRequestHeaders().entrySet()) {
				writer.append("\n\t").append(header.getKey()).append(": ");
				for (String value : header.getValue()) {
					writer.append(value).append(", ");
				}
			}
			writer.append("\ndata: ");
			writer.flush();
			exchange.getRequestBody().transferTo(buf);
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			exchange.sendResponseHeaders(200, buf.size());
			try (var responseBody = exchange.getResponseBody()) {
				buf.writeTo(responseBody);
			}
			successes.incrementAndGet();
		} catch (Exception e) {
			failures.incrementAndGet();
		}
	}

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final AtomicInteger successes = new AtomicInteger();
	private final AtomicInteger failures = new AtomicInteger();
	private final HttpServer server = HttpServer.create(
		new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080),
		0,
		"/",
		this::doHandle
	);

	public EchoServer() throws IOException {
		server.setExecutor(executor);
	}

	public void start() {
		executor.submit(this::reportRps);
		server.start();
		System.out.println("Started " + this);
		//Note: virtual threads cannot be "created, but not started".
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, this + "-shutdown"));
	}

	private void reportRps() {
		try {
			var wakeUpAt = System.nanoTime();
			var lineBreakForZero = false;
			//See the comment before "catch".
			//noinspection InfiniteLoopStatement
			do {
				wakeUpAt += Duration.ofSeconds(1).toNanos();
				var rps = successes.getAndSet(0);
				var eps = failures.getAndSet(0);
				if (rps > 0 || eps > 0) {
					System.out.println(ZonedDateTime.now() + " " + rps + " " + eps);
					lineBreakForZero = true;
				} else if (lineBreakForZero) {
					System.out.println();
					lineBreakForZero = false;
				}
				Thread.sleep(Duration.ofNanos(wakeUpAt - System.nanoTime()));
			} while (true);
			//InterruptedException: thrown if the thread was interrupted before or during sleep activity.
		} catch (InterruptedException ignored) {
			//Time to go off.
		}
	}

	private void shutdown() {
		server.stop(0);
		executor.shutdownNow();
		System.out.println("Stopped " + this);
	}
}
