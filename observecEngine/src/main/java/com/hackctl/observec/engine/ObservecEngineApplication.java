package com.hackctl.observec.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ObservecEngineApplication {

    public static void main(String[] args) {
        // previously done by init.sh — now in-jar so `java -jar` works anywhere
        com.hackctl.observec.engine.component.ObservecDataInitializer.ensureLayout();
        SpringApplication.run(ObservecEngineApplication.class, args);
    }
}