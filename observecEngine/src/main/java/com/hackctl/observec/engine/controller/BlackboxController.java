package com.hackctl.observec.engine.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hackctl.observec.engine.service.BlackboxService;

@RestController
@RequestMapping("/blackbox")
public class BlackboxController {

    private BlackboxService blackboxService;

    public BlackboxController(BlackboxService blackboxService) {
        this.blackboxService = blackboxService;
    }

    @GetMapping("/modules")
    public String getBlackboxModules() {
        return blackboxService.getBlackboxModules();
    }

    @PutMapping("/module")
    public String putBlackboxModules(@RequestBody String modules) {
        return blackboxService.putBlackboxModules(modules);
    }

    @DeleteMapping("/modules")
    public String deleteBlackboxModules(@RequestBody String modules) {
        return blackboxService.deleteBlackboxModules(modules);
    }

    @PostMapping("/modules")
    public String postBlackboxModules(@RequestBody String modules) {
        return blackboxService.postBlackboxModules(modules);
    }

    @GetMapping("/status")
    public String getBlackboxStatus() {
        return blackboxService.getBlackboxStatus();
    }

    @GetMapping("/config")
    public String getBlackboxConfig() {
        return blackboxService.getBlackboxConfig();
    }

    @GetMapping("/logs")
    public String getBlackboxLogs() {
        return blackboxService.getBlackboxLogs();
    }

    @PostMapping("/module/{name}/tls")
    public String uploadTlsMaterial(
            @PathVariable("name") String name,
            @RequestParam(value = "ca", required = false) MultipartFile ca,
            @RequestParam(value = "cert", required = false) MultipartFile cert,
            @RequestParam(value = "key", required = false) MultipartFile key) {
        return blackboxService.uploadTlsMaterial(name, ca, cert, key);
    }

    @GetMapping("/probes")
    public String getRecentProbes() {
        return blackboxService.getRecentProbes();
    }

    @GetMapping("/probe-log")
    public String getProbeLog(@org.springframework.web.bind.annotation.RequestParam("id") String id) {
        return blackboxService.getProbeLog(id);
    }

    @PostMapping("/reload")
    public String reloadBlackbox() {
        return blackboxService.reloadBlackbox();
    }

    @PostMapping("/enable")
    public String enableBlackbox() {
        return blackboxService.enableBlackbox();
    }

    @PostMapping("/disable")
    public String disableBlackbox() {
        return blackboxService.disableBlackbox();
    }

}
