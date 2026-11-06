package com.campus.catalog.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll(Sort.by("name")).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String trimmedName = request.name().trim();
        if (categoryRepository.existsByNameIgnoreCase(trimmedName)) {
            throw nameTaken(trimmedName);
        }

        Category category = new Category(trimmedName, request.description());
        Category saved;
        try {
            saved = categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            // Check if the name was actually taken by a concurrent insert; otherwise rethrow (#167).
            if (categoryRepository.existsByNameIgnoreCase(trimmedName)) {
                throw nameTaken(trimmedName);
            }
            throw ex;
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a duplicate name (#120).
        auditService.record(
                currentUser.id(), "CATEGORY_CREATED", "CATEGORY", saved.getId(), "Created category " + saved.getName());
        return CategoryResponse.from(saved);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = findOrThrow(id);
        String trimmedName = request.name().trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, id)) {
            throw nameTaken(trimmedName);
        }

        category.update(trimmedName, request.description());
        Category saved;
        try {
            saved = categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            // Check if the name was actually taken by a concurrent insert/update; otherwise rethrow (#167).
            if (categoryRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, id)) {
                throw nameTaken(trimmedName);
            }
            throw ex;
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a duplicate name (#120).
        auditService.record(
                currentUser.id(), "CATEGORY_UPDATED", "CATEGORY", saved.getId(), "Updated category " + saved.getName());
        return CategoryResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Category category = findOrThrow(id);
        if (assetRepository.existsByCategoryId(id)) {
            throw categoryInUse(category.getName());
        }

        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw categoryInUse(category.getName());
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a category in use (#120).
        auditService.record(
                currentUser.id(), "CATEGORY_DELETED", "CATEGORY", id, "Deleted category " + category.getName());
    }

    private Category findOrThrow(Long id) {
        return categoryRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("CATEGORY_NOT_FOUND", "Category " + id + " was not found"));
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException("CATEGORY_NAME_TAKEN", "Category '" + name + "' already exists");
    }

    private static ConflictException categoryInUse(String name) {
        return new ConflictException(
                "CATEGORY_IN_USE", "Category '" + name + "' cannot be deleted while assets still reference it");
    }
}
