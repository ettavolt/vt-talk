package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executors;

public class EchoServer {
	public static void main(String[] args) throws IOException {
		var server = HttpServer.create(
			new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080),
			0,
			"/",
			EchoServer::doHandle
		);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		System.out.println("Started EchoServer.");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.stop(0);
			System.out.println("Stopped EchoServer.");
		}, "EchoServer-Shutdown"));
	}

	private static void doHandle(HttpExchange exchange) throws IOException {
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
		System.out.println(Clock.systemUTC().millis());
	}
}
