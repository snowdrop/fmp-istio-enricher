package me.snowdrop.cloud.fabric8;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.handler.DeploymentHandler;
import io.fabric8.maven.core.handler.HandlerHub;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.fabric8.openshift.api.model.*;
import me.snowdrop.istio.api.model.v1.mesh.MeshConfig;
import me.snowdrop.istio.api.model.v1.mesh.ProxyConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 * <p>
 * TODO: once f8-m-p model has initContainers model , then annotations can be moved to templateSpec
 *
 * @author kameshs
 * @author charles moulliard
 */
public class IstioEnricher extends BaseEnricher {

    private static final String ISTIO_ANNOTATION_STATUS = "injected-version-releng@0d29a2c0d15f-VERSION-998e0e00d375688bcb2af042fc81a60ce5264009";
    private final DeploymentHandler deployHandler;
    private String clusterName;
    private KubernetesClient kubeClient;

    // Available configuration keys
    private enum Config implements Configs.Key {
        name("name"),
        enableCoreDump("yes"),
        istioVersion("0.2.12"),
        istioNamespace("istio-system"),
        istioConfigMapName("istio"),
        alpineVersion("3.5"),
        proxyName("istio-proxy"),
        proxyDockerImageName("docker.io/istio/proxy_debug"),
        proxyImageStreamName("proxy_debug"),
        initName("istio-init"),
        initDockerImageName("docker.io/istio/proxy_init"),
        initImageStreamName("proxy_init"),
        coreDumpName("enable-core-dump"),
        coreDumpDockerImageName("alpine"),
        coreDumpImageStreamName("alpine"),
        imagePullPolicy("IfNotPresent"),
        replicaCount("1");

        public String def() {
            return d;
        }

        Config(String d) {
            this.d = d;
        }

        private final String d;
    }

    public IstioEnricher(EnricherContext buildContext) {
        super(buildContext, "fmp-istio-enricher");
        HandlerHub handlerHub = new HandlerHub(buildContext.getProject());
        deployHandler = handlerHub.getDeploymentHandler();
        kubeClient = new ClusterAccess(getConfig(Config.istioNamespace)).createDefaultClient(log);
    }

