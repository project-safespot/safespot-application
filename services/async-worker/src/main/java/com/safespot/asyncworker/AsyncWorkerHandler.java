package com.safespot.asyncworker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.safespot.asyncworker.config.LambdaConfig;
import com.safespot.asyncworker.consumer.SqsBatchProcessor;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AsyncWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final SqsBatchProcessor PROCESSOR;
    private static final MeterRegistry METER_REGISTRY;

    static {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles("async-worker");
        ctx.register(LambdaConfig.class);
        ctx.refresh();
        PROCESSOR = ctx.getBean(SqsBatchProcessor.class);
        METER_REGISTRY = ctx.getBean(MeterRegistry.class);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        try {
            return PROCESSOR.process(event, context.getAwsRequestId());
        } finally {
            // Lambda 실행 종료 전에 metrics를 CloudWatch로 flush한다.
            // SimpleMeterRegistry인 경우(테스트/로컬)에는 no-op.
            if (METER_REGISTRY instanceof CloudWatchMeterRegistry cw) {
                cw.stop();
            }
        }
    }
}
