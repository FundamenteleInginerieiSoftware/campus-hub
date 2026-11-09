package com.campus.project.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.project.domain.Difficulty;
import com.campus.project.domain.Keywords;
import com.campus.project.domain.LocalizedText;
import com.campus.project.domain.Project;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectSearchTest {

    private final ProjectSearch search = new ProjectSearch();

    @Test
    void exactKeywordScoresThree() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("termometru"), List.of("probe"));

        SearchResult result = search.search("termometru", List.of(p));

        assertThat(result.hits())
                .as("exact keyword match without title match must score 3; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 3));
    }

    @Test
    void pluralFindsSingularByPrefix() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("termometru"), List.of("probe"));
        Project fiveCharKw = project("degrees", "Scale unit", "Scara unitate", List.of("grade"), List.of("scale"));
        Project longerKw = project("degrees-pl", "Scale unit", "Scara unitate", List.of("gradele"), List.of("scale"));

        SearchResult result = search.search("termometre", List.of(p));
        SearchResult queryLongerThanFiveCharKw = search.search("gradele", List.of(fiveCharKw));
        SearchResult fiveCharQueryAgainstLongerKw = search.search("grade", List.of(longerKw));

        assertThat(result.hits())
                .as("plural sharing a 5+ character prefix with a keyword must score 2; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 2));
        assertThat(queryLongerThanFiveCharKw.hits())
                .as(
                        "7-char query 'gradele' must prefix-match 5-char keyword 'grade' with score 2; see ProjectSearch.keywordScore")
                .containsExactly(new ScoredProject(fiveCharKw, 2));
        assertThat(fiveCharQueryAgainstLongerKw.hits())
                .as(
                        "5-char query 'grade' must prefix-match 7-char keyword 'gradele' with score 2; see ProjectSearch.keywordScore")
                .containsExactly(new ScoredProject(longerKw, 2));
    }

    @Test
    void shortWordsDoNotPrefixMatch() {
        Project p = project("pwm", "Signal check", "Verificare semnal", List.of("pwms"), List.of("signal"));

        SearchResult result = search.search("pwm", List.of(p));
        List<String> boundaryTokens = ProjectSearch.tokens("x dc");

        assertThat(result.hits())
                .as("tokens shorter than 5 characters must not prefix-match; see ProjectSearch.search")
                .isEmpty();
        assertThat(boundaryTokens)
                .as("1-character tokens must be dropped while 2-character tokens are kept; see ProjectSearch.tokens")
                .containsExactly("dc");
    }

    @Test
    void diacriticsAreStripped() {
        Project temp =
                project("thermometer", "Heat probe", "Sonda caldura", List.of("temperatura"), List.of("temperature"));
        Project station = project("weather", "Weather box", "Cutie meteo", List.of("stație"), List.of("weather"));

        SearchResult queryWithDiacritics = search.search("temperatură", List.of(temp));
        SearchResult keywordWithDiacritics = search.search("statie", List.of(station));

        assertThat(queryWithDiacritics.hits())
                .as(
                        "diacritics on query must be stripped so 'temperatură' matches 'temperatura'; see ProjectSearch.normalize")
                .containsExactly(new ScoredProject(temp, 3));
        assertThat(keywordWithDiacritics.hits())
                .as("diacritics on keyword must be stripped so 'statie' matches 'stație'; see ProjectSearch.normalize")
                .containsExactly(new ScoredProject(station, 3));
    }

    @Test
    void cedillaFormsAreStripped() {
        Project station = project("weather", "Weather box", "Cutie meteo", List.of("statie"), List.of("weather"));

        String normalized = ProjectSearch.normalize("ştaţie");
        SearchResult result = search.search("ştaţie", List.of(station));

        assertThat(normalized)
                .as("legacy cedilla characters ş and ţ must normalize to ASCII s and t; see ProjectSearch.normalize")
                .isEqualTo("statie");
        assertThat(result.hits())
                .as(
                        "query with legacy cedilla 'ştaţie' must match keyword 'statie' with score 3; see ProjectSearch.search")
                .containsExactly(new ScoredProject(station, 3));
    }

    @Test
    void upperCaseAndArticleSuffixMatch() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("termometru"), List.of("probe"));

        SearchResult result = search.search("TERMOMETRUL", List.of(p));

        assertThat(result.hits())
                .as(
                        "uppercase definite article form 'TERMOMETRUL' must prefix-match 'termometru' with score 2; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 2));
    }

    @Test
    void bothLanguagesAlwaysSearched() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("senzor"), List.of("thermometer"));

        SearchResult result = search.search("thermometer", List.of(p));

        assertThat(result.hits())
                .as("search must always match against both RO and EN keywords; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 3));
    }

    @Test
    void titleWordAddsOne() {
        Project p = project(
                "thermometer",
                "Digital thermometer",
                "Termometru digital",
                List.of("termometru"),
                List.of("temperature"));

        SearchResult result = search.search("termometru", List.of(p));

        assertThat(result.hits())
                .as("token matching both a keyword (3) and a title word (1) must score 4; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 4));
    }

    @Test
    void repeatedWordCountsOnce() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("senzor"), List.of("probe"));

        SearchResult single = search.search("senzor", List.of(p));
        SearchResult repeated = search.search("senzor senzor", List.of(p));

        assertThat(repeated.hits())
                .as(
                        "duplicate query tokens must be deduplicated so repeating a word does not inflate the score; see ProjectSearch.tokens")
                .isEqualTo(single.hits());
    }

    @Test
    void stopWordsAreIgnored() {
        Project p = project(
                "thermometer", "Build a heat probe", "Vreau o sonda caldura", List.of("termometru"), List.of("probe"));

        SearchResult withStopWords = search.search("vreau sa construiesc un termometru", List.of(p));
        SearchResult withoutStopWords = search.search("construiesc termometru", List.of(p));
        SearchResult onlyStopWords = search.search("vreau să un cu i want to build a", List.of(p));

        assertThat(withStopWords)
                .as("Romanian and English stop words must be ignored before scoring; see ProjectSearch.tokens")
                .isEqualTo(withoutStopWords);
        assertThat(onlyStopWords.hits())
                .as("query containing only stop words must list all projects with score 0; see ProjectSearch.search")
                .containsExactly(new ScoredProject(p, 0));
    }

    @Test
    void emptyQueryListsAllByEnglishTitle() {
        Project z = project("first-slug", "Zeta meter", "Aparat zeta", List.of("zeta"), List.of("zeta"));
        Project a = project("second-slug", "Alpha scope", "Osciloscop alfa", List.of("alfa"), List.of("alpha"));

        SearchResult emptyResult = search.search("   ", List.of(z, a));
        SearchResult nullResult = search.search(null, List.of(z, a));

        assertThat(emptyResult.hits())
                .as(
                        "blank query must return all projects ordered by English title with score 0; see ProjectSearch.search")
                .containsExactly(new ScoredProject(a, 0), new ScoredProject(z, 0));
        assertThat(emptyResult.ambiguous())
                .as("blank query must never be marked ambiguous; see ProjectSearch.search")
                .isFalse();
        assertThat(nullResult.hits())
                .as(
                        "null query must return all projects ordered by English title with score 0; see ProjectSearch.search")
                .containsExactly(new ScoredProject(a, 0), new ScoredProject(z, 0));
    }

    @Test
    void punctuationOnlyListsAll() {
        Project z = project("z-proj", "Zeta meter", "Aparat zeta", List.of("zeta"), List.of("zeta"));
        Project a = project("a-proj", "Alpha scope", "Osciloscop alfa", List.of("alfa"), List.of("alpha"));

        SearchResult bangResult = search.search("!!!", List.of(z, a));
        SearchResult emojiResult = search.search("🙂", List.of(z, a));

        assertThat(bangResult.hits())
                .as(
                        "punctuation-only query must return all projects ordered by English title with score 0; see ProjectSearch.search")
                .containsExactly(new ScoredProject(a, 0), new ScoredProject(z, 0));
        assertThat(bangResult.ambiguous())
                .as("punctuation-only query must not be ambiguous; see ProjectSearch.search")
                .isFalse();
        assertThat(emojiResult.hits())
                .as(
                        "emoji-only query must return all projects ordered by English title with score 0; see ProjectSearch.search")
                .containsExactly(new ScoredProject(a, 0), new ScoredProject(z, 0));
        assertThat(emojiResult.ambiguous())
                .as("emoji-only query must not be ambiguous; see ProjectSearch.search")
                .isFalse();
    }

    @Test
    void noMatchReturnsEmpty() {
        Project p = project("thermometer", "Heat probe", "Sonda caldura", List.of("termometru"), List.of("probe"));

        SearchResult result = search.search("submarin", List.of(p));

        assertThat(result.hits())
                .as("query matching no keywords or title words must return an empty hit list; see ProjectSearch.search")
                .isEmpty();
        assertThat(result.ambiguous())
                .as("empty hit list must not be ambiguous; see ProjectSearch.search")
                .isFalse();
    }

    @Test
    void ranksByScoreThenEnglishTitle() {
        Project low = project("low", "Alpha sensor", "Senzor alfa", List.of("termometre"), List.of("alpha"));
        Project tieB = project("a-slug", "charlie probe", "Sonda charlie", List.of("termometru"), List.of("charlie"));
        Project tieA = project("z-slug", "Bravo probe", "Sonda bravo", List.of("termometru"), List.of("bravo"));
        Project multiWordKw =
                project("robot", "Line robot", "Robot linie", List.of("urmaritor linie"), List.of("line follower"));

        SearchResult result = search.search("termometru", List.of(low, tieB, tieA));
        SearchResult multiWordResult = search.search("follower", List.of(multiWordKw));

        assertThat(result.hits())
                .as("hits must be sorted by score descending, then English title ascending; see ProjectSearch.search")
                .containsExactly(new ScoredProject(tieA, 3), new ScoredProject(tieB, 3), new ScoredProject(low, 2));
        assertThat(multiWordResult.hits())
                .as(
                        "multi-word keywords in JSON must be tokenized so 'follower' matches 'line follower'; see ProjectSearch.scoreProject")
                .containsExactly(new ScoredProject(multiWordKw, 3));
    }

    @Test
    void ambiguousWhenTopTwoWithinOne() {
        Project top = project("top", "Alpha probe", "Sonda alfa", List.of("termometru"), List.of("alpha"));
        Project second = project("second", "Bravo probe", "Sonda bravo", List.of("termometre"), List.of("bravo"));

        SearchResult result = search.search("termometru", List.of(top, second));

        assertThat(result.ambiguous())
                .as(
                        "top two hits with scores 3 and 2 (gap 1 <= AMBIGUITY_GAP) must be marked ambiguous; see ProjectSearch.search")
                .isTrue();
    }

    @Test
    void notAmbiguousWhenGapIsTwo() {
        Project top = project("top", "Termometru alpha", "Termometru alfa", List.of("termometru"), List.of("alpha"));
        Project second = project("second", "Bravo probe", "Sonda bravo", List.of("termometre"), List.of("bravo"));

        SearchResult result = search.search("termometru", List.of(top, second));

        assertThat(result.hits())
                .as("top hit scores 4 (exact keyword + title) and second scores 2 (prefix); see ProjectSearch.search")
                .containsExactly(new ScoredProject(top, 4), new ScoredProject(second, 2));
        assertThat(result.ambiguous())
                .as(
                        "gap of 2 between top two hits (> AMBIGUITY_GAP) must not be marked ambiguous; see ProjectSearch.search")
                .isFalse();
    }

    @Test
    void notAmbiguousWithOneHit() {
        Project only = project("only", "Alpha probe", "Sonda alfa", List.of("termometru"), List.of("alpha"));

        SearchResult result = search.search("termometru", List.of(only));

        assertThat(result.ambiguous())
                .as("a single hit must never be marked ambiguous; see ProjectSearch.search")
                .isFalse();
    }

    private static Project project(
            String slug, String enTitle, String roTitle, List<String> roKeywords, List<String> enKeywords) {
        return new Project(
                slug,
                Difficulty.BEGINNER,
                2,
                new LocalizedText(roTitle, enTitle),
                new LocalizedText("Rezumat", "Summary"),
                new Keywords(roKeywords, enKeywords),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
