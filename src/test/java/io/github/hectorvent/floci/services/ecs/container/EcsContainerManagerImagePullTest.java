package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task pulls its images at launch, as the ECS agent does under {@code ECS_IMAGE_PULL_BEHAVIOR},
 * so a tag moved in its registry is what the next task runs, and each container reports the
 * digest of the image it runs.
 */
class EcsContainerManagerImagePullTest {

    private static final String APP_IMAGE = "registry.example:5000/app:latest";
    private static final String SIDECAR_IMAGE = "sidecar:latest";

    private ContainerLifecycleManager lifecycleManager;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().imagePullBehavior()).thenReturn(ImagePullBehavior.ALWAYS);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, mock(RegionResolver.class), awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class));
    }

    @Test
    void everyImageIsPulledWithTheConfiguredBehaviourBeforeAnyContainerIsCreated() {
        when(lifecycleManager.resolveImageForLaunch(APP_IMAGE, ImagePullBehavior.ALWAYS))
                .thenReturn(Optional.of("sha256:new-manifest"));
        when(lifecycleManager.resolveImageForLaunch(SIDECAR_IMAGE, ImagePullBehavior.ALWAYS))
                .thenReturn(Optional.empty());
        EcsTask task = task();

        manager.startTask(task, taskDefinition(), List.of(), "us-east-1");

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).resolveImageForLaunch(APP_IMAGE, ImagePullBehavior.ALWAYS);
        order.verify(lifecycleManager).resolveImageForLaunch(SIDECAR_IMAGE, ImagePullBehavior.ALWAYS);
        order.verify(lifecycleManager).createAndStart(any());
        List<Container> containers = task.getContainers();
        assertEquals("sha256:new-manifest", containers.get(0).getImageDigest());
        assertNull(containers.get(1).getImageDigest(), "a locally built image has no manifest digest");
    }

    @Test
    void aFailedPullLeavesNoContainerCreated() {
        when(lifecycleManager.resolveImageForLaunch(APP_IMAGE, ImagePullBehavior.ALWAYS))
                .thenReturn(Optional.of("sha256:new-manifest"));
        when(lifecycleManager.resolveImageForLaunch(SIDECAR_IMAGE, ImagePullBehavior.ALWAYS))
                .thenThrow(new NotFoundException("pull access denied for sidecar"));

        assertThrows(NotFoundException.class,
                () -> manager.startTask(task(), taskDefinition(), List.of(), "us-east-1"));

        verify(lifecycleManager, never()).createAndStart(any());
    }

    private static TaskDefinition taskDefinition() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(APP_IMAGE);
        ContainerDefinition sidecar = new ContainerDefinition();
        sidecar.setName("sidecar");
        sidecar.setImage(SIDECAR_IMAGE);
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("app-service");
        taskDef.setContainerDefinitions(List.of(app, sidecar));
        return taskDef;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        return task;
    }
}
