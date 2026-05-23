package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import vio.barie.core.dto.ParkingLotCreateRequest;
import vio.barie.core.dto.ParkingLotResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.mapper.ParkingLotMapper;
import vio.barie.core.repository.BuildingRepository;
import vio.barie.core.repository.ParkingLotRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Parking Lot Service Test")
class ParkingLotServiceTest {

    @Mock
    private ParkingLotRepository parkingLotRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private ParkingLotMapper parkingLotMapper;

    @InjectMocks
    private ParkingLotServiceImpl parkingLotService;

    private ParkingLotEntity parkingLotEntity;
    private BuildingEntity buildingEntity;
    private ParkingLotCreateRequest parkingLotRequest;
    private ParkingLotResponse parkingLotResponse;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        buildingEntity = new BuildingEntity();
        buildingEntity.setId(1L);
        buildingEntity.setName("Building A");

        parkingLotRequest = new ParkingLotCreateRequest();
        parkingLotRequest.setName("Lot A1");
        parkingLotRequest.setBuildingId(1L);

        parkingLotEntity = new ParkingLotEntity();
        parkingLotEntity.setId(1L);
        parkingLotEntity.setName("Lot A1");
        parkingLotEntity.setBuilding(buildingEntity);
        parkingLotEntity.setCreatedAt(LocalDateTime.now());

        parkingLotResponse = new ParkingLotResponse();
        parkingLotResponse.setId(1L);
        parkingLotResponse.setName("Lot A1");
        parkingLotResponse.setBuildingId(1L);
        parkingLotResponse.setCreatedAt(parkingLotEntity.getCreatedAt());
    }

    @Test
    @DisplayName("Should create parking lot successfully")
    void testCreateParkingLot() {
        when(buildingRepository.findById(1L)).thenReturn(Optional.of(buildingEntity));
        when(parkingLotMapper.toEntity(parkingLotRequest)).thenReturn(parkingLotEntity);
        when(parkingLotRepository.save(any(ParkingLotEntity.class))).thenReturn(parkingLotEntity);
        when(parkingLotMapper.toResponse(parkingLotEntity)).thenReturn(parkingLotResponse);

        ParkingLotResponse result = parkingLotService.create(parkingLotRequest);

        assertNotNull(result);
        assertEquals("Lot A1", result.getName());
        assertEquals(1L, result.getBuildingId());
        verify(parkingLotRepository, times(1)).save(any(ParkingLotEntity.class));
    }

    @Test
    @DisplayName("Should throw exception when building not found")
    void testCreateParkingLotBuildingNotFound() {
        when(buildingRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> {
            parkingLotRequest.setBuildingId(99L);
            parkingLotService.create(parkingLotRequest);
        });
    }

    @Test
    @DisplayName("Should find all parking lots")
    void testFindAllParkingLots() {
        List<ParkingLotEntity> parkingLots = Arrays.asList(parkingLotEntity);
        when(parkingLotRepository.findAll()).thenReturn(parkingLots);
        when(parkingLotMapper.toResponseList(parkingLots)).thenReturn(Arrays.asList(parkingLotResponse));

        List<ParkingLotResponse> result = parkingLotService.findAll();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Lot A1", result.get(0).getName());
    }

    @Test
    @DisplayName("Should find parking lot by id")
    void testFindParkingLotById() {
        when(parkingLotRepository.findById(1L)).thenReturn(Optional.of(parkingLotEntity));
        when(parkingLotMapper.toResponse(parkingLotEntity)).thenReturn(parkingLotResponse);

        ParkingLotResponse result = parkingLotService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Lot A1", result.getName());
    }

    @Test
    @DisplayName("Should throw exception when parking lot not found")
    void testFindParkingLotByIdNotFound() {
        when(parkingLotRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> parkingLotService.findById(99L));
    }

    @Test
    @DisplayName("Should update parking lot successfully")
    void testUpdateParkingLot() {
        ParkingLotCreateRequest updateRequest = new ParkingLotCreateRequest();
        updateRequest.setName("Lot A1 Updated");
        updateRequest.setBuildingId(1L);

        when(parkingLotRepository.findById(1L)).thenReturn(Optional.of(parkingLotEntity));
        when(buildingRepository.findById(1L)).thenReturn(Optional.of(buildingEntity));
        when(parkingLotRepository.save(any(ParkingLotEntity.class))).thenReturn(parkingLotEntity);
        when(parkingLotMapper.toResponse(parkingLotEntity)).thenReturn(parkingLotResponse);

        ParkingLotResponse result = parkingLotService.update(1L, updateRequest);

        assertNotNull(result);
        verify(parkingLotRepository, times(1)).save(any(ParkingLotEntity.class));
    }

    @Test
    @DisplayName("Should delete parking lot successfully")
    void testDeleteParkingLot() {
        when(parkingLotRepository.findById(1L)).thenReturn(Optional.of(parkingLotEntity));

        parkingLotService.delete(1L);

        verify(parkingLotRepository, times(1)).delete(parkingLotEntity);
    }
}