    /*
     * Result generated should be
     *
       apiVersion: v1
       kind: DeploymentConfig
       metadata:
         annotations:
           sidecar.istio.io/status: >-
             injected-version-releng@0d29a2c0d15f-0.2.12-998e0e00d375688bcb2af042fc81a60ce5264009
         name: helloworld
       spec:
         replicas: 0
         template:
           metadata:
             annotations:
               sidecar.istio.io/status: >-
                 injected-version-releng@0d29a2c0d15f-0.2.12-998e0e00d375688bcb2af042fc81a60ce5264009
             labels:
               app: helloworld
               version: v1
           spec:
             containers:
               - image: istio/examples-helloworld-v1
                 imagePullPolicy: IfNotPresent
                 name: helloworld
                 ports:
                   - containerPort: 5000
                     protocol: TCP
                 resources:
                   requests:
                     cpu: 100m
                 terminationMessagePath: /dev/termination-log
                 terminationMessagePolicy: File
               - args:
                   - proxy
                   - sidecar
                   - '-v'
                   - '2'
                   - '--configPath'
                   - /etc/istio/proxy
                   - '--binaryPath'
                   - /usr/local/bin/envoy
                   - '--serviceCluster'
                   - helloworld
                   - '--drainDuration'
                   - 45s
                   - '--parentShutdownDuration'
                   - 1m0s
                   - '--discoveryAddress'
                   - 'istio-pilot.istio-system:8080'
                   - '--discoveryRefreshDelay'
                   - 1s
                   - '--zipkinAddress'
                   - 'zipkin.istio-system:9411'
                   - '--connectTimeout'
                   - 10s
                   - '--statsdUdpAddress'
                   - 'istio-mixer.istio-system:9125'
                   - '--proxyAdminPort'
                   - '15000'
                 env:
                   - name: POD_NAME
                     valueFrom:
                       fieldRef:
                         apiVersion: v1
                         fieldPath: metadata.name
                   - name: POD_NAMESPACE
                     valueFrom:
                       fieldRef:
                         apiVersion: v1
                         fieldPath: metadata.namespace
                   - name: INSTANCE_IP
                     valueFrom:
                       fieldRef:
                         apiVersion: v1
                         fieldPath: status.podIP
                 # Proxy - Container
                 image: 'docker.io/istio/proxy_debug:0.2.12'
                 imagePullPolicy: IfNotPresent
                 name: istio-proxy
                 resources: {}
                 securityContext:
                   privileged: true
                   readOnlyRootFilesystem: false
                   runAsUser: 1337
                 terminationMessagePath: /dev/termination-log
                 terminationMessagePolicy: File
                 volumeMounts:
                   - mountPath: /etc/istio/proxy
                     name: istio-envoy
                   - mountPath: /etc/certs/
                     name: istio-certs
                     readOnly: true
             dnsPolicy: ClusterFirst
             initContainers:
               - args:
                   - '-p'
                   - '15001'
                   - '-u'
                   - '1337'
                 image: 'docker.io/istio/proxy_init:0.2.12'
                 imagePullPolicy: IfNotPresent
                 name: istio-init
                 resources: {}
                 securityContext:
                   capabilities:
                     add:
                       - NET_ADMIN
                   privileged: true
                 terminationMessagePath: /dev/termination-log
                 terminationMessagePolicy: File
               - args:
                   - '-c'
                   - >-
                     sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t &&
                     ulimit -c unlimited
                 command:
                   - /bin/sh
                 image: alpine
                 imagePullPolicy: IfNotPresent
                 name: enable-core-dump
                 resources: {}
                 securityContext:
                   privileged: true
                 terminationMessagePath: /dev/termination-log
                 terminationMessagePolicy: File
             restartPolicy: Always
             schedulerName: default-scheduler
             securityContext: {}
             terminationGracePeriodSeconds: 30
             volumes:
               - emptyDir:
                   medium: Memory
                   sizeLimit: '0'
                 name: istio-envoy
               - name: istio-certs
                 secret:
                   defaultMode: 420
                   optional: true
                   secretName: istio.default
    */

    @Override
    public void addMissingResources(KubernetesListBuilder builder) {
        // first check that we actually know the requested Istio version
        final String istioVersion = getConfig(Config.istioVersion);
        final String proxyArgsTemplate = ProxyArgs.findByRelease(istioVersion);
        if(proxyArgsTemplate == null) {
            throw new IllegalArgumentException("Unknown Istio release: " + istioVersion);
        }

        clusterName = getConfig(Config.name, MavenUtil.createDefaultResourceName(getProject()));

        final MeshConfig meshConfig = fetchConfigMap(kubeClient, getConfig(Config.istioNamespace));
        final ProxyConfig config = meshConfig.getDefaultConfig();

        // replace placeholders in proxy args template
        final String proxyArgs = String.format(proxyArgsTemplate, clusterName, config.getDiscoveryAddress(), config.getZipkinAddress(), config.getStatsdUdpAddress());
        List<String> sidecarArgs = Arrays.asList(proxyArgs.split(","));

        builder.accept(new TypedVisitor<PodSpecBuilder>() {
            public void visit(PodSpecBuilder podSpecBuilder) {

                    podSpecBuilder
                            // Add Istio Proxy
                            .addNewContainer()
                              .withName(getConfig(Config.proxyName))
                              .withResources(new ResourceRequirements())
                              .withTerminationMessagePath("/dev/termination-log")
                              .withImage(getConfig(Config.proxyImageStreamName))
                              .withImagePullPolicy(getConfig(Config.imagePullPolicy))
                              .withArgs(sidecarArgs)
                              .withEnv(proxyEnvVars())
                              .withSecurityContext(new SecurityContextBuilder()
                                    .withRunAsUser(1337l)
                                    .withPrivileged(true)
                                    .withReadOnlyRootFilesystem(false)
                                    .build())
                              .withVolumeMounts(istioVolumeMounts())
                            .endContainer()
                            // Specify Istio volumes
                            .withVolumes(istioVolumes())
                            // Add Istio Init container and Core Dump if enabled
                            .withInitContainers(populateInitContainers());
                }
        });

        // Add Missing triggers
        builder.accept(new TypedVisitor<DeploymentConfigBuilder>() {
            public void visit(DeploymentConfigBuilder deploymentConfigBuilder) {
                deploymentConfigBuilder
                   .editOrNewSpec()
                     // Add Istio Side car annotation
                     .editOrNewTemplate()
                       .editOrNewMetadata()
                         .addToAnnotations("sidecar.istio.io/status", ISTIO_ANNOTATION_STATUS.replace("VERSION", istioVersion))
                       .endMetadata()
                     .endTemplate()
                     // Specify the replica count
                     .withReplicas(Integer.parseInt(getConfig(Config.replicaCount)))
                     .withTriggers(populateTriggers(istioVersion))
                   .endSpec();
            }
        });
        // TODO - Check if it already exists before to add it to the Kubernetes List
        // Add ImageStreams about Istio Proxy, Istio Init and Core Dump
        builder.addAllToImageStreamItems(istioImageStream(istioVersion)).build();
    }

