package com.safespot.prescalingcontroller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.prescalingcontroller.config.PreScalingProperties;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngressPatchService {

    private final KubernetesClient k8sClient;
    private final PreScalingProperties properties;
    private final ObjectMapper objectMapper;

    public boolean patchWeights(int baseWeight, int surgeWeight) {
        PreScalingProperties.Routing routing = properties.getRouting();
        Resource<Ingress> resource = ingressResource(routing.getIngressName());

        try {
            Ingress ingress = resource.get();
            if (ingress == null) {
                log.warn("[Ingress patch] ingress not found: {}/{}",
                        properties.getNamespace(), routing.getIngressName());
                return false;
            }

            Map<String, String> annotations = ingress.getMetadata() != null
                    ? ingress.getMetadata().getAnnotations()
                    : null;
            if (annotations == null || !annotations.containsKey(routing.getActionAnnotationKey())) {
                log.warn("[Ingress patch] annotation missing: {}/{} key={}",
                        properties.getNamespace(), routing.getIngressName(), routing.getActionAnnotationKey());
                return false;
            }

            JsonNode root = objectMapper.readTree(annotations.get(routing.getActionAnnotationKey()));
            ArrayNode targetGroups = (ArrayNode) root.path("forwardConfig").path("targetGroups");
            boolean baseFound = false;
            boolean surgeFound = false;

            for (JsonNode targetGroup : targetGroups) {
                if (!(targetGroup instanceof ObjectNode groupNode)) {
                    continue;
                }
                String serviceName = groupNode.path("serviceName").asText();
                if (routing.getBaseServiceName().equals(serviceName)) {
                    groupNode.put("weight", baseWeight);
                    baseFound = true;
                } else if (routing.getSurgeServiceName().equals(serviceName)) {
                    groupNode.put("weight", surgeWeight);
                    surgeFound = true;
                }
            }

            if (!baseFound || !surgeFound) {
                log.warn("[Ingress patch] target groups missing: baseFound={}, surgeFound={}, ingress={}/{}",
                        baseFound, surgeFound, properties.getNamespace(), routing.getIngressName());
                return false;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("annotations", Map.of(
                    routing.getActionAnnotationKey(), objectMapper.writeValueAsString(root)
            ));

            String patchBody = objectMapper.writeValueAsString(Map.of("metadata", metadata));
            resource.patch(new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE).build(), patchBody);

            log.info("[Ingress patch] success: {}/{} baseWeight={} surgeWeight={}",
                    properties.getNamespace(), routing.getIngressName(), baseWeight, surgeWeight);
            return true;
        } catch (Exception e) {
            log.error("[Ingress patch] failed: {}/{} baseWeight={} surgeWeight={}",
                    properties.getNamespace(), routing.getIngressName(), baseWeight, surgeWeight, e);
            return false;
        }
    }

    Resource<Ingress> ingressResource(String ingressName) {
        return k8sClient.network().v1()
                .ingresses()
                .inNamespace(properties.getNamespace())
                .withName(ingressName);
    }
}
