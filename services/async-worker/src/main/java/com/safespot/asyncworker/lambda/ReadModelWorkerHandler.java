package com.safespot.asyncworker.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.safespot.asyncworker.AsyncWorkerApplication;
import com.safespot.asyncworker.consumer.SqsBatchProcessor;
import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.envelope.EnvelopeParser;
import com.safespot.asyncworker.idempotency.IdempotencyService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.SpringApplication;

public class ReadModelWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    // SnapStart: static 초기화로 컨텍스트 스냅샷에 포함
    private static final ConfigurableApplicationContext APPLICATION_CONTEXT =
        SpringApplication.run(AsyncWorkerApplication.class);

    private final SqsBatchProcessor processor;

    public ReadModelWorkerHandler() {
        EnvelopeParser parser = APPLICATION_CONTEXT.getBean(EnvelopeParser.class);
        IdempotencyService idempotencyService = APPLICATION_CONTEXT.getBean(IdempotencyService.class);
        EventDispatcher dispatcher = APPLICATION_CONTEXT.getBean("readModelWorkerDispatcher", EventDispatcher.class);
        this.processor = new SqsBatchProcessor(parser, idempotencyService, dispatcher);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return processor.process(event);
    }
}
