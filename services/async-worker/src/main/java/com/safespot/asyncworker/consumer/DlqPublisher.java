package com.safespot.asyncworker.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqPublisher {

    private final SqsClient sqsClient;

    /**
     * invalid payload처럼 retry해도 성공할 수 없는 메시지를 DLQ에 직접 전송한다.
     * 원래 메시지는 호출 측에서 BatchItemFailure에서 제외해 SQS가 ACK(삭제)하게 한다.
     *
     * DLQ URL은 source queue ARN의 naming convention으로 derive한다.
     * safespot-{env}-async-worker-sqs-{name} → safespot-{env}-async-worker-dlq-{name}
     */
    public void forward(String sourceArn, String messageBody) {
        if (sourceArn == null || !sourceArn.startsWith("arn:aws:sqs:")) {
            log.warn("DlqPublisher: cannot derive DLQ URL from sourceArn={}, skip forward", sourceArn);
            return;
        }
        try {
            String dlqUrl = toDlqUrl(sourceArn);
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(dlqUrl)
                .messageBody(messageBody)
                .build());
            log.info("DlqPublisher: forwarded invalid payload to DLQ: dlqUrl={}", dlqUrl);
        } catch (Exception e) {
            log.error("DlqPublisher: failed to forward to DLQ: sourceArn={}, reason={}", sourceArn, e.getMessage(), e);
        }
    }

    private String toDlqUrl(String arn) {
        // arn:aws:sqs:{region}:{account}:{queue-name}
        String[] parts = arn.split(":");
        String region  = parts[3];
        String account = parts[4];
        String dlqName = parts[5].replace("-sqs-", "-dlq-");
        return String.format("https://sqs.%s.amazonaws.com/%s/%s", region, account, dlqName);
    }
}
