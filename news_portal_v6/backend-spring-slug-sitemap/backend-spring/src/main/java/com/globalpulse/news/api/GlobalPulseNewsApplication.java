package com.globalpulse.news.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.globalpulse.news")
@EnableJpaRepositories(basePackages = "com.globalpulse.news.db")
@EntityScan(basePackages = "com.globalpulse.news.db")
@EnableScheduling
public class GlobalPulseNewsApplication {
  public static void main(String[] args) {
    SpringApplication.run(GlobalPulseNewsApplication.class, args);
  }
}
