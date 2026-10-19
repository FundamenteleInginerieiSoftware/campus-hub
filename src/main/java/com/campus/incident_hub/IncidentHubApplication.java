package com.campus.incident_hub;

import com.campus.incident_hub.catalog.Category;
import com.campus.incident_hub.catalog.CategoryRepository;
import com.campus.incident_hub.catalog.LabRoom;
import com.campus.incident_hub.catalog.LabRoomRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class IncidentHubApplication {

@Autowired
CategoryRepository categoryRepository;
	@Autowired
    LabRoomRepository labRoomRepository;

	public static void main(String[] args) {
		SpringApplication.run(IncidentHubApplication.class, args);
	}
}