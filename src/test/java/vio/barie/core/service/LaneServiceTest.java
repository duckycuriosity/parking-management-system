package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import vio.barie.core.dto.LaneCreateRequest;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.mapper.LaneMapper;
import vio.barie.core.repository.LaneRepository;
import vio.barie.core.repository.ParkingLotRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Lane Service Test")
class LaneServiceTest {

    @Mock
    private LaneRepository laneRepository;

    @Mock
    private ParkingLotRepository parkingLotRepository;

    @Mock
    private LaneMapper laneMapper;

    @InjectMocks
    private LaneServiceImpl laneService;

    private LaneEntity laneEntity;
    private ParkingLotEntity parkingLotEntity;
    private LaneCreateRequest laneRequest;
    private LaneResponse laneResponse;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        parkingLotEntity = new ParkingLotEntity();
        parkingLotEntity.setId(1L);
        parkingLotEntity.setName("Lot A1");

        laneRequest = new LaneCreateRequest();
        laneRequest.setName("Lane A1");
        laneRequest.setType("ENTRY");
        laneRequest.setStatus("ACTIVE");
        laneRequest.setParkingLotId(1L);

        laneEntity = new LaneEntity();
        laneEntity.setId(1L);
        laneEntity.setName("Lane A1");
        laneEntity.setType("ENTRY");
        laneEntity.setStatus("ACTIVE");
        laneEntity.setParkingLot(parkingLotEntity);
        laneEntity.setCreatedAt(LocalDateTime.now());

        laneResponse = new LaneResponse();
        laneResponse.setId(1L);
        laneResponse.setName("Lane A1");
        laneResponse.setType("ENTRY");
        laneResponse.setStatus("ACTIVE");
        laneResponse.setParkingLotId(1L);
        laneResponse.setCreatedAt(laneEntity.getCreatedAt());
    }

    @Test
    @DisplayName("Should create lane successfully")
    void testCreateLane() {
        when(parkingLotRepository.findById(1L)).thenReturn(Optional.of(parkingLotEntity));
        when(laneMapper.toEntity(laneRequest)).thenReturn(laneEntity);
        when(laneRepository.save(any(LaneEntity.class))).thenReturn(laneEntity);
        when(laneMapper.toResponse(laneEntity)).thenReturn(laneResponse);

        LaneResponse result = laneService.create(laneRequest);

        assertNotNull(result);
        assertEquals("Lane A1", result.getName());
        assertEquals("ENTRY", result.getType());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(1L, result.getParkingLotId());
        verify(laneRepository, times(1)).save(any(LaneEntity.class));
    }

    @Test
    @DisplayName("Should throw exception when parking lot not found")
    void testCreateLaneParkingLotNotFound() {
        when(parkingLotRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> {
            laneRequest.setParkingLotId(99L);
            laneService.create(laneRequest);
        });
    }

    @Test
    @DisplayName("Should find all lanes")
    void testFindAllLanes() {
        List<LaneEntity> lanes = Arrays.asList(laneEntity);
        when(laneRepository.findAll()).thenReturn(lanes);
        when(laneMapper.toResponseList(lanes)).thenReturn(Arrays.asList(laneResponse));

        List<LaneResponse> result = laneService.findAll();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Lane A1", result.get(0).getName());
    }

    @Test
    @DisplayName("Should find lane by id")
    void testFindLaneById() {
        when(laneRepository.findById(1L)).thenReturn(Optional.of(laneEntity));
        when(laneMapper.toResponse(laneEntity)).thenReturn(laneResponse);

        LaneResponse result = laneService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Lane A1", result.getName());
    }

    @Test
    @DisplayName("Should throw exception when lane not found")
    void testFindLaneByIdNotFound() {
        when(laneRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> laneService.findById(99L));
    }

    @Test
    @DisplayName("Should update lane successfully")
    void testUpdateLane() {
        LaneCreateRequest updateRequest = new LaneCreateRequest();
        updateRequest.setName("Lane A1 Updated");
        updateRequest.setType("EXIT");
        updateRequest.setStatus("INACTIVE");
        updateRequest.setParkingLotId(1L);

        when(laneRepository.findById(1L)).thenReturn(Optional.of(laneEntity));
        when(parkingLotRepository.findById(1L)).thenReturn(Optional.of(parkingLotEntity));
        when(laneRepository.save(any(LaneEntity.class))).thenReturn(laneEntity);
        when(laneMapper.toResponse(laneEntity)).thenReturn(laneResponse);

        LaneResponse result = laneService.update(1L, updateRequest);

        assertNotNull(result);
        verify(laneRepository, times(1)).save(any(LaneEntity.class));
    }

    @Test
    @DisplayName("Should delete lane successfully")
    void testDeleteLane() {
        when(laneRepository.findById(1L)).thenReturn(Optional.of(laneEntity));

        laneService.delete(1L);

        verify(laneRepository, times(1)).delete(laneEntity);
    }
}
