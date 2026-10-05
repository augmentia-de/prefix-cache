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
 * Prevents cache misses through non-deterministic tool serialization.
 * <p>
 * Prerequisite for prefix caching: identical tools must be serialized
 * byte-identically across all requests. Dangerous would be (a) a toString() string instead
 * of a JSON object for the parameters and (b) a HashMap-based, non-deterministic
 * key order. Both are ruled out here.
 */
class ToolDeterminismTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolParametersAreSerializedAsRealJsonObjectsNotStrings() throws Exception {
        for (ToolSpecification spec : ToolProvider.getAll()) {
            Map<String, Object> params = JsonSchemaElementUtils.toMap(spec.parameters());

            // parameters is a JSON object (LinkedHashMap), not a toString() string
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