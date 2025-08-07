Today I'd like to tell what I've learned about one of the recent features in JVM
that is promised to deal in one more way with "Java being slow." Namely, Virtual Threads developed under the Project Loom.

Let's start with a refresher of two basic ideas in software development: parallelism and concurrency.

Parallelism is about performing multiple tasks at the same _moment_. Be it on several cores,
several sockets, several nodes, etc. For example, one node can be classifying an image as showing or not a dog,
while another one is making a picture of a duck.

Concurrency, in its turn, doesn't necessarily require performing multiple tasks at the same _moment_,
but rather that their timelines overlap. So, if the tasks have segments of waiting (red ones),
instead of doing nothing, we can switch to another task and keep the core busy.
Note that a concurrency-capable system doesn't require more than one core.
Of course, most modern systems support parallelism and concurrency.
Q?

Now, let's see what the methods we know to use the parallelism and concurrency capabilities of operating systems.

The simplest way is to just let a developer control the platform threads. This is the most natural way in, say, C.
Great for parallelism: launch a thread per core, and let them crunch numbers,
with, hopefully, short interruptions by OS to keep ownself running.
However, if our threads have to wait for something else happening elsewhere, e.g. in a different process, or the kernel,
maybe reached to over the network — it might be tricky to _concurrently_ process enough tasks to use up all available core time.
Mainly, because platform threads are heavy. One thing often mentioned is the stack size, which is said to require
four megabytes of _continuous_ memory (although `-Xsssize` is 1MiB on amd64).
And then there are some other structures the kernel manages for a thread.
Overall, they say that it is hard to get more than a thousand of platform threads in an application.

Is there a way to reduce the cost of running more tasks _concurrently_?
Well, let's forbid a developer to write the code that waits!
This can be done with or without syntax sugar, but at the end there are a bunch of small chunks
that can be thrown at the core for execution. Even without parallelism (like in Node.js),
it still can handle a good number of concurrent tasks, cheaply, as long as they actually need to wait a lot.
Note that in this example because the third request arrives together with the second one, it will actually start a little bit later.
As a result, its second chunk competes for resources as well, and, overall, it finishes later than desired.

Is it possible to take the best of both? Let's make sure (explicitly or not) that once a task begins to wait,
it is put aside and gives the time to another one. And, also, if there is more than one task ready to go,
let's run them in parallel. This is sometimes called N-to-M scheduling,
and this approach is taken by Go, Kotlin Coroutines, Project Reactor, and, now, Java's Virtual Threads.
This way the tasks can fill up any number of cores without unintended delays. It is also less expensive than handling lots of platform threads.
Switching a task between cores might be suboptimal, but in the background of switching tasks on a core,
there is little drawback. There is one important difference with the scheduling the platform performs:
the time a single task is allowed to run uninterruptedly is not constrainted, i.e. there is no _preemptive_ concurrency.

The Project Loom uses some specific terminology: there are _Virtual Threads_ (aka "green threads")
which are _multiplexed_ onto _Platform Threads_.
A Virtual Thread gets CPU time after being _mounted_ and before being _unmounted_.
Q?

Now, let us see how these models are accessed in different languages or libraries.
I have two samples for Node.js, but I'll leave them until the end because it is single-threaded
and thus cannot really compete with the topic feature.

First, we'll look at the oldest of the four: Go (announced 2009, stable in 2012).
From the very beginning, it had the namesake feature of launching a "goroutine" by means of `go func();`.
Every I/O operation, a block in the `sync` package, or using a channel is a so-called "rescheduling" point,
when a goroutine is unmounted from the carrier thread and put aside. As we can see, Go allows writing
fairly straight-forward and synchronous-looking code. I'll launch it while the server is running to show
that it is in fact working. And once more without the echo server to show Go's different approach to error reporting.
Note, it would be the same had we done everything on the main thread.
Q?

Let's move on to what is available on JVM.
Unfortunately, around the 2010s we had a decade of stagnation in Java, so the Java users tried to save themselves.
In 2013, the first stable version of Reactor Core was released. Its main idea is to explicitly break the execution
when a blocking call is going to be made. To avoid callback-style lambdas, I added the steps of the reactive chain as methods.
Unfortunately, there is only support for "unmounting" at network communication,
but not on File I/O or synchronization primitives. Note that unlike Go, which waits for all coroutines to end before terminating,
here we have to explicitly wait for the reactive chain to finish. Let's run it with and without the echo server.
When it works, then it works. But if there is an error, we only get a reference to the chain but not the line in the step method
that triggered the problem.
It is only a little bit better if we enable debugging: it shows that something happened at `responseSingle`,
and we can find it in the method invoked at this point in the chain.
Q?

