package com.example.churnrag;

import software.amazon.awscdk.Arn;
import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.customresources.Provider;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpMethod;
import software.amazon.awscdk.services.bedrock.CfnDataSource;
import software.amazon.awscdk.services.bedrock.CfnKnowledgeBase;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ServicePrincipalOpts;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.opensearchserverless.CfnAccessPolicy;
import software.amazon.awscdk.services.opensearchserverless.CfnCollection;
import software.amazon.awscdk.services.opensearchserverless.CfnSecurityPolicy;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Customer churn RAG pipeline.
 *
 * Documents (support tickets, call transcripts, churn reports) land in S3, get chunked
 * and embedded into an OpenSearch Serverless vector collection via a Bedrock Knowledge
 * Base, and are queried through an HTTP API backed by Bedrock's RetrieveAndGenerate API.
 */
public class ChurnRagStack extends Stack {

    private static final String COLLECTION_NAME = "churn-rag-kb";
    private static final String INDEX_NAME = "churn-kb-index";
    private static final String VECTOR_FIELD = "bedrock-knowledge-base-default-vector";
    private static final String TEXT_FIELD = "AMAZON_BEDROCK_TEXT_CHUNK";
    private static final String METADATA_FIELD = "AMAZON_BEDROCK_METADATA";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String EMBEDDING_MODEL_ID = "amazon.titan-embed-text-v2:0";
    private static final String GENERATION_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";
    private static final String LAMBDA_JAR = "build/libs/customer-churn-rag.jar";

    public ChurnRagStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String embeddingModelArn = Arn.format(ArnComponents.builder()
                .service("bedrock")
                .resource("foundation-model")
                .resourceName(EMBEDDING_MODEL_ID)
                .account("")
                .build(), this);

        String generationModelArn = Arn.format(ArnComponents.builder()
                .service("bedrock")
                .resource("foundation-model")
                .resourceName(GENERATION_MODEL_ID)
                .account("")
                .build(), this);

