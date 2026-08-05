package com.fixbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FixBridge backend entry point.
 *
 * <p>FixBridge is a working name — brand identity, AI model names, pricing rules and provider keys
 * are all externalized configuration (see {@code application.yml} and {@code BrandProperties}),
 * never hard-coded.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
public class FixBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixBridgeApplication.class, args);
    }
}
