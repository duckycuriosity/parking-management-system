package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import vio.barie.core.dto.BuildingCreateRequest;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.mapper.BuildingMapper;
import vio.barie.core.repository.BuildingRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Building Service Test")
class BuildingServiceTest {

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private BuildingMapper buildingMapper;

    @InjectMocks
    private BuildingServiceImpl buildingService;

    private BuildingEntity buildingEntity;
    private BuildingCreateRequest buildingRequest;
    private BuildingResponse buildingResponse;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        buildingRequest = new BuildingCreateRequest();
        buildingRequest.setName("Building A");
        buildingRequest.setAddress("123 Main St");

        buildingEntity = new BuildingEntity();
        buildingEntity.setId(1L);
        buildingEntity.setName("Building A");
        buildingEntity.setAddress("123 Main St");
        buildingEntity.setCreatedAt(LocalDateTime.now());

        buildingResponse = new BuildingResponse();
        buildingResponse.setId(1L);
        buildingResponse.setName("Building A");
        buildingResponse.setAddress("123 Main St");
        buildingResponse.setCreatedAt(buildingEntity.getCreatedAt());
    }

    @Test
    @DisplayName("Should create building successfully")
    void testCreateBuilding() {
        when(buildingMapper.toEntity(buildingRequest)).thenReturn(buildingEntity);
        when(buildingRepository.save(any(BuildingEntity.class))).thenReturn(buildingEntity);
        when(buildingMapper.toResponse(buildingEntity)).thenReturn(buildingResponse);

        BuildingResponse result = buildingService.create(buildingRequest);

        assertNotNull(result);
        assertEquals("Building A", result.getName());
        assertEquals("123 Main St", result.getAddress());
        verify(buildingRepository, times(1)).save(any(BuildingEntity.class));
    }

    @Test
    @DisplayName("Should find all buildings")
    void testFindAllBuildings() {
        List<BuildingEntity> buildings = Arrays.asList(buildingEntity);
        when(buildingRepository.findAll()).thenReturn(buildings);
        when(buildingMapper.toResponseList(buildings)).thenReturn(Arrays.asList(buildingResponse));

        List<BuildingResponse> result = buildingService.findAll();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Building A", result.get(0).getName());
    }

    @Test
    @DisplayName("Should find building by id")
    void testFindBuildingById() {
        when(buildingRepository.findById(1L)).thenReturn(Optional.of(buildingEntity));
        when(buildingMapper.toResponse(buildingEntity)).thenReturn(buildingResponse);

        BuildingResponse result = buildingService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Building A", result.getName());
    }

    @Test
    @DisplayName("Should throw exception when building not found")
    void testFindBuildingByIdNotFound() {
        when(buildingRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> buildingService.findById(99L));
    }

    @Test
    @DisplayName("Should update building successfully")
    void testUpdateBuilding() {
        BuildingCreateRequest updateRequest = new BuildingCreateRequest();
        updateRequest.setName("Building A Updated");
        updateRequest.setAddress("456 Oak Ave");

        when(buildingRepository.findById(1L)).thenReturn(Optional.of(buildingEntity));
        when(buildingRepository.save(any(BuildingEntity.class))).thenReturn(buildingEntity);
        when(buildingMapper.toResponse(buildingEntity)).thenReturn(buildingResponse);

        BuildingResponse result = buildingService.update(1L, updateRequest);

        assertNotNull(result);
        verify(buildingRepository, times(1)).save(any(BuildingEntity.class));
    }

    @Test
    @DisplayName("Should delete building successfully")
    void testDeleteBuilding() {
        when(buildingRepository.findById(1L)).thenReturn(Optional.of(buildingEntity));

        buildingService.delete(1L);

        verify(buildingRepository, times(1)).delete(buildingEntity);
    }
}
