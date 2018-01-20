package me.snowdrop.cloud.fabric8;

public enum ProxyArgs {
    RELEASE_0_2_12("0.2.12",
                   "proxy,sidecar," +
                   "-v,2," +
                   "--configPath,/etc/istio/proxy," +
                   "--binaryPath,/usr/local/bin/envoy," +
                   "--serviceCluster,%s," +
                   "--drainDuration,45s," +
                   "--parentShutdownDuration,1m0s," +
                   "--discoveryAddress,%s," +
                   "--discoveryRefreshDelay,1s," +
                   "--zipkinAddress,%s," +
                   "--connectTimeout,10s," +
                   "--statsdUdpAddress,%s," +
                   "--proxyAdminPort,15000"),

    RELEASE_0_3_0("0.3.0", RELEASE_0_2_12.args + "," +
                  "--controlPlaneAuthPolicy,%s"),

    RELEASE_0_4_0("0.4.0",RELEASE_0_3_0.args);

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
