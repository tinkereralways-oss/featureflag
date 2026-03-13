package com.paymentplatform.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SampleConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleConsumerApplication.class, args);
    }
}
