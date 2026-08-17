package com.example.churnrag.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrieveAndGenerateConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateInput;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateType;

import java.util.Map;

/**
 * POST /query { "question": "..." } -> answers customer-churn questions by
 * retrieving relevant chunks from the Bedrock Knowledge Base and generating
 * a grounded response.
 */
public class QueryHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BedrockAgentRuntimeClient CLIENT = BedrockAgentRuntimeClient.create();
    private static final String KNOWLEDGE_BASE_ID = System.getenv("KNOWLEDGE_BASE_ID");
    private static final String GENERATION_MODEL_ARN = System.getenv("GENERATION_MODEL_ARN");

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String question = extractQuestion(event.getBody());
            if (question == null || question.isBlank()) {
                return response(400, Map.of("error", "Request body must include a non-empty \"question\" field"));
            }

            RetrieveAndGenerateResponse ragResponse = CLIENT.retrieveAndGenerate(RetrieveAndGenerateRequest.builder()
                    .input(RetrieveAndGenerateInput.builder().text(question).build())
                    .retrieveAndGenerateConfiguration(RetrieveAndGenerateConfiguration.builder()
                            .type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                            .knowledgeBaseConfiguration(KnowledgeBaseRetrieveAndGenerateConfiguration.builder()
                                    .knowledgeBaseId(KNOWLEDGE_BASE_ID)
                                    .modelArn(GENERATION_MODEL_ARN)
                                    .build())
                            .build())
                    .build());

            return response(200, Map.of(
                    "answer", ragResponse.output().text(),
                    "sessionId", ragResponse.sessionId()));
        } catch (Exception e) {
            context.getLogger().log("Query failed: " + e);
            return response(500, Map.of("error", "Failed to answer question: " + e.getMessage()));
        }
    }

    private static String extractQuestion(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        Map<?, ?> parsed = MAPPER.readValue(body, Map.class);
        Object question = parsed.get("question");
        return question == null ? null : question.toString();
    }

    private static APIGatewayV2HTTPResponse response(int status, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(MAPPER.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
