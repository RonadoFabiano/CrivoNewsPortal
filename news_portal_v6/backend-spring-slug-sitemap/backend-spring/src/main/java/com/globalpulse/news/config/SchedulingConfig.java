package com.globalpulse.news.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private final AppRuntimeProperties runtimeProperties;

    public SchedulingConfig(AppRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(runtimeProperties.getScheduling().getPoolSize());
        scheduler.setThreadNamePrefix(runtimeProperties.getScheduling().getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(runtimeProperties.getScheduling().getAwaitTerminationSeconds());
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}