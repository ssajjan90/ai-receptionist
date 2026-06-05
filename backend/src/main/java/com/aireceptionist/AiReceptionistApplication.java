package com.aireceptionist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class AiReceptionistApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiReceptionistApplication.class, args);
    }
}
