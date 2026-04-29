package com.safespot.asyncworker.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.safespot.asyncworker.config.LambdaConfig;
import com.safespot.asyncworker.consumer.SqsBatchProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class CacheWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final SqsBatchProcessor PROCESSOR;

    static {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles("cache-worker");
        ctx.register(LambdaConfig.class);
        ctx.refresh();
        PROCESSOR = ctx.getBean(SqsBatchProcessor.class);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return PROCESSOR.process(event, context.getAwsRequestId());
    }
}
