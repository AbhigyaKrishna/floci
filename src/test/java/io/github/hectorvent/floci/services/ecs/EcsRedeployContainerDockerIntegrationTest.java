package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("docker")
@QuarkusTest
@TestProfile(EcsRedeployContainerDockerIntegrationTest.DockerProfile.class)
class EcsRedeployContainerDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String IMAGE = "public.ecr.aws/docker/library/busybox:1.36";

    public static class DockerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false");
        }
    }

    @Inject
    EcsService service;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for ECS redeploy integration test");
    }

    @Test
    void redeployRemovesTheOldDockerContainer() throws InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cluster = "redeploy-" + suffix;
        String family = "redeploy-task-" + suffix;
        String serviceName = "redeploy-service-" + suffix;
        String oldDockerId = null;
        String newDockerId = null;

        service.createCluster(cluster, REGION);
        try {
            TaskDefinition firstDefinition = registerTaskDefinition(family);
            service.createService(cluster, serviceName, firstDefinition.getTaskDefinitionArn(), 1,
                    LaunchType.FARGATE, List.of(), null, REGION);
            EcsTask oldTask = awaitRunningTask(cluster, firstDefinition.getTaskDefinitionArn());
            oldDockerId = oldTask.getContainers().getFirst().getRuntimeId();

            TaskDefinition secondDefinition = registerTaskDefinition(family);
            service.updateService(cluster, serviceName, secondDefinition.getTaskDefinitionArn(),
                    null, null, REGION);
            EcsTask newTask = awaitRunningTask(cluster, secondDefinition.getTaskDefinitionArn());
            newDockerId = newTask.getContainers().getFirst().getRuntimeId();
            assertNotEquals(oldDockerId, newDockerId);
            awaitStoppedTask(cluster, oldTask.getTaskArn());

            String removedId = oldDockerId;
            assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(removedId).exec(),
                    "the stale task's Docker container must be removed");
            assertTrue(dockerClient.inspectContainerCmd(newDockerId).exec().getState().getRunning());
            assertEquals(1, runningTasks(cluster).size());
        } finally {
            try {
                service.deleteService(cluster, serviceName, true, REGION);
            } catch (Exception ignored) {
                // The service may not have been created before a test failure.
            }
            removeOwnedContainer(oldDockerId);
            removeOwnedContainer(newDockerId);
        }
    }

    private TaskDefinition registerTaskDefinition(String family) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage(IMAGE);
        definition.setCommand(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 120 & wait"));
        return service.registerTaskDefinition(family, List.of(definition), null, null, null,
                null, null, List.of(), REGION);
    }

    private EcsTask awaitRunningTask(String cluster, String taskDefinitionArn) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (EcsTask task : runningTasks(cluster)) {
                if (taskDefinitionArn.equals(task.getTaskDefinitionArn())) {
                    return task;
                }
            }
            Thread.sleep(200);
        }
        fail("ECS did not start a task on " + taskDefinitionArn);
        return null;
    }

    private void awaitStoppedTask(String cluster, String taskArn) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if ("STOPPED".equals(service.describeTasks(cluster, List.of(taskArn), REGION).getFirst().getLastStatus())) {
                return;
            }
            Thread.sleep(200);
        }
        fail("ECS did not stop the stale task " + taskArn);
    }

    private List<EcsTask> runningTasks(String cluster) {
        return service.describeTasks(cluster, service.listTasks(cluster, null, null, null, REGION), REGION)
                .stream().filter(task -> "RUNNING".equals(task.getLastStatus())).toList();
    }

    private void removeOwnedContainer(String dockerId) {
        if (dockerId == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(dockerId).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // The service already removed this test's container.
        }
    }
}
