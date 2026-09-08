package com.hackctl.observec.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ObservecEngineApplication {

    public static void main(String[] args) {

        com.hackctl.observec.engine.component.ObservecDataInitializer.ensureLayout();
        SpringApplication.run(ObservecEngineApplication.class, args);
    }
}
