package com.campus.catalog.web;

import com.campus.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

// 401 (and 403 under /admin) are added automatically by OpenApiConfig (#184).
@RestController
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Equipment categories lookup and admin CRUD")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/api/v1/categories")
    @Operation(summary = "List all equipment categories sorted by name")
    @ApiResponse(responseCode = "200", description = "List of categories")
    public List<CategoryResponse> listAll() {
        return categoryService.listAll();
    }

    @PostMapping("/api/v1/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Category created"),
        @ApiResponse(responseCode = "400", description = "Validation failed (VALIDATION_FAILED)"),
        @ApiResponse(responseCode = "409", description = "Category name already exists (CATEGORY_NAME_TAKEN)")
    })
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/api/v1/admin/categories/{id}")
    @Operation(summary = "Update a category")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or invalid category ID (VALIDATION_FAILED, INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Category not found (CATEGORY_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Category name already exists (CATEGORY_NAME_TAKEN)")
    })
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/api/v1/admin/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unused category")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Category deleted"),
        @ApiResponse(responseCode = "400", description = "Invalid category ID parameter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Category not found (CATEGORY_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Category is referenced by existing assets (CATEGORY_IN_USE)")
    })
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }
}
