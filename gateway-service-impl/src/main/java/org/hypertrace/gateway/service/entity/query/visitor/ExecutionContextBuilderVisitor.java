package org.hypertrace.gateway.service.entity.query.visitor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hypertrace.gateway.service.entity.query.AndNode;
import org.hypertrace.gateway.service.entity.query.DataFetcherNode;
import org.hypertrace.gateway.service.entity.query.EntityExecutionContext;
import org.hypertrace.gateway.service.entity.query.NoOpNode;
import org.hypertrace.gateway.service.entity.query.OrNode;
import org.hypertrace.gateway.service.entity.query.PaginateOnlyNode;
import org.hypertrace.gateway.service.entity.query.SelectionNode;
import org.hypertrace.gateway.service.entity.query.SortAndPaginateNode;

/**
 * Visitor for capturing the different sources corresponding to the expressions in the execution
 * tree
 *
 * <p>Returns set of sources for which attributes have already been fetched
 */
public class ExecutionContextBuilderVisitor implements Visitor<Void> {
  private final EntityExecutionContext executionContext;

  public ExecutionContextBuilderVisitor(EntityExecutionContext executionContext) {
    this.executionContext = executionContext;
  }

  /**
   * Removes the attribute selection source using {@link
   * EntityExecutionContext#removePendingSelectionSource(String)} and {@link
   * EntityExecutionContext#removePendingSelectionSourceForOrderBy(String)}, if the attribute has
   * already been requested from a different source
   *
   * <p>Example: select api.id, api.name api.id -> ["QS", "EDS"] api.name -> ["QS", EDS"]
   *
   * <p>If api.id, api.name has already been fetched from QS, there is no point fetching the same
   * set of attributes from EDS
   *
   * <p>Algorithm:
   *
   * <p>Gather the set of the attributes(say A) fetched from the {@link DataFetcherNode} source
   *
   * <p>for each pending selection source S from {@link
   * EntityExecutionContext#getPendingSelectionSources()} and {@link
   * EntityExecutionContext#getPendingMetricAggregationSourcesForOrderBy()}, get all the selection
   * attributes for the source
   *
   * <p>if the selection attributes for that source are already present in the set A, remove this
   * selection source using {@link EntityExecutionContext#removePendingSelectionSource(String)} and
   * {@link EntityExecutionContext#removePendingSelectionSourceForOrderBy(String)}
   */
  @Override
  public Void visit(DataFetcherNode dataFetcherNode) {
    String source = dataFetcherNode.getSource();

    executionContext.removePendingSelectionSource(source);
    // TODO: Currently, assumes that the order by attribute is also present in the selection set
    executionContext.removePendingSelectionSourceForOrderBy(source);

    // set of attributes which were fetched from the source
    Map<String, Set<String>> sourceToSelectionAttributeMap =
        executionContext.getExpressionContext().getSourceToSelectionAttributeMap();

    Set<String> fetchedAttributes =
        sourceToSelectionAttributeMap.getOrDefault(source, Collections.emptySet());

    if (!executionContext.getPendingSelectionSources().isEmpty()) {
      Set<String> redundantPendingSelectionSources =
          getRedundantPendingSelectionSources(
              fetchedAttributes,
              executionContext.getPendingSelectionSources(),
              executionContext.getExpressionContext().getSourceToSelectionAttributeMap());
      redundantPendingSelectionSources.forEach(executionContext::removePendingSelectionSource);
    }

    if (!executionContext.getPendingSelectionSourcesForOrderBy().isEmpty()) {
      Set<String> redundantPendingSelectionSourcesForOrderBy =
          getRedundantPendingSelectionSources(
              fetchedAttributes,
              executionContext.getPendingSelectionSourcesForOrderBy(),
              executionContext.getExpressionContext().getSourceToSelectionOrderByAttributeMap());
      redundantPendingSelectionSourcesForOrderBy.forEach(
          executionContext::removePendingSelectionSourceForOrderBy);
    }

    return null;
  }

  @Override
  public Void visit(AndNode andNode) {
    andNode.getChildNodes().forEach(n -> n.acceptVisitor(this));
    return null;
  }

  @Override
  public Void visit(OrNode orNode) {
    orNode.getChildNodes().forEach(n -> n.acceptVisitor(this));
    return null;
  }

  @Override
  public Void visit(SelectionNode selectionNode) {
    return selectionNode.getChildNode().acceptVisitor(this);
  }

  @Override
  public Void visit(SortAndPaginateNode sortAndPaginateNode) {
    return sortAndPaginateNode.getChildNode().acceptVisitor(this);
  }

  @Override
  public Void visit(NoOpNode noOpNode) {
    return null;
  }

  @Override
  public Void visit(PaginateOnlyNode paginateOnlyNode) {
    return paginateOnlyNode.getChildNode().acceptVisitor(this);
  }

  private Set<String> getRedundantPendingSelectionSources(
      Set<String> fetchedAttributes,
      Set<String> pendingAttributeSelectionSources,
      Map<String, Set<String>> sourceToAttributeSelectionMap) {
    if (pendingAttributeSelectionSources.isEmpty()) {
      return Collections.emptySet();
    }

    // if all the attributes from the selection source have already been fetched,
    // remove the source from pending selection sources, so that it does not fetch the same
    // set of attributes again
    return pendingAttributeSelectionSources.stream()
        .filter(
            source ->
                fetchedAttributes.containsAll(
                    sourceToAttributeSelectionMap.getOrDefault(source, Collections.emptySet())))
        .collect(Collectors.toSet());
  }
}
