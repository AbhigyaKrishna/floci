package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2LambdaTargetHeadersIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261012/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7796;
    private static final String FUNCTION_NAME = "alb-multi-header-fn";

    @InjectSpy
    LambdaService lambdaService;

    @Test
    void forwardsEveryMultiValueHeaderFromLambdaResponse() {
        String functionArn = createFunction();
        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            byte[] payload = """
                    {
                        "statusCode": 200,
                        "headers": {"Set-Cookie": "single=0", "X-Single": "s"},
                        "multiValueHeaders": {"Set-Cookie": ["a=1; Path=/", "b=2; Path=/"]},
                        "body": "ok"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            doReturn(new InvokeResult(200, null, payload, null, "request-id"))
                    .when(lambdaService).invoke(anyString(), eq(functionArn), any(byte[].class),
                            eq(InvocationType.RequestResponse));

            loadBalancerArn = createLoadBalancer();
            targetGroupArn = createLambdaTargetGroup(functionArn);
            listenerArn = createListener(loadBalancerArn, targetGroupArn);

            Response response = await().atMost(Duration.ofSeconds(3)).until(() -> given()
                    .baseUri("http://127.0.0.1")
                    .port(LISTENER_PORT)
                .when()
                    .get("/"), r -> r.statusCode() == 200);

            assertEquals("ok", response.body().asString());
            assertEquals(List.of("a=1; Path=/", "b=2; Path=/"), response.headers().getValues("Set-Cookie"));
            assertEquals("s", response.header("X-Single"));
        } finally {
            deleteListener(listenerArn);
            deleteTargetGroup(targetGroupArn);
            deleteLoadBalancer(loadBalancerArn);
            given().when().delete("/2015-03-31/functions/" + FUNCTION_NAME).then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static String createFunction() {
        return given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "nodejs20.x",
                            "Role": "arn:aws:iam::000000000000:role/lambda-role",
                            "Handler": "index.handler"
                        }
                        """.formatted(FUNCTION_NAME))
            .when()
                .post("/2015-03-31/functions")
            .then()
                .statusCode(201)
                .extract()
                .path("FunctionArn");
    }

    private static String createLoadBalancer() {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "lambda-multi-header-lb")
                .formParam("Type", "application")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String createLambdaTargetGroup(String functionArn) {
        String targetGroupArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "lambda-multi-header-tg")
                .formParam("TargetType", "lambda")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", functionArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        return targetGroupArn;
    }

    private static String createListener(String loadBalancerArn, String targetGroupArn) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", LISTENER_PORT)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteTargetGroup(String targetGroupArn) {
        if (targetGroupArn != null) {
            given()
                    .formParam("Action", "DeleteTargetGroup")
                    .formParam("TargetGroupArn", targetGroupArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String loadBalancerArn) {
        if (loadBalancerArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
