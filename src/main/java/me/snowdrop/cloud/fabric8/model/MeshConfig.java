package me.snowdrop.cloud.fabric8.model;

public class MeshConfig {

    public boolean enableTracing = false;
    public String mixerAddress = "istio-mixer.istio-system:15004";
    public String ingressService = "istio-ingress";
    public String rdsRefreshDelay = "1s";
    public DefaultConfig defaultConfig;

    public DefaultConfig getDefaultConfig() {
        return defaultConfig == null ? new DefaultConfig() : defaultConfig;
    }

    public void setDefaultConfig(DefaultConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

}
