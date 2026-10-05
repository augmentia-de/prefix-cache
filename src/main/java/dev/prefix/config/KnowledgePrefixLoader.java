package dev.prefix.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the shared knowledge prefix from a text file.
 * <p>
 * The prefix contains synthetic facts as "knowledge" that is available to all three agents
 * in the system message. Every time an agent sends a request
 * this prefix is prepended as a byte-identical header (block A) —
 * this enables provider-side prefix caching by OpenAI and other models.
 * <p>
 * Pattern from quad-core: {@code PromptAssembler.buildInitialMessages()} does exactly
 * this: sysMsgBuilder.append(cachePrefix).append("\n\n").append(systemPrompt).
 */
@Component
public class KnowledgePrefixLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePrefixLoader.class);

    /** The loaded prefix text — always identical across requests */
    private final String prefixContent;

    public KnowledgePrefixLoader(
            @Value("${prefix.knowledge-file:classpath:prefix-data/synthetic-knowledge.txt}") String knowledgeFilePath) {
        String content = null;
        try {
            content = loadFileAsText(knowledgeFilePath);
            //content = content + content +content;
            log.info("[KnowledgePrefix] Loaded {} bytes from {}", content.length(), knowledgeFilePath);
        } catch (Exception e) {
            log.error("[KnowledgePrefix] Failed to load knowledge file: {}", e.getMessage());
            content = "No knowledge available.";
        }
        this.prefixContent = content;
    }

    /**
     * Returns the entire prefix text.
     * This text is prepended as block A to every system message.
     */
    public String getPrefix() {
        return prefixContent;
    }

    /**
     * Checks whether the prefix is empty or was not initialized.
     */
    public boolean isEmpty() {
        return prefixContent == null || prefixContent.isBlank();
    }

    /**
     * Reads a file (classpath or filesystem) and returns its content as a String.
     */
    private String loadFileAsText(String path) throws IOException, URISyntaxException {
        Path filePath;

        if (path.startsWith("classpath:")) {
            // remove the classpath: prefix, then resolve as a resource
            String resourcePath = path.substring("classpath:".length());
            var resource = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
            if (resource == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            filePath = Path.of(resource.toURI());
        } else {
            filePath = Path.of(path);
        }

        return Files.readString(filePath, StandardCharsets.UTF_8);
    }
}
