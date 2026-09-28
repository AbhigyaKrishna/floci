package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsRegisteredTargets;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Bridges ECS services to ELBv2: when an ECS service declares a {@code loadBalancers}
 * block, this registrar registers each running task container as a target in the named
 * ELBv2 target group (and deregisters it when the task stops).
 * <p>
 * One-way dependency ECS → ELBv2; the ELBv2 data plane reaches the container over plain
 * TCP and never calls back into ECS, so there is no cycle.
 */
@ApplicationScoped
public class EcsLoadBalancerRegistrar {

    private static final Logger LOG = Logger.getLogger(EcsLoadBalancerRegistrar.class);

    private final ElbV2Service elbV2Service;
    private final EcsContainerManager containerManager;
    // taskArn → the targets registered for it, persisted so they can be released after a restart
    private final AccountAwareStorageBackend<EcsRegisteredTargets> ledger;

    @Inject
    public EcsLoadBalancerRegistrar(ElbV2Service elbV2Service, EcsContainerManager containerManager,
                                    StorageFactory storageFactory) {
        this.elbV2Service = elbV2Service;
        this.containerManager = containerManager;
        this.ledger = storageFactory.create("ecs", "ecs-registered-targets.json",
                new TypeReference<Map<String, EcsRegisteredTargets>>() {});
    }

    /**
     * Registers the task's load-balanced containers as ELBv2 targets. The targets are recorded
     * before they are registered, so a process killed in between still leaves a record for the
     * next run to release; releasing a recorded target that never registered is harmless.
     */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        List<EcsRegisteredTargets.Target> planned = new ArrayList<>();
        forEachTarget(task, svc, (tgArn, td) ->
                planned.add(new EcsRegisteredTargets.Target(tgArn, td.getId(), td.getPort())));
        if (planned.isEmpty()) {
            return;
        }
        String taskArn = task.getTaskArn();
        if (taskArn != null) {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(planned)));
        }
        List<EcsRegisteredTargets.Target> registered = new ArrayList<>();
        for (EcsRegisteredTargets.Target target : planned) {
            try {
                elbV2Service.registerTargets(region, target.targetGroupArn(), List.of(target.toDescription()));
                registered.add(target);
                LOG.infov("Registered ECS task target {0}:{1} into target group {2}",
                        target.id(), target.port(), target.targetGroupArn());
            } catch (Exception e) {
                LOG.warnv("Could not register ECS target into {0}: {1}", target.targetGroupArn(), e.getMessage());
            }
        }
        if (taskArn == null || registered.size() == planned.size()) {
            return;
        }
        if (registered.isEmpty()) {
            ledger.delete(taskArn);
        } else {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(registered)));
        }
    }

    /**
     * Deregisters the task's load-balanced containers from their ELBv2 target groups: the targets
     * {@link #registerTask} recorded, or, for a task it holds no record of, the ones its
     * containers resolve to now.
     */
    public void deregisterTask(EcsTask task, EcsServiceModel svc, String region) {
        Optional<EcsRegisteredTargets> recorded = task.getTaskArn() == null
                ? Optional.empty() : ledger.get(task.getTaskArn());
        if (recorded.isPresent()) {
            deregister(recorded.get());
            ledger.delete(task.getTaskArn());
            return;
        }
        forEachTarget(task, svc, (tgArn, td) -> deregister(region, tgArn, td));
    }

    /**
     * Deregisters every target ECS recorded for a task, in every account. Only for startup, when
     * ECS holds no task at all: task state is memory-only, so each recorded target belongs to a
     * task of a previous run whose container is gone or is being removed, and Docker is free to
     * hand its address to an unrelated container. Targets ECS did not register are never touched.
     */
    public void releaseRecordedTargets() {
        for (AccountAwareStorageBackend.AccountEntry<EcsRegisteredTargets> entry
                : ledger.scanAllAccountEntries(key -> true)) {
            RequestScopes.runAs(entry.accountId(), () -> deregister(entry.value()));
            ledger.deleteForAccount(entry.accountId(), entry.key());
            LOG.infov("Released the load balancer targets of ECS task {0} left by a previous run", entry.key());
        }
    }

    private void deregister(EcsRegisteredTargets recorded) {
        if (recorded.targets() == null) {
            return;
        }
        for (EcsRegisteredTargets.Target target : recorded.targets()) {
            deregister(recorded.region(), target.targetGroupArn(), target.toDescription());
        }
    }

    private void deregister(String region, String tgArn, TargetDescription td) {
        try {
            elbV2Service.deregisterTargets(region, tgArn, List.of(td));
            LOG.infov("Deregistered ECS task target {0}:{1} from target group {2}",
                    td.getId(), td.getPort(), tgArn);
        } catch (Exception e) {
            LOG.warnv("Could not deregister ECS target from {0}: {1}", tgArn, e.getMessage());
        }
    }

    private void forEachTarget(EcsTask task, EcsServiceModel svc,
                               BiConsumer<String, TargetDescription> action) {
        if (svc == null || svc.getLoadBalancers() == null || svc.getLoadBalancers().isEmpty()) {
            return;
        }
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return;
        }
        for (EcsLoadBalancer lb : svc.getLoadBalancers()) {
            if (lb.getTargetGroupArn() == null || lb.getTargetGroupArn().isBlank()) {
                continue;
            }
            Container container = task.getContainers().stream()
                    .filter(c -> lb.getContainerName() == null
                            || lb.getContainerName().equals(c.getName()))
                    .findFirst()
                    .orElse(null);
            if (container == null || container.getNetworkBindings() == null) {
                continue;
            }
            NetworkBinding binding = container.getNetworkBindings().stream()
                    .filter(b -> lb.getContainerPort() == null
                            || lb.getContainerPort() == b.containerPort())
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                continue;
            }
            TargetDescription td = new TargetDescription();
            td.setId(containerManager.resolveContainerHost(container));
            td.setPort(binding.hostPort());
            action.accept(lb.getTargetGroupArn(), td);
        }
    }
}