Let's see JetBrain's take on this problem. In 2018, Kotlin Coroutines became stable. Thanks to a different compiler,
supporting "suspending" functions and code blocks, the chain looks nicer than Project Reactor's.
However, File I/O is still not covered, so to avoid it blocking resources of the main dispatcher,
we have to move it to a different one. There is a fancy indicator on the gutter to show suspension points,
where, in Loom's terminology, unmounting and remounting happen. Let's run it. While the echo server is accessible,
everything looks all right. However, if it is stopped, there is zero evidence of where the problem originated.
Note, I've only scratched the surface of Kotlin Coroutines, and there might be something to improve the traceability,
particularly if run under IntelliJ.
Q?

Finally, being true to itself in slow to start and warm up, but then fast to run, JVM got Virtual Threads in 2023 (v21)
with one significant obstacle to the adoption removed in 2025 (v24). As we can see in this example, the code looks
100% synchronous, and in fact, we can run it synchronously on the main thread. Virtual Threads also produce the usual looking
stack traces; however, they are not visible in the regular thread dumps until [v25](https://bugs.openjdk.org/browse/JDK-8356870).
Let's experiment with this new Java's feature. To start, let's try to run 100_000 requests sequentially.
The echo server has a counter for the requests completed successfully or not over roughly a second.
Sequential execution gives us about X RPS. Next, let's try to run all with a thread-per-request style.
The example uses `ExecutorService#close` to wait for all tasks to complete.
Suprisingly, we get only a threefold increase in RPS, but also some failed requests. Any ideas what was done wrong?
One thing we can change is to stop logging from adding unnecessary synchronization of the threads.
The result: a great drop in RPS and most requests failed. Why? Well, there are about 50_000 ports to use to make a request.
If I launch it again, we can see the cpu load indicator showing a huge portion of time spent by the kernel,
likely fulfilling all the File and Network I/O.
How to deal with this? Let's add a semaphore with 10_000 permits.
Only a twofold gain in RPS over the sequential case, and many requests have failed anyway.
What can we do? Let's not launch all tasks at the same time and then make them wait at the semaphore, but delay the launch instead.
No significant difference. Let's reduce the number of concurrent tasks to a mere 100.
Everything succeeds quickly, and RPS is several times higher. We might not have had enough requests to see a stabilized RPS,
let's up the number to 1_000_000. Now, an eightfold increase of RPS over the sequential execution. Coincidentally,
this laptop has eight cores. What if we reduce the concurrency even further, to just 16? Slower overall, but not much.
Even if we move acquiring a permit from the sempahore into the thread, this only slows ramp-up, but RPS stabilizes at the same level.
I would say, Virtual Threads are well done, because the JVM can manage 1_000_000 of them without a noticeable loss of performance.
However, as the Project Loom's authors are tirelessly warning us, they can't make a system work faster, they can only help
sharing the existing resources by concurrent tasks.

One more experiment: let's add a random delay before sending the echo response. Uniform randomness out of 5 should yield
us an average delay of 2.5s. If we have only 10_000 concurrent requests, RPS should be around 4_000.
Yes, this is how it works. If we log the failed requests, we'll see that they all fail at reading the response.
I'd say this is caused by my system not being tuned for such kind of stress testing, i.e. it's not JVM's fault.

If anyone is curious, the same could be observed with Golang's coroutines.
I didn't manage to limit concurrency with Reactor, despite something called "backpressure propagation."
Kotlin's coroutines can't compete in terms of concurrency, staying at the level of 5k RPS.

Q?

How to use it in one's project? I've also made a small example Spring Web application (servlet-based) to showcase
how different parts could be configured to use virtual threads. The app has the following endpoints:
echo, proxy (to the echo server), write a simple entity to the database,
run a match against index in Elasticsearch, and send something over a web socket.
There is a component to index the entities.

The first thing to configure is `spring.threads.virtual.enabled: true`.
It changes the outcome of `@ConditionalOnThreading`,
for example, replacing ThreadPool-based task executor or scheduler with Thread-Per-Task-based SimpleAsync ones.
Due to Web Sockets support adding another scheduler bean, the context doesn't get the autoconfigured ones at the
application level, so they have to be added explicitly.
Note, in 6.1's SimpleAsyncTaskScheduler Fixed Delay tasks were effectively executed as Fixed Rate,
because scheduling was only used to launch the task. In 6.2, Fixed Delay tasks are run in
a separate single-threaded scheduler so that they won't be executed more often than intended
(but maybe less often due to contention).
They recommend limiting the concurrency anyway (which is the second job done by constrined-size thread pools),
and for this we have the properties `spring.task.[scheduling|execution].simple.concurrency-limit`.
Note, Spring's TaskScheduler beans work as TaskExecutors as well.
Also, to simplify debugging _some_ concurrency issues, we should make sure that all these customizations
are switched on by the same Spring setting. That's why I have two `@Bean` methods in `SchedulerConfig`:
one for Platform threading, the other - for Virtual.

`spring.threads.virtual.enabled` also affects threads used by the embedded servlet containers.
However, it stops Jetty's (the only one I'm looking at here) Adaptive Execution Strategy to process requests
right on the thread that accepted it (aka \[execute-]produce-consume). So, for a time being,
the application will need to supply a `WebServerFactoryCustomizer<JettyServletWebServerFactory>` to replace
a `QueuedThreadPool` with a `VirtualThreadPool`.
Note: the customizer has to come after Spring's own `JettyVirtualThreadsWebServerFactoryCustomizer`,
to override what the latter sets. This could be done by implementing `Ordered` interface or by putting an `@Order` annotation.
Also, don't forget about concurrency limit and set `VirtualThreadPool#maxThreads` to something different
from the default `200`. Mind that every connector will always use `selectors` + `acceptors` threads waiting
for connections and requests respectively. E.g. I had a problem trying 8-sized pool backing one selector and three
acceptors for TCP and UDS HTTP connectors.
Unfortunately, there will be an idle "Keep-Alive" Platform Thread
(the third job done by Thread Pools' non-daemon "core" threads), and a Platform Thread in the server's maintenance Scheduler.
The latter could not be overridden without shadowing Spring's `JettyServletWebServerFactory`.

These are the only two beans affected by the Spring's virtual thread setting, but there are =more pools and threads
used by the application.

One is the worker pool for the RestClient's implementation.
Since there is Jetty Web Socket on the classpath, Spring will default to instantiating Jetty-backed ClientHttpRequestFactory,
but we can force JDK's implementation with the `spring.http.client.factory: jdk` setting.  By default, it uses an unbound
ThreadPoolExecutor, and to configure it to use Virtual Threads,
we need a `ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder>`.
However, `HttpClientImpl.SelectorManager` extends (Platform) `Thread`, so we cannot get rid of it.

Hikari spawns several maintenance threads
(connection adder, closer, leak detector, min connection rotator),
and since there is no customizer for this bean, we have to use a BeanPostProcessor to inject a Virtual Thread Factory.
The framework uses a similar facility to configure the URL from the common setting,
see `HikariJdbcConnectionDetailsBeanPostProcessor`. Note: Hikari caches connection-to-thread relations via ThreadLocals,
which is [harmful](https://github.com/brettwooldridge/HikariCP/issues/2151) in thread-_life_-per-request execution style.
If the number of connections is small (recommended anyway), it is not a big problem.

Elasticsearch Client uses an Apache's HttpComponents Async Client 4.5, which is by default backed by an NC-sized Thread Pool.
Could be customized with `RestClientBuilderCustomizer`, overriding the default no-op `customize(HttpAsyncClientBuilder)`.
Spring can have RestClient backed by HttpComponents Client 5+, which is not compatible with v4.

Spring's `SimpleAsyncTaskExecutor` doesn't support interrupting tasks on shutdown, so let's use a separate Thread
to perform RDBMS → Elasticsearch synchronization. The idea is that we'll batch by ten records, but won't wait for longer
than ten seconds to push a batch. To flip a Thread implementation, we can use 
`org.springframework.boot.autoconfigure.thread.Threading.X#isActive(Environment)` method.

The last piece that will still be using Platform Threads is the `SimpleMessageBroker`, that abstracts the Web Sockets support.
Actual WS communication and the most processing use Jetty Server's pool. Heartbeats and SockJS support need a scheduler.
There is no default way to inject a different implementation instead of ThreadPoolScheduler, even with `@Primary`,
so instead of `@EnableWebSocketMessageBroker` we'll have to add a patched Configuration instead.
Again, let's only enable VT-backed scheduler if the global setting is set so.
By the way, this `messageBrokerTaskScheduler` is what prevents `TaskScheduling/ExecutionAutoConfiguration` from
adding the `applicationTaskExecutor` and/or `taskScheduler`.

There is also a PT for MySQL's connections cleaner, but we don't have any control over it.

Other things that I know of but don't demonstrate here:
- Neo4J's driver is built on top of Netty, not sure if it can/should be switched to VTs
    (although Micronaut has some [interesting development](https://micronaut.io/2025/06/30/transitioning-to-virtual-threads-using-the-micronaut-loom-carrier/)).
- AWS SDK has blocking clients backed by the blocking Apache Http Components (v4),
    that should work great with VTs; for async clients SDK uses max(8, NC) threads for executor, 5 for scheduler.
- Twilio uses blocking Apache Http Components (v4) as well.

Any Q?

A word on limiting concurrency. The approach, which Hikari's team is promoting, is that there should be no more connections
to than twice the number of cores on the DB server. Meaning, that's often just enough to load up the underlying storage.
Since "underlying storage" here is just RAM, I'm using only the number of connections equal to the number of cores.
However, the app communicates with ElasticSearch and Echo server, so it can potentially handle more requests at the same time, 
than there are available DB connections. To reflect this possibility, we can allow more requests to be accepted 
and let them (maybe) wait at the DB pool rather than in the network stack. Ideally, the process of accepting requests
should be influenced by a backpressure propagated from the request-fulfilling subsystems, but I don't think there are
means to implement it yet. I've tried to do something like this for the parallel echo test on Project Reactor 
but failed to achieve the desired effect.

Now, let's run various tests that verify the correctness of the endpoints with different threading.

Note that instead of all those separate pools, there is an NC-sized ForkJoinPool of carrier threads
(controlled by `-Djdk.virtualThreadScheduler.parallelism`), an unblocker, a Master NIO Poller, and unparkers (for timers,
 controlled by `-Djdk.virtualThreadScheduler.timerQueues`, defaults to `max(1, highestOneBit(NC/4)`).

So, is there a benefit of using Virtual Threads in such a small project? I've done some stress testing 
and checked the resource usage. To make it more noticeable, I've reduced the number of JDK infrastructure threads:
```
-XX:CICompilerCount=6 \
-XX:ConcGCThreads=2 \
-XX:ParallelGCThreads=6 \
-XX:G1ConcRefinementThreads=6
```

I was launching the app against an empty database, performing one round of "warm-up" stress testing
(which also gets something in the ES index), measuring RSS and the number of threads.
These were followed by another round of stress testing and measurements.

I have no idea why it is always that the last three-four WS test invocations fail, no matter the threading.
If only the WS test is repeated, the last 12 connections fail.

1/3 fewer threads at peak, 1/4 at idle, the heap is the same size (RSS is larger, but varies greatly from a run to run).

We can check that Jetty executed most requests on the same Virtual Thread that accepted them
([EPC style](https://jetty.org/docs/jetty/12/programming-guide/arch/threads.html)),
which is expected to be better performance-wise, as the request doesn't switch cores. At least not until it begins to block.

Q?

Tips on using VTs.
1. [Check the official guide](https://docs.oracle.com/en/java/javase/24/core/virtual-threads.html#GUID-8AEDDBE6-F783-4D77-8786-AC5A79F517C0).
2. Use arrow lambdas when spawning new threads to get the source line in the stack trace.
3. Replace pool sizing with a semaphore.
4. A running VT doesn't keep JVM from stopping. Join the executor or otherwise keep "main" waiting for an interrupt.
5. Use v24+ to avoid problems around synchronized keyword.
6. Use v25+ to be able to get VTs in thread dumps.
7. Reschedule a fixed delay task manually: `finally {TaskScheduler#schedule(Runnable task, Instant startTime)}`.
8. Constrain the number of DB connections. Don't keep them open while communicating with other services.
9. Use [ScopedValues](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/ScopedValue.html) (stable in v25+)
    instead of ThreadLocals. They allow clean rebinding, are compatible with `jakarta.servlet.Filter`,
    but not with `org.springframework.web.servlet.HandlerInterceptor`
    or `org.springframework.web.context.request.WebRequestInterceptor`.

I've also made a Node.js-promises echo-test example. It has one interesting property:
if I ask it to make 100_000 requests, it cannot reach the stage of actually sending one.
Well, there are only so many ports for connections.

