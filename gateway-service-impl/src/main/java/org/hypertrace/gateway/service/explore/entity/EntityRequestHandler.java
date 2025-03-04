package org.hypertrace.gateway.service.explore.entity;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hypertrace.core.attribute.service.v1.AttributeMetadata;
import org.hypertrace.core.query.service.client.QueryServiceClient;
import org.hypertrace.entity.query.service.client.EntityQueryServiceClient;
import org.hypertrace.entity.query.service.v1.ColumnMetadata;
import org.hypertrace.entity.query.service.v1.ResultSetChunk;
import org.hypertrace.entity.query.service.v1.ResultSetMetadata;
import org.hypertrace.entity.query.service.v1.Row;
import org.hypertrace.entity.query.service.v1.Value;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.converters.EntityServiceAndGatewayServiceConverter;
import org.hypertrace.gateway.service.common.datafetcher.EntityFetcherResponse;
import org.hypertrace.gateway.service.common.datafetcher.QueryServiceEntityFetcher;
import org.hypertrace.gateway.service.common.util.AttributeMetadataUtil;
import org.hypertrace.gateway.service.common.util.MetricAggregationFunctionUtil;
import org.hypertrace.gateway.service.entity.EntitiesRequestContext;
import org.hypertrace.gateway.service.entity.config.EntityIdColumnsConfigs;
import org.hypertrace.gateway.service.explore.ExploreRequestContext;
import org.hypertrace.gateway.service.explore.RequestHandler;
import org.hypertrace.gateway.service.v1.common.FunctionExpression;
import org.hypertrace.gateway.service.v1.entity.EntitiesRequest;
import org.hypertrace.gateway.service.v1.entity.Entity.Builder;
import org.hypertrace.gateway.service.v1.explore.ExploreRequest;
import org.hypertrace.gateway.service.v1.explore.ExploreResponse;

/**
 * {@link EntityRequestHandler} is currently used only when the selections, group bys and filters
 * are on EDS. Can be extended later to support multiple sources. Only needed, when there is a group
 * by on the request, else can directly use {@link
 * org.hypertrace.gateway.service.v1.entity.EntitiesRequest}
 *
 * <p>Currently,
 *
 * <ul>
 *   <li>Query to {@link
 *       org.hypertrace.gateway.service.common.datafetcher.QueryServiceEntityFetcher} with the time
 *       filter to get set of entity ids. Can be extended to support QS filters
 *   <li>Query to {@link EntityServiceEntityFetcher} with selections, group bys, and filters with an
 *       IN clause on entity ids
 * </ul>
 */
public class EntityRequestHandler extends RequestHandler {
  private final AttributeMetadataProvider attributeMetadataProvider;

  private final QueryServiceEntityFetcher queryServiceEntityFetcher;
  private final EntityServiceEntityFetcher entityServiceEntityFetcher;

  public EntityRequestHandler(
      AttributeMetadataProvider attributeMetadataProvider,
      EntityIdColumnsConfigs entityIdColumnsConfigs,
      QueryServiceClient queryServiceClient,
      int qsRequestTimeout,
      EntityQueryServiceClient entityQueryServiceClient) {
    super(queryServiceClient, qsRequestTimeout, attributeMetadataProvider);

    this.attributeMetadataProvider = attributeMetadataProvider;
    this.queryServiceEntityFetcher =
        new QueryServiceEntityFetcher(
            queryServiceClient,
            qsRequestTimeout,
            attributeMetadataProvider,
            entityIdColumnsConfigs);
    this.entityServiceEntityFetcher =
        new EntityServiceEntityFetcher(
            attributeMetadataProvider, entityIdColumnsConfigs, entityQueryServiceClient);
  }

  @VisibleForTesting
  public EntityRequestHandler(
      AttributeMetadataProvider attributeMetadataProvider,
      QueryServiceClient queryServiceClient,
      int qsRequestTimeout,
      QueryServiceEntityFetcher queryServiceEntityFetcher,
      EntityServiceEntityFetcher entityServiceEntityFetcher) {
    super(queryServiceClient, qsRequestTimeout, attributeMetadataProvider);

    this.attributeMetadataProvider = attributeMetadataProvider;
    this.queryServiceEntityFetcher = queryServiceEntityFetcher;
    this.entityServiceEntityFetcher = entityServiceEntityFetcher;
  }

  @Override
  public ExploreResponse.Builder handleRequest(
      ExploreRequestContext requestContext, ExploreRequest exploreRequest) {
    // Track if we have Group By so we can determine if we need to do Order By, Limit and Offset
    // ourselves.
    if (!exploreRequest.getGroupByList().isEmpty()) {
      requestContext.setHasGroupBy(true);
    }

    Set<String> entityIds = getEntityIds(requestContext, exploreRequest);
    ExploreResponse.Builder builder = ExploreResponse.newBuilder();

    if (entityIds.isEmpty()) {
      return builder;
    }

    Iterator<ResultSetChunk> resultSetChunkIterator =
        entityServiceEntityFetcher.getResults(requestContext, exploreRequest, entityIds);

    while (resultSetChunkIterator.hasNext()) {
      org.hypertrace.entity.query.service.v1.ResultSetChunk chunk = resultSetChunkIterator.next();
      getLogger().debug("Received chunk: {}", chunk);

      if (chunk.getRowCount() < 1) {
        break;
      }

      if (!chunk.hasResultSetMetadata()) {
        getLogger().warn("Chunk doesn't have result metadata so couldn't process the response.");
        break;
      }

      chunk
          .getRowList()
          .forEach(
              row ->
                  handleRow(
                      row,
                      chunk.getResultSetMetadata(),
                      builder,
                      requestContext,
                      attributeMetadataProvider));
    }

    // If there's a Group By in the request, we need to do the sorting and pagination ourselves.
    if (requestContext.hasGroupBy()) {
      sortAndPaginatePostProcess(
          builder,
          requestContext.getOrderByExpressions(),
          requestContext.getRowLimitBeforeRest(),
          requestContext.getOffset());
    }

    if (requestContext.hasGroupBy() && requestContext.getIncludeRestGroup()) {
      getTheRestGroupRequestHandler()
          .getRowsForTheRestGroup(requestContext, exploreRequest, builder);
    }

    return builder;
  }

