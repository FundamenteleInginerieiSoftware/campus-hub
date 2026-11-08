package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.campus.common.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private CurrentUser currentUser;

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
        when(currentUser.id()).thenReturn(3L);

        CategoryResponse response =
                categoryService.create(new CategoryRequest("  Oscilloscopes  ", "Signal measurement"));

        assertEquals("Oscilloscopes", response.name());
        verify(auditService)
                .record(eq(3L), eq("CATEGORY_CREATED"), eq("CATEGORY"), eq(null), eq("Created category Oscilloscopes"));
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class, () -> categoryService.create(new CategoryRequest("Oscilloscopes", "Desc")));

        assertEquals("CATEGORY_NAME_TAKEN", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsDuplicateNameOnConcurrentRace() {
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(false, true);
        when(categoryRepository.saveAndFlush(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("unique index constraint"));

        ConflictException ex = assertThrows(
                ConflictException.class, () -> categoryService.create(new CategoryRequest("Oscilloscopes", "Desc")));

        assertEquals("CATEGORY_NAME_TAKEN", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createRethrowsDataIntegrityViolationWhenNameNotTaken() {
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(false);
        when(categoryRepository.saveAndFlush(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> categoryService.create(new CategoryRequest("Oscilloscopes", "Desc")));
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createDoesNotCatchAuditFailureAsDuplicateName() {
        Category saved = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.existsByNameIgnoreCase("Oscilloscopes")).thenReturn(false);
        when(categoryRepository.saveAndFlush(any(Category.class))).thenReturn(saved);
        when(currentUser.id()).thenReturn(3L);
        doThrow(new DataIntegrityViolationException("actor_id FK failure"))
                .when(auditService)
                .record(any(), any(), any(), any(), any());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> categoryService.create(new CategoryRequest("Oscilloscopes", "Desc")));
    }

    @Test
    void updateModifiesCategoryRecordsAuditAndReturnsResponse() {
        Category existing = new Category("Old Name", "Old Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Cameras", 5L)).thenReturn(false);
        when(categoryRepository.saveAndFlush(existing)).thenReturn(existing);
        when(currentUser.id()).thenReturn(3L);

        CategoryResponse response = categoryService.update(5L, new CategoryRequest("  Cameras  ", "Video equipment"));

        assertNotNull(response);
        assertEquals("Cameras", response.name());
        assertEquals("Video equipment", response.description());
        verify(auditService).record(3L, "CATEGORY_UPDATED", "CATEGORY", null, "Updated category Cameras");
    }

    @Test
    void updateRejectsDuplicateNameFromAnotherCategory() {
        Category existing = new Category("Old Name", "Old Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Cameras", 5L)).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class, () -> categoryService.update(5L, new CategoryRequest("Cameras", "Video")));

        assertEquals("CATEGORY_NAME_TAKEN", ex.getCode());
        verify(categoryRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateHandlesConcurrentRaceAndRethrowsOtherConstraintViolations() {
        Category existing = new Category("Old Name", "Old Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Cameras", 5L)).thenReturn(false, true, false, false);
        when(categoryRepository.saveAndFlush(existing))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        ConflictException raceEx = assertThrows(
                ConflictException.class, () -> categoryService.update(5L, new CategoryRequest("Cameras", "Video")));
        assertEquals("CATEGORY_NAME_TAKEN", raceEx.getCode());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> categoryService.update(5L, new CategoryRequest("Cameras", "Video")));
    }

    @Test
    void updateThrowsNotFoundWhenMissing() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class, () -> categoryService.update(99L, new CategoryRequest("Cameras", "Desc")));

        assertEquals("CATEGORY_NOT_FOUND", ex.getCode());
    }

    @Test
    void deleteDoesNotCatchAuditFailureAsCategoryInUse() {
        Category existing = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByCategoryId(5L)).thenReturn(false);
        when(currentUser.id()).thenReturn(3L);
        doThrow(new DataIntegrityViolationException("actor_id FK failure"))
                .when(auditService)
                .record(any(), any(), any(), any(), any());

        assertThrows(DataIntegrityViolationException.class, () -> categoryService.delete(5L));
    }

    @Test
    void deleteRejectsCategoryStillReferencedByAssets() {
        Category existing = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByCategoryId(5L)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () -> categoryService.delete(5L));

        assertEquals("CATEGORY_IN_USE", ex.getCode());
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesUnusedCategoryAndRecordsAudit() {
        Category existing = new Category("Oscilloscopes", "Desc");
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByCategoryId(5L)).thenReturn(false);
        when(currentUser.id()).thenReturn(3L);

        categoryService.delete(5L);

        verify(categoryRepository).delete(existing);
        verify(categoryRepository).flush();
        verify(auditService).record(3L, "CATEGORY_DELETED", "CATEGORY", 5L, "Deleted category Oscilloscopes");
    }
}
