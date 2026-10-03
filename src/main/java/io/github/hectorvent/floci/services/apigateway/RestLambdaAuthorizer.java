package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates and caches REST Lambda authorizer policies, then evaluates each requested method. */
@ApplicationScoped
public class RestLambdaAuthorizer {
    static final int MAX_ENTRIES = 1_000;

    private final ObjectMapper objectMapper;
    private final IamPolicyEvaluator policyEvaluator;
    private final Clock clock;
    private final ConcurrentHashMap<CacheKey, Entry> cache = new ConcurrentHashMap<>();

    @Inject
    public RestLambdaAuthorizer(ObjectMapper objectMapper, IamPolicyEvaluator policyEvaluator) {
        this(objectMapper, policyEvaluator, Clock.systemUTC());
    }

    RestLambdaAuthorizer(ObjectMapper objectMapper, IamPolicyEvaluator policyEvaluator, Clock clock) {
        this.objectMapper = objectMapper;
        this.policyEvaluator = policyEvaluator;
        this.clock = clock;
    }

    record Scope(String accountId, String region, String apiId, String stageName) { }

    record CacheKey(Scope scope, String deploymentId, String authorizerId, String uri,
                    String type, String identitySource, int ttlSeconds, List<String> identityValues) {
        CacheKey {
            identityValues = List.copyOf(identityValues);
        }
    }

    record Result(String principalId, String policyDocument, Map<String, Object> context) {
        Result {
            context = Map.copyOf(context);
        }
    }

    private record Entry(Result result, Instant expiresAt) { }

    Result parse(byte[] payload) throws IOException {
        JsonNode output = objectMapper.readTree(payload);
        if (output == null || !output.isObject() || !output.path("principalId").isTextual()
                || output.path("principalId").asText().isEmpty()) {
            throw new IllegalArgumentException("Authorizer must return a nonempty principalId");
        }
        JsonNode policy = output.path("policyDocument");
        validatePolicy(policy);
        return new Result(output.path("principalId").asText(), policy.toString(), parseContext(output.get("context")));
    }

    private static void validatePolicy(JsonNode policy) {
        JsonNode statements = policy.path("Statement");
        if (!policy.isObject() || (!statements.isObject() && !statements.isArray()) || statements.isEmpty()) {
            throw new IllegalArgumentException("Authorizer must return a policy with statements");
        }
        if (statements.isObject()) {
            validateStatement(statements);
            return;
        }
        for (JsonNode statement : statements) {
            validateStatement(statement);
        }
    }

    private static void validateStatement(JsonNode statement) {
        String effect = statement.path("Effect").asText();
        if (!statement.isObject() || (!"Allow".equals(effect) && !"Deny".equals(effect))
                || (statement.has("Action") == statement.has("NotAction"))
                || (statement.has("Resource") == statement.has("NotResource"))
                || !validStringOrList(statement.has("Action") ? statement.get("Action") : statement.path("NotAction"))
                || !validStringOrList(statement.has("Resource") ? statement.get("Resource") : statement.path("NotResource"))
                || (statement.has("Condition") && !statement.get("Condition").isObject())) {
            throw new IllegalArgumentException("Invalid authorizer policy statement");
        }
    }

    private static boolean validStringOrList(JsonNode value) {
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> parseContext(JsonNode context) {
        if (context == null) {
            return Map.of();
        }
        if (!context.isObject()) {
            throw new IllegalArgumentException("Authorizer context must be an object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : context.properties()) {
            JsonNode value = property.getValue();
            if (!property.getKey().matches("[A-Za-z0-9_]+")
                    || (!value.isTextual() && !value.isNumber() && !value.isBoolean())) {
                throw new IllegalArgumentException("Authorizer context must contain scalar values and valid keys");
            }
            values.put(property.getKey(), objectMapper.convertValue(value, Object.class));
        }
        return values;
    }

    boolean permits(Result result, String methodArn, Map<String, List<String>> conditions) {
        return policyEvaluator.evaluate(CallerContext.of(List.of(result.policyDocument())), null,
                "execute-api:Invoke", methodArn, conditions) == IamPolicyEvaluator.Decision.ALLOW;
    }

    Result get(CacheKey key) {
        if (key.ttlSeconds() <= 0) {
            return null;
        }
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            cache.remove(key, entry);
            return null;
        }
        return entry.result();
    }

    // As in the AppSync authorizer cache, sweep, eviction and insertion must share the capacity check.
    synchronized void put(CacheKey key, Result result) {
        if (key.ttlSeconds() <= 0) {
            return;
        }
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (!cache.containsKey(key) && cache.size() >= MAX_ENTRIES) {
            cache.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
        }
        cache.put(key, new Entry(result, now.plusSeconds(key.ttlSeconds())));
    }

    void flush(Scope scope) {
        cache.keySet().removeIf(key -> key.scope().equals(scope));
    }
}
