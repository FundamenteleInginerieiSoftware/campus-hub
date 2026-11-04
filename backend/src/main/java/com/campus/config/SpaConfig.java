package com.campus.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the React app in prod (ADR-002). The Dockerfile copies Stefania's {@code frontend/dist} into
 * {@code static/}, so {@code /index.html} and {@code /assets/*.js} are plain files.
 *
 * <p>React Router pages like {@code /reservations} or {@code /admin/incidents} are not files: they
 * exist only in the browser. If someone reloads such a page or opens a link to it, the server must
 * answer with {@code index.html}, and React then shows the right page. In dev nothing changes: Vite
 * serves the frontend and {@code static/} is empty, so these paths simply give 404.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Spring tries controllers first; this handler only sees paths that no controller mapped.
        // Registering "/**" here replaces Spring Boot's default static file handler for the same path.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new IndexHtmlFallback());
    }

    static class IndexHtmlFallback extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null) {
                return file;
            }
            // An unknown API path must stay a JSON 404, not turn into a web page with status 200.
            // A path with a dot is a missing file (an old /assets/index-abc.js after a deploy):
            // sending HTML instead would make the browser fail on it with a confusing error.
            if (resourcePath.startsWith("api/") || resourcePath.contains(".")) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
