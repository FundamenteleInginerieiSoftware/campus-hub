package com.campus.project.service;

import com.campus.project.domain.Project;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// Pure in-memory scoring engine for student project queries (no database, no external AI service).
// Matches queries in either Romanian or English against both languages' keywords and titles.
@Component
public class ProjectSearch {

    // Exact keyword match ranks highest so naming the core component or topic wins outright.
    static final int EXACT = 3;

    // Prefix match catches Romanian/English plurals and definite articles (e.g. "termometre", "termometrul") just below
    // exact hits.
    static final int PREFIX = 2;

    // Title word match gives a +1 tie-breaker boost when the searched word also appears in the project title.
    static final int TITLE = 1;

    // Minimum 5-character stem prevents short words like "pwm" or "led" from falsely matching unrelated words.
    static final int MIN_PREFIX = 5;

    // When the top two projects are within 1 point, the UI shows a "Did you mean..." prompt with both choices.
    static final int AMBIGUITY_GAP = 1;

    // Conversational filler words stripped in both languages so "vreau sa construiesc un termometru" scores like
    // "construiesc termometru".
    private static final Set<String> STOP_WORDS = Set.of(
            "vreau", "sa", "un", "o", "pentru", "de", "la", "cu", "si", "care", "imi", "este", "din", "i", "want", "to",
            "build", "make", "a", "an", "the", "for", "with", "my", "of", "and");

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Comparator<Project> BY_EN_TITLE_AND_SLUG = Comparator.comparing(
                    (Project p) -> p.title().en(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Project::slug);

    private static final Comparator<ScoredProject> BY_SCORE_THEN_EN_TITLE = Comparator.comparingInt(
                    ScoredProject::score)
            .reversed()
            .thenComparing(sp -> sp.project(), BY_EN_TITLE_AND_SLUG);

    // NFD decomposes both standard comma-below (ș, ț) and legacy cedilla (ş, ţ) into ASCII base letter + combining
    // mark.
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("");
    }

    public static List<String> tokens(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(NON_ALPHANUMERIC.split(normalized))
                .filter(token -> token.length() >= 2 && !STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    public SearchResult search(String query, List<Project> projects) {
        List<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            List<ScoredProject> allSorted = projects.stream()
                    .sorted(BY_EN_TITLE_AND_SLUG)
                    .map(project -> new ScoredProject(project, 0))
                    .toList();
            return new SearchResult(allSorted, false);
        }

        List<ScoredProject> hits = projects.stream()
                .map(project -> new ScoredProject(project, scoreProject(project, queryTokens)))
                .filter(scored -> scored.score() > 0)
                .sorted(BY_SCORE_THEN_EN_TITLE)
                .toList();

        boolean ambiguous =
                hits.size() >= 2 && (hits.get(0).score() - hits.get(1).score() <= AMBIGUITY_GAP);
        return new SearchResult(hits, ambiguous);
    }

    private int scoreProject(Project project, List<String> queryTokens) {
        // Bug fixed: normalizing keywords without splitting on non-alphanumeric characters meant multi-word or
        // hyphenated keywords (e.g. "line follower", "hc-sr04") could never match tokenized query words.
        // Fix: tokenize keywords with tokens(...) so keywords and queries share the exact same token rules.
        List<String> keywords = project.keywords().all().stream()
                .flatMap(kw -> tokens(kw).stream())
                .distinct()
                .toList();

        Set<String> titleWords = new HashSet<>();
        titleWords.addAll(tokens(project.title().ro()));
        titleWords.addAll(tokens(project.title().en()));

        int total = 0;
        for (String token : queryTokens) {
            total += keywordScore(token, keywords) + (titleWords.contains(token) ? TITLE : 0);
        }
        return total;
    }

    private int keywordScore(String token, List<String> keywords) {
        int best = 0;
        for (String kw : keywords) {
            if (token.equals(kw)) {
                return EXACT;
            }
            if (token.length() >= MIN_PREFIX
                    && kw.length() >= MIN_PREFIX
                    && token.regionMatches(0, kw, 0, MIN_PREFIX)) {
                best = Math.max(best, PREFIX);
            }
        }
        return best;
    }
}
