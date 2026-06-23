package com.lm.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LmRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmRoutingApplication.class, args);
    }
}
