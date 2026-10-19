package com.campus.incident_hub.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AssetControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AssetService assetService;

    @InjectMocks
    private AssetController assetController;

    private Category testCategory;
    private LabRoom testLabRoom;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(assetController).build();
        testCategory = new Category(1L, "Măsură", "Echipamente de măsură");
        testLabRoom = new LabRoom(1L, "B204", "Laborator Robotică", "Corp B");
    }

    @Test
    void shouldReturn200OKForAssetsEndpoint() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCreateAsset() throws Exception {
        Asset asset = new Asset("Osciloscop", "DS1054Z", "OSC-101", "Descriere osciloscop", testCategory, testLabRoom);
        when(assetService.createAsset(any(Asset.class))).thenReturn(asset);

        String jsonPayload = """                                                                                                                                                                                              
                    {                                                                                                                                                                                                             
                        "name": "Osciloscop",                                                                                                                                                                                     
                        "model": "DS1054Z",                                                                                                                                                                                       
                        "serialNumber": "OSC-101",                                                                                                                                                                                
                        "description": "Descriere osciloscop",                                                                                                                                                                    
                        "status": "AVAILABLE",                                                                                                                                                                                    
                        "category": { "id": 1 },                                                                                                                                                                                  
                        "labRoom": { "id": 1 }                                                                                                                                                                                    
                    }                                                                                                                                                                                                             
                    """;

        mockMvc.perform(post("/api/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldDeleteAsset() throws Exception {
        Long idToDelete = 1L;

        mockMvc.perform(delete("/api/assets/{id}", idToDelete))
                .andExpect(status().isOk());

        verify(assetService).deleteAsset(idToDelete);
    }
}