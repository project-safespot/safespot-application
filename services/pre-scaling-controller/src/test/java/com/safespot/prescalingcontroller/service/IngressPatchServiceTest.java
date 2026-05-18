package com.safespot.prescalingcontroller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.prescalingcontroller.config.PreScalingProperties;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngressPatchServiceTest {

    @Mock private KubernetesClient k8sClient;
    @Mock private Resource<Ingress> ingressResource;

    private ObjectMapper objectMapper;
    private IngressPatchService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = spy(new IngressPatchService(k8sClient, buildProperties(), objectMapper));
        doReturn(ingressResource).when(service).ingressResource("api-public-read");
    }

    @Test
    void patchWeights_base0Surge100_success() throws Exception {
        when(ingressResource.get()).thenReturn(buildIngress(actionJson(100, 0)));

        boolean result = service.patchWeights(0, 100);

        assertThat(result).isTrue();
        String patchBody = capturePatchBody();
        JsonNode annotationNode = extractAnnotationJson(patchBody);
        assertThat(annotationNode.at("/forwardConfig/targetGroups/0/weight").asInt()).isEqualTo(0);
        assertThat(annotationNode.at("/forwardConfig/targetGroups/1/weight").asInt()).isEqualTo(100);
    }

    @Test
    void patchWeights_base100Surge0_success() throws Exception {
        when(ingressResource.get()).thenReturn(buildIngress(actionJson(0, 100)));

        boolean result = service.patchWeights(100, 0);

        assertThat(result).isTrue();
        JsonNode annotationNode = extractAnnotationJson(capturePatchBody());
        assertThat(annotationNode.at("/forwardConfig/targetGroups/0/weight").asInt()).isEqualTo(100);
        assertThat(annotationNode.at("/forwardConfig/targetGroups/1/weight").asInt()).isEqualTo(0);
    }

    @Test
    void patchWeights_preservesNonWeightFieldsAndStickiness() throws Exception {
        when(ingressResource.get()).thenReturn(buildIngress(actionJson(100, 0)));

        boolean result = service.patchWeights(0, 100);

        assertThat(result).isTrue();
        JsonNode annotationNode = extractAnnotationJson(capturePatchBody());
        assertThat(annotationNode.at("/forwardConfig/stickinessConfig/enabled").asBoolean()).isTrue();
        assertThat(annotationNode.at("/forwardConfig/stickinessConfig/durationSeconds").asInt()).isEqualTo(60);
        assertThat(annotationNode.at("/forwardConfig/targetGroups/0/servicePort").asText()).isEqualTo("80");
    }

    @Test
    void patchWeights_missingAnnotation_returnsFalse() {
        when(ingressResource.get()).thenReturn(buildIngressWithoutAnnotation());

        boolean result = service.patchWeights(0, 100);

        assertThat(result).isFalse();
    }

    @Test
    void patchWeights_missingIngress_returnsFalse() {
        when(ingressResource.get()).thenReturn(null);

        boolean result = service.patchWeights(0, 100);

        assertThat(result).isFalse();
    }

    @Test
    void patchWeights_patchFailure_returnsFalse() {
        when(ingressResource.get()).thenReturn(buildIngress(actionJson(100, 0)));
        when(ingressResource.patch(any(), anyString())).thenThrow(new RuntimeException("patch failed"));

        boolean result = service.patchWeights(0, 100);

        assertThat(result).isFalse();
    }

    private String capturePatchBody() {
        try {
            var patchInvocation = org.mockito.Mockito.mockingDetails(ingressResource)
                    .getInvocations()
                    .stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("patch"))
                    .findFirst()
                    .orElseThrow();
            return patchInvocation.getArgument(1).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode extractAnnotationJson(String patchBody) throws Exception {
        JsonNode patch = objectMapper.readTree(patchBody);
        String value = patch.at("/metadata/annotations/alb.ingress.kubernetes.io~1actions.api-public-read-weighted").asText();
        return objectMapper.readTree(value);
    }

    private Ingress buildIngress(String actionJson) {
        Ingress ingress = new Ingress();
        ObjectMeta meta = new ObjectMeta();
        meta.setAnnotations(Map.of(
                "alb.ingress.kubernetes.io/actions.api-public-read-weighted", actionJson
        ));
        ingress.setMetadata(meta);
        return ingress;
    }

    private Ingress buildIngressWithoutAnnotation() {
        Ingress ingress = new Ingress();
        ObjectMeta meta = new ObjectMeta();
        meta.setAnnotations(Map.of("other", "value"));
        ingress.setMetadata(meta);
        return ingress;
    }

    private String actionJson(int baseWeight, int surgeWeight) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "forward",
                    "forwardConfig", Map.of(
                            "stickinessConfig", Map.of(
                                    "enabled", true,
                                    "durationSeconds", 60
                            ),
                            "targetGroups", new Object[] {
                                    Map.of("serviceName", "api-public-read", "servicePort", "80", "weight", baseWeight),
                                    Map.of("serviceName", "api-public-read-surge", "servicePort", "80", "weight", surgeWeight)
                            }
                    )
            ));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private PreScalingProperties buildProperties() {
        PreScalingProperties properties = new PreScalingProperties();
        properties.setNamespace("application");
        return properties;
    }
}
