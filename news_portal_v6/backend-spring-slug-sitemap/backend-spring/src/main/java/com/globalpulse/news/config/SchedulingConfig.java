package com.globalpulse.news.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * SchedulingConfig — configura um pool de threads dedicado para os workers agendados.
 *
 * Sem isso, o Spring usa apenas 1 thread para todos os @Scheduled,
 * causando fila e competição entre AiSummaryWorker, EntityWorker e MapNarrativeWorker.
 *
 * Com pool de 4 threads:
 *  - Thread 1: AiSummaryWorker (processa artigos com Groq)
 *  - Thread 2: EntityWorker (extrai entidades com Groq)
 *  - Thread 3: MapNarrativeWorker (análise narrativa com Groq)
 *  - Thread 4: NewsIngestionJob + outros jobs (coleta RSS, scraper)
 *
 * O GroqRateLimiter garante que apenas 20 req/min chegam à Groq,
 * independente de quantos threads estejam rodando.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("crivo-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
