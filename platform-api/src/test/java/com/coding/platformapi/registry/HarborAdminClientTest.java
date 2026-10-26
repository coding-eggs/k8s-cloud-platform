package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HarborAdminClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HarborAdminClient client = new HarborAdminClient(builder.build());

    private static final String BASE = "http://harbor.example.com";
    private static final String PROJECT = "t-acme";

    @Test
    void existingProjectIsReused() {
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"project_id\":7,\"name\":\"t-acme\"}", APPLICATION_JSON));

        assertThat(client.ensureProject(BASE, "admin", "pw", PROJECT)).isEqualTo(7L);
    }

    @Test
    void missingProjectIsCreatedThenReadBackForItsId() {
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo(BASE + "/api/v2.0/projects"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"project_name\":\"t-acme\",\"public\":false}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED));
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"project_id\":9,\"name\":\"t-acme\"}", APPLICATION_JSON));

        assertThat(client.ensureProject(BASE, "admin", "pw", PROJECT)).isEqualTo(9L);
    }

    @Test
    void createRobotReturnsTheOneTimeSecret() {
        server.expect(requestTo(BASE + "/api/v2.0/robots"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"name":"ci","level":"project","duration":-1,
                         "permissions":[{"kind":"project","namespace":"t-acme",
                           "access":[{"resource":"repository","action":"push"},
                                     {"resource":"repository","action":"pull"}]}]}"""))
                .andRespond(withSuccess("{\"id\":42,\"name\":\"robot$t-acme+ci\",\"secret\":\"s3cr3t\"}",
                        APPLICATION_JSON));

        HarborAdminClient.HarborRobot robot = client.createRobot(BASE, "admin", "pw", PROJECT);
        assertThat(robot.id()).isEqualTo(42L);
        assertThat(robot.name()).isEqualTo("robot$t-acme+ci");
        assertThat(robot.secret()).isEqualTo("s3cr3t");
    }

    @Test
    void robotConflictMeansWeDeleteByNameThenCreate() {
        // 已存在的同名 robot（上一次同步留下的）：列表里能看到，删掉之后才能建
        server.expect(requestTo(BASE + "/api/v2.0/robots?page_size=100&q=Level%3Dproject,ProjectID%3D7"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"id":41,"name":"robot$t-acme+ci"},{"id":43,"name":"robot$t-acme+other"}]
                        """, APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v2.0/robots/41"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteRobotByName(BASE, "admin", "pw", 7L, "robot$t-acme+ci");
        server.verify();
    }
}
