package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code CreateCluster} request. */
@RegisterForReflection
public class CreateClusterRequest {

    private String clusterName;
    private Map<String, String> tags;
    private List<ClusterSetting> settings;

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<ClusterSetting> getSettings() { return settings; }
    public void setSettings(List<ClusterSetting> settings) { this.settings = settings; }
}