  private Set<String> getEntityIds(
      ExploreRequestContext requestContext, ExploreRequest exploreRequest) {
    EntitiesRequestContext entitiesRequestContext =
        convert(attributeMetadataProvider, requestContext);
    EntitiesRequest entitiesRequest =
        EntitiesRequest.newBuilder()
            .setEntityType(exploreRequest.getContext())
            .setStartTimeMillis(exploreRequest.getStartTimeMillis())
            .setEndTimeMillis(exploreRequest.getEndTimeMillis())
            .build();
    EntityFetcherResponse response =
        queryServiceEntityFetcher.getEntities(entitiesRequestContext, entitiesRequest);
    return response.getEntityKeyBuilderMap().values().stream()
        .map(Builder::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  private EntitiesRequestContext convert(
      AttributeMetadataProvider attributeMetadataProvider, ExploreRequestContext requestContext) {
    String entityType = requestContext.getContext();

    String timestampAttributeId =
        AttributeMetadataUtil.getTimestampAttributeId(
            attributeMetadataProvider, requestContext, entityType);
    return new EntitiesRequestContext(
        requestContext.getTenantId(),
        requestContext.getStartTimeMillis(),
        requestContext.getEndTimeMillis(),
        entityType,
        timestampAttributeId,
        requestContext.getHeaders());
  }

  private void handleRow(
      Row row,
      ResultSetMetadata resultSetMetadata,
      ExploreResponse.Builder builder,
      ExploreRequestContext requestContext,
      AttributeMetadataProvider attributeMetadataProvider) {
    var rowBuilder = org.hypertrace.gateway.service.v1.common.Row.newBuilder();
    for (int i = 0; i < resultSetMetadata.getColumnMetadataCount(); i++) {
      handleColumn(
          row.getColumn(i),
          resultSetMetadata.getColumnMetadata(i),
          rowBuilder,
          requestContext,
          attributeMetadataProvider);
    }
    builder.addRow(rowBuilder);
  }

  private void handleColumn(
      Value value,
      ColumnMetadata metadata,
      org.hypertrace.gateway.service.v1.common.Row.Builder rowBuilder,
      ExploreRequestContext requestContext,
      AttributeMetadataProvider attributeMetadataProvider) {
    FunctionExpression function =
        requestContext.getFunctionExpressionByAlias(metadata.getColumnName());
    handleColumn(value, metadata, rowBuilder, requestContext, attributeMetadataProvider, function);
  }

  void handleColumn(
      Value value,
      ColumnMetadata metadata,
      org.hypertrace.gateway.service.v1.common.Row.Builder rowBuilder,
      ExploreRequestContext requestContext,
      AttributeMetadataProvider attributeMetadataProvider,
      FunctionExpression function) {
    Map<String, AttributeMetadata> attributeMetadataMap =
        attributeMetadataProvider.getAttributesMetadata(
            requestContext, requestContext.getContext());
    Map<String, AttributeMetadata> resultKeyToAttributeMetadataMap =
        this.remapAttributeMetadataByResultName(
            requestContext.getExploreRequest(), attributeMetadataMap);
    org.hypertrace.gateway.service.v1.common.Value gwValue;
    if (function != null) {
      // Function expression value
      gwValue =
          EntityServiceAndGatewayServiceConverter.convertToGatewayValueForMetricValue(
              MetricAggregationFunctionUtil.getValueTypeForFunctionType(
                  function, attributeMetadataMap),
              resultKeyToAttributeMetadataMap,
              metadata,
              value);
    } else {
      // Simple columnId expression value eg. groupBy columns or column selections
      gwValue =
          EntityServiceAndGatewayServiceConverter.convertToGatewayValue(
              metadata.getColumnName(), value, resultKeyToAttributeMetadataMap);
    }

    rowBuilder.putColumns(metadata.getColumnName(), gwValue);
  }

  private Map<String, AttributeMetadata> remapAttributeMetadataByResultName(
      ExploreRequest request, Map<String, AttributeMetadata> attributeMetadataByIdMap) {
    return AttributeMetadataUtil.remapAttributeMetadataByResultKey(
        Streams.concat(
                request.getSelectionList().stream(),
                // Add groupBy to Selection list.
                // The expectation from the Gateway service client is that they do not add the group
                // by expressions to the selection expressions in the request
                request.getGroupByList().stream())
            .collect(Collectors.toUnmodifiableList()),
        attributeMetadataByIdMap);
  }
}
