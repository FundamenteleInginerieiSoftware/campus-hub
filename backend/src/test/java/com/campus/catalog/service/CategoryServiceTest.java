package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.domain.Category;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.web.CategoryRequest;
import com.campus.catalog.web.CategoryResponse;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void listAllReturnsCategoriesSortedByName() {
        when(categoryRepository.findAll(Sort.by("name")))
                .thenReturn(List.of(new Category("Cameras", "Video"), new Category("Oscilloscopes", "Lab")));

        List<CategoryResponse> result = categoryService.listAll();

        assertEquals(2, result.size());
        assertEquals("Cameras", result.getFirst().name());
    }

    @Test
    void createSavesCategoryAndRecordsAudit() {
        Category saved = new Category("Oscilloscopes", "Signal measurement");
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(false);
        when(categoryRepository.saveAndFlush(any(Category.class))).thenReturn(saved);

        CategoryResponse response =
                categoryService.create(new CategoryRequest("  Oscilloscopes  ", "Signal measurement"), 3L);

        assertEquals("Oscilloscopes", response.name());
        verify(auditService)
                .record(eq(3L), eq("CATEGORY_CREATED"), eq("CATEGORY"), eq(null), eq("Created category Oscilloscopes"));
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> categoryService.create(new CategoryRequest("Oscilloscopes", "Desc"), 3L));

        assertEquals("CATEGORY_NAME_TAKEN", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateThrowsNotFoundWhenMissing() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class, () -> categoryService.update(99L, new CategoryRequest("Cameras", "Desc"), 3L));

        assertEquals("CATEGORY_NOT_FOUND", ex.getCode());
    }

    @Test
    void deleteRejectsCategoryStillReferencedByAssets() {
        Category existing = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByCategoryId(5L)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () -> categoryService.delete(5L, 3L));

        assertEquals("CATEGORY_IN_USE", ex.getCode());
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesUnusedCategoryAndRecordsAudit() {
        Category existing = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByCategoryId(5L)).thenReturn(false);

        categoryService.delete(5L, 3L);

        verify(categoryRepository).delete(existing);
        verify(categoryRepository).flush();
        verify(auditService).record(3L, "CATEGORY_DELETED", "CATEGORY", 5L, "Deleted category Oscilloscopes");
    }
}
