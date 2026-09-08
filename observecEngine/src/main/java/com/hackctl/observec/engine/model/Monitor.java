package com.hackctl.observec.engine.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Monitor {

    private String id;
    private String name;
    private String target;
    private String type;
    private String method;
    private String body;
    private String contentType;
    private List<Integer> expectedStatusCodes;
    private String keyword;
    private Boolean keywordInverted;
    private String interval;
    private String timeout;
    private Integer retries;
    private Boolean followRedirects;
    private Boolean enabled;
    private String receiver;
    private String slackWebhookUrl;
    private String slackChannel;
    private String description;
    private Map<String, String> labels;
    private String preferredIpProtocol;
    private Boolean ipProtocolFallback;
    private List<String> validHttpVersions;
    private Boolean tlsEnabled;
    private String tlsServerName;
    private String tlsCaFile;
    private String tlsCertFile;
    private String tlsKeyFile;
    private String targets;
    private Instant createdAt;
    private Instant updatedAt;

    public Monitor() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public List<Integer> getExpectedStatusCodes() { return expectedStatusCodes; }
    public void setExpectedStatusCodes(List<Integer> expectedStatusCodes) { this.expectedStatusCodes = expectedStatusCodes; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public Boolean getKeywordInverted() { return keywordInverted; }
    public void setKeywordInverted(Boolean keywordInverted) { this.keywordInverted = keywordInverted; }

    public String getInterval() { return interval; }
    public void setInterval(String interval) { this.interval = interval; }

    public String getTimeout() { return timeout; }
    public void setTimeout(String timeout) { this.timeout = timeout; }

    public Integer getRetries() { return retries; }
    public void setRetries(Integer retries) { this.retries = retries; }

    public Boolean getFollowRedirects() { return followRedirects; }
    public void setFollowRedirects(Boolean followRedirects) { this.followRedirects = followRedirects; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }

    public String getSlackChannel() { return slackChannel; }
    public void setSlackChannel(String slackChannel) { this.slackChannel = slackChannel; }

    public String getPreferredIpProtocol() { return preferredIpProtocol; }
    public void setPreferredIpProtocol(String preferredIpProtocol) { this.preferredIpProtocol = preferredIpProtocol; }

    public Boolean getIpProtocolFallback() { return ipProtocolFallback; }
    public void setIpProtocolFallback(Boolean ipProtocolFallback) { this.ipProtocolFallback = ipProtocolFallback; }

    public List<String> getValidHttpVersions() { return validHttpVersions; }
    public void setValidHttpVersions(List<String> validHttpVersions) { this.validHttpVersions = validHttpVersions; }

    public Boolean getTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(Boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

    public String getTlsServerName() { return tlsServerName; }
    public void setTlsServerName(String tlsServerName) { this.tlsServerName = tlsServerName; }

    public String getTlsCaFile() { return tlsCaFile; }
    public void setTlsCaFile(String tlsCaFile) { this.tlsCaFile = tlsCaFile; }

    public String getTlsCertFile() { return tlsCertFile; }
    public void setTlsCertFile(String tlsCertFile) { this.tlsCertFile = tlsCertFile; }

    public String getTlsKeyFile() { return tlsKeyFile; }
    public void setTlsKeyFile(String tlsKeyFile) { this.tlsKeyFile = tlsKeyFile; }

    public String getTargets() { return targets; }
    public void setTargets(String targets) { this.targets = targets; }
}
