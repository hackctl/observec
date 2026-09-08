package com.hackctl.observec.engine.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hackctl.observec.engine.service.AlertmanagerService;

@RestController
@RequestMapping("/alertmanager")
public class AlertmanagerController {

    private final AlertmanagerService alertmanagerService;

    public AlertmanagerController(AlertmanagerService alertmanagerService) {
        this.alertmanagerService = alertmanagerService;
    }

    @PostMapping("/webhook")
    public void receiveWebhook(@RequestBody String payload) {
        alertmanagerService.handleWebhook(payload);
    }

    @GetMapping("/notifications")
    public String getNotifications() {
        return alertmanagerService.getRecentNotifications();
    }

    @GetMapping("/status")
    public String getStatus() {
        return alertmanagerService.getAlertmanagerStatus();
    }

    @GetMapping("/config")
    public String getConfig() {
        return alertmanagerService.getAlertmanagerConfig();
    }

    @GetMapping("/logs")
    public String getLogs() {
        return alertmanagerService.getAlertmanagerLogs();
    }

    @GetMapping("/receivers")
    public String getReceivers() {
        return alertmanagerService.getReceivers();
    }

    @GetMapping("/routes")
    public String getRoutes() {
        return alertmanagerService.getRoutes();
    }

    @PutMapping("/routes")
    public String putRoutes(@RequestBody String routes) {
        return alertmanagerService.putRoutes(routes);
    }

    @GetMapping("/inhibit-rules")
    public String getInhibitRules() {
        return alertmanagerService.getInhibitRules();
    }

    @PutMapping("/inhibit-rules")
    public String putInhibitRules(@RequestBody String inhibitRules) {
        return alertmanagerService.putInhibitRules(inhibitRules);
    }

    @PostMapping("/receivers")
    public String postReceivers(@RequestBody String receivers) {
        return alertmanagerService.postReceivers(receivers);
    }

    @PutMapping("/receiver")
    public String putReceiver(@RequestBody String receiver) {
        return alertmanagerService.putReceiver(receiver);
    }

    @DeleteMapping("/receivers")
    public String deleteReceivers(@RequestBody String receivers) {
        return alertmanagerService.deleteReceivers(receivers);
    }

    // enable/start alertmanager on demand — jar-first
    @PostMapping("/enable")
    public String enableAlertmanager() {
        return alertmanagerService.enableAlertmanager();
    }

    // reload alertmanager (used by reload buttons near Enable)
    @PostMapping("/reload")
    public String reloadAlertmanager() {
        return alertmanagerService.enableAlertmanager();
    }

    // disable/stop alertmanager on demand — jar-first
    @PostMapping("/disable")
    public String disableAlertmanager() {
        return alertmanagerService.disableAlertmanager();
    }
}
