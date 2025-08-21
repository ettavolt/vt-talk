Pieces of code to demonstrate how Virtual Threads in Java could be used
and how their performance compares to other technologies.

1. [EchoServer](jvm/src/main/java/org/example/EchoServer.java), Java VTs.
2. [Golang](golang/main.go) client.
3. Node.js [callbacks](nodejs/callbacks.ts) and [promises](nodejs/promises.ts).
4. [Java Project Reactor](jvm/src/main/java/org/example/Reactor.java).
5. Kotlin [coroutines](jvm/src/main/java/org/example/Koroutine.kt).
6. Java [VT client](jvm/src/main/java/org/example/VirtualThread.java).
7. An example Spring Boot [web service](service/src/main/java/org/example/spring/Configs.kt) switched to use VTs.
    - Needs [two local components](stack).
8. [Tests](itest/src/test/java/org/example/itest/TestAll.kt) for the service.

[The text and slides](presentation) are also available.
