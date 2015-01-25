package com.linkedin.pinot.core.plan;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.pinot.core.common.DataSource;
import com.linkedin.pinot.core.common.Operator;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.operator.BReusableFilteredDocIdSetOperator;
import com.linkedin.pinot.core.operator.MProjectionOperator;


/**
 * ProjectionPlanNode takes care of a map from column name to its corresponding
 * data source.
 *
 * @author xiafu
 *
 */
public class ProjectionPlanNode implements PlanNode {
  private static final Logger _logger = Logger.getLogger("QueryPlanLog");
  
  private final Map<String, ColumnarDataSourcePlanNode> _dataSourcePlanNodeMap =
      new HashMap<String, ColumnarDataSourcePlanNode>();
  private final DocIdSetPlanNode _docIdSetPlanNode;
  private MProjectionOperator _projectionOperator = null;

  public ProjectionPlanNode(IndexSegment indexSegment, String[] strings, DocIdSetPlanNode docIdSetPlanNode) {
    _docIdSetPlanNode = docIdSetPlanNode;
    for (String column : strings) {
      _dataSourcePlanNodeMap.put(column, new ColumnarDataSourcePlanNode(indexSegment, column, docIdSetPlanNode));
    }
  }

  public ProjectionPlanNode(IndexSegment indexSegment, DocIdSetPlanNode docIdSetPlanNode) {
    _docIdSetPlanNode = docIdSetPlanNode;
    for (String column : indexSegment.getColumnNames()) {
      _dataSourcePlanNodeMap.put(column, new ColumnarDataSourcePlanNode(indexSegment, column, docIdSetPlanNode));
    }

  }

  @Override
  public synchronized Operator run() {
    if (_projectionOperator == null) {
      Map<String, DataSource> dataSourceMap = new HashMap<String, DataSource>();
      BReusableFilteredDocIdSetOperator docIdSetOperator = (BReusableFilteredDocIdSetOperator) _docIdSetPlanNode.run();
      for (String column : _dataSourcePlanNodeMap.keySet()) {
        dataSourceMap.put(column, (DataSource) _dataSourcePlanNodeMap.get(column).run());
      }
      _projectionOperator = new MProjectionOperator(dataSourceMap, docIdSetOperator);
    }
    return _projectionOperator;
  }

  @Override
  public void showTree(String prefix) {
    _logger.debug(prefix + "Operator: MProjectionOperator");
    _logger.debug(prefix + "Argument 0: DocIdSet - ");
    _docIdSetPlanNode.showTree(prefix + "    ");
    int i = 0;
    for (String column : _dataSourcePlanNodeMap.keySet()) {
      _logger.debug(prefix + "Argument " + (i + 1) + ": DataSourceOperator");
      _dataSourcePlanNodeMap.get(column).showTree(prefix + "    ");
      i++;
    }
  }

  public ColumnarDataSourcePlanNode getDataSourcePlanNode(String column) {
    return _dataSourcePlanNodeMap.get(column);
  }
}
