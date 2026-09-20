package com.smartbancs.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.smartbancs")
@EnableJpaRepositories(basePackages = "com.smartbancs.infra.repository")
@EntityScan(basePackages = "com.smartbancs.infra.persistence")
public class SmartBancsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartBancsApplication.class, args);
    }
}