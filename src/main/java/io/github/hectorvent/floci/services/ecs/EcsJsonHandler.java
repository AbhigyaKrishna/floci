package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateTaskSetRequest;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.HealthCheck;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ListTasksRequest;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.RegisterTaskDefinitionRequest;
import io.github.hectorvent.floci.services.ecs.model.RunTaskRequest;
import io.github.hectorvent.floci.services.ecs.model.RuntimePlatform;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.UpdateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class EcsJsonHandler {

    private final EcsService service;
    private final EcsResponseWriter writer;
    private final ObjectMapper objectMapper;
    private final HostVolumePolicy hostVolumePolicy;

    @Inject
    public EcsJsonHandler(EcsService service, EcsResponseWriter writer, ObjectMapper objectMapper,
                          HostVolumePolicy hostVolumePolicy) {
        this.service = service;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.hostVolumePolicy = hostVolumePolicy;
    }

    /** Builds its own response writer, for callers that assemble the handler without CDI. */
    public EcsJsonHandler(EcsService service, ObjectMapper objectMapper, HostVolumePolicy hostVolumePolicy) {
        this(service, new EcsResponseWriter(service, objectMapper), objectMapper, hostVolumePolicy);
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            // Clusters
            case "CreateCluster" -> handleCreateCluster(request, region);
            case "DescribeClusters" -> handleDescribeClusters(request, region);
            case "ListClusters" -> handleListClusters(request, region);
            case "DeleteCluster" -> handleDeleteCluster(request, region);
            case "UpdateCluster" -> handleUpdateCluster(request, region);
            case "UpdateClusterSettings" -> handleUpdateClusterSettings(request, region);
            case "PutClusterCapacityProviders" -> handlePutClusterCapacityProviders(request, region);
            // Task Definitions
            case "RegisterTaskDefinition" -> handleRegisterTaskDefinition(request, region);
            case "DescribeTaskDefinition" -> handleDescribeTaskDefinition(request, region);
            case "ListTaskDefinitions" -> handleListTaskDefinitions(request, region);
            case "ListTaskDefinitionFamilies" -> handleListTaskDefinitionFamilies(request, region);
            case "DeregisterTaskDefinition" -> handleDeregisterTaskDefinition(request, region);
            case "DeleteTaskDefinitions" -> handleDeleteTaskDefinitions(request, region);
            // Tasks
            case "RunTask" -> handleRunTask(request, region);
            case "StartTask" -> handleStartTask(request, region);
            case "StopTask" -> handleStopTask(request, region);
            case "DescribeTasks" -> handleDescribeTasks(request, region);
            case "ListTasks" -> handleListTasks(request, region);
            case "UpdateTaskProtection" -> handleUpdateTaskProtection(request, region);
            case "GetTaskProtection" -> handleGetTaskProtection(request, region);
            // Services
            case "CreateService" -> handleCreateService(request, region);
            case "UpdateService" -> handleUpdateService(request, region);
            case "DeleteService" -> handleDeleteService(request, region);
            case "DescribeServices" -> handleDescribeServices(request, region);
            case "ListServices" -> handleListServices(request, region);
            case "ListServicesByNamespace" -> handleListServicesByNamespace(request, region);
            // Tags
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            // Account Settings
            case "PutAccountSetting" -> handlePutAccountSetting(request, region);
            case "PutAccountSettingDefault" -> handlePutAccountSettingDefault(request, region);
            case "DeleteAccountSetting" -> handleDeleteAccountSetting(request, region);
            case "ListAccountSettings" -> handleListAccountSettings(request, region);
            // Attributes
            case "PutAttributes" -> handlePutAttributes(request, region);
            case "DeleteAttributes" -> handleDeleteAttributes(request, region);
            case "ListAttributes" -> handleListAttributes(request, region);
            // Container Instances
            case "RegisterContainerInstance" -> handleRegisterContainerInstance(request, region);
            case "DeregisterContainerInstance" -> handleDeregisterContainerInstance(request, region);
            case "DescribeContainerInstances" -> handleDescribeContainerInstances(request, region);
            case "ListContainerInstances" -> handleListContainerInstances(request, region);
            case "UpdateContainerAgent" -> handleUpdateContainerAgent(request, region);
            case "UpdateContainerInstancesState" -> handleUpdateContainerInstancesState(request, region);
            // Capacity Providers
            case "CreateCapacityProvider" -> handleCreateCapacityProvider(request, region);
            case "UpdateCapacityProvider" -> handleUpdateCapacityProvider(request, region);
            case "DeleteCapacityProvider" -> handleDeleteCapacityProvider(request, region);
            case "DescribeCapacityProviders" -> handleDescribeCapacityProviders(request, region);
            // Task Sets
            case "CreateTaskSet" -> handleCreateTaskSet(request, region);
            case "UpdateTaskSet" -> handleUpdateTaskSet(request, region);
            case "DeleteTaskSet" -> handleDeleteTaskSet(request, region);
            case "DescribeTaskSets" -> handleDescribeTaskSets(request, region);
            case "UpdateServicePrimaryTaskSet" -> handleUpdateServicePrimaryTaskSet(request, region);
            // Service Deployments & Revisions
            case "DescribeServiceDeployments" -> handleDescribeServiceDeployments(request, region);
            case "ListServiceDeployments" -> handleListServiceDeployments(request, region);
            case "DescribeServiceRevisions" -> handleDescribeServiceRevisions(request, region);
            // Stubs
            case "SubmitTaskStateChange" -> handleSubmitTaskStateChange(request, region);
            case "SubmitContainerStateChange" -> handleSubmitContainerStateChange(request, region);
            case "SubmitAttachmentStateChanges" -> handleSubmitAttachmentStateChanges(request, region);
            case "DiscoverPollEndpoint" -> handleDiscoverPollEndpoint(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    private Response handleCreateCluster(JsonNode req, String region) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName(req.path("clusterName").asText(null));
        request.setTags(parseTagMap(req.path("tags")));
        request.setSettings(parseClusterSettings(req.path("settings")));
        EcsCluster cluster = service.createCluster(request, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleDescribeClusters(JsonNode req, String region) {
        List<String> ids = jsonArrayToList(req.path("clusters"));
        List<EcsCluster> found = service.describeClusters(ids, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(c -> arr.add(writer.clusterNode(c)));
        resp.set("clusters", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListClusters(JsonNode req, String region) {
        List<String> arns = service.listClusters(region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("clusterArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeleteCluster(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        EcsCluster cluster = service.deleteCluster(clusterId, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCluster(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateCluster(clusterRef, settings, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateClusterSettings(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateClusterSettings(clusterRef, settings, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handlePutClusterCapacityProviders(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<String> providers = jsonArrayToList(req.path("capacityProviders"));
        List<Map<String, Object>> defaultStrategy = parseRawObjectList(req.path("defaultCapacityProviderStrategy"));
        EcsCluster cluster = service.putClusterCapacityProviders(clusterRef, providers, defaultStrategy, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    // ── Task Definitions ──────────────────────────────────────────────────────

    private Response handleRegisterTaskDefinition(JsonNode req, String region) {
        RegisterTaskDefinitionRequest request = new RegisterTaskDefinitionRequest();
        request.setFamily(req.path("family").asText());
        request.setContainerDefinitions(parseContainerDefinitions(req.path("containerDefinitions")));
        request.setNetworkMode(parseEnum(req, "networkMode", NetworkMode.class));
        request.setCpu(req.has("cpu") ? req.path("cpu").asText() : null);
        request.setMemory(req.has("memory") ? req.path("memory").asText() : null);
        request.setTaskRoleArn(req.hasNonNull("taskRoleArn") ? req.path("taskRoleArn").asText() : null);
        request.setExecutionRoleArn(
                req.hasNonNull("executionRoleArn") ? req.path("executionRoleArn").asText() : null);
        request.setRequiresCompatibilities(jsonArrayToList(req.path("requiresCompatibilities")));
        request.setVolumes(parseVolumes(req.path("volumes")));
        request.setRuntimePlatform(parseRuntimePlatform(req.path("runtimePlatform")));
        request.setTags(parseTagMap(req.path("tags")));

        TaskDefinition td = service.registerTaskDefinition(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.describeTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitions(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        String status = req.has("status") ? req.path("status").asText() : null;
        List<String> arns = service.listTaskDefinitions(familyPrefix, status);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("taskDefinitionArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitionFamilies(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        List<String> families = service.listTaskDefinitionFamilies(familyPrefix);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        families.forEach(arr::add);
        resp.set("families", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeregisterTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.deregisterTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskDefinitions(JsonNode req, String region) {
        List<String> refs = jsonArrayToList(req.path("taskDefinitions"));
        List<TaskDefinition> deleted = service.deleteTaskDefinitions(refs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(td -> arr.add(writer.taskDefinitionNode(td)));
        resp.set("taskDefinitions", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    private Response handleRunTask(JsonNode req, String region) {
        RunTaskRequest request = new RunTaskRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setCount(req.path("count").asInt(1));
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setGroup(req.has("group") ? req.path("group").asText() : null);
        request.setStartedBy(req.has("startedBy") ? req.path("startedBy").asText() : null);
        request.setContainerOverrides(
                parseContainerOverrides(req.path("overrides").path("containerOverrides")));
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));

        List<EcsTask> launched = service.runTask(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(writer.taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleStartTask(JsonNode req, String region) {
        RunTaskRequest request = new RunTaskRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setContainerInstances(jsonArrayToList(req.path("containerInstances")));
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setGroup(req.has("group") ? req.path("group").asText() : null);
        request.setStartedBy(req.has("startedBy") ? req.path("startedBy").asText() : null);

        List<EcsTask> launched = service.startTask(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(writer.taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleStopTask(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String task = req.path("task").asText();
        String reason = req.has("reason") ? req.path("reason").asText() : null;

        EcsTask stopped = service.stopTask(cluster, task, reason, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("task", writer.taskNode(stopped));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTasks(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));
        List<EcsTask> found = service.describeTasks(cluster, taskRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(t -> arr.add(writer.taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListTasks(JsonNode req, String region) {
        ListTasksRequest request = new ListTasksRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setFamily(req.has("family") ? req.path("family").asText() : null);
        request.setDesiredStatus(req.has("desiredStatus") ? req.path("desiredStatus").asText() : null);
        request.setServiceName(req.has("serviceName") ? req.path("serviceName").asText() : null);

        List<String> arns = service.listTasks(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("taskArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleUpdateTaskProtection(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));
        boolean protectionEnabled = req.path("protectionEnabled").asBoolean(false);
        Integer expiresInMinutes = req.has("expiresInMinutes") ? req.path("expiresInMinutes").asInt() : null;

        List<ProtectedTask> result = service.updateTaskProtection(cluster, taskRefs, protectionEnabled,
                expiresInMinutes, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(pt -> arr.add(writer.protectedTaskNode(pt)));
        resp.set("protectedTasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleGetTaskProtection(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));

        List<ProtectedTask> result = service.getTaskProtection(cluster, taskRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(pt -> arr.add(writer.protectedTaskNode(pt)));
        resp.set("protectedTasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Services ──────────────────────────────────────────────────────────────

    private Response handleCreateService(JsonNode req, String region) {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setServiceName(req.path("serviceName").asText());
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setDesiredCount(req.path("desiredCount").asInt(1));
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setLoadBalancers(parseLoadBalancers(req.path("loadBalancers")));
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setTags(parseTagMap(req.path("tags")));
        request.setSchedulingStrategy(parseChoice(req, "schedulingStrategy", SCHEDULING_STRATEGIES));
        request.setDeploymentControllerType(parseChoice(req.path("deploymentController"), "type",
                "deploymentController.type", DEPLOYMENT_CONTROLLER_TYPES));
        request.setAvailabilityZoneRebalancing(
                parseChoice(req, "availabilityZoneRebalancing", AZ_REBALANCING));
        request.setServiceConnectConfiguration(
                parseServiceConnectConfiguration(req.path("serviceConnectConfiguration")));

        EcsServiceModel svc = service.createService(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private List<EcsLoadBalancer> parseLoadBalancers(JsonNode node) {
        List<EcsLoadBalancer> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode lb : node) {
            String targetGroupArn = lb.hasNonNull("targetGroupArn")
                    ? lb.path("targetGroupArn").asText() : null;
            String loadBalancerName = lb.hasNonNull("loadBalancerName")
                    ? lb.path("loadBalancerName").asText() : null;
            String containerName = lb.hasNonNull("containerName")
                    ? lb.path("containerName").asText() : null;
            Integer containerPort = lb.hasNonNull("containerPort")
                    ? lb.path("containerPort").asInt() : null;

            // AWS rejects malformed loadBalancers entries with InvalidParameterException.
            // containerName + containerPort are always required; an entry must target
            // either a target group (ALB/NLB) or a classic load balancer by name.
            if (containerName == null || containerName.isBlank()) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry is missing the required containerName.", 400);
            }
            if (containerPort == null) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry is missing the required containerPort.", 400);
            }
            boolean hasTargetGroup = targetGroupArn != null && !targetGroupArn.isBlank();
            boolean hasLoadBalancerName = loadBalancerName != null && !loadBalancerName.isBlank();
            if (!hasTargetGroup && !hasLoadBalancerName) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry must specify either targetGroupArn or loadBalancerName.", 400);
            }

            EcsLoadBalancer m = new EcsLoadBalancer();
            m.setTargetGroupArn(targetGroupArn);
            m.setLoadBalancerName(loadBalancerName);
            m.setContainerName(containerName);
            m.setContainerPort(containerPort);
            result.add(m);
        }
        return result;
    }

    /** Parse an ECS {@code networkConfiguration} node (camelCase, as the data-plane API uses).
     *  Public so the Step Functions ecs:runTask integration can reuse it after recasing its
     *  PascalCase input, rather than duplicating the awsvpc parsing. */
    public NetworkConfiguration parseNetworkConfiguration(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("awsvpcConfiguration")) {
            return null;
        }
        JsonNode awsvpc = node.path("awsvpcConfiguration");
        AwsVpcConfiguration awsvpcConfig = new AwsVpcConfiguration();
        awsvpcConfig.setSubnets(jsonArrayToList(awsvpc.path("subnets")));
        awsvpcConfig.setSecurityGroups(jsonArrayToList(awsvpc.path("securityGroups")));
        if (awsvpc.hasNonNull("assignPublicIp")) {
            awsvpcConfig.setAssignPublicIp(awsvpc.path("assignPublicIp").asText());
        }
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpcConfig);
        return networkConfiguration;
    }

    private Response handleUpdateService(JsonNode req, String region) {
        UpdateServiceRequest request = new UpdateServiceRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setService(req.path("service").asText());
        request.setTaskDefinition(req.has("taskDefinition") ? req.path("taskDefinition").asText() : null);
        request.setDesiredCount(req.has("desiredCount") ? req.path("desiredCount").asInt() : null);
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setAvailabilityZoneRebalancing(
                parseChoice(req, "availabilityZoneRebalancing", AZ_REBALANCING));
        request.setForceNewDeployment(req.path("forceNewDeployment").asBoolean(false));
        request.setServiceConnectConfiguration(
                parseServiceConnectConfiguration(req.path("serviceConnectConfiguration")));

        EcsServiceModel svc = service.updateService(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleDeleteService(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String serviceName = req.path("service").asText();
        boolean force = req.path("force").asBoolean(false);

        EcsServiceModel svc = service.deleteService(cluster, serviceName, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleDescribeServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> serviceIds = jsonArrayToList(req.path("services"));

        EcsService.DescribeServicesResult found =
                service.describeServicesDetailed(cluster, serviceIds, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.services().forEach(s -> arr.add(writer.serviceNode(s)));
        resp.set("services", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    private Response handleListServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> arns = service.listServices(cluster, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("serviceArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleListServicesByNamespace(JsonNode req, String region) {
        String namespace = req.path("namespace").asText();
        List<String> arns = service.listServicesByNamespace(namespace, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("serviceArns", arr);
        return Response.ok(resp).build();
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response handleTagResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        Map<String, String> tags = parseTagMap(req.path("tags"));
        service.tagResource(resourceArn, tags);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        List<String> tagKeys = jsonArrayToList(req.path("tagKeys"));
        service.untagResource(resourceArn, tagKeys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        Map<String, String> tags = service.listTagsForResource(resourceArn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("tags", writer.tagsNode(tags));
        return Response.ok(resp).build();
    }

    // ── Account Settings ──────────────────────────────────────────────────────

    private Response handlePutAccountSetting(JsonNode req, String region) {
        String name = req.path("name").asText();
        String value = req.path("value").asText();
        var entry = service.putAccountSetting(name, value);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handlePutAccountSettingDefault(JsonNode req, String region) {
        String name = req.path("name").asText();
        String value = req.path("value").asText();
        var entry = service.putAccountSettingDefault(name, value);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handleDeleteAccountSetting(JsonNode req, String region) {
        String name = req.path("name").asText();
        var entry = service.deleteAccountSetting(name);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handleListAccountSettings(JsonNode req, String region) {
        String filterName = req.has("name") ? req.path("name").asText() : null;
        String filterValue = req.has("value") ? req.path("value").asText() : null;
        var settings = service.listAccountSettings(filterName, filterValue);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        settings.forEach(e -> arr.add(writer.settingNode(e.getKey(), e.getValue())));
        resp.set("settings", arr);
        return Response.ok(resp).build();
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    private Response handlePutAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> stored = service.putAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        stored.forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeleteAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> deleted = service.deleteAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleListAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String targetType = req.has("targetType") ? req.path("targetType").asText() : null;
        String attributeName = req.has("attributeName") ? req.path("attributeName").asText() : null;
        String attributeValue = req.has("attributeValue") ? req.path("attributeValue").asText() : null;
        List<Attribute> result = service.listAttributes(cluster, targetType, attributeName, attributeValue, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    // ── Container Instances ───────────────────────────────────────────────────

    private Response handleRegisterContainerInstance(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String instanceIdentityDocument = req.has("instanceIdentityDocument")
                ? req.path("instanceIdentityDocument").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        ContainerInstance instance = service.registerContainerInstance(cluster, instanceIdentityDocument, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDeregisterContainerInstance(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        boolean force = req.path("force").asBoolean(false);
        ContainerInstance instance = service.deregisterContainerInstance(cluster, containerInstance, force, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDescribeContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        List<ContainerInstance> found = service.describeContainerInstances(cluster, instanceRefs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(ci -> arr.add(writer.containerInstanceNode(ci)));
        resp.set("containerInstances", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String status = req.has("status") ? req.path("status").asText() : null;
        List<String> arns = service.listContainerInstances(cluster, status, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("containerInstanceArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerAgent(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        ContainerInstance instance = service.updateContainerAgent(cluster, containerInstance, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerInstancesState(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        String status = req.path("status").asText();
        List<ContainerInstance> updated = service.updateContainerInstancesState(cluster, instanceRefs, status, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        updated.forEach(ci -> arr.add(writer.containerInstanceNode(ci)));
        resp.set("containerInstances", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Capacity Providers ────────────────────────────────────────────────────

    private Response handleCreateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        Map<String, String> tags = parseTagMap(req.path("tags"));
        CapacityProvider cp = service.createCapacityProvider(name, asgProvider, tags, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        CapacityProvider cp = service.updateCapacityProvider(name, asgProvider);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDeleteCapacityProvider(JsonNode req, String region) {
        String nameOrArn = req.path("capacityProvider").asText();
        CapacityProvider cp = service.deleteCapacityProvider(nameOrArn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDescribeCapacityProviders(JsonNode req, String region) {
        List<String> providers = req.has("capacityProviders") ? jsonArrayToList(req.path("capacityProviders")) : null;
        List<CapacityProvider> found = service.describeCapacityProviders(providers);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(cp -> arr.add(writer.capacityProviderNode(cp)));
        resp.set("capacityProviders", arr);
        return Response.ok(resp).build();
    }

    // ── Task Sets ─────────────────────────────────────────────────────────────

    private Response handleCreateTaskSet(JsonNode req, String region) {
        CreateTaskSetRequest request = new CreateTaskSetRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setService(req.path("service").asText());
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setScaleValue(req.path("scale").path("value").asDouble(100.0));
        request.setScaleUnit(req.path("scale").path("unit").asText("PERCENT"));
        request.setExternalId(req.has("externalId") ? req.path("externalId").asText() : null);

        TaskSet ts = service.createTaskSet(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleUpdateTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        double scaleValue = req.path("scale").path("value").asDouble(100.0);
        String scaleUnit = req.path("scale").path("unit").asText("PERCENT");

        TaskSet ts = service.updateTaskSet(cluster, svc, taskSet, scaleValue, scaleUnit, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        boolean force = req.path("force").asBoolean(false);

        TaskSet ts = service.deleteTaskSet(cluster, svc, taskSet, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskSets(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        List<String> taskSetRefs = req.has("taskSets") ? jsonArrayToList(req.path("taskSets")) : null;

        List<TaskSet> found = service.describeTaskSets(cluster, svc, taskSetRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(ts -> arr.add(writer.taskSetNode(ts)));
        resp.set("taskSets", arr);
        return Response.ok(resp).build();
    }

    private Response handleUpdateServicePrimaryTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String primaryTaskSet = req.path("primaryTaskSet").asText();

        TaskSet ts = service.updateServicePrimaryTaskSet(cluster, svc, primaryTaskSet, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    // ── Service Deployments & Revisions ───────────────────────────────────────

    private Response handleDescribeServiceDeployments(JsonNode req, String region) {
        List<String> arns = jsonArrayToList(req.path("serviceDeploymentArns"));
        List<ServiceDeployment> found = service.describeServiceDeployments(arns);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(d -> arr.add(writer.serviceDeploymentNode(d)));
        resp.set("serviceDeployments", arr);
        return Response.ok(resp).build();
    }

    private Response handleListServiceDeployments(JsonNode req, String region) {
        String svc = req.path("service").asText();
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> statusFilter = req.has("status") ? jsonArrayToList(req.path("status")) : null;

        List<ServiceDeployment> deployments = service.listServiceDeploymentsDetailed(svc, cluster, statusFilter, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deployments.forEach(d -> {
            ObjectNode brief = objectMapper.createObjectNode();
            brief.put("serviceDeploymentArn", d.getServiceDeploymentArn());
            brief.put("serviceArn", d.getServiceArn());
            brief.put("clusterArn", d.getClusterArn());
            brief.put("status", d.getStatus());
            if (d.getCreatedAt() != null) { brief.put("createdAt", d.getCreatedAt().toEpochMilli() / 1000.0); }
            if (d.getUpdatedAt() != null) { brief.put("finishedAt", d.getUpdatedAt().toEpochMilli() / 1000.0); }
            arr.add(brief);
        });
        resp.set("serviceDeployments", arr);
        return Response.ok(resp).build();
    }

    private Response handleDescribeServiceRevisions(JsonNode req, String region) {
        List<String> arns = jsonArrayToList(req.path("serviceRevisionArns"));
        List<ServiceRevision> found = service.describeServiceRevisions(arns);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(r -> arr.add(writer.serviceRevisionNode(r)));
        resp.set("serviceRevisions", arr);
        return Response.ok(resp).build();
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private Response handleSubmitTaskStateChange(JsonNode req, String region) {
        String ack = service.submitTaskStateChange();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleSubmitContainerStateChange(JsonNode req, String region) {
        String ack = service.submitContainerStateChange();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleSubmitAttachmentStateChanges(JsonNode req, String region) {
        String ack = service.submitAttachmentStateChanges();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleDiscoverPollEndpoint(JsonNode req, String region) {
        String baseUrl = service.getBaseUrl();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("endpoint", baseUrl);
        resp.put("telemetryEndpoint", baseUrl);
        resp.put("serviceConnectEndpoint", baseUrl);
        return Response.ok(resp).build();
    }

    /** Renders an ECS task to its data-plane JSON shape, for the Step Functions ecs:runTask
     *  integration ({@link io.github.hectorvent.floci.services.stepfunctions.AslExecutor}). */
    public ObjectNode taskNode(EcsTask task) {
        return writer.taskNode(task);
    }

    private static final Set<String> SCHEDULING_STRATEGIES = Set.of("REPLICA", "DAEMON");
    private static final Set<String> DEPLOYMENT_CONTROLLER_TYPES = Set.of("ECS", "CODE_DEPLOY", "EXTERNAL");
    private static final Set<String> AZ_REBALANCING = Set.of("ENABLED", "DISABLED");

    /** Optional enum-valued string field: absent → {@code null}; present but not in {@code allowed} → 400. */
    private static String parseChoice(JsonNode node, String field, Set<String> allowed) {
        return parseChoice(node, field, field, allowed);
    }

    private static String parseChoice(JsonNode node, String field, String displayName, Set<String> allowed) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid " + displayName + ": " + value + ". Valid values: "
                            + String.join(", ", new java.util.TreeSet<>(allowed)) + ".", 400);
        }
        return value;
    }

    /**
     * Keeps the caller's Service Connect configuration as given. AWS's {@code Service} shape has
     * no member for it, so DescribeServices reports it on each deployment rather than on the
     * service, and a generated client drops anything written anywhere else.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseServiceConnectConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private List<ContainerDefinition> parseContainerDefinitions(JsonNode node) {
        List<ContainerDefinition> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            ContainerDefinition def = new ContainerDefinition();
            def.setName(item.path("name").asText());
            def.setImage(item.path("image").asText());
            def.setEssential(item.path("essential").asBoolean(true));
            if (item.has("cpu")) { def.setCpu(item.path("cpu").asInt()); }
            if (item.has("memory")) { def.setMemory(item.path("memory").asInt()); }
            if (item.has("memoryReservation")) { def.setMemoryReservation(item.path("memoryReservation").asInt()); }

            def.setPortMappings(parsePortMappings(item.path("portMappings")));
            def.setEnvironment(parseKeyValuePairs(item.path("environment")));
            if (item.has("secrets")) {
                def.setSecrets(parseSecrets(item.path("secrets")));
            }
            def.setMountPoints(parseMountPoints(item.path("mountPoints")));
            def.setVolumesFrom(parseVolumesFrom(item.path("volumesFrom")));
            def.setLogConfiguration(parseLogConfiguration(item.path("logConfiguration")));
            def.setFirelensConfiguration(parseFirelensConfiguration(
                    item.path("firelensConfiguration"), result.size() + 1));
            if (item.has("healthCheck")) {
                def.setHealthCheck(parseHealthCheck(item.path("healthCheck")));
            }

            if (item.has("command") && item.path("command").isArray()) {
                List<String> cmd = new ArrayList<>();
                item.path("command").forEach(c -> cmd.add(c.asText()));
                def.setCommand(cmd);
            }
            if (item.has("entryPoint") && item.path("entryPoint").isArray()) {
                List<String> ep = new ArrayList<>();
                item.path("entryPoint").forEach(e -> ep.add(e.asText()));
                def.setEntryPoint(ep);
            }

            result.add(def);
        }
        return result;
    }

    private List<PortMapping> parsePortMappings(JsonNode node) {
        List<PortMapping> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            int containerPort = item.path("containerPort").asInt(0);
            int hostPort = item.path("hostPort").asInt(0);
            String protocol = item.path("protocol").asText("tcp");
            result.add(new PortMapping(containerPort, hostPort, protocol));
        }
        return result;
    }

    private List<KeyValuePair> parseKeyValuePairs(JsonNode node) {
        List<KeyValuePair> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new KeyValuePair(item.path("name").asText(), item.path("value").asText()));
        }
        return result;
    }

    private List<Secret> parseSecrets(JsonNode node) {
        List<Secret> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new Secret(item.path("name").asText(), item.path("valueFrom").asText()));
        }
        return result;
    }

    private List<VolumeFrom> parseVolumesFrom(JsonNode node) {
        List<VolumeFrom> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new VolumeFrom(
                    item.path("sourceContainer").asText(),
                    item.path("readOnly").asBoolean(false)));
        }
        return result;
    }

    private RuntimePlatform parseRuntimePlatform(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String cpuArchitecture = node.path("cpuArchitecture").asText(null);
        String operatingSystemFamily = node.path("operatingSystemFamily").asText(null);
        if (cpuArchitecture == null && operatingSystemFamily == null) {
            return null;
        }
        return new RuntimePlatform(cpuArchitecture, operatingSystemFamily);
    }

    private LogConfiguration parseLogConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String logDriver = node.path("logDriver").asText(null);
        if (logDriver == null) {
            return null;
        }
        Map<String, String> options = null;
        if (node.path("options").isObject()) {
            Map<String, String> parsed = new LinkedHashMap<>();
            node.path("options").fields()
                    .forEachRemaining(entry -> parsed.put(entry.getKey(), entry.getValue().asText()));
            options = parsed;
        }
        List<Secret> secretOptions = node.has("secretOptions") ? parseSecrets(node.path("secretOptions")) : null;
        return new LogConfiguration(logDriver, options, secretOptions);
    }

    private FirelensConfiguration parseFirelensConfiguration(JsonNode node, int containerIndex) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!node.hasNonNull("type")) {
            throw new AwsException("ClientException",
                    "1 validation error detected: Value null at 'containerDefinitions." + containerIndex
                            + ".member.firelensConfiguration.type' failed to satisfy constraint: Member must not be null",
                    400);
        }
        String type = node.path("type").asText();
        if (!"fluentd".equals(type) && !"fluentbit".equals(type)) {
            throw new AwsException("ClientException",
                    "1 validation error detected: Value '" + type + "' at 'containerDefinitions." + containerIndex
                            + ".member.firelensConfiguration.type' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [fluentd, fluentbit]",
                    400);
        }
        Map<String, String> options = null;
        if (node.path("options").isObject()) {
            LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
            node.path("options").fields()
                    .forEachRemaining(entry -> parsed.put(entry.getKey(), entry.getValue().asText()));
            options = parsed;
        }
        return new FirelensConfiguration(type, options);
    }
    private HealthCheck parseHealthCheck(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!node.hasNonNull("command") || !node.path("command").isArray() || node.path("command").isEmpty()) {
            throw new AwsException("ClientException", "HealthCheck command is required.", 400);
        }
        List<String> command = jsonArrayToList(node.path("command"));
        Integer interval = node.has("interval") ? node.path("interval").asInt() : null;
        Integer timeout = node.has("timeout") ? node.path("timeout").asInt() : null;
        Integer retries = node.has("retries") ? node.path("retries").asInt() : null;
        Integer startPeriod = node.has("startPeriod") ? node.path("startPeriod").asInt() : null;
        return new HealthCheck(command, interval, timeout, retries, startPeriod);
    }

    private List<Volume> parseVolumes(JsonNode node) {
        List<Volume> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String hostSourcePath = item.path("host").path("sourcePath").asText(null);
            if (hostSourcePath != null && !hostSourcePath.isBlank()) {
                hostVolumePolicy.validate(hostSourcePath);
            }
            EfsVolumeConfiguration efs = parseEfsVolumeConfiguration(item.path("efsVolumeConfiguration"));
            result.add(new Volume(item.path("name").asText(), hostSourcePath, efs));
        }
        return result;
    }

    private EfsVolumeConfiguration parseEfsVolumeConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String fileSystemId = node.path("fileSystemId").asText(null);
        if (fileSystemId == null) {
            return null;
        }
        Integer transitEncryptionPort = node.path("transitEncryptionPort").isNumber()
                ? node.path("transitEncryptionPort").asInt() : null;
        JsonNode auth = node.path("authorizationConfig");
        String rootDirectory = node.path("rootDirectory").asText(null);
        String accessPointId = auth.path("accessPointId").asText(null);
        if (accessPointId != null && !accessPointId.isBlank()
                && rootDirectory != null && !rootDirectory.isBlank() && !"/".equals(rootDirectory)) {
            throw new AwsException("InvalidParameterException",
                    "Root directory must either be omitted or set to '/' when an EFS access point "
                            + "is specified in authorizationConfig.accessPointId.", 400);
        }
        return new EfsVolumeConfiguration(
                fileSystemId,
                rootDirectory,
                node.path("transitEncryption").asText(null),
                transitEncryptionPort,
                accessPointId,
                auth.path("iam").asText(null));
    }

    private List<MountPoint> parseMountPoints(JsonNode node) {
        List<MountPoint> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new MountPoint(
                    item.path("sourceVolume").asText(),
                    item.path("containerPath").asText(),
                    item.path("readOnly").asBoolean(false)));
        }
        return result;
    }

    /** Parses ECS container overrides from data-plane JSON. Reused by the Step Functions
     *  ecs:runTask integration ({@link io.github.hectorvent.floci.services.stepfunctions.AslExecutor}). */
    public List<ContainerOverride> parseContainerOverrides(JsonNode node) {
        List<ContainerOverride> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            ContainerOverride co = new ContainerOverride();
            co.setName(item.path("name").asText());
            if (item.has("command") && item.path("command").isArray()) {
                List<String> cmd = new ArrayList<>();
                item.path("command").forEach(c -> cmd.add(c.asText()));
                co.setCommand(cmd);
            }
            co.setEnvironment(parseKeyValuePairs(item.path("environment")));
            result.add(co);
        }
        return result;
    }

    private List<ClusterSetting> parseClusterSettings(JsonNode node) {
        List<ClusterSetting> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new ClusterSetting(item.path("name").asText(), item.path("value").asText()));
        }
        return result;
    }

    private List<Attribute> parseAttributes(JsonNode node) {
        List<Attribute> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new Attribute(
                    item.path("name").asText(),
                    item.has("value") ? item.path("value").asText() : null,
                    item.has("targetType") ? item.path("targetType").asText() : null,
                    item.has("targetId") ? item.path("targetId").asText() : null
            ));
        }
        return result;
    }

    private Map<String, String> parseTagMap(JsonNode node) {
        Map<String, String> result = new HashMap<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.put(item.path("key").asText(), item.path("value").asText());
        }
        return result;
    }

    private Map<String, Object> parseRawObject(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private List<Map<String, Object>> parseRawObjectList(JsonNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(objectMapper.convertValue(item, Map.class));
        }
        return result;
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private <T extends Enum<T>> T parseEnum(JsonNode req, String field, Class<T> enumClass) {
        if (!req.has(field)) {
            return null;
        }
        String val = req.path(field).asText();
        try {
            return Enum.valueOf(enumClass, val);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
