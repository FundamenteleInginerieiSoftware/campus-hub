package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Calls SpaConfig's fallback resolver directly, without Spring. SpaFrontendTest checks the same
 * behaviour over HTTP, but only with a frontend build present, and its Spring context (with the
 * resource chain's cache) is reused between tests, so PIT's mutants of this class went unnoticed.
 *
 * <p>When the resolver returns null, Spring answers 404 (SpaFrontendTest.missingFileIsA404NotTheReactApp
 * shows that over HTTP), so "null" here means "404" for the browser.
 */
class SpaConfigTest {

    private final SpaConfig.IndexHtmlFallback resolver = new SpaConfig.IndexHtmlFallback();

    // The trailing "/" matters: Spring builds "<location>/<path>" from it, like for classpath:/static/.
    private static Resource location(Path dir) {
        return new FileSystemResource(dir.toString() + "/");
    }

    private static Path frontendBuild(Path dir) throws IOException {
        Files.writeString(dir.resolve("index.html"), "<div id=\"root\">react</div>");
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("assets/app-123.js"), "console.log('app')");
        return dir;
    }

    @Test
    void existingFileIsServedAsItIs(@TempDir Path dir) throws IOException {
        Resource found = resolver.getResource("assets/app-123.js", location(frontendBuild(dir)));

        assertThat(found)
                .as("a file that exists in static/ is returned itself; see SpaConfig.IndexHtmlFallback.getResource")
                .isNotNull();
        assertThat(found.getFilename())
                .as("a file that exists in static/ is returned itself, not index.html;"
                        + " see SpaConfig.IndexHtmlFallback.getResource")
                .isEqualTo("app-123.js");
    }

    @Test
    void reactPageGivesIndexHtmlWhenTheBuildIsThere(@TempDir Path dir) throws IOException {
        Resource found = resolver.getResource("catalog", location(frontendBuild(dir)));

        assertThat(found)
                .as("a React page with no file of its own gets index.html; see SpaConfig.IndexHtmlFallback.getResource")
                .isNotNull();
        assertThat(found.getFilename())
                .as("a React page with no file of its own gets index.html; see SpaConfig.IndexHtmlFallback.getResource")
                .isEqualTo("index.html");
    }

    @Test
    void reactPageIsNotFoundWithoutAFrontendBuild(@TempDir Path emptyStatic) throws IOException {
        // Dev, or a Docker build that forgot the frontend: static/ has no index.html.
        Resource found = resolver.getResource("catalog", location(emptyStatic));

        assertThat(found)
                .as("without index.html a React page is a plain 404, not a missing file handed to Spring;"
                        + " see SpaConfig.IndexHtmlFallback.getResource")
                .isNull();
    }
}
