package me.snowdrop.cloud.fabric8.model;

/*
 *  NOTE: If you change any values in this section, make sure to make
 *  the same changes in start up args (See ProxyArgs enum)
 */
public class DefaultConfig {

    /*
     * This setting determines how frequently should Envoy fetch and update its internal configuration
     * from Istio Pilot. Lower refresh delay results in higher CPU utilization and potential performance
     * loss in exchange for faster convergence. Tweak this value according to your setup.
     */
    private String discoveryRefreshDelay = "1s";

    // TCP connection timeout between Envoy & the application, and between Envoys.
    private String connectTimeout = "10s";

    // Where should envoy's configuration be stored in the istio-proxy container
    private String configPath = "/etc/istio/proxy";
    private String binaryPath = "/usr/local/bin/envoy";

    // The pseudo service name used for Envoy.
    private String serviceCluster = "istio-proxy";

    // These settings that determine how long an old Envoy
    // process should be kept alive after an occasional reload.
    private String drainDuration = "45s";
    private String parentShutdownDuration = "1m0s";

    /*
     *  Port where Envoy listens (on local host) for admin commands
     *  You can exec into the istio-proxy container in a pod and
     *  curl the admin port (curl http://localhost:15000/) to obtain
     *  diagnostic information from Envoy. See
     *  https://lyft.github.io/envoy/docs/operations/admin.html
     *  for more details
     */
    private int proxyAdminPort = 15000;

    // Address where Istio Pilot service is running
    private String discoveryAddress = "istio-pilot.istio-system:15003";

    // Zipkin trace collector
    private String zipkinAddress = "zipkin.istio-system:9411";

    // Statsd metrics collector. Istio mixer exposes a UDP endpoint
    // to collect and convert statsd metrics into Prometheus metrics.
    private String statsdUdpAddress = "istio-mixer.istio-system:9125";

    // Enable mutual TLS authentication between
    // sidecars and istio control plane.
    private String controlPlaneAuthPolicy = "MUTUAL_TLS";

    public DefaultConfig() {}

    public String getDiscoveryRefreshDelay() {
        return discoveryRefreshDelay;
    }

    public void setDiscoveryRefreshDelay(String discoveryRefreshDelay) {
        this.discoveryRefreshDelay = discoveryRefreshDelay;
    }

    public String getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(String connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public String getServiceCluster() {
        return serviceCluster;
    }

    public void setServiceCluster(String serviceCluster) {
        this.serviceCluster = serviceCluster;
    }

    public String getDrainDuration() {
        return drainDuration;
    }

    public void setDrainDuration(String drainDuration) {
        this.drainDuration = drainDuration;
    }

    public String getParentShutdownDuration() {
        return parentShutdownDuration;
    }

    public void setParentShutdownDuration(String parentShutdownDuration) {
        this.parentShutdownDuration = parentShutdownDuration;
    }

    public int getProxyAdminPort() {
        return proxyAdminPort;
    }

    public void setProxyAdminPort(int proxyAdminPort) {
        this.proxyAdminPort = proxyAdminPort;
    }

    public String getDiscoveryAddress() {
        return discoveryAddress;
    }

    public void setDiscoveryAddress(String discoveryAddress) {
        this.discoveryAddress = discoveryAddress;
    }

    public String getZipkinAddress() {
        return zipkinAddress;
    }

    public void setZipkinAddress(String zipkinAddress) {
        this.zipkinAddress = zipkinAddress;
    }

    public String getStatsdUdpAddress() {
        return statsdUdpAddress;
    }

    public void setStatsdUdpAddress(String statsdUdpAddress) {
        this.statsdUdpAddress = statsdUdpAddress;
    }

    public String getControlPlaneAuthPolicy() {
        return controlPlaneAuthPolicy;
    }

    public void setControlPlaneAuthPolicy(String controlPlaneAuthPolicy) {
        this.controlPlaneAuthPolicy = controlPlaneAuthPolicy;
    }
}
