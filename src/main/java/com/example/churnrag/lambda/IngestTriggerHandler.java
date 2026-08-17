package com.example.churnrag.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobResponse;

/**
 * Fires whenever a new object lands in the churn documents bucket and kicks
 * off a Bedrock Knowledge Base ingestion job so it gets chunked, embedded,
 * and indexed automatically.
 */
public class IngestTriggerHandler implements RequestHandler<S3Event, String> {

    private static final BedrockAgentClient CLIENT = BedrockAgentClient.create();
    private static final String KNOWLEDGE_BASE_ID = System.getenv("KNOWLEDGE_BASE_ID");
    private static final String DATA_SOURCE_ID = System.getenv("DATA_SOURCE_ID");

    @Override
    public String handleRequest(S3Event event, Context context) {
        StartIngestionJobResponse response = CLIENT.startIngestionJob(StartIngestionJobRequest.builder()
                .knowledgeBaseId(KNOWLEDGE_BASE_ID)
                .dataSourceId(DATA_SOURCE_ID)
                .build());

        String jobId = response.ingestionJob().ingestionJobId();
        context.getLogger().log("Started ingestion job " + jobId + " for " + event.getRecords().size()
                + " new object(s)");
        return jobId;
    }
}
