package org.example.spring

import com.zaxxer.hikari.HikariDataSource
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.elasticsearch.client.RestClientBuilder
import org.springframework.beans.BeansException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.autoconfigure.elasticsearch.RestClientBuilderCustomizer
import org.springframework.boot.autoconfigure.http.client.ClientHttpRequestFactoryBuilderCustomizer
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.thread.Threading
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.task.TaskExecutor
import org.springframework.lang.NonNull
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.net.http.HttpClient
import java.util.concurrent.Executors

@Component
@ConditionalOnThreading(Threading.VIRTUAL)
class VtEnablingPostProcessor : BeanPostProcessor {
    @Throws(BeansException::class)
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        when (bean) {
            is HikariDataSource -> bean.threadFactory = Thread.ofVirtual().name("Hikari-", 1).factory()
            else -> {}
        }
        return bean
    }
}


@Component
@ConditionalOnThreading(Threading.VIRTUAL)
class VtEnablerConfigs(
    private val serverProperties: ServerProperties,
) :
    WebServerFactoryCustomizer<JettyServletWebServerFactory>,
    Ordered,
    ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder>,
    RestClientBuilderCustomizer
{
    /**
     * Can't change the thread in the maintenance scheduler, because it has to be passed via
     * [constructor][org.eclipse.jetty.server.Server],
     * and [Spring's factory][org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory.createServer] is private.
     * There will also be a [keep-alive thread][org.eclipse.jetty.util.thread.VirtualThreadPool._keepAlive].
     */
    override fun customize(factory: JettyServletWebServerFactory) {
        VirtualThreadPool()
            .apply {
                maxThreads = serverProperties.jetty.threads.max
                isTracking = true
                name = "Jetty-"
            }
            .let(factory::setThreadPool)
    }

    /**
     * Going after [Spring's customizer][org.springframework.boot.autoconfigure.web.embedded.JettyVirtualThreadsWebServerFactoryCustomizer.getOrder]
     * to use pool that can trigger AdaptiveExecutionStrategy to work more as ExecuteProduceConsume.
     */
    override fun getOrder(): Int {
        // After org.springframework.boot.autoconfigure.web.embedded.JettyVirtualThreadsWebServerFactoryCustomizer#getOrder
        return 2
    }

    /**
     * Will have a [Selector Manager PT][jdk.internal.net.http.HttpClientImpl.SelectorManager] anyway.
     */
    override fun customize(builder: JdkClientHttpRequestFactoryBuilder): JdkClientHttpRequestFactoryBuilder {
        return builder.withHttpClientCustomizer(this::configureJdkHttpClient)
    }

    private fun configureJdkHttpClient(builder: HttpClient.Builder) {
        Thread.ofVirtual().name("HttpClient-", 1).factory()
            .let(Executors::newThreadPerTaskExecutor)
            .let(builder::executor)
    }

    override fun customize(builder: RestClientBuilder) {}

    override fun customize(builder: HttpAsyncClientBuilder) {
        Thread.ofVirtual().name("ES-HTTP-", 1).factory()
            .let(builder::setThreadFactory)
        /*IOReactorConfig
            .custom()
            .setIoThreadCount(2)
            .build()
            .let(builder::setDefaultIOReactorConfig)*/
    }
}


@Configuration(proxyBeanMethods = false)
class SchedulerConfig {
    @Bean(
        name = [
            "taskScheduler",
            TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
            AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME
        ]
    )
    @ConditionalOnThreading(Threading.PLATFORM)
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder) = builder.build()!!

    /**
     * [Auto configuration][org.springframework.boot.autoconfigure.task.TaskSchedulingConfigurations.TaskSchedulerConfiguration]
     * doesn't create one when [something][DelegatingWebSocketMessageBrokerConfiguration.messageBrokerTaskScheduler] is injecting another scheduler.
     */
    @Bean(
        name = [
            "taskScheduler",
            TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
            AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME
        ]
    )
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun vtTaskScheduler(builder: SimpleAsyncTaskSchedulerBuilder) = builder.build()!!
}


@Configuration(proxyBeanMethods = false)
class CommonRestClientConfig {
    @Bean
    fun restClient(builder: RestClient.Builder): RestClient = builder.build()
}


/**
 * Replacement of [@EnableWebSocketMessageBroker][org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker]
 * to inject VTs.
 */
@Configuration(proxyBeanMethods = false)
class BrokerConfig : DelegatingWebSocketMessageBrokerConfiguration() {
    //Spring no longer likes overrides with @Primary. Probably due to native compilation efforts.
    @Bean(name = ["messageBrokerTaskScheduler", "messageBrokerSockJsTaskScheduler"])
    @NonNull
    override fun messageBrokerTaskScheduler(): TaskScheduler {
        if (Threading.VIRTUAL.isActive(applicationContext!!.environment)) {
            return SimpleAsyncTaskScheduler().apply {
                setVirtualThreads(true)
                threadNamePrefix = "MessageBroker-"
                phase = this@BrokerConfig.phase
            }
        }
        //This calls the same @Bean method, nothing to wrap here.
        @Suppress("SpringConfigurationProxyMethods")
        return super.messageBrokerTaskScheduler()
    }
}


@Configuration(proxyBeanMethods = false)
class StompConfig : WebSocketMessageBrokerConfigurer {
    private var messageBrokerTaskScheduler: ObjectProvider<TaskScheduler>? = null

    //Will come from BrokerConfig#messageBrokerTaskScheduler,
    //but BrokerConfig wants an instance of this WebSocketMessageBrokerConfigurer.
    @Autowired
    fun setMessageBrokerTaskScheduler(messageBrokerTaskScheduler: ObjectProvider<TaskScheduler>) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/only-me")
        registry
            //The first one is for 'SendTo', the other is for 'SendToUser'
            //when these annotations come without a value.
            //Otherwise, these are equivalent, i.e. to receive SendToUser from /queue
            //client still needs to subscribe with 'userDestination' prefix.
            .enableSimpleBroker("/topic", "/queue")
            .setTaskScheduler(messageBrokerTaskScheduler!!.getObject())
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry
            .addEndpoint("/ws")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        maybeConfigureExecutor(registration)
    }

    override fun configureClientOutboundChannel(registration: ChannelRegistration) {
        maybeConfigureExecutor(registration)
    }

    private fun maybeConfigureExecutor(registration: ChannelRegistration) {
        val executor = messageBrokerTaskScheduler!!.getObject()
        if (executor is TaskExecutor) {
            registration.executor(executor)
        }
    }
}
