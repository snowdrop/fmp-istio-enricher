package me.snowdrop.cloud.fabric8;

public enum ProxyArgs {
    RELEASE_0_2_12("0.2.12","proxy,sidecar" +
                            "-v,2," +
                            "--configPath,/etc/istio/proxy," +
                            "--binaryPath,/usr/local/bin/envoy," +
                            "--serviceCluster,APP_CLUSTER_NAME," +
                            "--drainDuration,45s," +
                            "--parentShutdownDuration,1m0s," +
                            "--discoveryAddress,istio-pilot.ISTIO_NAMESPACE:8080," +
                            "--discoveryRefreshDelay," +
                            "--discoveryRefreshDelay,1s," +
                            "--zipkinAddress,zipkin.ISTIO_NAMESPACE:9411," +
                            "--connectTimeout,10s," +
                            "--statsdUdpAddress,istio-mixer.ISTIO_NAMESPACE:9125," +
                            "--proxyAdminPort,15000"),

    RELEASE_0_3_0("0.3.0","proxy,sidecar" +
                           "-v,2," +
                           "--configPath,/etc/istio/proxy," +
                           "--binaryPath,/usr/local/bin/envoy," +
                           "--serviceCluster,APP_CLUSTER_NAME," +
                           "--drainDuration,45s," +
                           "--parentShutdownDuration,1m0s," +
                           "--discoveryAddress,istio-pilot.ISTIO_NAMESPACE:8080," +
                           "--discoveryRefreshDelay," +
                           "--discoveryRefreshDelay,1s," +
                           "--zipkinAddress,zipkin.ISTIO_NAMESPACE:9411," +
                           "--connectTimeout,10s," +
                           "--statsdUdpAddress,istio-mixer.ISTIO_NAMESPACE:9125," +
                           "--proxyAdminPort,15000," +
                           "controlPlaneAuthPolicy,NONE");

    private final String version;
    private final String args;

    ProxyArgs(String version, String args) {
        this.version = version;
        this.args = args;
    }
    public String getArgs() {
        return args;
    }

    public static String findByRelease(String release){
        for(ProxyArgs v : values()){
            if( v.version.equals(release)){
                return v.args;
            }
        }
        return null;
    }
}
