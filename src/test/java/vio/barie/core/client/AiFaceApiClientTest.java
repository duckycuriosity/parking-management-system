package vio.barie.core.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import vio.barie.core.config.exception.ServiceException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiFaceApiClientTest {
    private AiFaceApiClient client;
    private MockRestServiceServer server;
    private final MockMultipartFile image = new MockMultipartFile("images", "face.jpg", "image/jpeg", new byte[]{1});

    @BeforeEach
    void setup() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AiFaceApiClient(restTemplate);
        ReflectionTestUtils.setField(client, "baseUrl", "http://core-ai");
    }

    @Test
    void preservesExternalProfileIdContractInsideWrappedResponse() {
        server.expect(requestTo("http://core-ai/faces")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"profile\":{\"profileId\":43,\"uuid\":\"provider\"}}", MediaType.APPLICATION_JSON));
        var response = client.createProfile("Owner", List.of(image));
        assertThat(response.getAiProfileId()).isEqualTo(43);
        assertThat(response.getUuid()).isEqualTo("provider");
        server.verify();
    }

    @Test
    void doesNotSilentlyTruncateFaceImages() {
        assertThatThrownBy(() -> client.createProfile("Owner", Collections.nCopies(6, image)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("five");
        server.verify();
    }

    @Test
    void doesNotTreatMultipleProfilesAsOneIdentity() {
        server.expect(requestTo("http://core-ai/faces")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("[{\"id\":43},{\"id\":44}]", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.createProfile("Owner", List.of(image)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("invalid profile response");
        server.verify();
    }
}
