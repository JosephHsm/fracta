package com.fracta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FractaApplication {

    public static void main(String[] args) {
        SpringApplication.run(FractaApplication.class, args);
    }
}
