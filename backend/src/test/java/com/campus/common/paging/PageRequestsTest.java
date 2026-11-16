package com.campus.common.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campus.common.error.InvalidParameterException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("name", "createdAt", "action");

    @Test
    void ofBuildsPageableForValidBounds() {
        Sort sort = PageRequests.parseSort(null, "name,asc", ALLOWED);
        Pageable pageable = PageRequests.of(1, 20, sort);

        assertEquals(1, pageable.getPageNumber(), "page number should match input; see PageRequests.of");
        assertEquals(20, pageable.getPageSize(), "page size should match input; see PageRequests.of");
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("name").getDirection());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("id").getDirection());
    }

    @Test
    void ofRejectsNegativePageZeroSizeAndOverflowOffset() {
        Sort sort = Sort.by("name");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.of(-1, 20, sort),
                "negative page index must be rejected; see PageRequests.validatePageBounds");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.of(0, 0, sort),
                "zero page size must be rejected; see PageRequests.validatePageBounds");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.of(0, 101, sort),
                "page size above maxPageSize must be rejected; see PageRequests.validatePageBounds");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.of(Integer.MAX_VALUE, 2, sort),
                "page * size > Integer.MAX_VALUE must be rejected; see PageRequests.validatePageBounds");
    }

    @Test
    void parseSortSupportsPerFieldDefaultsAndMatchingTieBreak() {
        Map<String, Sort.Direction> defaults = Map.of("createdAt", Sort.Direction.DESC);

        Sort createdAtSort = PageRequests.parseSort("createdAt", "createdAt,desc", ALLOWED, defaults, true);
        assertEquals(Sort.Direction.DESC, createdAtSort.getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.DESC, createdAtSort.getOrderFor("id").getDirection());

        Sort actionSort = PageRequests.parseSort("action", "createdAt,desc", ALLOWED, defaults, true);
        assertEquals(Sort.Direction.ASC, actionSort.getOrderFor("action").getDirection());
        assertEquals(Sort.Direction.ASC, actionSort.getOrderFor("id").getDirection());
    }

    @Test
    void parseSortRejectsUnknownFieldBadDirectionAndTooManyCommas() {
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.parseSort("unknown,asc", "name,asc", ALLOWED),
                "non-whitelisted sort field must be rejected; see PageRequests.parseSort");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.parseSort("name,sideways", "name,asc", ALLOWED),
                "invalid sort direction must be rejected; see PageRequests.parseSort");
        assertThrows(
                InvalidParameterException.class,
                () -> PageRequests.parseSort("name,asc,extra", "name,asc", ALLOWED),
                "sort with extra comma must be rejected; see PageRequests.parseSort");
    }
}
