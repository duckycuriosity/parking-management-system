package vio.barie.core.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.entity.ParkingLotEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@DisplayName("ParkingLot Repository Test")
class ParkingLotRepositoryTest {

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Test
    @DisplayName("Should save parking lot successfully")
    void testSaveParkingLot() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);

        ParkingLotEntity saved = parkingLotRepository.save(parkingLot);

        assertNotNull(saved.getId());
        assertEquals("Level 1", saved.getName());
        assertEquals(savedBuilding.getId(), saved.getBuilding().getId());
    }

    @Test
    @DisplayName("Should find parking lot by id")
    void testFindParkingLotById() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity saved = parkingLotRepository.save(parkingLot);

        Optional<ParkingLotEntity> found = parkingLotRepository.findById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals("Level 1", found.get().getName());
    }

    @Test
    @DisplayName("Should find all parking lots")
    void testFindAllParkingLots() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot1 = new ParkingLotEntity();
        parkingLot1.setName("Level 1");
        parkingLot1.setBuilding(savedBuilding);

        ParkingLotEntity parkingLot2 = new ParkingLotEntity();
        parkingLot2.setName("Level 2");
        parkingLot2.setBuilding(savedBuilding);

        parkingLotRepository.save(parkingLot1);
        parkingLotRepository.save(parkingLot2);

        List<ParkingLotEntity> parkingLots = parkingLotRepository.findAll();

        assertEquals(2, parkingLots.size());
    }

    @Test
    @DisplayName("Should update parking lot")
    void testUpdateParkingLot() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity saved = parkingLotRepository.save(parkingLot);

        saved.setName("Level 1 Updated");
        ParkingLotEntity updated = parkingLotRepository.save(saved);

        assertEquals("Level 1 Updated", updated.getName());
    }

    @Test
    @DisplayName("Should delete parking lot")
    void testDeleteParkingLot() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity saved = parkingLotRepository.save(parkingLot);

        parkingLotRepository.deleteById(saved.getId());

        Optional<ParkingLotEntity> found = parkingLotRepository.findById(saved.getId());
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Should maintain parking lot relationship with building")
    void testParkingLotBuildingRelationship() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot1 = new ParkingLotEntity();
        parkingLot1.setName("Level 1");
        parkingLot1.setBuilding(savedBuilding);

        ParkingLotEntity parkingLot2 = new ParkingLotEntity();
        parkingLot2.setName("Level 2");
        parkingLot2.setBuilding(savedBuilding);

        parkingLotRepository.save(parkingLot1);
        parkingLotRepository.save(parkingLot2);

        Optional<BuildingEntity> foundBuilding = buildingRepository.findById(savedBuilding.getId());
        assertTrue(foundBuilding.isPresent());
        assertEquals(2, foundBuilding.get().getParkingLots().size());
    }
}