package com.smartbancs.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "aiWorkerExecutor")
    public Executor aiWorkerExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ai-worker-");
        executor.setVirtualThreads(true);
        return executor;
    }
}