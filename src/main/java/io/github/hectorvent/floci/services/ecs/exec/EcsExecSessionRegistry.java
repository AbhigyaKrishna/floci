package io.github.hectorvent.floci.services.ecs.exec;

import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open {@code ExecuteCommand} sessions, keyed by session id.
 *
 * <p>A session is created by the ECS API and claimed once, by the client that opens the data
 * channel with the matching token. Sessions are in memory and short lived, the way AWS's are: the
 * token is single use and expires if nobody connects.
 */
@ApplicationScoped
public class EcsExecSessionRegistry implements Resettable {

    private static final Logger LOG = Logger.getLogger(EcsExecSessionRegistry.class);

    /** How long a session waits for its client before it is dropped. */
    static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ExecSession> sessions = new ConcurrentHashMap<>();

    /** Mints a session for a container, returning it with the token the client must present. */
    public ExecSession create(String taskArn, String clusterArn, String containerName,
                              String containerArn, String runtimeId, List<String> command,
                              boolean interactive) {
        expireStaleSessions();
        String sessionId = "ecs-execute-command-" + UUID.randomUUID().toString().replace("-", "");
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        ExecSession session = new ExecSession(sessionId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                taskArn, clusterArn, containerName, containerArn, runtimeId, command, interactive,
                Instant.now());
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * Claims a session for a connecting client. The session is removed, so a replayed token cannot
     * open a second channel into the container.
     */
    public Optional<ExecSession> claim(String sessionId, String tokenValue) {
        ExecSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.tokenValue().equals(tokenValue)) {
            LOG.warnv("Rejected an ECS Exec channel for session {0}: the token does not match", sessionId);
            return Optional.empty();
        }
        return sessions.remove(sessionId, session) ? Optional.of(session) : Optional.empty();
    }

    public Optional<ExecSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    private void expireStaleSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.values().removeIf(session -> session.createdAt().isBefore(cutoff));
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}
