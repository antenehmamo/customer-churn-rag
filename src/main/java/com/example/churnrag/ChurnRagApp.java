package com.example.churnrag;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class ChurnRagApp {
    public static void main(final String[] args) {
        App app = new App();

        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        String region = System.getenv("CDK_DEFAULT_REGION");

        new ChurnRagStack(app, "CustomerChurnRagStack", StackProps.builder()
                .env(Environment.builder()
                        .account(account)
                        .region(region)
                        .build())
                .description("Customer churn RAG pipeline: S3 knowledge docs -> Bedrock Knowledge Base "
                        + "(OpenSearch Serverless vector store) -> query API")
                .build());

        app.synth();
    }
}
