package com.campus.catalog.service;

import com.campus.catalog.domain.Category;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.web.CategoryRequest;
import com.campus.catalog.web.CategoryResponse;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
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

    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll(Sort.by("name")).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request, Long actorId) {
        String trimmedName = request.name().trim();
        if (categoryRepository.existsByNameIgnoreCase(trimmedName)) {
            throw nameTaken(trimmedName);
        }

        Category category = new Category(trimmedName, request.description());
        try {
            Category saved = categoryRepository.saveAndFlush(category);
            auditService.record(
                    actorId, "CATEGORY_CREATED", "CATEGORY", saved.getId(), "Created category " + saved.getName());
            return CategoryResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent creates can both pass existsByNameIgnoreCase; the DB unique index stops the second with 409.
            throw nameTaken(trimmedName);
        }
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request, Long actorId) {
        Category category = findOrThrow(id);
        String trimmedName = request.name().trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, id)) {
            throw nameTaken(trimmedName);
        }

        category.update(trimmedName, request.description());
        try {
            Category saved = categoryRepository.saveAndFlush(category);
            auditService.record(
                    actorId, "CATEGORY_UPDATED", "CATEGORY", saved.getId(), "Updated category " + saved.getName());
            return CategoryResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent create or rename can pass the check above; the DB unique index stops it with 409.
            throw nameTaken(trimmedName);
        }
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        Category category = findOrThrow(id);
        if (assetRepository.existsByCategoryId(id)) {
            throw categoryInUse(category.getName());
        }

        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
            auditService.record(actorId, "CATEGORY_DELETED", "CATEGORY", id, "Deleted category " + category.getName());
        } catch (DataIntegrityViolationException ex) {
            throw categoryInUse(category.getName());
        }
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
