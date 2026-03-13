package com.paymentplatform.flagserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FeatureFlagServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeatureFlagServerApplication.class, args);
    }
}
