package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A parsed {@code RunTask} (or {@code StartTask}) request.
 *
 * <p>ECS's launch surface is wide enough that threading it through positional parameters stopped
 * scaling, so the handler fills this and the service reads it. The narrower
 * {@code runTask(...)} overloads on the service remain for callers that only need the common
 * members, such as the EventBridge, Scheduler and Step Functions ECS targets.
 */
@RegisterForReflection
public class RunTaskRequest {

    private String cluster;
    private String taskDefinition;
    private int count = 1;
    private LaunchType launchType;
    private String group;
    private String startedBy;
    private List<ContainerOverride> containerOverrides;
    private NetworkConfiguration networkConfiguration;
    private List<String> containerInstances;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }

    public List<ContainerOverride> getContainerOverrides() { return containerOverrides; }
    public void setContainerOverrides(List<ContainerOverride> containerOverrides) {
        this.containerOverrides = containerOverrides;
    }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public List<String> getContainerInstances() { return containerInstances; }
    public void setContainerInstances(List<String> containerInstances) {
        this.containerInstances = containerInstances;
    }
}
