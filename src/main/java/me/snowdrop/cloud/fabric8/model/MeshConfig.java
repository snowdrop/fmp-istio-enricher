package me.snowdrop.cloud.fabric8.model;

public class MeshConfig {

    // Edit this list to avoid using mTLS to connect to these services.
    // Typically, these are control services (e.g kubernetes API server) that don't have Istio sidecar
    // to transparently terminate mTLS authentication.
    public String[] mtlsExcludedServices = {"kubernetes.default.svc.cluster.local"};

    // Set the following variable to true to disable policy checks by the Mixer.
    // Note that metrics will still be reported to the Mixer.
    public boolean disablePolicyChecks = false;

    // Set enableTracing to false to disable request tracing.
    public boolean enableTracing = true;

    public String mixerAddress = "istio-mixer.istio-system:15004";
    public String ingressService = "istio-ingress";

    // Along with discoveryRefreshDelay, this setting determines how
    // frequently should Envoy fetch and update its internal configuration
    // from Istio Pilot. Lower refresh delay results in higher CPU
    // utilization and potential performance loss in exchange for faster
    // convergence. Tweak this value according to your setup.
    public String rdsRefreshDelay = "1s";

    public DefaultConfig defaultConfig;

    public DefaultConfig getDefaultConfig() {
        return defaultConfig == null ? new DefaultConfig() : defaultConfig;
    }

    public void setDefaultConfig(DefaultConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

}
