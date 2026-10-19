package com.coding.platformapi.trace;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TempoTraceClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TempoTraceClient client = new TempoTraceClient(builder.build(), "http://tempo:3200");

    @Test
    void searchBuildsATraceQlQueryWithClusterAndService() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/search");
                    String query = request.getURI().getQuery();
                    assertThat(query).contains("resource.cluster.name").contains("coding-cluster")
                            .contains("resource.service.name").contains("movie")
                            .contains("limit=20");
                })
                .andRespond(withSuccess("""
                        {"traces":[{"traceID":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","rootServiceName":"movie",
                        "rootTraceName":"GET /","startTimeUnixNano":"1","durationMs":12}]}
                        """, MediaType.APPLICATION_JSON));

        TraceSearchRequest req = new TraceSearchRequest();
        req.setClusterName("coding-cluster");
        req.setServiceName("movie");
        req.setStart(1000L);
        req.setEnd(2000L);

        var out = client.search(req);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTraceId()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(out.get(0).getRootService()).isEqualTo("movie");
        assertThat(out.get(0).getDurationMs()).isEqualTo(12);
        server.verify();
    }

    @Test
    void traceDetailFlattensOtlpResourceAttributes() {
        String json = """
                {"batches":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"stringValue":"movie"}},
                  {"key":"cluster.name","value":{"stringValue":"coding-cluster"}},
                  {"key":"k8s.namespace.name","value":{"stringValue":"default"}}]},
                  "scopeSpans":[{"spans":[{
                    "spanId":"s1","parentSpanId":"","name":"GET /",
                    "startTimeUnixNano":"1000000000","endTimeUnixNano":"5000000000",
                    "attributes":[{"key":"http.method","value":{"stringValue":"GET"}}],
                    "status":{"code":"STATUS_CODE_OK"}}]}]}]}
                """;
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var out = client.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThat(out.getSpans()).hasSize(1);
        var span = out.getSpans().get(0);
        assertThat(span.getServiceName()).isEqualTo("movie");
        assertThat(span.getClusterName()).isEqualTo("coding-cluster");
        assertThat(span.getNamespace()).isEqualTo("default");
        assertThat(span.getDurationMs()).isEqualTo(4000);
        assertThat(span.getAttributes()).containsEntry("http.method", "GET");
        server.verify();
    }

    @Test
    void itAlsoAcceptsTheOtelResourceSpansShape() {
        String json = """
                {"resourceSpans":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"stringValue":"web"}}]},
                  "scopeSpans":[{"spans":[{
                    "spanId":"s2","name":"GET /x",
                    "startTimeUnixNano":"1000000000","endTimeUnixNano":"2000000000"}]}]}]}
                """;
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/traces/dddddddddddddddddddddddddddddddd"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var out = client.get("dddddddddddddddddddddddddddddddd");

        assertThat(out.getSpans()).hasSize(1);
        assertThat(out.getSpans().get(0).getServiceName()).isEqualTo("web");
        assertThat(out.getSpans().get(0).getDurationMs()).isEqualTo(1000);
    }

    @Test
    void servicesComeFromTempoTagValuesFilteredByCluster() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/api/v2/search/tag/resource.service.name/values");
                    String query = request.getURI().getQuery();
                    assertThat(query).contains("resource.cluster.name").contains("coding-cluster");
                })
                .andRespond(withSuccess("""
                        {"tagValues":[{"type":"string","value":"movie"},{"type":"string","value":"order"}]}
                        """, MediaType.APPLICATION_JSON));

        var out = client.serviceNames("coding-cluster", 1000L, 2000L);

        assertThat(out).containsExactly("movie", "order");
        server.verify();
    }

    @Test
    void searchAddsErrorAndDurationFiltersToTraceQl() {
        server.expect(request -> {
                    String query = java.net.URLDecoder.decode(
                            String.valueOf(request.getURI().getRawQuery()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(query).contains("status = error").contains("duration > 1000ms");
                })
                .andRespond(withSuccess("{\"traces\":[]}", MediaType.APPLICATION_JSON));

        TraceSearchRequest req = new TraceSearchRequest();
        req.setErrorsOnly(true);
        req.setMinDurationMs(1000);
        req.setStart(1000L);
        req.setEnd(2000L);

        client.search(req);

        server.verify();
    }

    @Test
    void durationAndCountsFallBackToTheTempoSearchShape() {
        // 部分 Tempo 版本的 /api/search 没有顶层 durationMs —— 用根 span 的 durationNanos，
        // 服务数/span 数从 serviceStats 汇总
        server.expect(request -> { })
                .andRespond(withSuccess("""
                        {"traces":[{"traceID":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "rootServiceName":"movie","rootTraceName":"GET /","startTimeUnixNano":"1000000000",
                        "spanSet":{"spans":[{"durationNanos":"1500000000"}]},
                        "serviceStats":{"movie":{"spanCount":3},"db":{"spanCount":2}}}]}
                        """, MediaType.APPLICATION_JSON));

        TraceSearchRequest req = new TraceSearchRequest();
        req.setStart(1000L);
        req.setEnd(2000L);

        var out = client.search(req);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getDurationMs()).isEqualTo(1500);
        assertThat(out.get(0).getServiceCount()).isEqualTo(2);
        assertThat(out.get(0).getSpanCount()).isEqualTo(5);
    }

    @Test
    void anInvalidTraceIdIsRejected() {
        assertThatThrownBy(() -> client.get("../../etc/passwd"))
                .hasMessageContaining("traceId");
    }
}
