package com.bankos;

import com.bankos.datagen.SyntheticDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP on the generated synthetic dataset (offline profile, no model connectivity):
 * upload -> process -> cases with evidence and tool trace -> review -> replay -> metrics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:bankos-it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bankos.processing.async=false"
})
@ActiveProfiles("offline")
class BatchIntegrationTest {

    static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("bankos-it-data");
            new SyntheticDataGenerator(SyntheticDataGenerator.DEFAULT_SEED).generate(DATA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("bankos.data.policies-file", () -> DATA.resolve("policies.json").toString());
        registry.add("bankos.data.ground-truth-file", () -> DATA.resolve("ground_truth.json").toString());
        Path uploads = Files.createTempDirectory("bankos-it-uploads");
        registry.add("bankos.storage.upload-dir", uploads::toString);
    }

    @LocalServerPort
    int port;

    private final ObjectMapper json = JsonMapper.builder().build();
    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void syntheticBatchIsReconciledInvestigatedAndScoredAgainstGroundTruth() {
        String batchId = uploadSyntheticBatch();
        JsonNode processed = post("/api/batches/" + batchId + "/process", null, 200);
        assertThat(processed.path("status").asString()).isEqualTo("READY_FOR_REVIEW");
        assertThat(processed.path("policyVersion").asInt()).isEqualTo(1);
        assertThat(processed.path("totals").path("payments").asInt()).isEqualTo(201);
        assertThat(processed.path("totals").path("invoices").asInt()).isEqualTo(12);

        JsonNode metrics = get("/api/metrics?batchId=" + batchId, 200);
        JsonNode evaluation = metrics.path("evaluation");
        assertThat(evaluation.path("available").asBoolean()).isTrue();
        assertThat(evaluation.path("expectedAnomalies").asInt()).isEqualTo(20);
        assertThat(evaluation.path("missed")).isEmpty();
        assertThat(evaluation.path("unexpected")).isEmpty();
        assertThat(evaluation.path("precision").asDouble()).isEqualTo(1.0);
        assertThat(evaluation.path("recall").asDouble()).isEqualTo(1.0);
        assertThat(evaluation.path("statusAccuracy").asDouble()).isEqualTo(1.0);
        assertThat(metrics.path("toolInvocations").asLong()).isEqualTo(2L * 20);

        // Flagship case: duplicate settlement with exact CSV evidence and a recorded tool trace.
        JsonNode dup = get("/api/cases/CASE-" + batchId + "-PAY-1042-DUP", 200);
        assertThat(dup.path("type").asString()).isEqualTo("DUPLICATE_SETTLEMENT");
        assertThat(dup.path("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(dup.path("amount").decimalValue()).isEqualByComparingTo("48000.00");
        assertThat(dup.path("policyVersion").asInt()).isEqualTo(1);
        assertThat(texts(dup.path("settlementIds"))).containsExactlyInAnyOrder("SET-1042-A", "SET-1042-B");
        assertThat(dup.path("evidence")).hasSize(3);
        assertThat(dup.path("evidence").get(1).path("source").asString()).isEqualTo("settlements.csv");
        assertThat(dup.path("evidence").get(1).path("row").asInt()).isPositive();
        assertThat(dup.path("investigation").path("status").asString()).isEqualTo("OFFLINE_FALLBACK");
        assertThat(texts(dup.path("investigation").path("evidenceIds")))
                .isSubsetOf(ids(dup.path("evidence"))).isNotEmpty();
        List<String> tools = new ArrayList<>();
        dup.path("agentTrace").forEach(t -> {
            if (!t.path("tool").isNull() && t.path("state").asString().equals("SUCCEEDED")) {
                tools.add(t.path("tool").asString());
            }
        });
        assertThat(tools).containsExactly("getCaseEvidence", "getCasePolicy");
        assertThat(dup.path("graph").path("nodes").size()).isGreaterThanOrEqualTo(4);

        // Invoice contradiction cites the real PDF page.
        JsonNode invoiceCase = get("/api/cases/CASE-" + batchId + "-PAY-1042-INVMIS", 200);
        assertThat(invoiceCase.path("evidence").findValues("page").stream().anyMatch(p -> p.asInt() == 1)).isTrue();
        assertThat(invoiceCase.path("description").asString()).contains("RF-260024");

        // Scanned invoice: unreadable, escalated, nothing invented.
        JsonNode unreadable = get("/api/cases/CASE-" + batchId + "-PAY-1090-NOINV", 200);
        assertThat(unreadable.path("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(unreadable.path("description").asString()).contains("UNREADABLE");

        // Ambiguous match: both candidates listed, none chosen.
        JsonNode ambiguous = get("/api/cases/CASE-" + batchId + "-SET-9002-AMB", 200);
        assertThat(ambiguous.path("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(ambiguous.path("evidence").findValuesAsString("recordId")).contains("PAY-1150", "PAY-1151");

        // Within tolerance -> auto-matched.
        assertThat(get("/api/cases/CASE-" + batchId + "-PAY-1188-AMT", 200).path("status").asString())
                .isEqualTo("AUTO_MATCHED");

        JsonNode events = get("/api/batches/" + batchId + "/events", 200);
        List<String> actions = new ArrayList<>();
        events.forEach(e -> actions.add(e.path("action").asString("") + e.path("tool").asString("")));
        assertThat(actions).contains("STATE_PARSING", "STATE_RECONCILING", "STATE_INVESTIGATING",
                "STATE_APPLYING_POLICY", "STATE_READY_FOR_REVIEW", "getCaseEvidence", "getCasePolicy");

        assertThat(get("/api/cases?batchId=" + batchId + "&status=AUTO_MATCHED", 200)).hasSize(4);
    }

    @Test
    void processingTheSameBatchTwiceCreatesNoDuplicateCases() {
        String batchId = uploadSyntheticBatch();
        post("/api/batches/" + batchId + "/process", null, 200);
        int first = get("/api/cases?batchId=" + batchId, 200).size();

        JsonNode again = post("/api/batches/" + batchId + "/process", null, 200);

        assertThat(again.path("status").asString()).isEqualTo("READY_FOR_REVIEW");
        assertThat(get("/api/cases?batchId=" + batchId, 200).size()).isEqualTo(first).isEqualTo(20);
        assertThat(again.path("totals").path("payments").asInt()).isEqualTo(201);
    }

    @Test
    void humanReviewIsAuditedWithoutOverwritingDetectionFacts() {
        String batchId = uploadSyntheticBatch();
        post("/api/batches/" + batchId + "/process", null, 200);
        String caseId = "CASE-" + batchId + "-PAY-1042-DUP";

        JsonNode review = post("/api/cases/" + caseId + "/review",
                Map.of("actor", "analyst.maria", "decision", "INVESTIGATING", "note", "Checking settlement lifecycle"), 201);
        assertThat(review.path("auditEventId").asLong()).isPositive();
        assertThat(review.path("reviewStatus").asString()).isEqualTo("INVESTIGATING");

        post("/api/cases/" + caseId + "/review", Map.of("actor", "lead.ion", "decision", "APPROVE"), 201);

        JsonNode detail = get("/api/cases/" + caseId, 200);
        assertThat(detail.path("status").asString()).as("policy status untouched").isEqualTo("REVIEW_REQUIRED");
        assertThat(detail.path("type").asString()).isEqualTo("DUPLICATE_SETTLEMENT");
        assertThat(detail.path("evidence")).hasSize(3);
        assertThat(detail.path("review").path("status").asString()).isEqualTo("APPROVED");
        assertThat(detail.path("review").path("reviewedBy").asString()).isEqualTo("lead.ion");
        List<String> audit = new ArrayList<>();
        detail.path("auditTrail").forEach(a -> audit.add(a.path("action").asString() + ":" + a.path("actor").asString()));
        assertThat(audit).contains("REVIEW_INVESTIGATING:analyst.maria", "REVIEW_APPROVE:lead.ion");

        JsonNode invalid = post("/api/cases/" + caseId + "/review", Map.of("decision", "APPROVE"), 400);
        assertThat(invalid.path("error").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void policyReplayReturnsVersionedDiffWithoutOverwritingHistory() {
        String batchId = uploadSyntheticBatch();
        post("/api/batches/" + batchId + "/process", null, 200);

        JsonNode replay = post("/api/policies/replay", Map.of("batchId", batchId, "amountTolerance", 5), 200);

        assertThat(replay.path("simulationOnly").asBoolean()).isTrue();
        assertThat(replay.path("baseVersion").path("version").asInt()).isEqualTo(1);
        int newVersion = replay.path("newVersion").path("version").asInt();
        assertThat(newVersion).isGreaterThan(1);
        assertThat(replay.path("newVersion").path("amountTolerance").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(replay.path("evaluatedCases").asInt()).isEqualTo(20);
        assertThat(replay.path("changedCases").asInt()).isEqualTo(1);
        JsonNode change = replay.path("changes").get(0);
        assertThat(change.path("caseId").asString()).isEqualTo("CASE-" + batchId + "-PAY-1188-AMT");
        assertThat(change.path("beforeStatus").asString()).isEqualTo("AUTO_MATCHED");
        assertThat(change.path("afterStatus").asString()).isEqualTo("REVIEW_REQUIRED");

        JsonNode stored = get("/api/cases/CASE-" + batchId + "-PAY-1188-AMT", 200);
        assertThat(stored.path("status").asString()).isEqualTo("AUTO_MATCHED");
        assertThat(stored.path("policyVersion").asInt()).isEqualTo(1);
        assertThat(get("/api/policies/1", 200).path("amountTolerance").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(get("/api/policies/" + newVersion, 200).path("basedOn").asInt()).isEqualTo(1);
    }

    @Test
    void invalidInputGetsActionableErrors() {
        Path bad = DATA.resolve("bad-payments.csv");
        try {
            Files.writeString(bad, "id,amount\nPAY-1,10.00\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("payments", new FileSystemResource(bad));
        parts.add("settlements", new FileSystemResource(DATA.resolve("settlements.csv")));
        JsonNode error = multipart(parts, 400);
        assertThat(error.path("error").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(texts(error.path("details"))).contains("payment_id", "reference", "currency");

        assertThat(get("/api/cases/CASE-NOPE", 404).path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(post("/api/batches/B999/process", null, 404).path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/metrics", 400).path("error").asString()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void healthEndpointIsUp() {
        assertThat(get("/actuator/health", 200).path("status").asString()).isEqualTo("UP");
    }

    // ------------------------------------------------------------------ helpers

    private String uploadSyntheticBatch() {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("payments", new FileSystemResource(DATA.resolve("payments.csv")));
        parts.add("settlements", new FileSystemResource(DATA.resolve("settlements.csv")));
        try (Stream<Path> pdfs = Files.list(DATA.resolve("invoices"))) {
            pdfs.sorted().forEach(p -> parts.add("invoices", new FileSystemResource(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode batch = multipart(parts, 201);
        assertThat(batch.path("status").asString()).isEqualTo("UPLOADED");
        return batch.path("id").asString();
    }

    private JsonNode multipart(MultiValueMap<String, Object> parts, int expectedStatus) {
        return exchange(http.post().uri("/api/batches").contentType(MediaType.MULTIPART_FORM_DATA).body(parts), expectedStatus);
    }

    private JsonNode get(String uri, int expectedStatus) {
        return exchange(http.get().uri(uri), expectedStatus);
    }

    private JsonNode post(String uri, Object body, int expectedStatus) {
        RestClient.RequestBodySpec spec = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }
        return exchange(spec, expectedStatus);
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> spec, int expectedStatus) {
        return spec.exchange((request, response) -> {
            String content = new String(response.getBody().readAllBytes());
            assertThat(response.getStatusCode().value()).as(request.getURI() + " -> " + content).isEqualTo(expectedStatus);
            return content.isEmpty() ? json.nullNode() : json.readTree(content);
        });
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }

    private static List<String> ids(JsonNode evidence) {
        List<String> values = new ArrayList<>();
        evidence.forEach(e -> values.add(e.path("id").asString()));
        return values;
    }
}
