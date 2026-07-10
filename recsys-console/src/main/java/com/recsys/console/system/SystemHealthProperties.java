package com.recsys.console.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "recsys.console.health")
public class SystemHealthProperties {

    private int connectTimeoutMs = 300;
    private int readTimeoutMs = 500;
    private List<Target> targets = new ArrayList<>();

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public List<Target> getTargets() {
        return targets;
    }

    public void setTargets(List<Target> targets) {
        this.targets = targets == null ? new ArrayList<>() : targets;
    }

    public static class Target {
        private String service;
        private String name;
        private String kind = "app";
        private String url;
        private String passiveStatus;
        private String passiveMessage;

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getPassiveStatus() {
            return passiveStatus;
        }

        public void setPassiveStatus(String passiveStatus) {
            this.passiveStatus = passiveStatus;
        }

        public String getPassiveMessage() {
            return passiveMessage;
        }

        public void setPassiveMessage(String passiveMessage) {
            this.passiveMessage = passiveMessage;
        }
    }
}
