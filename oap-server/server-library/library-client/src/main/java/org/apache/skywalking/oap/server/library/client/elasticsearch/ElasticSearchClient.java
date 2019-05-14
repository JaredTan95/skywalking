/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.library.client.elasticsearch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.skywalking.oap.server.library.client.Client;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.IndexTemplatesExistRequest;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author peng-yongsheng
 */
public class ElasticSearchClient implements Client {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchClient.class);

    public static final String TYPE = "type";
    private final String clusterNodes;
    private final String namespace;
    private final String user;
    private final String password;
    private RestHighLevelClient client;

    public ElasticSearchClient(String clusterNodes, String namespace, String user, String password) {
        this.clusterNodes = clusterNodes;
        this.namespace = namespace;
        this.user = user;
        this.password = password;
    }

    @Override public void connect() throws IOException {
        List<HttpHost> pairsList = parseClusterNodes(clusterNodes);
        RestClientBuilder builder;
        if (StringUtils.isNotBlank(user) && StringUtils.isNotBlank(password)) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, password));
            builder = RestClient.builder(pairsList.toArray(new HttpHost[0]))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        } else {
            builder = RestClient.builder(pairsList.toArray(new HttpHost[0]));
        }
        client = new RestHighLevelClient(builder);
        client.ping(RequestOptions.DEFAULT);
    }

    @Override public void shutdown() throws IOException {
        client.close();
    }

    private List<HttpHost> parseClusterNodes(String nodes) {
        List<HttpHost> httpHosts = new LinkedList<>();
        logger.info("elasticsearch cluster nodes: {}", nodes);
        String[] nodesSplit = nodes.split(",");
        for (String node : nodesSplit) {
            String host = node.split(":")[0];
            String port = node.split(":")[1];
            httpHosts.add(new HttpHost(host, Integer.valueOf(port)));
        }

        return httpHosts;
    }

    public boolean createIndex(String indexName, JsonObject settings, JsonObject mapping) throws IOException {
        indexName = formatIndexName(indexName);
        CreateIndexRequest request = new CreateIndexRequest(indexName);
        request.settings(settings.toString(), XContentType.JSON);
        request.mapping(mapping.toString(), XContentType.JSON);
        CreateIndexResponse response = client.indices().create(request, RequestOptions.DEFAULT);
        logger.debug("create {} index finished, isAcknowledged: {}", indexName, response.isAcknowledged());
        return response.isAcknowledged();
    }

    public JsonObject getIndex(String indexName) throws IOException {
        indexName = formatIndexName(indexName);
        GetIndexRequest request = new GetIndexRequest(indexName);
        GetIndexResponse response = client.indices().get(request, RequestOptions.DEFAULT);
        Gson gson = new Gson();
        return gson.fromJson(response.toString(), JsonObject.class);
    }

    public boolean deleteIndex(String indexName) throws IOException {
        indexName = formatIndexName(indexName);
        DeleteIndexRequest request = new DeleteIndexRequest(indexName);
        AcknowledgedResponse response = client.indices().delete(request, RequestOptions.DEFAULT);
        logger.debug("delete {} index finished, isAcknowledged: {}", indexName, response.isAcknowledged());
        return response.isAcknowledged();
    }

    public boolean isExistsIndex(String indexName) throws IOException {
        indexName = formatIndexName(indexName);
        GetIndexRequest request = new GetIndexRequest(indexName);
        return client.indices().exists(request, RequestOptions.DEFAULT);
    }

    public boolean isExistsTemplate(String indexName) throws IOException {
        indexName = formatIndexName(indexName);
        IndexTemplatesExistRequest request = new IndexTemplatesExistRequest("/_template/" + indexName);
        return client.indices().existsTemplate(request, RequestOptions.DEFAULT);
    }

    public boolean createTemplate(String indexName, JsonObject settings, JsonObject mapping) throws IOException {
        indexName = formatIndexName(indexName);

        JsonArray patterns = new JsonArray();
        patterns.add(indexName + "_*");

        JsonObject template = new JsonObject();
        template.add("index_patterns", patterns);
        template.add("settings", settings);
        template.add("mappings", mapping);

        Request request = new Request(HttpPut.METHOD_NAME, "/_template/" + indexName);
        Response response = client.getLowLevelClient().performRequest(request);
        return response.getStatusLine().getStatusCode() == 200;
    }

    public boolean deleteTemplate(String indexName) throws IOException {
        indexName = formatIndexName(indexName);
        DeleteIndexTemplateRequest request = new DeleteIndexTemplateRequest("/_template/" + indexName);
        AcknowledgedResponse deleteTemplateAcknowledge = client.indices().deleteTemplate(request, RequestOptions.DEFAULT);
        return deleteTemplateAcknowledge.isAcknowledged();
    }

    public SearchResponse search(String indexName, SearchSourceBuilder searchSourceBuilder) throws IOException {
        indexName = formatIndexName(indexName);
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.source(searchSourceBuilder);
        return client.search(searchRequest, RequestOptions.DEFAULT);
    }

    public GetResponse get(String indexName, String id) throws IOException {
        indexName = formatIndexName(indexName);
        GetRequest request = new GetRequest(indexName, id);
        return client.get(request, RequestOptions.DEFAULT);
    }

    public MultiGetResponse multiGet(String indexName, List<String> ids) throws IOException {
        final String newIndexName = formatIndexName(indexName);
        MultiGetRequest request = new MultiGetRequest();
        ids.forEach(id -> request.add(newIndexName, id));
        return client.mget(request, RequestOptions.DEFAULT);
    }

    public void forceInsert(String indexName, String id, XContentBuilder source) throws IOException {
        IndexRequest request = prepareInsert(indexName, id, source);
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        client.index(request, RequestOptions.DEFAULT);
    }

    public void forceUpdate(String indexName, String id, XContentBuilder source, long version) throws IOException {
        UpdateRequest request = prepareUpdate(indexName, id, source);
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        client.update(request, RequestOptions.DEFAULT);
    }

    public void forceUpdate(String indexName, String id, XContentBuilder source) throws IOException {
        UpdateRequest request = prepareUpdate(indexName, id, source);
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        client.update(request, RequestOptions.DEFAULT);
    }

    public IndexRequest prepareInsert(String indexName, String id, XContentBuilder source) {
        indexName = formatIndexName(indexName);
        return new IndexRequest(indexName).id(id).source(source);
    }

    public UpdateRequest prepareUpdate(String indexName, String id, XContentBuilder source) {
        indexName = formatIndexName(indexName);
        return new UpdateRequest(indexName, id).doc(source);
    }

    public long delete(String indexName, String timeBucketColumnName, long endTimeBucket) throws IOException {
        indexName = formatIndexName(indexName);

        DeleteByQueryRequest deleteByQueryRequest =
            new DeleteByQueryRequest(indexName);

        deleteByQueryRequest.setConflicts("proceed");
        deleteByQueryRequest.setQuery(QueryBuilders.rangeQuery(timeBucketColumnName).lte(endTimeBucket));

        BulkByScrollResponse bulkResponse = client.deleteByQuery(deleteByQueryRequest, RequestOptions.DEFAULT);
        return bulkResponse.getStatus().getSuccessfullyProcessed();
    }

    public String formatIndexName(String indexName) {
        if (StringUtils.isNotEmpty(namespace)) {
            return namespace + "_" + indexName;
        }
        return indexName;
    }

    public BulkProcessor createBulkProcessor(int bulkActions, int bulkSize, int flushInterval, int concurrentRequests) {
        BulkProcessor.Listener listener = new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                int numberOfActions = request.numberOfActions();
                logger.debug("Executing bulk [{}] with {} requests", executionId, numberOfActions);
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                if (response.hasFailures()) {
                    logger.warn("Bulk [{}] executed with failures", executionId);
                } else {
                    logger.info("Bulk [{}] completed in {} milliseconds", executionId, response.getTook().getMillis());
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                logger.error("Failed to execute bulk", failure);
            }
        };

        return BulkProcessor.builder((request, bulkListener) ->
            client.bulkAsync(request, RequestOptions.DEFAULT, bulkListener), listener)
            .setBulkActions(bulkActions)
            .setBulkSize(new ByteSizeValue(bulkSize, ByteSizeUnit.MB))
            .setFlushInterval(TimeValue.timeValueSeconds(flushInterval))
            .setConcurrentRequests(concurrentRequests)
            .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
            .build();
    }
}
