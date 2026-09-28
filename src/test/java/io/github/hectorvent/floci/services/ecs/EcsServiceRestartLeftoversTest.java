package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A restarted Floci knows no task, since task state is memory-only, so what the previous run's
 * tasks left behind has to go before the scheduler starts their replacements: containers a run
 * without a graceful shutdown left serving, and the load balancer targets and Cloud Map instances
 * that even a graceful one leaves registered.
 */
class EcsServiceRestartLeftoversTest {

    private static final String REGION = "us-east-1";
    private static final String TARGET_GROUP_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/0123456789abcdef";

    @Test
    void releasesTheRegistrationsThePreviousRunRecorded() {
        EcsLoadBalancerRegistrar lbRegistrar = mock(EcsLoadBalancerRegistrar.class);
        EcsServiceDiscoveryRegistrar discoveryRegistrar = mock(EcsServiceDiscoveryRegistrar.class);

        service(new SharedStorageFactory(), true, mock(EcsContainerManager.class),
                lbRegistrar, discoveryRegistrar).releasePreviousRunLeftovers();

        verify(lbRegistrar).releaseRecordedTargets();
        verify(discoveryRegistrar).releaseRecordedInstances();
    }

    @Test
    void theSchedulerWaitsUntilThePreviousRunsContainersAreRemoved() {
        SharedStorageFactory storage = new SharedStorageFactory();
        persistServiceWithLoadBalancer(storage);
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(false, false, true);
        EcsService restarted = service(storage, false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        restarted.releasePreviousRunLeftovers();
        restarted.reconcile();
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());

        restarted.reconcile();
        restarted.reconcile();
        verify(containerManager, times(3)).removeLeftoverContainers();
    }

    @Test
    void theSweepIsNotRetriedWhileNoServiceNeedsATask() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(false);
        EcsService restarted = service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        restarted.releasePreviousRunLeftovers();
        restarted.reconcile();
        restarted.reconcile();

        verify(containerManager).removeLeftoverContainers();
    }

    @Test
    void dockerModeRemovesTheContainersAPreviousRunLeft() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);

        service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null).releasePreviousRunLeftovers();

        verify(containerManager).removeLeftoverContainers();
    }

    @Test
    void mockModeHasNoContainersToRemove() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);

        service(new SharedStorageFactory(), true, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null).releasePreviousRunLeftovers();

        verify(containerManager, never()).removeLeftoverContainers();
    }

    @Test
    void startupReleasesLeftoversBeforeTheSchedulerRuns() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsService service = service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        service.init();
        try {
            verify(containerManager).removeLeftoverContainers();
            verify(containerManager, never()).startTask(any(), any(), any(), anyString());
        } finally {
            service.stopManagedContainers();
        }
    }

    private static void persistServiceWithLoadBalancer(StorageFactory storage) {
        EcsService first = service(storage, true, mock(EcsContainerManager.class),
                mock(EcsLoadBalancerRegistrar.class), null);
        first.createCluster("app-cluster", Map.of(), REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("web");
        container.setImage("nginx:alpine");
        first.registerTaskDefinition("web", List.of(container), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);
        EcsLoadBalancer lb = new EcsLoadBalancer();
        lb.setTargetGroupArn(TARGET_GROUP_ARN);
        lb.setContainerName("web");
        lb.setContainerPort(80);
        first.createService("app-cluster", "web-svc", "web", 1, LaunchType.EC2, List.of(lb), null, REGION);
    }

    private static EcsService service(StorageFactory storage, boolean mockMode,
                                      EcsContainerManager containerManager,
                                      EcsLoadBalancerRegistrar lbRegistrar,
                                      EcsServiceDiscoveryRegistrar discoveryRegistrar) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, lbRegistrar, storage, null, new EcsExecSessionRegistry(), discoveryRegistrar);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
