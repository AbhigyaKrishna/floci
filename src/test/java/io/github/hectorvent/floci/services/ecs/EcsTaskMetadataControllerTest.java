package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EphemeralStorage;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The task metadata endpoint's response shapes. A workload reads these through
 * {@code ECS_CONTAINER_METADATA_URI_V4} expecting AWS's exact PascalCase members, so the mapping
 * from Floci's task model onto them is what this pins down.
 */
class EcsTaskMetadataControllerTest {

    private static final String METADATA_ID = "9e2b1f0c4d5e4a1b8c7d6e5f4a3b2c1d";
    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/metadata-cluster/abc123";

    private ObjectMapper objectMapper;
    private EcsTaskMetadataController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EcsService service = mock(EcsService.class);
        EcsTask task = task();
        TaskDefinition taskDef = taskDefinition();
        when(service.findByMetadataId(anyString())).thenAnswer(inv ->
                METADATA_ID.equals(inv.getArgument(0))
                        ? Optional.of(new EcsService.MetadataTarget(task,
                                task.getContainers().getFirst(), taskDef))
                        : Optional.empty());
        controller = new EcsTaskMetadataController(service, objectMapper);
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN);
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/metadata-cluster");
        task.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/web:3");
        task.setLastStatus("RUNNING");
        task.setDesiredStatus("RUNNING");
        task.setLaunchType(LaunchType.FARGATE);
        task.setPlatformVersion("1.4.0");
        task.setPlatformFamily("Linux");
        task.setCpu("256");
        task.setMemory("512");
        task.setEphemeralStorage(new EphemeralStorage(20));
        task.setAvailabilityZone("us-east-1a");
        task.setCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        task.setStartedAt(Instant.parse("2026-01-01T10:00:05Z"));
        task.setPullStartedAt(Instant.parse("2026-01-01T10:00:01Z"));
        task.setPullStoppedAt(Instant.parse("2026-01-01T10:00:04Z"));
        task.setPrivateIpAddress("172.31.0.42");

        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-default-us-east-1-a"));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);
        task.setNetworkConfiguration(networkConfiguration);

        Container container = new Container();
        container.setName("app");
        container.setImage("nginx:latest");
        container.setLastStatus("RUNNING");
        container.setDockerId("docker-id-1");
        container.setMetadataId(METADATA_ID);
        container.setContainerArn("arn:aws:ecs:us-east-1:000000000000:container/abc123/app");
        task.setContainers(List.of(container));
        return task;
    }

    private static TaskDefinition taskDefinition() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("nginx:latest");
        app.setCpu(128);
        app.setMemory(256);
        app.setLogConfiguration(new LogConfiguration("awslogs",
                Map.of("awslogs-group", "/ecs/web"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("web");
        taskDef.setRevision(3);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }

    private JsonNode body(Response response) {
        return objectMapper.valueToTree(response.getEntity());
    }

    @Test
    void containerMetadataReportsTheAwsMembers() {
        Response response = controller.container(METADATA_ID);
        assertEquals(200, response.getStatus());

        JsonNode container = body(response);
        assertEquals("docker-id-1", container.path("DockerId").asText());
        assertEquals("app", container.path("Name").asText());
        assertEquals("nginx:latest", container.path("Image").asText());
        assertEquals("RUNNING", container.path("KnownStatus").asText());
        assertEquals("NORMAL", container.path("Type").asText());
        assertEquals(128, container.path("Limits").path("CPU").asInt());
        assertEquals(256, container.path("Limits").path("Memory").asInt());
        // SubnetId is not a member of the v4 network object; only the documented ones are written.
        assertTrue(container.path("Networks").get(0).path("SubnetId").isMissingNode(),
                "the network object must carry only the members AWS documents");
        assertEquals("awslogs", container.path("LogDriver").asText());
        assertEquals("/ecs/web", container.path("LogOptions").path("awslogs-group").asText());
        assertEquals("web", container.path("Labels").path("com.amazonaws.ecs.task-definition-family").asText());
        assertEquals("3", container.path("Labels").path("com.amazonaws.ecs.task-definition-version").asText());
        assertEquals(TASK_ARN, container.path("Labels").path("com.amazonaws.ecs.task-arn").asText());
        assertEquals("awsvpc", container.path("Networks").get(0).path("NetworkMode").asText());
        assertEquals("172.31.0.42", container.path("Networks").get(0).path("IPv4Addresses").get(0).asText());
        assertEquals("2026-01-01T10:00:05Z", container.path("StartedAt").asText());
    }

    @Test
    void taskMetadataReportsTheTaskAndItsContainers() {
        Response response = controller.task(METADATA_ID);
        assertEquals(200, response.getStatus());

        JsonNode task = body(response);
        assertEquals(TASK_ARN, task.path("TaskARN").asText());
        assertEquals("web", task.path("Family").asText());
        assertEquals("3", task.path("Revision").asText());
        assertEquals("FARGATE", task.path("LaunchType").asText());
        assertEquals("us-east-1a", task.path("AvailabilityZone").asText());
        // The task's CPU limit is a vCPU count, not the CPU units the API takes.
        assertEquals(0.25, task.path("Limits").path("CPU").asDouble(), 0.0001);
        assertEquals(512, task.path("Limits").path("Memory").asInt());
        assertEquals("2026-01-01T10:00:01Z", task.path("PullStartedAt").asText());
        assertEquals(1, task.path("Containers").size());
        assertEquals("app", task.path("Containers").get(0).path("Name").asText());
        // Fargate reports the clock's accuracy and the task's ephemeral storage alongside it.
        assertEquals("SYNCHRONIZED", task.path("ClockDrift").path("ClockSynchronizationStatus").asText());
        assertTrue(task.path("ClockDrift").has("ReferenceTimestamp"));
        assertEquals(20 * 1024, task.path("EphemeralStorageMetrics").path("Reserved").asInt());
    }

    @Test
    void aContainerWithoutLimitsReportsNoneRatherThanZeroes() {
        EcsService service = mock(EcsService.class);
        EcsTask task = task();
        TaskDefinition taskDef = taskDefinition();
        taskDef.getContainerDefinitions().getFirst().setCpu(null);
        taskDef.getContainerDefinitions().getFirst().setMemory(null);
        when(service.findByMetadataId(anyString())).thenReturn(Optional.of(
                new EcsService.MetadataTarget(task, task.getContainers().getFirst(), taskDef)));

        JsonNode container = body(new EcsTaskMetadataController(service, objectMapper)
                .container(METADATA_ID));

        assertTrue(container.path("Limits").isMissingNode(),
                "Limits is omitted when the container definition sets none");
        assertTrue(container.path("ImageID").isMissingNode(),
                "ImageID is omitted until the image digest is known");
    }

    @Test
    void statsAnswerRatherThanFailWhenNothingIsSampled() {
        assertEquals(200, controller.containerStats(METADATA_ID).getStatus());

        Response taskStats = controller.taskStats(METADATA_ID);
        assertEquals(200, taskStats.getStatus());
        assertTrue(body(taskStats).has("docker-id-1"),
                "task stats are keyed by container id even when the samples are empty");
    }

    @Test
    void anUnknownIdIsNotFound() {
        Response response = controller.container("not-a-metadata-id");
        assertEquals(404, response.getStatus());
        assertTrue(body(response).path("error").asText().contains("Unable to get metadata"));
    }
}
