"""
CloudFormation custom-resource handler (used via aws-cdk-lib's `customresources.Provider`).

CloudFormation has no native resource for OpenSearch Serverless vector indexes, so this
Lambda signs and sends the index-creation/deletion request directly against the
collection's data-plane endpoint using SigV4 (service "aoss"), via boto3's credential
provider only -- no extra dependencies beyond what the Python 3.12 runtime ships with.
"""
import json
import time

import boto3
import urllib3
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest

http = urllib3.PoolManager()
session = boto3.Session()
region = session.region_name


def _signed_request(method, url, body=None):
    headers = {"Content-Type": "application/json"}
    data = json.dumps(body) if body is not None else None
    request = AWSRequest(method=method, url=url, data=data, headers=headers)
    SigV4Auth(session.get_credentials(), "aoss", region).add_auth(request)
    return http.request(method, url, body=data, headers=dict(request.headers))


def handler(event, context):
    request_type = event["RequestType"]
    props = event["ResourceProperties"]
    endpoint = props["CollectionEndpoint"]
    index_name = props["IndexName"]
    vector_field = props.get("VectorField", "bedrock-knowledge-base-default-vector")
    dimension = int(props.get("Dimension", 1024))
    url = f"{endpoint}/{index_name}"

    if request_type == "Delete":
        resp = _signed_request("DELETE", url)
        if resp.status not in (200, 404):
            print(f"Warning: failed to delete index {index_name}: {resp.status} {resp.data}")
        return {"PhysicalResourceId": index_name}

    if request_type in ("Create", "Update"):
        body = {
            "settings": {"index.knn": True},
            "mappings": {
                "properties": {
                    vector_field: {
                        "type": "knn_vector",
                        "dimension": dimension,
                        "method": {
                            "engine": "faiss",
                            "name": "hnsw",
                            "space_type": "l2",
                        },
                    },
                    "AMAZON_BEDROCK_TEXT_CHUNK": {"type": "text"},
                    "AMAZON_BEDROCK_METADATA": {"type": "text", "index": False},
                }
            },
        }
        resp = _signed_request("PUT", url, body)
        if resp.status >= 300:
            raise Exception(f"Failed to create index {index_name}: {resp.status} {resp.data}")

        # OpenSearch Serverless index creation is eventually consistent; give it a
        # moment to propagate before the Bedrock Knowledge Base validates against it.
        time.sleep(45)
        return {"PhysicalResourceId": index_name}

    raise Exception(f"Unsupported RequestType: {request_type}")
