package com.campus.catalog.web;

import com.campus.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Equipment categories lookup and admin CRUD")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/api/v1/categories")
    @Operation(summary = "List all equipment categories sorted by name")
    public List<CategoryResponse> listAll() {
        return categoryService.listAll();
    }

    @PostMapping("/api/v1/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category (409 CATEGORY_NAME_TAKEN if duplicate)")
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request, AdminActor.currentUserId());
    }

    @PutMapping("/api/v1/admin/categories/{id}")
    @Operation(summary = "Update a category (404 CATEGORY_NOT_FOUND, 409 CATEGORY_NAME_TAKEN)")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request, AdminActor.currentUserId());
    }

    @DeleteMapping("/api/v1/admin/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unused category (409 CATEGORY_IN_USE if assets reference it)")
    public void delete(@PathVariable Long id) {
        categoryService.delete(id, AdminActor.currentUserId());
    }
}
