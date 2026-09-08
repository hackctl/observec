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

    // return all the prometheus scrape jobs
    @GetMapping("/jobs")
    public String getPrometheusJobs() {
        return prometheusService.getPrometheusJobs();
    }

    // update a job
    @PutMapping("/job")
    public String putPrometheusJobs(@RequestBody String jobs) {
        return prometheusService.putPrometheusJobs(jobs);
    }

    // delete a job
    @DeleteMapping("/jobs")
    public String deletePrometheusJobs(@RequestBody String jobs) {
        return prometheusService.deletePrometheusJobs(jobs);
    }

    // add a job
    @PostMapping("/jobs")
    public String postPrometheusJobs(@RequestBody String jobs) {
        return prometheusService.postPrometheusJobs(jobs);
    }

    // return all the prometheus rule files
    @GetMapping("/rules")
    public String getPrometheusRuleFiles() {
        return prometheusService.getPrometheusRuleFiles();
    }

    // update a rule file
    @PutMapping("/rule")
    public String putPrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.putPrometheusRuleFiles(ruleFiles);
    }

    // delete a rule file
    @DeleteMapping("/rules")
    public String deletePrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.deletePrometheusRuleFiles(ruleFiles);
    }

    // add a rule file
    @PostMapping("/rules")
    public String postPrometheusRuleFiles(@RequestBody String ruleFiles) {
        return prometheusService.postPrometheusRuleFiles(ruleFiles);
    }

    // return empty rule file directories
    @GetMapping("/rule-directories")
    public String getPrometheusRuleDirectories() {
        return prometheusService.getPrometheusRuleDirectories();
    }

    // create a rule file directory
    @PostMapping("/rule-directories")
    public String postPrometheusRuleDirectory(@RequestBody String directoryPath) {
        return prometheusService.postPrometheusRuleDirectory(directoryPath);
    }

    // delete an empty rule file directory
    @DeleteMapping("/rule-directories")
    public String deletePrometheusRuleDirectory(@RequestBody String directoryPath) {
        return prometheusService.deletePrometheusRuleDirectory(directoryPath);
    }

    // return all the prometheus service discovery files
    @GetMapping("/service-files")
    public String getPrometheusServiceDiscoveryFiles() {
        return prometheusService.getPrometheusServiceDiscoveryFiles();
    }

    // update a service discovery file
    @PutMapping("/service-file")
    public String putPrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.putPrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    // delete a service discovery file
    @DeleteMapping("/service-files")
    public String deletePrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.deletePrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    // add a service discovery file
    @PostMapping("/service-files")
    public String postPrometheusServiceDiscoveryFiles(@RequestBody String serviceDiscoveryFiles) {
        return prometheusService.postPrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);
    }

    // return empty service discovery directories
    @GetMapping("/service-directories")
    public String getPrometheusServiceDiscoveryDirectories() {
        return prometheusService.getPrometheusServiceDiscoveryDirectories();
    }

    // create a service discovery directory
    @PostMapping("/service-directories")
    public String postPrometheusServiceDiscoveryDirectory(@RequestBody String directoryPath) {
        return prometheusService.postPrometheusServiceDiscoveryDirectory(directoryPath);
    }

    // delete an empty service discovery directory
    @DeleteMapping("/service-directories")
    public String deletePrometheusServiceDiscoveryDirectory(@RequestBody String directoryPath) {
        return prometheusService.deletePrometheusServiceDiscoveryDirectory(directoryPath);
    }

    // return all uploaded kubeconfig files (names relative to kubeConfigs dir)
    @GetMapping("/kube-configs")
    public String getKubeConfigs() {
        return prometheusService.getKubeConfigs();
    }

    // upload a kubeconfig file into kubeConfigs dir (multipart: name + file)
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

    // delete kubeconfig files, body [ "<kubeConfigName>", ... ]
    @DeleteMapping("/kube-configs")
    public String deleteKubeConfigs(@RequestBody String kubeConfigNames) {
        return prometheusService.deleteKubeConfigs(kubeConfigNames);
    }

    // return the status of the prometheus
    @GetMapping("/status")
    public String getPrometheusStatus() {
        return prometheusService.getPrometheusStatus();
    }

    // return the full prometheus config as yaml
    @GetMapping("/config")
    public String getPrometheusConfig() {
        return prometheusService.getPrometheusConfig();
    }

    // return recent prometheus logs
    @GetMapping("/logs")
    public String getPrometheusLogs() {
        return prometheusService.getPrometheusLogs();
    }

    // reload the prometheus on demand
    @PostMapping("/reload")
    public String reloadPrometheus() {
        return prometheusService.reloadPrometheus();
    }

    // enable/start prometheus on demand — jar-first
    @PostMapping("/enable")
    public String enablePrometheus() {
        return prometheusService.enablePrometheus();
    }

    // disable/stop prometheus on demand — jar-first
    @PostMapping("/disable")
    public String disablePrometheus() {
        return prometheusService.disablePrometheus();
    }
}
