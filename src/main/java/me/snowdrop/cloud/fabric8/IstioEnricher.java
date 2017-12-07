package me.snowdrop.cloud.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSetBuilder;
import io.fabric8.maven.core.handler.DeploymentHandler;
import io.fabric8.maven.core.handler.HandlerHub;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.fabric8.openshift.api.model.*;

import java.util.ArrayList;
import java.util.List;

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

    // Available configuration keys
    private enum Config implements Configs.Key {
        name("name"),
        enabled("yes"),
        istioVersion("0.2.12"),
        istioNamespace("istio-system"),
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

        clusterName = getConfig(Config.name, MavenUtil.createDefaultResourceName(getProject()));

        String[] proxyArgs = ProxyArgs.findByRelease(getConfig(Config.istioVersion)).split(",");
        List<String> sidecarArgs = new ArrayList<>();
        for (int i = 0; i < proxyArgs.length; i++) {
            //cluster name defaults to app name a.k.a controller name
            if ("APP_CLUSTER_NAME".equalsIgnoreCase(proxyArgs[i])) {
                sidecarArgs.add(clusterName);
            } else {
                sidecarArgs.add(proxyArgs[i]);
            }

            // Use istio namespace
            if ("ISTIO_NAMESPACE".equalsIgnoreCase(proxyArgs[i])) {
                sidecarArgs.add(getConfig(Config.istioNamespace));
            } else {
                sidecarArgs.add(proxyArgs[i]);
            }
        }


        builder.accept(new TypedVisitor<PodSpecBuilder>() {
            public void visit(PodSpecBuilder podSpecBuilder) {
                if ("yes".equalsIgnoreCase(getConfig(Config.enabled))) {
                    log.info("Adding Istio proxy, init and core-dump");

                    podSpecBuilder
                            // Add Istio Proxy, Volumes and Secret
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
                            .withVolumes(istioVolumes())
                            // Add Istio Init container and Core Dump
                            .withInitContainers(istioInitContainer(), coreDumpInitContainer());
                }
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
                        .addToAnnotations("sidecar.istio.io/status", ISTIO_ANNOTATION_STATUS.replace("VERSION", getConfig(Config.istioVersion)))
                        .endMetadata()
                        .endTemplate()
                        // Specify the replica count
                        .withReplicas(Integer.parseInt(getConfig(Config.replicaCount)))
                        //.withTriggers()
                        .addNewTrigger()
                        .withType("ImageChange")
                        .withNewImageChangeParams()
                        .withAutomatic(true)
                        .withNewFrom()
                        .withKind("ImageStreamTag")
                        .withName(getConfig(Config.initImageStreamName) + ":" + getConfig(Config.istioVersion))
                        .endFrom()
                        .withContainerNames(getConfig(Config.initName))
                        .endImageChangeParams()
                        .endTrigger()
                        .addNewTrigger()
                        .withType("ImageChange")
                        .withNewImageChangeParams()
                        .withAutomatic(true)
                        .withNewFrom()
                        .withKind("ImageStreamTag")
                        .withName(getConfig(Config.coreDumpImageStreamName) + ":" + getConfig(Config.alpineVersion))
                        .endFrom()
                        .withContainerNames("enable-core-dump")
                        .endImageChangeParams()
                        .endTrigger()
                        .addNewTrigger()
                        .withType("ImageChange")
                        .withNewImageChangeParams()
                        .withAutomatic(true)
                        .withNewFrom()
                        .withKind("ImageStreamTag")
                        .withName(getConfig(Config.proxyImageStreamName) + ":" + getConfig(Config.istioVersion))
                        .endFrom()
                        .withContainerNames(getConfig(Config.proxyName))
                        .endImageChangeParams()
                        .endTrigger()
                        .addNewTrigger()
                        .withType("ImageChange")
                        .withNewImageChangeParams()
                        .withAutomatic(true)
                        .withNewFrom()
                        .withKind("ImageStreamTag")
                        .withName(clusterName + ":latest")
                        .endFrom()
                        .withContainerNames("spring-boot")
                        .endImageChangeParams()
                        .endTrigger()
                        .endSpec();
            }
        });
        // TODO - Check if it already exists before to add it to the Kubernetes List
        // Add ImageStreams about Istio Proxy, Istio Init and Core Dump
        builder.addAllToImageStreamItems(istioImageStream()).build();
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
    protected List<ImageStream> istioImageStream() {
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
                .withName(getConfig(Config.initDockerImageName) + ":" + getConfig(Config.istioVersion))
                .endFrom()
                .withName(getConfig(Config.istioVersion))
                .endTag()
                .endSpec()
                .build();
        imageStreams.add(imageStreamBuilder.build());

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

        imageStreamBuilder = new ImageStreamBuilder();
        imageStreamBuilder
                .withNewMetadata()
                .withName(getConfig(Config.proxyImageStreamName))
                .endMetadata()

                .withNewSpec()
                .addNewTag()
                .withNewFrom()
                .withKind("DockerImage")
                .withName(getConfig(Config.proxyDockerImageName) + ":" + getConfig(Config.istioVersion))
                .endFrom()
                .withName(getConfig(Config.istioVersion))
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

    private List<ImageConfiguration> istioImages() {
        List<ImageConfiguration> images = new ArrayList<>();
        ImageConfiguration.Builder builder = new ImageConfiguration.Builder();
        builder
                .name(getConfig(Config.proxyImageStreamName))
                .buildConfig(new BuildImageConfiguration.Builder()
                        .from(getConfig(Config.proxyDockerImageName))
                        .build());
        images.add(builder.build());

        builder = new ImageConfiguration.Builder();
        builder
                .name(getConfig(Config.initImageStreamName))
                .buildConfig(new BuildImageConfiguration.Builder()
                        .from(getConfig(Config.initDockerImageName))
                        .build());
        images.add(builder.build());

        builder = new ImageConfiguration.Builder();
        builder
                .name(getConfig(Config.coreDumpImageStreamName))
                .buildConfig(new BuildImageConfiguration.Builder()
                        .from(getConfig(Config.coreDumpDockerImageName))
                        .build());
        images.add(builder.build());

        return images;
    }

}