    private List<DeploymentTriggerPolicy> populateTriggers(String istioVersion) {
        List<DeploymentTriggerPolicy> triggers =  new ArrayList<>();
        DeploymentTriggerPolicyBuilder trigger = new DeploymentTriggerPolicyBuilder();

        // Add Istio Init Image
        trigger.withType("ImageChange")
               .withNewImageChangeParams()
                 .withAutomatic(true)
                 .withNewFrom()
                   .withKind("ImageStreamTag")
                   .withName(getConfig(Config.initImageStreamName) + ":" + istioVersion)
                 .endFrom()
                 .withContainerNames(getConfig(Config.initName))
               .endImageChangeParams()
               .build();
        triggers.add(trigger.build());


        // Add Istio Proxy Image
        trigger.withType("ImageChange")
               .withNewImageChangeParams()
                 .withAutomatic(true)
                 .withNewFrom()
                   .withKind("ImageStreamTag")
                   .withName(getConfig(Config.proxyImageStreamName) + ":" + istioVersion)
                 .endFrom()
                 .withContainerNames(getConfig(Config.proxyName))
               .endImageChangeParams()
               .build();
        triggers.add(trigger.build());

        // Add Core Dump image if enableCoreDump
        if ("yes".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
            trigger.withType("ImageChange")
                   .withNewImageChangeParams()
                     .withAutomatic(true)
                     .withNewFrom()
                       .withKind("ImageStreamTag")
                       .withName(clusterName + ":latest")
                      .endFrom()
                      .withContainerNames("spring-boot")
                   .endImageChangeParams()
                   .build();
            triggers.add(trigger.build());
        }

        return triggers;
    }

    protected List<Container> populateInitContainers() {
        List<Container> initcontainerList = new ArrayList<>();

        // Add Istio container which setup IPTABLES
        initcontainerList.add(istioInitContainer());

        if ("yes".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
            initcontainerList.add(coreDumpInitContainer());
        }
        return initcontainerList;
    }

    private MeshConfig fetchConfigMap(KubernetesClient kubeClient, String namespace) {

        final String configMapName = getConfig(Config.istioConfigMapName);
        ConfigMap map = kubeClient.configMaps().withName(configMapName).get();

        final YAMLMapper mapper = new YAMLMapper();
        final String meshConfigAsString = map.getData().get("mesh");
        if(meshConfigAsString != null) {
            try {
                final MeshConfig meshConfig = mapper.readValue(meshConfigAsString, MeshConfig.class);

                return meshConfig;
            } catch (IOException e) {
                throw new IllegalArgumentException("Couldn't parse Istio Mesh configuration", e);
            }
        } else {
            throw new IllegalArgumentException("Couldn't find an Istio Mesh configuration in "
                    + configMapName + " ConfigMap in namespace " + namespace);
        }
    }

