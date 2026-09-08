package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackctl.observec.engine.model.Monitor;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

@Component
public class MonitorStore {

    private final String storePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Monitor> monitors = new LinkedHashMap<>();

    @Autowired(required = false)
    @Lazy
    private GitService gitService;

    public MonitorStore(
            @Value("${observec.monitor.store-path:/observec/observecData/monitors/monitors.json}") String storePath,
            ObjectMapper objectMapper) {
        this.storePath = storePath;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        File file = new File(storePath);
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, monitors);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to init monitor store at " + storePath, e);
            }
            return;
        }
        try {
            if (file.length() == 0) {
                return;
            }
            Map<String, Monitor> loaded = objectMapper.readValue(file,
                    new TypeReference<Map<String, Monitor>>() {});
            if (loaded != null) {
                monitors.putAll(loaded);
                if (migrateLegacyTlsPaths()) {
                    persist();
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to load monitors from " + storePath, e);
        }
    }

    public Map<String, Monitor> list() {
        lock.readLock().lock();
        try {
            return new LinkedHashMap<>(monitors);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Monitor get(String id) {
        lock.readLock().lock();
        try {
            return monitors.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean contains(String id) {
        lock.readLock().lock();
        try {
            return monitors.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(Monitor monitor) {
        lock.writeLock().lock();
        try {
            monitors.put(monitor.getId(), monitor);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String id) {
        lock.writeLock().lock();
        try {
            monitors.remove(id);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void saveAll(Map<String, Monitor> all) {
        lock.writeLock().lock();
        try {
            monitors.clear();
            if (all != null) monitors.putAll(all);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // rewrite absolute tls paths left over from blackbox/blackboxData/tls location
    private boolean migrateLegacyTlsPaths() {
        boolean changed = false;
        for (Monitor m : monitors.values()) {
            if (m.getTlsCaFile() != null && m.getTlsCaFile().contains("blackbox/blackboxData/tls")) {
                m.setTlsCaFile(m.getTlsCaFile().replace("blackbox/blackboxData/tls", "blackbox/tls"));
                changed = true;
            }
            if (m.getTlsCertFile() != null && m.getTlsCertFile().contains("blackbox/blackboxData/tls")) {
                m.setTlsCertFile(m.getTlsCertFile().replace("blackbox/blackboxData/tls", "blackbox/tls"));
                changed = true;
            }
            if (m.getTlsKeyFile() != null && m.getTlsKeyFile().contains("blackbox/blackboxData/tls")) {
                m.setTlsKeyFile(m.getTlsKeyFile().replace("blackbox/blackboxData/tls", "blackbox/tls"));
                changed = true;
            }
        }
        return changed;
    }

    private void persist() {
        try {
            File file = new File(storePath);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, monitors);
            if (gitService != null) gitService.autoCommit("chore(monitor): update monitors.json");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to persist monitors to " + storePath, e);
        }
    }

    public String getStorePath() {
        return storePath;
    }
}
