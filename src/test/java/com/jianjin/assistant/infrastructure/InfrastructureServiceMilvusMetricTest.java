package com.jianjin.assistant.infrastructure;

import com.jianjin.assistant.infrastructure.platform.MilvusConnector;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InfrastructureServiceMilvusMetricTest {

    @Test
    void ragVectorSearchUsesCosineMetric() {
        MilvusConnector connector = mock(MilvusConnector.class);
        MilvusClient client = mock(MilvusClient.class);
        when(connector.client()).thenReturn(client);
        when(client.search(any(SearchParam.class))).thenReturn(R.success(SearchResults.getDefaultInstance()));
        InfrastructureService infrastructure = new InfrastructureService(
                null, connector, null, null, null,
                null, null, null, null, null, null);

        infrastructure.milvusSearchWithScores(List.of(0.1f, 0.2f), 3);

        ArgumentCaptor<SearchParam> captor = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(captor.capture());
        assertEquals(MetricType.COSINE.name(), captor.getValue().getMetricType());
        assertEquals(MetricType.COSINE, MilvusConnector.RAG_METRIC);
    }

    @Test
    void ragVectorSearchPushesDocumentScopeIntoMilvusExpression() {
        MilvusConnector connector = mock(MilvusConnector.class);
        MilvusClient client = mock(MilvusClient.class);
        when(connector.client()).thenReturn(client);
        when(client.search(any(SearchParam.class))).thenReturn(R.success(SearchResults.getDefaultInstance()));
        InfrastructureService infrastructure = new InfrastructureService(
                null, connector, null, null, null,
                null, null, null, null, null, null);

        infrastructure.milvusSearchWithScores(List.of(0.1f), 3, Set.of("abc123", "def456"));

        ArgumentCaptor<SearchParam> captor = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(captor.capture());
        String expression = captor.getValue().getExpr();
        org.junit.jupiter.api.Assertions.assertTrue(expression.startsWith("doc_hash in ["));
        org.junit.jupiter.api.Assertions.assertTrue(expression.contains("\"abc123\""));
        org.junit.jupiter.api.Assertions.assertTrue(expression.contains("\"def456\""));
    }
}
