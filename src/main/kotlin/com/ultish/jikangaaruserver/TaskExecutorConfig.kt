package com.ultish.jikangaaruserver

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import java.util.concurrent.ThreadFactory

/**
 * This configures the SimpleAsyncTaskExecutor in Spring to use virtual threads.
 */
@Configuration
class TaskExecutorConfig {

    @Bean
    fun virtualThreadTaskExecutor(): SimpleAsyncTaskExecutor {
        val executor = SimpleAsyncTaskExecutor("virtual-thread-")
        executor.setThreadFactory(virtualThreadFactory())
        return executor
    }

    private fun virtualThreadFactory(): ThreadFactory {
        return Thread.ofVirtual().factory()
    }
}