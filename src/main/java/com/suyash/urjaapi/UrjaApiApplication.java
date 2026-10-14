package com.suyash.urjaapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // turns on @Scheduled, used to load and refresh the portal cache
public class UrjaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrjaApiApplication.class, args);
    }

}
