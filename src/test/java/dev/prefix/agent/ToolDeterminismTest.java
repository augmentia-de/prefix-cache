package dev.prefix.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verhindert Cache-Misses durch nichtdeterministische Tool-Serialisierung.
 * <p>
 * Voraussetzung für Prefix-Caching: identische Tools müssen über alle Requests hinweg
 * byte-identisch serialisiert werden. Gefährlich wäre (a) ein toString()-String statt
 * eines JSON-Objekts für die Parameter und (b) eine HashMap-basierte, nichtdeterministische
 * Key-Reihenfolge. Beides wird hier abgesichert.
 */
class ToolDeterminismTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolParametersAreSerializedAsRealJsonObjectsNotStrings() throws Exception {
        for (ToolSpecification spec : ToolProvider.getAll()) {
            Map<String, Object> params = JsonSchemaElementUtils.toMap(spec.parameters());

            // parameters ist ein JSON-Objekt (LinkedHashMap), kein toString()-String
            assertTrue(params instanceof LinkedHashMap,
                    "parameters should use a LinkedHashMap for deterministic key order");

            assertEquals("object", params.get("type"), "schema type must be 'object'");
            assertTrue(params.containsKey("properties"), "schema must contain 'properties'");
            assertTrue(params.containsKey("required"), "schema must contain 'required'");
        }
    }

    @Test
    void toolSerializationIsByteIdenticalAcrossRepeatCalls() throws Exception {
        List<ToolSpecification> first = ToolProvider.getAll();
        List<ToolSpecification> second = ToolProvider.getAll();

        String jsonA = serializeTools(first);
        String jsonB = serializeTools(second);

        assertEquals(jsonA, jsonB, "Tool list must serialize byte-identically every time");
    }

    @Test
    void toolSerializationIsDeterministicWithinRun() throws Exception {
        String first = serializeTools(ToolProvider.getAll());
        String second = serializeTools(ToolProvider.getAll());
        assertEquals(first, second);
    }

    private String serializeTools(List<ToolSpecification> tools) throws Exception {
        List<Map<String, Object>> toolMaps = tools.stream().map(spec -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", spec.name());
            map.put("description", spec.description());
            map.put("parameters", JsonSchemaElementUtils.toMap(spec.parameters()));
            return map;
        }).toList();
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toolMaps);
    }
}