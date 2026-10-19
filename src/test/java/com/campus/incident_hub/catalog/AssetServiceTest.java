package com.campus.incident_hub.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private AssetService assetService; // Se injectează mock-ul repository în service

    private Category testCategory;
    private LabRoom testLabRoom;

    @BeforeEach

    void setUp() {
        testCategory = new Category(1L, "Măsură", "Echipamente de măsură");
        testLabRoom = new LabRoom(1L, "B204", "Laborator Robotică", "Corp B");
    }

    @Test
    void shouldReturnAllAssets() {
        Asset asset1 = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere osciloscop", testCategory, testLabRoom);
        Asset asset2 = new Asset("Multimetru", "Fluke 87V", "MUL-202", "Descriere multimetru", testCategory, testLabRoom);

        when(assetRepository.findAll()).thenReturn(List.of(asset1, asset2));

        List<Asset> result = assetService.getAllAssets();

        assertEquals(2, result.size());
        assertEquals("Osciloscop", result.get(0).getName());
        verify(assetRepository, times(1)).findAll();
    }
}
