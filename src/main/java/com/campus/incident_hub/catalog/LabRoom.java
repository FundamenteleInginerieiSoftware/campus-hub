package com.campus.incident_hub.catalog;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lab_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class LabRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String building;
}
