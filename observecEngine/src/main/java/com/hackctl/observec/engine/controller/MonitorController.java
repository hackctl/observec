package com.hackctl.observec.engine.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hackctl.observec.engine.service.MonitorService;

@RestController
@RequestMapping("/monitors")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    // list all monitors (Uptime-Kuma style overview)
    @GetMapping
    public String listMonitors() {
        return monitorService.listMonitors();
    }

    // aggregate status for dashboard (overall + per monitor health)
    @GetMapping("/status")
    public String getMonitorsStatus() {
        return monitorService.getMonitorsStatus();
    }

    // heartbeats bulk for dashboard heartbeat bars
    @GetMapping("/heartbeats")
    public String getAllHeartbeats() {
        return monitorService.getAllHeartbeats();
    }

    // table data with prometheus predefined queries (response time, last fail/success, uptime, ssl)
    @GetMapping("/table")
    public String getMonitorsTable(@RequestParam(value = "range", required = false) String range) {
        return monitorService.getMonitorsTable(range);
    }

    @GetMapping("/{id}")
    public String getMonitor(@PathVariable("id") String id) {
        return monitorService.getMonitor(id);
    }

    @GetMapping("/{id}/status")
    public String getMonitorStatus(@PathVariable("id") String id) {
        return monitorService.getMonitorStatus(id);
    }

    @GetMapping("/{id}/heartbeats")
    public String getMonitorHeartbeats(@PathVariable("id") String id) {
        return monitorService.getMonitorHeartbeats(id);
    }

    // create monitor - body is monitor JSON { name, target, type, interval, ... }
    @PostMapping
    public String createMonitor(@RequestBody String body) {
        return monitorService.createMonitor(body);
    }

    // update monitor
    @PutMapping("/{id}")
    public String updateMonitor(@PathVariable("id") String id, @RequestBody String body) {
        return monitorService.updateMonitor(id, body);
    }

    // delete single monitor
    @DeleteMapping("/{id}")
    public String deleteMonitor(@PathVariable("id") String id) {
        return monitorService.deleteMonitor(id);
    }

    // bulk delete - body [ "id1", "id2" ]
    @DeleteMapping
    public String deleteMonitors(@RequestBody String body) {
        return monitorService.deleteMonitorsBulk(body);
    }

    // pause = disable (removes job/rule, keeps store entry)
    @PostMapping("/{id}/pause")
    public String pauseMonitor(@PathVariable("id") String id) {
        return monitorService.pauseMonitor(id);
    }

    @PostMapping("/{id}/resume")
    public String resumeMonitor(@PathVariable("id") String id) {
        return monitorService.resumeMonitor(id);
    }

    // upload TLS material for monitor's blackbox module (same as blackbox but for monitor)
    @PostMapping("/{id}/tls")
    public String uploadMonitorTls(
            @PathVariable("id") String id,
            @RequestParam(value = "ca", required = false) MultipartFile ca,
            @RequestParam(value = "cert", required = false) MultipartFile cert,
            @RequestParam(value = "key", required = false) MultipartFile key) {
        return monitorService.uploadMonitorTls(id, ca, cert, key);
    }
}
