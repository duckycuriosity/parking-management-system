package vio.barie.core.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingLotEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@DisplayName("Lane Repository Test")
class LaneRepositoryTest {

    @Autowired
    private LaneRepository laneRepository;

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Test
    @DisplayName("Should save lane successfully")
    void testSaveLane() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane = new LaneEntity();
        lane.setName("Lane A");
        lane.setType("ENTRY");
        lane.setStatus("ACTIVE");
        lane.setParkingLot(savedParkingLot);

        LaneEntity saved = laneRepository.save(lane);

        assertNotNull(saved.getId());
        assertEquals("Lane A", saved.getName());
        assertEquals("ENTRY", saved.getType());
        assertEquals("ACTIVE", saved.getStatus());
    }

    @Test
    @DisplayName("Should find lane by id")
    void testFindLaneById() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane = new LaneEntity();
        lane.setName("Lane A");
        lane.setType("ENTRY");
        lane.setStatus("ACTIVE");
        lane.setParkingLot(savedParkingLot);
        LaneEntity saved = laneRepository.save(lane);

        Optional<LaneEntity> found = laneRepository.findById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals("Lane A", found.get().getName());
    }

    @Test
    @DisplayName("Should find all lanes")
    void testFindAllLanes() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane1 = new LaneEntity();
        lane1.setName("Lane A");
        lane1.setType("ENTRY");
        lane1.setStatus("ACTIVE");
        lane1.setParkingLot(savedParkingLot);

        LaneEntity lane2 = new LaneEntity();
        lane2.setName("Lane B");
        lane2.setType("EXIT");
        lane2.setStatus("ACTIVE");
        lane2.setParkingLot(savedParkingLot);

        laneRepository.save(lane1);
        laneRepository.save(lane2);

        List<LaneEntity> lanes = laneRepository.findAll();

        assertEquals(2, lanes.size());
    }

    @Test
    @DisplayName("Should update lane")
    void testUpdateLane() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane = new LaneEntity();
        lane.setName("Lane A");
        lane.setType("ENTRY");
        lane.setStatus("ACTIVE");
        lane.setParkingLot(savedParkingLot);
        LaneEntity saved = laneRepository.save(lane);

        saved.setStatus("INACTIVE");
        LaneEntity updated = laneRepository.save(saved);

        assertEquals("INACTIVE", updated.getStatus());
    }

    @Test
    @DisplayName("Should delete lane")
    void testDeleteLane() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane = new LaneEntity();
        lane.setName("Lane A");
        lane.setType("ENTRY");
        lane.setStatus("ACTIVE");
        lane.setParkingLot(savedParkingLot);
        LaneEntity saved = laneRepository.save(lane);

        laneRepository.deleteById(saved.getId());

        Optional<LaneEntity> found = laneRepository.findById(saved.getId());
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Should maintain lane relationship with parking lot")
    void testLaneParkingLotRelationship() {
        BuildingEntity building = new BuildingEntity();
        building.setName("Test Building");
        building.setAddress("123 Test St");
        BuildingEntity savedBuilding = buildingRepository.save(building);

        ParkingLotEntity parkingLot = new ParkingLotEntity();
        parkingLot.setName("Level 1");
        parkingLot.setBuilding(savedBuilding);
        ParkingLotEntity savedParkingLot = parkingLotRepository.save(parkingLot);

        LaneEntity lane1 = new LaneEntity();
        lane1.setName("Lane A");
        lane1.setType("ENTRY");
        lane1.setStatus("ACTIVE");
        lane1.setParkingLot(savedParkingLot);

        LaneEntity lane2 = new LaneEntity();
        lane2.setName("Lane B");
        lane2.setType("EXIT");
        lane2.setStatus("ACTIVE");
        lane2.setParkingLot(savedParkingLot);

        laneRepository.save(lane1);
        laneRepository.save(lane2);

        Optional<ParkingLotEntity> foundParkingLot = parkingLotRepository.findById(savedParkingLot.getId());
        assertTrue(foundParkingLot.isPresent());
        assertEquals(2, foundParkingLot.get().getLanes().size());
    }
}
