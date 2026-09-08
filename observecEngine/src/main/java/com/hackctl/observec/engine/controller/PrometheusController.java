package com.hackctl.observec.engine.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hackctl.observec.engine.service.PrometheusService;

@RestController
@RequestMapping("/prometheus")
public class PrometheusController {

    private PrometheusService prometheusService;

    public PrometheusController(PrometheusService prometheusService) {
        this.prometheusService = prometheusService;
    }

    @GetMapping("/jobs")
    public String getPrometheusJobs() {
        return prometheusService.getPrometheusJobs();
    }

    @PutMapping("/job")
    public String putPrometheusJobs(@RequestBody String jobs) {
        return prometheusService.putPrometheusJobs(jobs);
    }

    @DeleteMapping("/jobs")
    public String deletePrometheusJobs(@RequestBody String jobs) {
        return prometheusService.deletePrometheusJobs(jobs);
    }

    @PostMapping("/jobs")
    public String postPrometheusJobs(@RequestBody String jobs) {
        return prometheusService.postPrometheusJobs(jobs);
    }

    @GetMapping("/rules")
    public String getPrometheusRuleFiles() {
        return prometheusService.getPrometheusRuleFiles();
    }

    @PutMapping("/rule")
    public String putPrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.putPrometheusRuleFiles(ruleFiles);
    }

    @DeleteMapping("/rules")
    public String deletePrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.deletePrometheusRuleFiles(ruleFiles);
    }

    @PostMapping("/rules")
    public String postPrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.postPrometheusRuleFiles(ruleFiles);
    }

    @GetMapping("/rule-directories")
    public String getPrometheusRuleDirectories() {
        return prometheusService.getPrometheusRuleDirectories();
    }

    @PostMapping("/rule-directories")
    public String postPrometheusRuleDirectory(@RequestBody String directoryPath) {
        return prometheusService.postPrometheusRuleDirectory(directoryPath);
    }

    @DeleteMapping("/rule-directories")
    public String deletePrometheusRuleDirectory(@RequestBody String directoryPath) {
        return prometheusService.deletePrometheusRuleDirectory(directoryPath);
    }

    @GetMapping("/service-files")
    public String getPrometheusServiceDiscoveryFiles() {
        return prometheusService.getPrometheusServiceDiscoveryFiles();
    }

    @PutMapping("/service-file")
    public String putPrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.putPrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    @DeleteMapping("/service-files")
    public String deletePrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.deletePrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    @PostMapping("/service-files")
    public String postPrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.postPrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    @GetMapping("/service-directories")
    public String getPrometheusServiceDiscoveryDirectories() {
        return prometheusService.getPrometheusServiceDiscoveryDirectories();
    }

    @PostMapping("/service-directories")
    public String postPrometheusServiceDiscoveryDirectory(@RequestBody String directoryPath) {
        return prometheusService.postPrometheusServiceDiscoveryDirectory(directoryPath);
    }

    @DeleteMapping("/service-directories")
    public String deletePrometheusServiceDiscoveryDirectory(@RequestBody String directoryPath) {
        return prometheusService.deletePrometheusServiceDiscoveryDirectory(directoryPath);
    }

    @GetMapping("/kube-configs")
    public String getKubeConfigs() {
        return prometheusService.getKubeConfigs();
    }

    @PostMapping("/kube-config")
    public String uploadKubeConfig(
            @RequestParam("name") String name,
            @RequestParam("file") MultipartFile file) {
        try {
            return prometheusService.uploadKubeConfig(name, file.getBytes());
        } catch (java.io.IOException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read uploaded kubeconfig", exception);
        }
    }

    @DeleteMapping("/kube-configs")
    public String deleteKubeConfigs(@RequestBody String kubeConfigNames) {
        return prometheusService.deleteKubeConfigs(kubeConfigNames);
    }

    @GetMapping("/status")
    public String getPrometheusStatus() {
        return prometheusService.getPrometheusStatus();
    }

    @GetMapping("/config")
    public String getPrometheusConfig() {
        return prometheusService.getPrometheusConfig();
    }

    @GetMapping("/logs")
    public String getPrometheusLogs() {
        return prometheusService.getPrometheusLogs();
    }

    @PostMapping("/reload")
    public String reloadPrometheus() {
        return prometheusService.reloadPrometheus();
    }

    @PostMapping("/enable")
    public String enablePrometheus() {
        return prometheusService.enablePrometheus();
    }

    @PostMapping("/disable")
    public String disablePrometheus() {
        return prometheusService.disablePrometheus();
    }
}
