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
import io.fabric8.utils.Strings;
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
        withDebugImage("true"),
        istioVersion("0.4.0"),
        istioNamespace("istio-system"),
        istioConfigMapName("istio"),
        alpineVersion("3.5"),
        controlPlaneAuthPolicy("NONE"),

        proxyName("istio-proxy"),
        proxyDockerImageName("docker.io/istio/proxy"),
        proxyImageStreamName("proxy"),

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
        final String proxyArgs = String.format(
                proxyArgsTemplate,
                clusterName,
                config.getDiscoveryAddress(),
                config.getZipkinAddress(),
                config.getStatsdUdpAddress(),
                config.getControlPlaneAuthPolicy()
        );
        List<String> sidecarArgs = Arrays.asList(proxyArgs.split(","));

        builder.accept(new TypedVisitor<PodSpecBuilder>() {
            public void visit(PodSpecBuilder podSpecBuilder) {
                    String serviceAccountName = getServiceAccountName(podSpecBuilder);
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
                            .withVolumes(istioVolumes(serviceAccountName))
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
                   .withName(istioImageName(getConfig(Config.proxyImageStreamName)) + ":" + istioVersion)
                 .endFrom()
                 .withContainerNames(getConfig(Config.proxyName))
               .endImageChangeParams()
               .build();
        triggers.add(trigger.build());

        // Add Core Dump image if enableCoreDump
        if ("true".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
            trigger.withType("ImageChange")
                   .withNewImageChangeParams()
                     .withAutomatic(true)
                     .withNewFrom()
                       .withKind("ImageStreamTag")
                       .withName(getConfig(Config.coreDumpImageStreamName) + ":" + getConfig(Config.alpineVersion))
                      .endFrom()
                    .withContainerNames("enable-core-dump")
                   .endImageChangeParams()
                   .build();
            triggers.add(trigger.build());
        }

        // Add Trigger to include the Microservice app
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

        return triggers;
    }

    protected List<Container> populateInitContainers() {
        List<Container> initcontainerList = new ArrayList<>();

        // Add Istio container which setup IPTABLES
        initcontainerList.add(istioInitContainer());

        if ("true".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
            initcontainerList.add(coreDumpInitContainer());
        }
        return initcontainerList;
    }

    private MeshConfig fetchConfigMap(KubernetesClient kubeClient, String namespace) {

        final String configMapName = getConfig(Config.istioConfigMapName);
        ConfigMap map = kubeClient.configMaps().withName(configMapName).get();

        if(map == null) {
            throw new IllegalArgumentException("Couldn't find an ConfigMap named "
                    + configMapName + " in namespace " + namespace + ". Are you sure Istio was installed correctly?");
        }

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

        if ("true".equalsIgnoreCase(getConfig(Config.enableCoreDump))) {
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
                  .withName(istioImageName(getConfig(Config.proxyImageStreamName)))
                .endMetadata()

                .withNewSpec()
                  .addNewTag()
                  .withNewFrom()
                    .withKind("DockerImage")
                    .withName(istioImageName(getConfig(Config.proxyDockerImageName)) + ":" + istioVersion)
                  .endFrom()
                  .withName(istioVersion)
                  .endTag()
                .endSpec()
                .build();
        imageStreams.add(imageStreamBuilder.build());

        return imageStreams;
    }

    private String getServiceAccountName(PodSpecBuilder podSpecBuilder) {
        if (Strings.isNotBlank(podSpecBuilder.getServiceAccountName())) {
            return podSpecBuilder.getServiceAccountName();
        } else if (Strings.isNotBlank(podSpecBuilder.getServiceAccount())) {
            return podSpecBuilder.getServiceAccount();
        }

        return "default";
    }

    protected String istioImageName(String dockerImage) {
        StringBuilder name = new StringBuilder(dockerImage);
        name = "true".equalsIgnoreCase(getConfig(Config.withDebugImage)) ? name.append("_debug") : name;
        return name.toString();
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
    protected List<Volume> istioVolumes(String serviceAccountName) {
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
                        .withSecretName("istio." + serviceAccountName)
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
