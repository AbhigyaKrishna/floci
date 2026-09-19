package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * The ECS task metadata endpoint, version 4.
 *
 * <p>On AWS this is served at {@code http://169.254.170.2/v4/<id>} and every container is told
 * where to find it through {@code ECS_CONTAINER_METADATA_URI_V4}. A local task cannot be given a
 * link-local address of its own, so Floci serves the same paths on its own port and injects the
 * matching URI into each container it launches; an application, the AWS SDKs' ECS credential and
 * metadata clients, and the aws-for-fluent-bit init process all read the variable rather than the
 * address, so they work unchanged.
 *
 * <p>{@code /stats} answers with an empty document. Docker's own stats stream is not sampled here,
 * and AWS documents a null response when stats are unavailable, so a client that asks gets a valid
 * answer rather than an error.
 */
@Path("/v4")
@ApplicationScoped
public class EcsTaskMetadataController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final String SERVICE_GROUP_PREFIX = "service:";

    private final EcsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcsTaskMetadataController(EcsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response container(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        EcsService.MetadataTarget found = target.get();
        return Response.ok(containerNode(found.task(), found.container(), found.taskDefinition())).build();
    }

    @GET
    @Path("/{id}/task")
    @Produces(MediaType.APPLICATION_JSON)
    public Response task(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        EcsService.MetadataTarget found = target.get();
        return Response.ok(taskNode(found.task(), found.taskDefinition())).build();
    }

    @GET
    @Path("/{id}/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response containerStats(@PathParam("id") String id) {
        if (service.findByMetadataId(id).isEmpty()) {
            return notFound();
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/{id}/task/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response taskStats(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        ObjectNode stats = objectMapper.createObjectNode();
        List<Container> containers = target.get().task().getContainers();
        if (containers != null) {
            for (Container container : containers) {
                stats.putNull(container.getDockerId() != null ? container.getDockerId() : container.getName());
            }
        }
        return Response.ok(stats).build();
    }

    private Response notFound() {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", "Unable to get metadata for the requested id");
        return Response.status(Response.Status.NOT_FOUND).entity(error).build();
    }

    private ObjectNode containerNode(EcsTask task, Container container, TaskDefinition taskDef) {
        ContainerDefinition definition = definitionOf(taskDef, container.getName());
        ObjectNode n = objectMapper.createObjectNode();
        n.put("DockerId", container.getDockerId() != null ? container.getDockerId() : container.getMetadataId());
        n.put("Name", container.getName());
        n.put("DockerName", container.getName());
        n.put("Image", container.getImage());
        if (container.getImageDigest() != null && !container.getImageDigest().isBlank()) {
            n.put("ImageID", container.getImageDigest());
        }
        ArrayNode ports = portsNode(container);
        if (!ports.isEmpty()) {
            n.set("Ports", ports);
        }
        n.set("Labels", labelsNode(task, taskDef, container));
        n.put("DesiredStatus", task.getDesiredStatus());
        n.put("KnownStatus", container.getLastStatus());
        if (container.getExitCode() != null) {
            n.put("ExitCode", container.getExitCode());
        }
        ObjectNode limits = containerLimitsNode(definition);
        if (!limits.isEmpty()) {
            n.set("Limits", limits);
        }
        putTimestamp(n, "CreatedAt", task.getCreatedAt());
        putTimestamp(n, "StartedAt", task.getStartedAt());
        putTimestamp(n, "FinishedAt", task.getStoppedAt());
        n.put("Type", "NORMAL");
        n.put("ContainerARN", container.getContainerArn());
        if (container.getHealthStatus() != null) {
            n.putObject("Health").put("status", container.getHealthStatus());
        }
        if (definition != null && definition.getLogConfiguration() != null) {
            LogConfiguration logConfiguration = definition.getLogConfiguration();
            n.put("LogDriver", logConfiguration.logDriver());
            if (logConfiguration.options() != null) {
                ObjectNode options = n.putObject("LogOptions");
                logConfiguration.options().forEach(options::put);
            }
        }
        n.set("Networks", networksNode(task, taskDef));
        if (task.getPlatformVersion() != null) {
            n.put("Snapshotter", "overlayfs");
        }
        return n;
    }

    private ArrayNode portsNode(Container container) {
        ArrayNode ports = objectMapper.createArrayNode();
        if (container.getNetworkBindings() != null) {
            container.getNetworkBindings().forEach(binding -> {
                ObjectNode port = ports.addObject();
                port.put("ContainerPort", binding.containerPort());
                port.put("Protocol", binding.protocol());
                if (binding.hostPort() > 0) {
                    port.put("HostPort", binding.hostPort());
                }
            });
        }
        return ports;
    }

    private ObjectNode taskNode(EcsTask task, TaskDefinition taskDef) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("Cluster", task.getClusterArn());
        n.put("TaskARN", task.getTaskArn());
        if (taskDef != null) {
            n.put("Family", taskDef.getFamily());
            n.put("Revision", String.valueOf(taskDef.getRevision()));
        }
        n.put("DesiredStatus", task.getDesiredStatus());
        n.put("KnownStatus", task.getLastStatus());
        ObjectNode limits = taskLimitsNode(task);
        if (!limits.isEmpty()) {
            n.set("Limits", limits);
        }
        putTimestamp(n, "PullStartedAt", task.getPullStartedAt());
        putTimestamp(n, "PullStoppedAt", task.getPullStoppedAt());
        putTimestamp(n, "ExecutionStoppedAt", task.getExecutionStoppedAt());
        if (task.getAvailabilityZone() != null) {
            n.put("AvailabilityZone", task.getAvailabilityZone());
        }
        // A service's tasks are grouped as "service:<name>", which is where the endpoint's
        // ServiceName comes from; a standalone task has no service and reports none.
        if (task.getGroup() != null && task.getGroup().startsWith(SERVICE_GROUP_PREFIX)) {
            n.put("ServiceName", task.getGroup().substring(SERVICE_GROUP_PREFIX.length()));
        }
        if (task.getLaunchType() != null) {
            n.put("LaunchType", task.getLaunchType().name());
        }
        ArrayNode containers = n.putArray("Containers");
        if (task.getContainers() != null) {
            task.getContainers().forEach(container ->
                    containers.add(containerNode(task, container, taskDef)));
        }
        // Fargate reports the clock's accuracy and the task's ephemeral storage use. Floci has no
        // drift to report and does not meter the disk, so both are reported as a healthy baseline.
        if (task.getPlatformVersion() != null) {
            ObjectNode clockDrift = n.putObject("ClockDrift");
            clockDrift.put("ClockErrorBound", 0.0);
            clockDrift.put("ReferenceTimestamp", TIMESTAMP.format(Instant.now()));
            clockDrift.put("ClockSynchronizationStatus", "SYNCHRONIZED");
            if (task.getEphemeralStorage() != null) {
                ObjectNode storage = n.putObject("EphemeralStorageMetrics");
                storage.put("Utilized", 0);
                storage.put("Reserved", task.getEphemeralStorage().sizeInGiB() * 1024);
            }
        }
        return n;
    }

    private ObjectNode labelsNode(EcsTask task, TaskDefinition taskDef, Container container) {
        ObjectNode labels = objectMapper.createObjectNode();
        labels.put("com.amazonaws.ecs.cluster", task.getClusterArn());
        labels.put("com.amazonaws.ecs.container-name", container.getName());
        labels.put("com.amazonaws.ecs.task-arn", task.getTaskArn());
        if (taskDef != null) {
            labels.put("com.amazonaws.ecs.task-definition-family", taskDef.getFamily());
            labels.put("com.amazonaws.ecs.task-definition-version", String.valueOf(taskDef.getRevision()));
        }
        return labels;
    }

    /** The container's own limits, omitted member by member when the definition sets none. */
    private ObjectNode containerLimitsNode(ContainerDefinition definition) {
        ObjectNode limits = objectMapper.createObjectNode();
        if (definition == null) {
            return limits;
        }
        if (definition.getCpu() != null) {
            limits.put("CPU", definition.getCpu());
        }
        if (definition.getMemory() != null) {
            limits.put("Memory", definition.getMemory());
        }
        return limits;
    }

    /** The task's own limits: CPU as a vCPU count, memory in MiB, the way the endpoint reports them. */
    private ObjectNode taskLimitsNode(EcsTask task) {
        ObjectNode limits = objectMapper.createObjectNode();
        Integer cpuUnits = parseInteger(task.getCpu());
        if (cpuUnits != null) {
            limits.put("CPU", cpuUnits / 1024.0);
        }
        Integer memoryMb = parseInteger(task.getMemory());
        if (memoryMb != null) {
            limits.put("Memory", memoryMb);
        }
        return limits;
    }

    private ArrayNode networksNode(EcsTask task, TaskDefinition taskDef) {
        ArrayNode networks = objectMapper.createArrayNode();
        ObjectNode network = objectMapper.createObjectNode();
        network.put("NetworkMode", taskDef != null && taskDef.getNetworkMode() != null
                ? taskDef.getNetworkMode().name() : "bridge");
        ArrayNode addresses = network.putArray("IPv4Addresses");
        if (task.getPrivateIpAddress() != null) {
            addresses.add(task.getPrivateIpAddress());
        }
        AwsVpcConfiguration awsvpc = task.getNetworkConfiguration() != null
                ? task.getNetworkConfiguration().getAwsvpcConfiguration() : null;
        if (awsvpc != null && awsvpc.getSubnets() != null && !awsvpc.getSubnets().isEmpty()) {
            network.put("AttachmentIndex", 0);
        }
        networks.add(network);
        return networks;
    }

    private static ContainerDefinition definitionOf(TaskDefinition taskDef, String containerName) {
        if (taskDef == null || taskDef.getContainerDefinitions() == null) {
            return null;
        }
        return taskDef.getContainerDefinitions().stream()
                .filter(definition -> containerName.equals(definition.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putTimestamp(ObjectNode target, String field, Instant value) {
        if (value != null) {
            target.put(field, TIMESTAMP.format(value));
        }
    }
}
