package com.safespot.asyncworker.exception;

public class ResourceNotFoundException extends EventProcessingException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(resourceType + " not found: " + id);
    }
}
