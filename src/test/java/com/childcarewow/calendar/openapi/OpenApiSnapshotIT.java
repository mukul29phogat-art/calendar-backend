package com.childcarewow.calendar.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Snapshot test for the OpenAPI spec emitted by Springdoc.
 *
 * <p>The committed baseline lives at {@code docs/openapi.json}. CI runs this test as part of {@code
 * mvn verify}; any drift between what the running app emits and what's committed fails the build
 * with a diff. To regenerate the baseline after an intentional API change:
 *
 * <pre>{@code
 * mvn test -Dtest=OpenApiSnapshotIT -Dopenapi.snapshot=update
 * }</pre>
 *
 * <p>The test sorts JSON keys deterministically before comparing so unrelated ordering changes
 * don't surface as drift.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSnapshotIT {

  /** Project root → {@code docs/openapi.json}. Resolved relative to the working directory. */
  private static final Path SNAPSHOT_PATH = Path.of("docs", "openapi.json");

  @Autowired MockMvc mvc;

  @Test
  void specMatchesCommittedSnapshot() throws Exception {
    String currentSpec =
        mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();

    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    JsonNode currentTree = mapper.readTree(currentSpec);
    String currentNormalized =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentTree);

    boolean updateMode =
        Boolean.parseBoolean(System.getProperty("openapi.snapshot", "false"))
            || "update".equalsIgnoreCase(System.getenv("OPENAPI_SNAPSHOT"));
    boolean firstRun = !Files.exists(SNAPSHOT_PATH);

    if (updateMode || firstRun) {
      Files.createDirectories(SNAPSHOT_PATH.getParent());
      Files.writeString(SNAPSHOT_PATH, currentNormalized + "\n", StandardCharsets.UTF_8);
      // Updating or seeding — explicitly do not assert.
      return;
    }

    String committed = Files.readString(SNAPSHOT_PATH, StandardCharsets.UTF_8).trim();
    JsonNode committedTree = mapper.readTree(committed);
    String committedNormalized =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(committedTree);

    assertThat(currentNormalized.trim())
        .as(
            "OpenAPI spec drift detected. "
                + "Run `mvn test -Dtest=OpenApiSnapshotIT -Dopenapi.snapshot=update` to update.")
        .isEqualTo(committedNormalized.trim());
  }

  @Test
  void specHasExpectedTopLevelStructure() throws Exception {
    String spec = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode tree = mapper.readTree(spec);

    assertThat(tree.path("openapi").asText()).startsWith("3.");
    assertThat(tree.has("paths")).isTrue();
    // Series 4 endpoints we expect to see indexed in the paths object.
    List<String> expectedPaths =
        List.of(
            "/api/v1/auth/me",
            "/api/v1/users",
            "/api/v1/schools",
            "/api/v1/classrooms",
            "/api/v1/students",
            "/api/v1/whoami",
            "/api/v1/attachments/sign-upload");
    JsonNode paths = tree.path("paths");
    List<String> missing = new ArrayList<>();
    for (String p : expectedPaths) {
      if (!paths.has(p)) {
        missing.add(p);
      }
    }
    assertThat(missing).as("expected paths missing from spec").isEmpty();
  }

  /**
   * Helper used by {@link #specMatchesCommittedSnapshot} to canonicalize the JSON tree's key
   * ordering. Recurses through ObjectNodes and rewrites them with sorted-key copies. Currently
   * unused (the {@code ORDER_MAP_ENTRIES_BY_KEYS} feature on the ObjectMapper does the same job
   * during serialization), but kept in case Jackson's behavior changes.
   */
  @SuppressWarnings("unused")
  private static JsonNode sortKeysRecursive(JsonNode node, ObjectMapper mapper) {
    if (node instanceof ObjectNode obj) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        entries.add(Map.entry(e.getKey(), sortKeysRecursive(e.getValue(), mapper)));
      }
      Collections.sort(entries, Map.Entry.comparingByKey());
      ObjectNode sorted = mapper.createObjectNode();
      for (Map.Entry<String, JsonNode> e : entries) {
        sorted.set(e.getKey(), e.getValue());
      }
      return sorted;
    }
    return node;
  }
}
