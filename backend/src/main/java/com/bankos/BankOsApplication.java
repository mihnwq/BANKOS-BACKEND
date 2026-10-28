package com.bankos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankOsApplication {

    public static void main(String[] args)
    {
        SpringApplication.run(BankOsApplication.class, args);
    }
}
