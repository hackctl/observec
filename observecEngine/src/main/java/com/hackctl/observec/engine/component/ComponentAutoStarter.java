package com.hackctl.observec.engine.component;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hackctl.observec.engine.service.AlertmanagerService;
import com.hackctl.observec.engine.service.BlackboxService;
import com.hackctl.observec.engine.service.PrometheusService;

/**
 * Starts every monitored component on engine boot so blackbox, prometheus
 * and alertmanager are enabled by default at all times. Each enable call
 * is idempotent (no-op when already running) and failures are logged
 * without blocking startup or the remaining components.
 */
@Component
@Order(Integer.MAX_VALUE)
public class ComponentAutoStarter implements ApplicationRunner {

    private final BlackboxService blackboxService;
    private final PrometheusService prometheusService;
    private final AlertmanagerService alertmanagerService;

    public ComponentAutoStarter(BlackboxService blackboxService,
            PrometheusService prometheusService,
            AlertmanagerService alertmanagerService) {
        this.blackboxService = blackboxService;
        this.prometheusService = prometheusService;
        this.alertmanagerService = alertmanagerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        enable("blackbox", () -> blackboxService.enableBlackbox());
        enable("prometheus", () -> prometheusService.enablePrometheus());
        enable("alertmanager", () -> alertmanagerService.enableAlertmanager());
    }

    private void enable(String name, Runnable starter) {
        try {
            starter.run();
            System.out.println("[observec-autostart] " + name + " enabled");
        } catch (Exception exception) {
            System.err.println("[observec-autostart] failed to enable " + name + ": " + exception.getMessage());
        }
    }
}
