package com.campus.common.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void convertsSpringPageDirectly() {
        Page<String> springPage = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

        PageResponse<String> response = PageResponse.from(springPage);

        assertEquals(List.of("a", "b"), response.content());
        assertEquals(0, response.page());
        assertEquals(2, response.size());
        assertEquals(5L, response.totalElements());
        assertEquals(3, response.totalPages());
    }

    @Test
    void convertsAndMapsSpringPageElements() {
        Page<String> springPage = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

        PageResponse<String> response = PageResponse.from(springPage, String::toUpperCase);

        assertEquals(List.of("A", "B"), response.content());
        assertEquals(0, response.page());
        assertEquals(2, response.size());
        assertEquals(5L, response.totalElements());
        assertEquals(3, response.totalPages());
    }
}
