package com.zippay;

import org.springframework.boot.SpringApplication;

public class TestZippayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ZippayBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
