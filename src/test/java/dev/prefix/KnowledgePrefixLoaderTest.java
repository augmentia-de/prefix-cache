package dev.prefix;

import dev.prefix.agent.ToolProvider;
import dev.prefix.config.KnowledgePrefixLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KnowledgePrefixLoader — NO Spring context needed.
 */
class KnowledgePrefixLoaderTest {

    private KnowledgePrefixLoader loader;

    @BeforeEach
    void setUp() {
        loader = new KnowledgePrefixLoader("classpath:prefix-data/synthetic-knowledge.txt");
    }

    @Test
    void knowledgePrefixIsLoadedFromFile() {
        String prefix = loader.getPrefix();
        assertThat(prefix).isNotBlank();

        System.out.println("=== Loaded prefix: " + prefix.length() + " chars ===");
        System.out.println("--- FIRST 200 CHARS ---");
        System.out.println(prefix.substring(0, Math.min(200, prefix.length())));
        System.out.println("--- END ---");

        assertThat(loader.isEmpty()).isFalse();
    }

    @Test
    void prefixContainsExpectedSyntheticFacts() {
        String prefix = loader.getPrefix();

        assertThat(prefix).contains("Novaris");
        assertThat(prefix).contains("Mount Thorne");
        assertThat(prefix).contains("Lake Kaelen");
        assertThat(prefix).contains("Dr. Elena Voss");
        assertThat(prefix).contains("Xytherium-7");
        assertThat(prefix).contains("Glowcap mushrooms");
        assertThat(prefix).contains("Comet Voss");
        assertThat(prefix).contains("Cerulean Bay");
        assertThat(prefix).contains("Ombra River");
        assertThat(prefix).contains("Lumina");
    }

    @Test
    void loaderHandlesMissingGracefully() {
        KnowledgePrefixLoader broken = new KnowledgePrefixLoader("classpath:nonexistent-file.txt");
        assertThat(broken.getPrefix()).contains("No knowledge available");
    }
}
