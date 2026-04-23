package com.safespot.asyncworker.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.safespot.asyncworker.CacheWorkerApplication;
import com.safespot.asyncworker.consumer.SqsBatchProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class CacheWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    // SnapStart: static 초기화로 컨텍스트 스냅샷에 포함
    private static final ConfigurableApplicationContext APPLICATION_CONTEXT =
        new SpringApplicationBuilder(CacheWorkerApplication.class)
            .profiles("cache-worker")
            .run();

    private final SqsBatchProcessor processor;

    public CacheWorkerHandler() {
        this.processor = APPLICATION_CONTEXT.getBean(SqsBatchProcessor.class);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return processor.process(event, context.getAwsRequestId());
    }
}
