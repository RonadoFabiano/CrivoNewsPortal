package com.globalpulse.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
public class AppRuntimeProperties {

    private final Ingestion ingestion = new Ingestion();
    private final Rss rss = new Rss();
    private final Scraper scraper = new Scraper();
    private final Lab lab = new Lab();
    private final Scheduling scheduling = new Scheduling();
    private final Ai ai = new Ai();

    public Ingestion getIngestion() { return ingestion; }
    public Rss getRss() { return rss; }
    public Scraper getScraper() { return scraper; }
    public Lab getLab() { return lab; }
    public Scheduling getScheduling() { return scheduling; }
    public Ai getAi() { return ai; }

    public static class Ingestion {
        private boolean enableRss = true;
        private int maxPerCycle = 20;
        private long initialDelayMs = 15000;
        private long fixedDelayMs = 300000;

        public boolean isEnableRss() { return enableRss; }
        public void setEnableRss(boolean enableRss) { this.enableRss = enableRss; }
        public int getMaxPerCycle() { return maxPerCycle; }
        public void setMaxPerCycle(int maxPerCycle) { this.maxPerCycle = maxPerCycle; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    }

    public static class Rss {
        private String defaultFeed = "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419";
        private boolean enabled = true;
        private long initialDelayMs = 5000;
        private long fixedDelayMs = 900000;
        private int categoryCacheTtlMinutes = 15;

        public String getDefaultFeed() { return defaultFeed; }
        public void setDefaultFeed(String defaultFeed) { this.defaultFeed = defaultFeed; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public int getCategoryCacheTtlMinutes() { return categoryCacheTtlMinutes; }
        public void setCategoryCacheTtlMinutes(int categoryCacheTtlMinutes) { this.categoryCacheTtlMinutes = categoryCacheTtlMinutes; }
    }

    public static class Scraper {
        private int fetchTimeoutMs = 20000;
        private int maxPerPortal = 0;
        private long fixedDelayMs = 900000;
        private int cacheTtlMinutes = 30;
        private List<String> activePortals = new ArrayList<>();

        public int getFetchTimeoutMs() { return fetchTimeoutMs; }
        public void setFetchTimeoutMs(int fetchTimeoutMs) { this.fetchTimeoutMs = fetchTimeoutMs; }
        public int getMaxPerPortal() { return maxPerPortal; }
        public void setMaxPerPortal(int maxPerPortal) { this.maxPerPortal = maxPerPortal; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public int getCacheTtlMinutes() { return cacheTtlMinutes; }
        public void setCacheTtlMinutes(int cacheTtlMinutes) { this.cacheTtlMinutes = cacheTtlMinutes; }
        public List<String> getActivePortals() { return activePortals; }
        public void setActivePortals(List<String> activePortals) { this.activePortals = activePortals; }
    }

    public static class Lab {
        private String onlySource = "";
        private boolean disableWorkers = false;

        public String getOnlySource() { return onlySource; }
        public void setOnlySource(String onlySource) { this.onlySource = onlySource; }
        public boolean isDisableWorkers() { return disableWorkers; }
        public void setDisableWorkers(boolean disableWorkers) { this.disableWorkers = disableWorkers; }
    }

    public static class Scheduling {
        private int poolSize = 4;
        private int awaitTerminationSeconds = 30;
        private String threadNamePrefix = "crivo-scheduler-";

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
        public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) { this.awaitTerminationSeconds = awaitTerminationSeconds; }
        public String getThreadNamePrefix() { return threadNamePrefix; }
        public void setThreadNamePrefix(String threadNamePrefix) { this.threadNamePrefix = threadNamePrefix; }
    }

    public static class Ai {
        private final Worker worker = new Worker();
        private final EntityWorker entityWorker = new EntityWorker();

        public Worker getWorker() { return worker; }
        public EntityWorker getEntityWorker() { return entityWorker; }

        public static class Worker {
            private boolean enabled = true;
            private long delayMs = 5000;
            private int maxChars = 5000;
            private int maxRetries = 3;
            private int batchSize = 3;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getDelayMs() { return delayMs; }
            public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
            public int getMaxChars() { return maxChars; }
            public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
            public int getMaxRetries() { return maxRetries; }
            public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
            public int getBatchSize() { return batchSize; }
            public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        }

        public static class EntityWorker {
            private boolean enabled = true;
            private long initialDelayMs = 75000;
            private long delayMs = 20000;
            private int batchSize = 2;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getInitialDelayMs() { return initialDelayMs; }
            public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
            public long getDelayMs() { return delayMs; }
            public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
            public int getBatchSize() { return batchSize; }
            public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        }
    }
}
