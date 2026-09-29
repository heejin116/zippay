package com.zippay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZippayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZippayBackendApplication.class, args);
	}

}