        // -----------------------------------------------------------------
        // Source documents bucket
        // -----------------------------------------------------------------
        Bucket docBucket = Bucket.Builder.create(this, "ChurnDocumentsBucket")
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .enforceSsl(true)
                .versioned(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        // -----------------------------------------------------------------
        // OpenSearch Serverless vector collection
        // -----------------------------------------------------------------
        CfnSecurityPolicy encryptionPolicy = CfnSecurityPolicy.Builder.create(this, "CollectionEncryptionPolicy")
                .name(COLLECTION_NAME + "-enc")
                .type("encryption")
                .policy("{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/"
                        + COLLECTION_NAME + "\"]}],\"AWSOwnedKey\":true}")
                .build();

        CfnSecurityPolicy networkPolicy = CfnSecurityPolicy.Builder.create(this, "CollectionNetworkPolicy")
                .name(COLLECTION_NAME + "-net")
                .type("network")
                .policy("[{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/"
                        + COLLECTION_NAME + "\"]},{\"ResourceType\":\"dashboard\",\"Resource\":[\"collection/"
                        + COLLECTION_NAME + "\"]}],\"AllowFromPublic\":true}]")
                .build();

        CfnCollection collection = CfnCollection.Builder.create(this, "VectorCollection")
                .name(COLLECTION_NAME)
                .type("VECTORSEARCH")
                .description("Vector store for the customer churn knowledge base")
                .build();
        collection.addDependency(encryptionPolicy);
        collection.addDependency(networkPolicy);

        // -----------------------------------------------------------------
        // Bedrock Knowledge Base service role
        // -----------------------------------------------------------------
        Role kbRole = Role.Builder.create(this, "KnowledgeBaseRole")
                .assumedBy(new ServicePrincipal("bedrock.amazonaws.com", ServicePrincipalOpts.builder()
                        .conditions(Map.of(
                                "StringEquals", Map.of("aws:SourceAccount", this.getAccount()),
                                "ArnLike", Map.of("aws:SourceArn",
                                        "arn:aws:bedrock:" + this.getRegion() + ":" + this.getAccount()
                                                + ":knowledge-base/*")))
                        .build()))
                .build();

        kbRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of(embeddingModelArn))
                .build());
        docBucket.grantRead(kbRole);
        kbRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("aoss:APIAccessAll"))
                .resources(List.of(collection.getAttrArn()))
                .build());

        // -----------------------------------------------------------------
        // Custom resource that creates the k-NN vector index inside the
        // collection. CloudFormation has no native resource for this, so a
        // small Python Lambda signs and sends the index-creation request
        // directly against the collection's data endpoint.
        // -----------------------------------------------------------------
        Function indexCreatorFn = Function.Builder.create(this, "VectorIndexCreatorFunction")
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromAsset("lambda/index-creator"))
                .timeout(Duration.seconds(120))
                .build();
        indexCreatorFn.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("aoss:APIAccessAll"))
                .resources(List.of(collection.getAttrArn()))
                .build());

        CfnAccessPolicy dataAccessPolicy = CfnAccessPolicy.Builder.create(this, "CollectionDataAccessPolicy")
                .name(COLLECTION_NAME + "-access")
                .type("data")
                .policy("[{\"Rules\":["
                        + "{\"ResourceType\":\"index\",\"Resource\":[\"index/" + COLLECTION_NAME
                        + "/*\"],\"Permission\":[\"aoss:*\"]},"
                        + "{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + COLLECTION_NAME
                        + "\"],\"Permission\":[\"aoss:*\"]}"
                        + "],\"Principal\":[\"" + kbRole.getRoleArn() + "\",\""
                        + indexCreatorFn.getRole().getRoleArn() + "\"]}]")
                .build();
        dataAccessPolicy.getNode().addDependency(collection);

        Provider indexCreatorProvider = Provider.Builder.create(this, "VectorIndexCreatorProvider")
                .onEventHandler(indexCreatorFn)
                .build();

        CustomResource vectorIndex = CustomResource.Builder.create(this, "VectorIndexResource")
                .serviceToken(indexCreatorProvider.getServiceToken())
                .properties(Map.of(
                        "CollectionEndpoint", collection.getAttrCollectionEndpoint(),
                        "IndexName", INDEX_NAME,
                        "VectorField", VECTOR_FIELD,
                        "Dimension", String.valueOf(VECTOR_DIMENSION)))
                .build();
        vectorIndex.getNode().addDependency(dataAccessPolicy);

        // -----------------------------------------------------------------
        // Bedrock Knowledge Base + S3 data source
        // -----------------------------------------------------------------
        CfnKnowledgeBase kb = CfnKnowledgeBase.Builder.create(this, "ChurnKnowledgeBase")
                .name("customer-churn-knowledge-base")
                .description("Customer churn signals: support tickets, call transcripts, churn reports")
                .roleArn(kbRole.getRoleArn())
                .knowledgeBaseConfiguration(CfnKnowledgeBase.KnowledgeBaseConfigurationProperty.builder()
                        .type("VECTOR")
                        .vectorKnowledgeBaseConfiguration(
                                CfnKnowledgeBase.VectorKnowledgeBaseConfigurationProperty.builder()
                                        .embeddingModelArn(embeddingModelArn)
                                        .build())
                        .build())
                .storageConfiguration(CfnKnowledgeBase.StorageConfigurationProperty.builder()
                        .type("OPENSEARCH_SERVERLESS")
                        .opensearchServerlessConfiguration(
                                CfnKnowledgeBase.OpenSearchServerlessConfigurationProperty.builder()
                                        .collectionArn(collection.getAttrArn())
                                        .vectorIndexName(INDEX_NAME)
                                        .fieldMapping(CfnKnowledgeBase.OpenSearchServerlessFieldMappingProperty
                                                .builder()
                                                .vectorField(VECTOR_FIELD)
                                                .textField(TEXT_FIELD)
                                                .metadataField(METADATA_FIELD)
                                                .build())
                                        .build())
                        .build())
                .build();
        kb.getNode().addDependency(vectorIndex);

        CfnDataSource dataSource = CfnDataSource.Builder.create(this, "ChurnDocsDataSource")
                .knowledgeBaseId(kb.getAttrKnowledgeBaseId())
                .name("customer-churn-documents")
                .dataSourceConfiguration(CfnDataSource.DataSourceConfigurationProperty.builder()
                        .type("S3")
                        .s3Configuration(CfnDataSource.S3DataSourceConfigurationProperty.builder()
                                .bucketArn(docBucket.getBucketArn())
                                .build())
                        .build())
                .vectorIngestionConfiguration(CfnDataSource.VectorIngestionConfigurationProperty.builder()
                        .chunkingConfiguration(CfnDataSource.ChunkingConfigurationProperty.builder()
                                .chunkingStrategy("FIXED_SIZE")
                                .fixedSizeChunkingConfiguration(
                                        CfnDataSource.FixedSizeChunkingConfigurationProperty.builder()
                                                .maxTokens(500)
                                                .overlapPercentage(20)
                                                .build())
                                .build())
                        .build())
                .build();

        // -----------------------------------------------------------------
        // Query API: POST /query -> Bedrock RetrieveAndGenerate
        // -----------------------------------------------------------------
        Function queryFn = Function.Builder.create(this, "QueryFunction")
                .runtime(Runtime.JAVA_21)
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .handler("com.example.churnrag.lambda.QueryHandler::handleRequest")
                .code(Code.fromAsset(LAMBDA_JAR))
                .environment(Map.of(
                        "KNOWLEDGE_BASE_ID", kb.getAttrKnowledgeBaseId(),
                        "GENERATION_MODEL_ARN", generationModelArn))
                .build();
        queryFn.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:RetrieveAndGenerate", "bedrock:Retrieve"))
                .resources(List.of(kb.getAttrKnowledgeBaseArn()))
                .build());
        queryFn.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of(generationModelArn))
                .build());

        // -----------------------------------------------------------------
        // Ingestion trigger: new object in S3 -> StartIngestionJob
        // -----------------------------------------------------------------
        Function ingestTriggerFn = Function.Builder.create(this, "IngestTriggerFunction")
                .runtime(Runtime.JAVA_21)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .handler("com.example.churnrag.lambda.IngestTriggerHandler::handleRequest")
                .code(Code.fromAsset(LAMBDA_JAR))
                .environment(Map.of(
                        "KNOWLEDGE_BASE_ID", kb.getAttrKnowledgeBaseId(),
                        "DATA_SOURCE_ID", dataSource.getAttrDataSourceId()))
                .build();
        ingestTriggerFn.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:StartIngestionJob"))
                .resources(List.of(kb.getAttrKnowledgeBaseArn()))
                .build());
        docBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(ingestTriggerFn));

        // -----------------------------------------------------------------
        // HTTP API
        // -----------------------------------------------------------------
        HttpApi httpApi = HttpApi.Builder.create(this, "ChurnRagApi")
                .description("Customer churn RAG query API")
                .build();
        httpApi.addRoutes(AddRoutesOptions.builder()
                .path("/query")
                .methods(List.of(HttpMethod.POST))
                .integration(new HttpLambdaIntegration("QueryIntegration", queryFn))
                .build());

        // -----------------------------------------------------------------
        // Outputs
        // -----------------------------------------------------------------
        CfnOutput.Builder.create(this, "DocumentBucketName").value(docBucket.getBucketName()).build();
        CfnOutput.Builder.create(this, "KnowledgeBaseId").value(kb.getAttrKnowledgeBaseId()).build();
        CfnOutput.Builder.create(this, "ApiEndpoint").value(httpApi.getApiEndpoint() + "/query").build();
    }
}
