package com.aisw.kkori;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KkoriApplication {

    public static void main(String[] args) {
        SpringApplication.run(KkoriApplication.class, args);
    }

}
