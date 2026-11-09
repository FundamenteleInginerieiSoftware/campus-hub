package com.campus.project.service;

import com.campus.project.domain.LocalizedText;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.domain.ProjectStep;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and validates the curated project JSON files from the classpath once at startup.
 *
 * <p>If a file has a syntax error, an unknown field typo, a missing translation, or exceeds Calin's
 * 5-item bundle booking limit, startup fails immediately with the filename and field path so broken
 * content never reaches a demo or production deployment.
 */
@Component
public class ProjectLibrary {

    public static final String DEFAULT_PATTERN = "classpath*:projects/*.json";

    // Must match Calin's bundle reservation limit so every valid project can be booked in one go.
    private static final int MAX_LENDABLE_UNITS = 5;
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]+$");

    private final List<Project> projects;
    private final Map<String, Project> bySlug;

    @Autowired
    public ProjectLibrary(JsonMapper mapper) {
        this(mapper, DEFAULT_PATTERN);
    }

    public ProjectLibrary(JsonMapper mapper, String locationPattern) {
        // Jackson 3 disables FAIL_ON_UNKNOWN_PROPERTIES by default; enabling it on this reader
        // catches typos like "titel" at startup instead of silently leaving the field null.
        ObjectReader reader = mapper.readerFor(Project.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan project files at " + locationPattern, e);
        }

        if (resources.length == 0) {
            throw new IllegalStateException("No project files found at " + locationPattern);
        }

        // Sort deterministically by filename so duplicate-slug errors do not depend on OS filesystem order.
        Arrays.sort(resources, Comparator.comparing(r -> Objects.requireNonNullElse(r.getFilename(), "")));

        List<Project> loaded = new ArrayList<>(resources.length);
        Set<String> seenSlugs = new HashSet<>();

        for (Resource resource : resources) {
            String filename = Objects.requireNonNullElse(resource.getFilename(), resource.getDescription());
            Project project = readProject(reader, resource, filename);
            validate(project, filename, seenSlugs);
            loaded.add(project);
        }

        loaded.sort(Comparator.comparing(Project::slug));
        this.projects = List.copyOf(loaded);
        this.bySlug = this.projects.stream().collect(Collectors.toUnmodifiableMap(Project::slug, Function.identity()));
    }

    public List<Project> all() {
        return projects;
    }

    public Optional<Project> find(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySlug.get(slug));
    }

    private static Project readProject(ObjectReader reader, Resource resource, String filename) {
        // getInputStream() works inside the packaged Spring Boot fat jar, whereas getFile() fails on jar: URLs.
        try (InputStream in = resource.getInputStream()) {
            Project project = reader.readValue(in);
            if (project == null) {
                throw error(filename, "root", "must be a JSON object");
            }
            return project;
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("projects/" + filename + ": invalid JSON (" + e.getMessage() + ")", e);
        }
    }

    static void validate(Project project, String filename, Set<String> seenSlugs) {
        if (project.slug() == null || project.slug().isBlank()) {
            throw error(filename, "slug", "must not be blank");
        }
        if (!SLUG_PATTERN.matcher(project.slug()).matches()) {
            throw error(filename, "slug", "must match [a-z0-9-]+, got '" + project.slug() + "'");
        }
        if (!seenSlugs.add(project.slug())) {
            throw error(filename, "slug", "duplicate slug '" + project.slug() + "'");
        }

        if (project.difficulty() == null) {
            throw error(filename, "difficulty", "is required");
        }
        if (project.estimatedHours() < 1) {
            throw error(filename, "estimatedHours", "must be at least 1");
        }

        requireLocalizedText(project.title(), filename, "title");
        requireLocalizedText(project.summary(), filename, "summary");

        if (project.keywords() == null) {
            throw error(filename, "keywords", "is required (keywords.ro and keywords.en)");
        }
        requireKeywords(project.keywords().ro(), filename, "keywords.ro");
        requireKeywords(project.keywords().en(), filename, "keywords.en");

        validateComponents(project.components(), filename);
        validateLocalizedList(project.bringYourself(), filename, "bringYourself", true);
        validateSteps(project.steps(), filename);
        validateLocalizedList(project.mistakes(), filename, "mistakes", false);
    }

    private static void validateComponents(List<ProjectComponent> components, String filename) {
        if (components == null || components.isEmpty()) {
            throw error(filename, "components", "must not be empty");
        }

        Set<String> seenModels = new HashSet<>();
        // Bug fixed: summing int quantities could overflow 32-bit signed int on crafted large values and bypass
        // totalUnits > MAX_LENDABLE_UNITS. Fix: accumulate in a 64-bit long.
        long totalUnits = 0;

        for (int i = 0; i < components.size(); i++) {
            ProjectComponent component = components.get(i);
            String prefix = "components[" + i + "]";
            if (component == null) {
                throw error(filename, prefix, "must not be null");
            }
            if (component.model() == null || component.model().isBlank()) {
                throw error(filename, prefix + ".model", "must not be blank");
            }
            if (!seenModels.add(component.model())) {
                throw error(
                        filename,
                        prefix + ".model",
                        "duplicate model '" + component.model() + "' (increase quantity instead)");
            }
            if (component.quantity() < 1) {
                throw error(filename, prefix + ".quantity", "must be at least 1");
            }
            requireLocalizedText(component.why(), filename, prefix + ".why");
            totalUnits += component.quantity();
        }

        if (totalUnits > MAX_LENDABLE_UNITS) {
            throw error(
                    filename,
                    "components",
                    "total quantity " + totalUnits + " exceeds bundle maximum of " + MAX_LENDABLE_UNITS);
        }
    }

    private static void validateSteps(List<ProjectStep> steps, String filename) {
        if (steps == null || steps.isEmpty()) {
            throw error(filename, "steps", "must not be empty");
        }
        for (int i = 0; i < steps.size(); i++) {
            ProjectStep step = steps.get(i);
            String prefix = "steps[" + i + "]";
            if (step == null) {
                throw error(filename, prefix, "must not be null");
            }
            requireLocalizedText(step.title(), filename, prefix + ".title");
            requireLocalizedText(step.text(), filename, prefix + ".text");
        }
    }

    private static void validateLocalizedList(
            List<LocalizedText> items, String filename, String fieldPath, boolean allowEmpty) {
        if (items == null || (!allowEmpty && items.isEmpty())) {
            throw error(filename, fieldPath, allowEmpty ? "must not be null" : "must not be empty");
        }
        for (int i = 0; i < items.size(); i++) {
            requireLocalizedText(items.get(i), filename, fieldPath + "[" + i + "]");
        }
    }

    private static void requireKeywords(List<String> keywords, String filename, String fieldPath) {
        if (keywords == null || keywords.isEmpty()) {
            throw error(filename, fieldPath, "must not be empty");
        }
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            if (keyword == null || keyword.isBlank()) {
                throw error(filename, fieldPath + "[" + i + "]", "must not be blank");
            }
        }
    }

    private static void requireLocalizedText(LocalizedText text, String filename, String fieldPath) {
        if (text == null) {
            throw error(filename, fieldPath, "is required (" + fieldPath + ".ro and " + fieldPath + ".en)");
        }
        if (text.ro() == null || text.ro().isBlank()) {
            throw error(filename, fieldPath + ".ro", "must not be blank");
        }
        if (text.en() == null || text.en().isBlank()) {
            throw error(filename, fieldPath + ".en", "must not be blank");
        }
    }

    private static IllegalStateException error(String filename, String fieldPath, String problem) {
        return new IllegalStateException("projects/" + filename + ": " + fieldPath + " " + problem);
    }
}
