package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The ELBv2 targets ECS registered for one task, persisted so a restarted Floci, which knows no
 * task, deregisters exactly these and never a target someone else registered in the same group.
 */
@RegisterForReflection
public record EcsRegisteredTargets(String region, List<Target> targets) {

    @RegisterForReflection
    public record Target(String targetGroupArn, String id, Integer port) {
    }
}
