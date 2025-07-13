Running VT/Golang 100_000 parallel examples without semaphore makes it hard for the kernel to keep up.
NodeJS-promises example with 100_000 cannot reach the stage of actually sending a request.
Anyone knows how to constrain concurrency with Reactor?

[Practical](https://docs.oracle.com/en/java/javase/24/core/virtual-threads.html#GUID-8AEDDBE6-F783-4D77-8786-AC5A79F517C0):
1. Arrow lambdas to get the source frame in the stack trace.
1. Replace pool sizing with semaphore.
1. DB connection pooling.
1. v24 for synchronized. Doesn't need to change target.
1. Fixed delay scheduling.
1. Unless main joins, a VT doesn't keep JVM running.
1. Don't proxy, create pre-signed URLs.

1. module tests, 4T/8C: 53.6, 52.5, 52.1. 8T/8C: 41.3, 51.1, 50.3, 50.11. 4VT/8C: 52.8, 52.9.
1. ITs, 8VT/8C: 9, 8.6, 8.3, 8.2. 8T/8C: 7.9, 7.8, 8.3.
1. Ephemeral instance, PT: 7:47, VT: 10:11 / 10:52, but the VM was proportionally slower.


Today I'd like to tell what I've learned about one of the recent features in JVM
that is promised to deal with "Java being slow." Namely, Virtual Threads developed under the Project Loom.

Let's start with a refresher of two basic ideas in software development: parallelism and concurrency.

Parallelism is about performing multiple tasks at the same _moment_. Be it on several cores,
several sockets, several nodes, etc. For example, one node can be classifying an image as showing or not a dog,
while another one is making a picture of a duck.

Concurrency, on its turn, doesn't necessarily require performing multiple tasks at the same _moment_,
but rather that their timelines overlap. So, if the tasks have segments of waiting (red ones),
instead of doing nothing, we can switch to another task, and keep the core busy.
Note that a concurrency-capable system doesn't require more than one core.
Of course, most modern systems support parallelism and concurrency.
Q?

Now, let's see what various methods of using the parallelism and concurrency capabilities of operating systems,
or, how they are often called, platforms.

The simplest way is to just let a developer control the platform threads. This is the most natural way in, say, C.
Great for parallelism: launch a thread per core, and let them crunch numbers,
with, hopefully, short interruptions by OS to keep ownself running.
However, if our threads have to wait for something else happenning elsewhere, e.g.: in a different process, or the kernel,
maybe reached to over the network — it might be tricky to _concurrently_ process enough tasks to use up all available core time.
Mainly, because platform threads are heavy. One thing often mentioned is the stack size, which is said to require
four megabyte of _continuous_ memory (in fact `-Xsssize` is 1MiB on amd64, but I'll show where 4MiB figure could come from).
And then there are a lot of other structures the kernel manages for a thread.
In the end, they say that it is hard to get more than a thousand of platform threads in an application.

Is there a way to reduce the cost of running more tasks _concurrently_?
Well, let's forbid a developer to write the code that waits!
This can be done with or without syntax sugar, but at the end there are a bunch of small chunks
that can be thrown at the core for execution. Even without parallelism (like in Node.js),
it still can handle a good number of concurrent tasks, cheaply, as long as they actually need to wait a lot.
Note that in this example because the third request arrives together with the second one, it will actually start a little bit later.
As a result, its second chunk competes for resources as well, and, overall, it finishes later than desired.

Is it possible to take the best of both? Let's make sure (explicitly or not) that once the task begins to wait,
it is put aside and gives the time to the other one. And, also, if there is more than one task ready to go,
let's run them in parallel. This is sometimes called N-to-M scheduling,
and this approach is taken by Go, Kotlin Coroutines, Project Reactor, and, now, Java's Virtual Threads.
This way the tasks can fill up any number of cores without unintended delays. It is also less expensive than handling lots of platform threads.
Switching a task between cores might be suboptimal, but in the background of switching tasks on a core,
there is little drawback. There is one important difference with the scheduling the platform performs:
the time a single task is allowed to run uninterruptedly is not constrainted, i.e. there is no _preemptive_ concurrency.

I'm going to mention a little bit of terminology from the Project Loom here, but before that: any Q?

So, according to Loom, we have Virtual Threads (aka "green threads") which are multiplexed onto Platform Threads.
A Virtual Thread gets CPU time after being mounted and before being unmounted.
Q?

Now, let us see how these models are accessed in different languages or libraries.
I have two samples for Node.js, but I'll leave them until the end because it is single-threaded
and thus cannot really compete with the topic feature.

First, we'll look at the oldest of the four: Go (announced 2009, stable in 2012).
From the very beginning, it had the namesake feature of launching a "goroutine" by means of `go func();`.
Every I/O operation, block in the `sync` package, or using a channel is a so-called "rescheduling" point,
when a task is unmounted from the carrier thread and put aside. As we can see, Go allows writing
fairly straight-forward and synchronous-looking code. I'll launch it while the server is running to show
that it is in fact working. And once more without the echo server to show Go's different approach to error reporting.
Note, it would be the same had we done everything on the main thread.
Q?

Let's move on to what is available on JVM.
Unfortunately, around 2010 we had a decade of stagnation in Java, so the users tried to save themselves.
In 2013, the first stable version of Reactor Core was released. Its main idea is to explicitly break the execution
when a blocking call is going to be made. To avoid callback-style lambdas, I added the steps of the reactive chain as methods.
Unfortunately, there is only support for "unmounting" at network communication,
but not on File I/O or synchronization primitives. Note that unlike Go which waits for all coroutines to end before terminating,
here we have to explicitly wait for the reactive chain to finish. Let's run it with and without the echo server.
When it works, then it works. But if there is an error, we only get a reference to the chain, but not the line in the step method
that triggered the problem.
It is only a little bit better if we enable debugging: it shows that something happened at `responseSingle`,
and we can find it in the method invoked at this point in the chain.
Q?

Let's see JetBrain's take on this problem. In 2018, Kotlin Coroutines became stable. Thanks to a different compiler,
supporting "suspending" functions and code blocks, the chain looks nicer than Project Reactor's.
However, File I/O is still not covered, so to avoid it blocking resources of the main dispatcher,
we have to move it to a different one. There is a fancy indicator on the gutter to show suspension points,
where, in Loom's terminology, unmounting and remounting happens. Let's run it. While the echo server is accessible,
everything looks all right. However, if it is stopped, there is zero evidence of where the problem originated.
Note, I've only scratched the surface of Kotlin Coroutines, and there might be something to improve the traceability,
particularly if run under IntelliJ.
Q?

Finally, being true to itself in slow to start and warm up, but then fast to run, JVM got Virtual Threads in 2023 (v21)
with one significant obstacle to the adoption removed in 2025 (v24). As we can see in this example, the code looks
100% synchronous, and in fact, we can run it synchronously on the main thread. Virtual Threads also produce the usually looking
stack traces, however, they are not visible in the regular thread dumps. Let's experiment with this new Java's feature. To start,
let's try to run 100_000 requests sequentially. The echo server has a counter for the requests completed successfully or
not over roughly a second. Sequential execution gives us about X RPS. Next, let's try to run all with a thread-per-request style.
The example uses `ExecutorService#close` to wait for all tasks to complete.
Suprisingly, we get only a threefold increase in RPS, but also some failed requests. Any ideas what was done wrong?
One thing we can change is to stop logging from adding unnecessary synchronization of the threads.
The result: a great drop in RPS and most requests failed. Why? Well, there are about 50_000 ports to use to make a request.
If I launch it again, we can see the cpu load indicator showing a huge portion of time spent by the kernel,
likely fulfilling all the File and Network I/O.
How to deal with this? Let's add a semaphore with 10_000 permits.
Only a twofold gain in RPS over the sequential case, and many requests have failed.
What can we do? Let's not launch all tasks at the same time and then make them wait at the semaphore, but delay the launch instead.
No significant difference. Let's reduce the number of concurrent tasks to a mere 100.
Everything succeeds quickly and RPS is several times higher. We might not have had enough requests to see a stabilized RPS,
let's up the number to 1_000_000. Now, an eightfold increase of RPS over the sequential execution. Coincidentally,
this laptop has eight cores. What if we reduce the concurrency even further, to just 16? Slower overall, but not much.
Even if we move acquiring a permit from the sempahore into the thread, this only slows ramp-up, but RPS stabilizes at the same level.
I would say, Virtual Threads are well done, because the JVM can manage 1_000_000 of them without a noticeable loss of performance.
However, as the Project Loom's authors are tirelessly warning us, they can't make a system work faster, they can only help
sharing the existing resources by concurrent tasks.

One more experiment: let's add a random delay before sending the echo response. Uniform randomness out of 5 should yield
us an average delay of 2.5s. If we have only 10_000 concurrent requests, RPS should be around 4_000.
Yes, this is how it works. If we log the failed requests, we'll see that they all fail at reading the response.
I'd say, this is caused by my system not being tuned for such kind of stress testing, i.e. it's not JVM's fault.

If anyone is curious, the same could be observed with the other three technologies.

How to use it in one's project?

Let's use an example of Spring Web Rest Controller, that directs other requests to the echo server,
for some of them logs a timestamp to a conneciton-pooled database and sends a message over Web Socket.


No agent and RMI, fewer threads for GC and compiler (-XX:CICompilerCount=6 \
-XX:ConcGCThreads=2 \
-XX:ParallelGCThreads=6 \
-XX:G1ConcRefinementThreads=6)
%MEM   RSS THCNT
PT-CDS 1.9 1258816  68
VT-CDS 1.7 1132212  42
(1258816-1132212)/(68-42)=4869

PT+CDS 1.8 1211524  69
VT+CDS 1.6 1111424  43
(1211524-1111424)/(69-43)=3850

Library & application threads:
- VT Jetty Server request acceptors and selectors, on-demand processors, and a "keep-alive" PT in jetty.VirtualPool;
- no application scheduler/executor threads, just a "trigger" VT in SimpleAsyncTaskScheduler;
- no WebSocket MessageBroker threads, just a "trigger" VT; Jetty Server WS support uses the Server's pool.
- no pool for HTTP Client backing Elastic RHLC;
- no pools for RestTemplateBuilder-produced HttpClients;
- VTs for Hikari connection adder, closer, leak detector, min connection rotator (all via VT-backed pools);
- VT for the @Scheduled debugger acceptor-selector-executor;
- but VT carriers (-Djdk.virtualThreadScheduler.parallelism=8, defaults to NC), unblocker, unparkers (timers) (
jdk.virtualThreadScheduler.timerQueues, defaults to max(1, highestOneBit(NC/4))
);
- PT threads in Neo4J driver (although Netty has some [interesting development](https://micronaut.io/2025/06/30/transitioning-to-virtual-threads-using-the-micronaut-loom-carrier/));
- AWS Clients are not enabled; SDK uses max(8, NC) for executor, 5 for scheduler for Async clients;
- Twilio uses blocking Apache Http Components.


