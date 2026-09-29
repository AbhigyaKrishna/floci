package io.github.hectorvent.floci.services.ecs.model;

/**
 * An address a running task holds. A task's own address on a Docker network reaches only that
 * task whatever the port, so {@code port} is {@code null} for it. A loopback address is shared by
 * every task Floci publishes on the host, so it is the task's only together with one of the host
 * ports its containers were published on.
 */
public record EcsTaskAddress(String ip, Integer port) {

    public static EcsTaskAddress of(String ip, int hostPort) {
        return isLoopback(ip) ? new EcsTaskAddress(ip, hostPort) : new EcsTaskAddress(ip, null);
    }

    public static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "localhost".equals(ip) || "::1".equals(ip);
    }

    /** Whether an endpoint registered at {@code ip} and {@code port} reaches this task. */
    public boolean matches(String ip, Integer port) {
        return this.ip.equals(ip) && (this.port == null || this.port.equals(port));
    }
}
