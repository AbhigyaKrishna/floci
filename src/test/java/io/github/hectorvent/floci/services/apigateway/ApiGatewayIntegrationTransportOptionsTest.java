package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x500.X500Name;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the two REST integration transport settings AWS documents that Floci previously accepted
 * and ignored: {@code timeoutInMillis} and {@code tlsConfig.insecureSkipVerification}.
 *
 * <p>The TLS case matters because {@code HttpProxyInvoker} uses a default {@code HttpClient}: an
 * HTTPS backend presenting a self-signed certificate was unreachable with no way to opt out, which
 * is the common shape for an internal service behind a private CA.
 */
@QuarkusTest
class ApiGatewayIntegrationTransportOptionsTest {

    private static HttpServer slowServer;
    private static HttpsServer tlsServer;
    private static HttpsServer wrongHostTlsServer;
    private static HttpsServer expiredTlsServer;
    private static int slowPort;
    private static int tlsPort;
    private static int wrongHostTlsPort;
    private static int expiredTlsPort;

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackends() throws Exception {
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/", ApiGatewayIntegrationTransportOptionsTest::slowHandler);
        slowServer.start();
        slowPort = slowServer.getAddress().getPort();

        tlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tlsServer.setHttpsConfigurator(new HttpsConfigurator(selfSignedContext()));
        tlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        tlsServer.start();
        tlsPort = tlsServer.getAddress().getPort();

        // Same self-signed shape, but the certificate names a host this server is not reachable at,
        // so only hostname verification can reject it.
        wrongHostTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wrongHostTlsServer.setHttpsConfigurator(new HttpsConfigurator(
                selfSignedContextFor("wrong.example", List.of("wrong.example"))));
        wrongHostTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        wrongHostTlsServer.start();
        wrongHostTlsPort = wrongHostTlsServer.getAddress().getPort();

        // Correct hostname, self-signed, but expired two days ago.
        expiredTlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        expiredTlsServer.setHttpsConfigurator(new HttpsConfigurator(expiredSelfSignedContext()));
        expiredTlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        expiredTlsServer.start();
        expiredTlsPort = expiredTlsServer.getAddress().getPort();
    }

    @AfterAll
    static void stopBackends() {
        if (slowServer != null) slowServer.stop(0);
        if (tlsServer != null) tlsServer.stop(0);
        if (wrongHostTlsServer != null) wrongHostTlsServer.stop(0);
        if (expiredTlsServer != null) expiredTlsServer.stop(0);
    }

    /** Sleeps when asked to, so a configured timeout can be observed firing. */
    private static void slowHandler(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("slow=true")) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, 200, "{\"from\":\"backend\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** An SSLContext serving a genuinely self-signed cert no default trust store will accept. */
    private static SSLContext selfSignedContext() throws Exception {
        return selfSignedContextFor("localhost", List.of("localhost", "127.0.0.1"));
    }

    /**
     * A self-signed certificate for localhost whose validity window closed two days ago. Trusting
     * everything accepts it; AWS does not, because expiration is part of the basic validation that
     * survives insecureSkipVerification.
     */
    private static SSLContext expiredSelfSignedContext() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X500Name dn = new X500Name("CN=localhost");
        Instant now = Instant.now();
        X509Certificate certificate = generator.signCertificate(
                dn, keyPair.getPublic(), dn, keyPair.getPrivate(),
                List.of("localhost", "127.0.0.1"), false, CertificateGenerator.LeafUsage.SERVER,
                now.minus(10, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS));
        return contextFor(certificate, keyPair.getPrivate());
    }

    private static SSLContext selfSignedContextFor(String commonName, List<String> sans) throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                commonName, sans, KeyAlgorithm.RSA_2048);

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        generated.certificatePem().getBytes(StandardCharsets.UTF_8)));

        // RSA keys are emitted as PKCS#1 ("RSA PRIVATE KEY"), not PKCS#8, so use the generator's
        // own parser rather than PKCS8EncodedKeySpec.
        PrivateKey privateKey = generator.parsePrivateKey(generated.privateKeyPem());

        return contextFor(certificate, privateKey);
    }

    private static SSLContext contextFor(X509Certificate certificate, PrivateKey privateKey) throws Exception {
        char[] password = "floci-test".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("backend", privateKey, password, new Certificate[]{certificate});

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    /** Builds a deployed REST API whose /{proxy+} ANY method is an HTTP_PROXY to {@code targetUri}. */
    private String createApi(String name, String targetUri, String integrationExtras) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"ANY\",\"uri\":\"" + targetUri + "\""
                        + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void configuredTimeoutCutsOffASlowBackend() {
        String apiId = createApi("timeout-short", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":300");

        // Backend sleeps 2s; the 300ms integration timeout must fire and surface as 502.
        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(502);
    }

    @Test
    void aGenerousTimeoutLetsTheSameSlowBackendThrough() {
        String apiId = createApi("timeout-long", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":10000");

        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(200);
    }

    @Test
    void rejectsATimeoutBelowTheAwsMinimum() {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"timeout-invalid\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"GET\","
                        + "\"uri\":\"http://example.internal\",\"timeoutInMillis\":10}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration")
                .then().statusCode(400);
    }

    @Test
    void selfSignedHttpsBackendIsRejectedWithoutTlsConfig() {
        String apiId = createApi("tls-verified", "https://localhost:" + tlsPort + "/{proxy}", "");

        // Default trust store cannot verify the backend's self-signed cert → 502 Bad Gateway.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationReachesTheSelfSignedHttpsBackend() {
        String apiId = createApi("tls-insecure", "https://localhost:" + tlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(200)
                .body("tls", org.hamcrest.Matchers.equalTo("ok"));
    }

    @Test
    void insecureSkipVerificationStillVerifiesTheHostname() {
        String apiId = createApi("tls-insecure-wrong-host",
                "https://localhost:" + wrongHostTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // AWS documents insecureSkipVerification as skipping only the check that the certificate
        // was issued by a supported CA: expiration, hostname and the presence of a root CA are
        // still verified. A certificate issued for wrong.example must therefore fail against a
        // backend addressed as localhost.
        // See https://docs.aws.amazon.com/apigateway/latest/api/API_TlsConfig.html
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationStillRejectsAnExpiredCertificate() {
        String apiId = createApi("tls-insecure-expired",
                "https://localhost:" + expiredTlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        // Expiration is the other half of the basic validation AWS keeps. A backend whose
        // certificate has lapsed has to fail here too, or it works locally and breaks in AWS.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }
}
