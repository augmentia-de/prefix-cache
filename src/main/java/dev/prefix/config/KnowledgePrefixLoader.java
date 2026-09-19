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
 * Lädt den gemeinsamen Knowledge-Prefix aus einer Textdatei.
 * <p>
 * Der Prefix enthält synthetische Fakten als "Wissen", das allen drei Agenten
 * im SystemMessage zur Verfügung steht. Jedes Mal wenn ein Agent einen Request
 * sendet wird dieser Prefix als byte-identischer Header (Block A) vorangestellt —
 * ermöglicht Provider-seitiges Prefix-Caching von OpenAI und anderen Modellen.
 * <p>
 * Pattern aus quad-core: {@code PromptAssembler.buildInitialMessages()} tut exakt
 * dies: sysMsgBuilder.append(cachePrefix).append("\n\n").append(systemPrompt).
 */
@Component
public class KnowledgePrefixLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePrefixLoader.class);

    /** Der geladene Prefix-Text — immer identisch über Requests hinweg */
    private final String prefixContent;

    public KnowledgePrefixLoader(
            @Value("${prefix.knowledge-file:classpath:prefix-data/synthetic-knowledge.txt}") String knowledgeFilePath) {
        String content = null;
        try {
            content = loadFileAsText(knowledgeFilePath);
            log.info("[KnowledgePrefix] Loaded {} bytes from {}", content.length(), knowledgeFilePath);
        } catch (Exception e) {
            log.error("[KnowledgePrefix] Failed to load knowledge file: {}", e.getMessage());
            content = "No knowledge available.";
        }
        this.prefixContent = content;
    }

    /**
     * Gibt den gesamten Prefix-Text zurück.
     * Dieser Text wird als Block A in jedes SystemMessage geprepended.
     */
    public String getPrefix() {
        return prefixContent;
    }

    /**
     * Prüft ob der Prefix leer oder nicht initialisiert wurde.
     */
    public boolean isEmpty() {
        return prefixContent == null || prefixContent.isBlank();
    }

    /**
     * Liest eine Datei (classpath oder filesystem) und gibt ihren Inhalt als String zurück.
     */
    private String loadFileAsText(String path) throws IOException, URISyntaxException {
        Path filePath;

        if (path.startsWith("classpath:")) {
            // classpath: prefix entfernen, dann als Resource auflösen
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
