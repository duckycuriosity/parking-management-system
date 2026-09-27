package vio.barie.core.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import vio.barie.core.entity.BuildingEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@DisplayName("Building Repository Test")
class BuildingRepositoryTest {

    @Autowired
    private BuildingRepository buildingRepository;

    @Test
    @DisplayName("Should save building successfully")
    void testSaveBuilding() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");

        BuildingEntity saved = buildingRepository.save(building);

        assertNotNull(saved.getId());
        assertEquals("Test Building", saved.getName());
        assertEquals("123 Test St", saved.getAddress());
    }

    @Test
    @DisplayName("Should find building by id")
    void testFindBuildingById() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity saved = buildingRepository.save(building);

        Optional<BuildingEntity> found = buildingRepository.findById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals("Test Building", found.get().getName());
    }

    @Test
    @DisplayName("Should find all buildings")
    void testFindAllBuildings() {
        BuildingEntity building1 = new BuildingEntity();
        building1.setName("Building 1");
        building1.setAddress("Address 1");

        BuildingEntity building2 = new BuildingEntity();
        building2.setName("Building 2");
        building2.setAddress("Address 2");

        buildingRepository.save(building1);
        buildingRepository.save(building2);

        List<BuildingEntity> buildings = buildingRepository.findAll();

        assertEquals(2, buildings.size());
    }

    @Test
    @DisplayName("Should update building")
    void testUpdateBuilding() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Original Name");
        building.setAddress("Original Address");
        BuildingEntity saved = buildingRepository.save(building);

        saved.setName("Updated Name");
        saved.setAddress("Updated Address");
        BuildingEntity updated = buildingRepository.save(saved);

        assertEquals("Updated Name", updated.getName());
        assertEquals("Updated Address", updated.getAddress());
    }

    @Test
    @DisplayName("Should delete building")
    void testDeleteBuilding() {
        BuildingEntity building = new BuildingEntity();
        building.setName("To Delete");
        building.setAddress("Delete Address");
        BuildingEntity saved = buildingRepository.save(building);

        buildingRepository.deleteById(saved.getId());

        Optional<BuildingEntity> found = buildingRepository.findById(saved.getId());
        assertFalse(found.isPresent());
    }
}