    private static final Function<String, Properties> KEY_VALUE_TO_PROPERTIES = s -> {
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(s.getBytes()));
            return properties;
        } catch (IOException e) {
            throw new IllegalArgumentException();
        }
    };

    private static final Function<Properties, Map<String,String>> PROPERTIES_TO_MAP = p -> p.entrySet().stream()
            .collect(Collectors.toMap(
                    e -> String.valueOf(e.getKey()),
                    e -> String.valueOf(e.getValue())));


    /*
     *
       kind: ImageStream
       metadata:
         generation: 1
         name: proxy_debug
         namespace: demo
       spec:
         tags:
         - from:
             kind: DockerImage
             name: docker.io/istio/proxy_debug:0.2.12
           generation: 1
           name: latest
           referencePolicy:
             type: Source
     */
    private List<ImageStream> istioImageStream(String istioVersion) {
        List<ImageStream> imageStreams = new ArrayList<>();
        ImageStreamBuilder imageStreamBuilder = new ImageStreamBuilder();
        imageStreamBuilder
                .withNewMetadata()
                  .withName(getConfig(Config.initImageStreamName))
                .endMetadata()

                .withNewSpec()
                  .addNewTag()
                  .withNewFrom()
                    .withKind("DockerImage")
                    .withName(getConfig(Config.initDockerImageName) + ":" + istioVersion)
                  .endFrom()
                  .withName(istioVersion)
                  .endTag()
                .endSpec()
                .build();
        imageStreams.add(imageStreamBuilder.build());

        if ("yes".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
            imageStreamBuilder = new ImageStreamBuilder();
            imageStreamBuilder
                 .withNewMetadata()
                   .withName(getConfig(Config.coreDumpImageStreamName))
                 .endMetadata()

                 .withNewSpec()
                   .addNewTag()
                   .withNewFrom()
                     .withKind("DockerImage")
                     .withName(getConfig(Config.coreDumpDockerImageName) + ":" + getConfig(Config.alpineVersion))
                   .endFrom()
                   .withName(getConfig(Config.alpineVersion))
                   .endTag()
                 .endSpec()
                 .build();
            imageStreams.add(imageStreamBuilder.build());
        }

        imageStreamBuilder = new ImageStreamBuilder();
        imageStreamBuilder
                .withNewMetadata()
                  .withName(getConfig(Config.proxyImageStreamName))
                .endMetadata()

                .withNewSpec()
                  .addNewTag()
                  .withNewFrom()
                    .withKind("DockerImage")
                    .withName(getConfig(Config.proxyDockerImageName) + ":" + istioVersion)
                  .endFrom()
                  .withName(istioVersion)
                  .endTag()
                .endSpec()
                .build();
        imageStreams.add(imageStreamBuilder.build());

        return imageStreams;
    }

    protected Container istioInitContainer() {
        /*
          .put("name", "istio-init")
          .put("image", getConfig(Config.initImageStreamName))
          .put("imagePullPolicy", "IfNotPresent")
          .put("resources", new JsonObject())
          .put("terminationMessagePath", "/dev/termination-log")
          .put("terminationMessagePolicy", "File")
          .put("args", new JsonArray()
              .add("-p")
              .add("15001")
              .add("-u")
              .add("1337"))
          .put("securityContext",
              new JsonObject()
                  .put("capabilities",
                      new JsonObject()
                          .put("add", new JsonArray().add("NET_ADMIN")))
                  .put("privileged",true));
         */

        return new ContainerBuilder()
                .withName(getConfig(Config.initName))
                .withImage(getConfig(Config.initImageStreamName))
                .withImagePullPolicy("IfNotPresent")
                .withTerminationMessagePath("/dev/termination-log")
                .withTerminationMessagePolicy("File")
                .withArgs("-p", "15001", "-u", "1337")
                .withSecurityContext(new SecurityContextBuilder()
                        .withPrivileged(true)
                        .withCapabilities(new CapabilitiesBuilder()
                                .addToAdd("NET_ADMIN")
                                .build())
                        .build())
                .build();
    }

    protected Container coreDumpInitContainer() {
        /* Enable Core Dump
         *  args:
         *   - '-c'
         *   - >-
         *     sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t &&
         *     ulimit -c unlimited
         * command:
         *   - /bin/sh
         * image: alpine
         * imagePullPolicy: IfNotPresent
         * name: enable-core-dump
         * resources: {}
         * securityContext:
         *   privileged: true
         * terminationMessagePath: /dev/termination-log
         * terminationMessagePolicy: File
         */
        return new ContainerBuilder()
                .withName(getConfig(Config.coreDumpName))
                .withImage(getConfig(Config.coreDumpImageStreamName))
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh")
                .withArgs("-c", "sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t && ulimit -c unlimited")
                .withTerminationMessagePath("/dev/termination-log")
                .withTerminationMessagePolicy("File")
                .withSecurityContext(new SecurityContextBuilder()
                        .withPrivileged(true)
                        .build())
                .build();
    }

    /**
     * Generate the volumes to be mounted
     *
     * @return - list of {@link VolumeMount}
     */
    protected List<VolumeMount> istioVolumeMounts() {
        List<VolumeMount> volumeMounts = new ArrayList<>();

        VolumeMountBuilder istioProxyVolume = new VolumeMountBuilder();
        istioProxyVolume
                .withMountPath("/etc/istio/proxy")
                .withName("istio-envoy")
                .build();

        VolumeMountBuilder istioCertsVolume = new VolumeMountBuilder();
        istioCertsVolume
                .withMountPath("/etc/certs")
                .withName("istio-certs")
                .withReadOnly(true)
                .build();

        volumeMounts.add(istioProxyVolume.build());
        volumeMounts.add(istioCertsVolume.build());
        return volumeMounts;
    }

    /**
     * Generate the volumes
     *
     * @return - list of {@link Volume}
     */
    protected List<Volume> istioVolumes() {
        List<Volume> volumes = new ArrayList<>();

        VolumeBuilder empTyVolume = new VolumeBuilder();
        empTyVolume.withEmptyDir(new EmptyDirVolumeSourceBuilder()
                .withMedium("Memory")
                .build())
                .withName("istio-envoy")
                .build();

        VolumeBuilder secretVolume = new VolumeBuilder();
        secretVolume
                .withName("istio-certs")
                .withSecret(new SecretVolumeSourceBuilder()
                        .withSecretName("istio.default")
                        .withDefaultMode(420)
                        .build())
                .build();

        volumes.add(empTyVolume.build());
        volumes.add(secretVolume.build());
        return volumes;
    }

    /**
     * The method to return list of environment variables that will be needed for Istio proxy
     *
     * @return - list of {@link EnvVar}
     */
    protected List<EnvVar> proxyEnvVars() {
        List<EnvVar> envVars = new ArrayList<>();

        //POD_NAME
        EnvVarSource podNameVarSource = new EnvVarSource();
        podNameVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.name"));
        envVars.add(new EnvVar("POD_NAME", null, podNameVarSource));

        //POD_NAMESPACE
        EnvVarSource podNamespaceVarSource = new EnvVarSource();
        podNamespaceVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.namespace"));
        envVars.add(new EnvVar("POD_NAMESPACE", null, podNamespaceVarSource));

        //POD_IP
        EnvVarSource podIpVarSource = new EnvVarSource();
        podIpVarSource.setFieldRef(new ObjectFieldSelector(null, "status.podIP"));
        envVars.add(new EnvVar("INSTANCE_IP", null, podIpVarSource));

        return envVars;
    }

}
