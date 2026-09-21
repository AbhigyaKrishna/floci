package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class ElastiCacheMemcachedService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheMemcachedService.class);
    private static final String ENGINE = "memcached";
    private static final String ENGINE_VERSION = "1.6.22";
    /** Memcached's well-known port, used as the endpoint port when no backing container exists. */
    private static final int BACKEND_PORT = 11211;

    private final StorageBackend<String, CacheCluster> clusters;
    private final ElastiCacheMemcachedContainerManager containerManager;
    private final EmulatorConfig config;

    @Inject
    public ElastiCacheMemcachedService(ElastiCacheMemcachedContainerManager containerManager,
                                       StorageFactory storageFactory,
                                       EmulatorConfig config) {
        this.containerManager = containerManager;
        this.config = config;
        this.clusters = storageFactory.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
    }

    public CacheCluster createCacheCluster(String clusterId) {
        if (clusters.get(clusterId).isPresent()) {
            throw new AwsException("CacheClusterAlreadyExistsFault",
                    "Cache cluster " + clusterId + " already exists.", 400);
        }

        String image = config.services().elasticache().defaultMemcachedImage();
        LOG.infov("Creating Memcached cluster {0} with image {1}", clusterId, image);

        // A cache cluster record is metadata: its id and endpoint are derived from configuration,
        // so the cluster is created and reaches 'available' even when no Docker daemon is
        // reachable. Only connecting to the cache needs the container.
        ElastiCacheContainerHandle handle = containerManager.tryStart(clusterId, image);

        String endpointHost = resolveEndpointHost(handle);
        int endpointPort = handle != null ? handle.getPort() : BACKEND_PORT;
        Endpoint endpoint = new Endpoint(endpointHost, endpointPort);

        CacheCluster cluster = new CacheCluster(
                clusterId, CacheClusterStatus.AVAILABLE, ENGINE, ENGINE_VERSION,
                endpoint, Instant.now());
        if (handle != null) {
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
        } else {
            LOG.warnv("Memcached cluster {0} created without a backing container: no Docker daemon "
                    + "is reachable. Metadata operations work; connections to the cache do not "
                    + "until a daemon appears.", clusterId);
        }

        clusters.put(clusterId, cluster);
        LOG.infov("Memcached cluster {0} created, endpoint={1}:{2}", clusterId, endpointHost, endpointPort);
        return cluster;
    }

    /**
     * Restarts the container behind every cache cluster restored from disk. Invoked from
     * {@code EmulatorLifecycle} after {@code storageFactory.loadAll()}, for the same reason
     * {@code ElastiCacheService.restorePersistedRuntime} exists: only the record is persisted,
     * the container is process-local, and a cluster left unreconciled reports {@code available}
     * with nothing behind its endpoint. A cluster whose container cannot be brought back reports
     * {@code restore-failed} instead.
     *
     * <p>Starting a container waits for a readiness probe and may pull an image, so the work runs
     * in the background and must not delay emulator readiness. Clusters are marked
     * {@code creating} synchronously and flip to {@code available} or {@code restore-failed} as
     * each restore finishes. The cache comes back empty, as on any Floci restart.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        List<CacheCluster> toRestore = new ArrayList<>();
        for (CacheCluster cluster : clusters.scan(k -> true)) {
            if (cluster.getCacheClusterStatus() == CacheClusterStatus.DELETING) {
                continue;
            }
            cluster.setCacheClusterStatus(CacheClusterStatus.CREATING);
            clusters.put(cluster.getCacheClusterId(), cluster);
            toRestore.add(cluster);
        }
        if (toRestore.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOG.infov("Restoring {0} Memcached cluster(s) in the background", String.valueOf(toRestore.size()));
        return CompletableFuture.runAsync(() -> toRestore.forEach(this::restoreCluster));
    }

    /**
     * The endpoint is rebuilt from the new container rather than replayed from the record: the
     * host port Docker publishes is chosen per run, so a restored cluster that kept its old
     * endpoint would advertise a port nothing listens on.
     */
    private void restoreCluster(CacheCluster cluster) {
        String clusterId = cluster.getCacheClusterId();
        String image = config.services().elasticache().defaultMemcachedImage();
        try {
            ElastiCacheContainerHandle handle = containerManager.tryStart(clusterId, image);
            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                cluster.setConfigurationEndpoint(
                        new Endpoint(resolveEndpointHost(handle), handle.getPort()));
            } else {
                // Cleared rather than left alone: whatever the record carried describes a
                // container from the previous process, and nothing must read it as live.
                cluster.setContainerId(null);
                cluster.setContainerHost(null);
                cluster.setContainerPort(0);
                LOG.warnv("Memcached cluster {0} restored without a backing container: no Docker "
                        + "daemon is reachable. Metadata operations work; connections to the cache "
                        + "do not until a daemon appears.", clusterId);
            }
            cluster.setCacheClusterStatus(CacheClusterStatus.AVAILABLE);
            clusters.put(clusterId, cluster);
            LOG.infov("Restored Memcached cluster {0}, endpoint={1}:{2}", clusterId,
                    cluster.getConfigurationEndpoint().address(),
                    String.valueOf(cluster.getConfigurationEndpoint().port()));
        } catch (RuntimeException e) {
            cluster.setContainerId(null);
            cluster.setContainerHost(null);
            cluster.setContainerPort(0);
            cluster.setCacheClusterStatus(CacheClusterStatus.RESTORE_FAILED);
            cluster.setConfigurationEndpoint(null);
            try {
                clusters.put(clusterId, cluster);
            } catch (RuntimeException persistFailure) {
                e.addSuppressed(persistFailure);
            }
            LOG.warnv(e, "Failed to restore Memcached cluster {0}", clusterId);
        }
    }

    public CacheCluster getCacheCluster(String clusterId) {
        return clusters.get(clusterId).orElseThrow(() ->
                new AwsException("CacheClusterNotFound",
                        "Cache cluster " + clusterId + " not found.", 404));
    }

    public Collection<CacheCluster> listCacheClusters(String filterClusterId) {
        if (filterClusterId != null && !filterClusterId.isBlank()) {
            return clusters.get(filterClusterId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFound",
                            "Cache cluster " + filterClusterId + " not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public CacheCluster deleteCacheCluster(String clusterId) {
        CacheCluster cluster = getCacheCluster(clusterId);

        cluster.setCacheClusterStatus(CacheClusterStatus.DELETING);
        clusters.put(clusterId, cluster);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    cluster.getContainerId(), clusterId,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        clusters.delete(clusterId);
        LOG.infov("Memcached cluster {0} deleted", clusterId);
        return cluster;
    }

    private String resolveEndpointHost(ElastiCacheContainerHandle handle) {
        return config.hostname().orElse(handle != null ? handle.getHost() : "localhost");
    }
}
