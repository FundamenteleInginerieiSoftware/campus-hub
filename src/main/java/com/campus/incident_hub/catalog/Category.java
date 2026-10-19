package com.campus.incident_hub.catalog;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @NotBlank(message = "Numele categoriei este obligatoriu.")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    @Setter
    private String description;
}