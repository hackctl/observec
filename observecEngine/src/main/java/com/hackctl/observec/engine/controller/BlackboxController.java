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

    // return all the blackbox modules
    @GetMapping("/modules")
    public String getBlackboxModules() {
        return blackboxService.getBlackboxModules();
    }

    // update a module 
    @PutMapping("/module")
    public String putBlackboxModules(@RequestBody String modules) {
        return blackboxService.putBlackboxModules(modules);
    }

    // delete a module
    @DeleteMapping("/modules")
    public String deleteBlackboxModules(@RequestBody String modules) {
        return blackboxService.deleteBlackboxModules(modules);
    }

    // add a module
    @PostMapping("/modules")
    public String postBlackboxModules(@RequestBody String modules) {
        return blackboxService.postBlackboxModules(modules);
    }

    // return the status of the blackbox
    @GetMapping("/status")
    public String getBlackboxStatus() {
        return blackboxService.getBlackboxStatus();
    }

    // return the full blackbox config as yaml
    @GetMapping("/config")
    public String getBlackboxConfig() {
        return blackboxService.getBlackboxConfig();
    }

    // return recent blackbox exporter logs
    @GetMapping("/logs")
    public String getBlackboxLogs() {
        return blackboxService.getBlackboxLogs();
    }

    // upload tls material files for a module
    @PostMapping("/module/{name}/tls")
    public String uploadTlsMaterial(
            @PathVariable("name") String name,
            @RequestParam(value = "ca", required = false) MultipartFile ca,
            @RequestParam(value = "cert", required = false) MultipartFile cert,
            @RequestParam(value = "key", required = false) MultipartFile key) {
        return blackboxService.uploadTlsMaterial(name, ca, cert, key);
    }

    // recent probes from the blackbox exporter home page
    @GetMapping("/probes")
    public String getRecentProbes() {
        return blackboxService.getRecentProbes();
    }

    // debug logs of a single recent probe
    @GetMapping("/probe-log")
    public String getProbeLog(@org.springframework.web.bind.annotation.RequestParam("id") String id) {
        return blackboxService.getProbeLog(id);
    }

    // reload the blackbox exporter on demand
    @PostMapping("/reload")
    public String reloadBlackbox() {
        return blackboxService.reloadBlackbox();
    }

    // enable/start the blackbox exporter on demand — jar-first
    @PostMapping("/enable")
    public String enableBlackbox() {
        return blackboxService.enableBlackbox();
    }

    // disable/stop the blackbox exporter on demand — jar-first
    @PostMapping("/disable")
    public String disableBlackbox() {
        return blackboxService.disableBlackbox();
    }



}
