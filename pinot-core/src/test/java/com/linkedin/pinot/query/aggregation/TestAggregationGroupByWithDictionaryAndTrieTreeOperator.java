package com.linkedin.pinot.query.aggregation;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import junit.framework.Assert;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.GroupBy;
import com.linkedin.pinot.common.response.BrokerResponse;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.common.utils.NamedThreadFactory;
import com.linkedin.pinot.core.block.query.IntermediateResultsBlock;
import com.linkedin.pinot.core.common.Operator;
import com.linkedin.pinot.core.data.readers.RecordReaderFactory;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnMetadata;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.columnar.creator.ColumnarSegmentCreator;
import com.linkedin.pinot.core.indexsegment.creator.SegmentCreatorFactory;
import com.linkedin.pinot.core.indexsegment.dictionary.Dictionary;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfiguration;
import com.linkedin.pinot.core.indexsegment.generator.SegmentVersion;
import com.linkedin.pinot.core.operator.BDocIdSetOperator;
import com.linkedin.pinot.core.operator.UReplicatedDocIdSetOperator;
import com.linkedin.pinot.core.operator.query.AggregationFunctionGroupByOperator;
import com.linkedin.pinot.core.operator.query.MAggregationFunctionGroupByOperator;
import com.linkedin.pinot.core.operator.query.MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator;
import com.linkedin.pinot.core.operator.query.MAggregationGroupByOperator;
import com.linkedin.pinot.core.plan.Plan;
import com.linkedin.pinot.core.plan.PlanNode;
import com.linkedin.pinot.core.plan.maker.InstancePlanMakerImplV1;
import com.linkedin.pinot.core.plan.maker.PlanMaker;
import com.linkedin.pinot.core.query.aggregation.CombineService;
import com.linkedin.pinot.core.query.aggregation.groupby.AggregationGroupByOperatorService;
import com.linkedin.pinot.core.query.reduce.DefaultReduceService;
import com.linkedin.pinot.core.time.SegmentTimeUnit;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;


public class TestAggregationGroupByWithDictionaryAndTrieTreeOperator {

  private final String AVRO_DATA = "data/sample_data.avro";
  private static File INDEX_DIR = new File(TestAggregationGroupByWithDictionaryAndTrieTreeOperator.class.toString());

  public static IndexSegment _indexSegment;
  private static List<IndexSegment> _indexSegmentList;
  public static AggregationInfo _paramsInfo;
  public static GroupBy _groupBy;
  public static List<AggregationInfo> _aggregationInfos;

  public Map<String, Dictionary<?>> _dictionaryMap;
  public Map<String, ColumnMetadata> _medataMap;

  @BeforeClass
  public void setup() throws Exception {
    setupSegment();
    setupQuery();

    _indexSegment = ColumnarSegmentLoader.load(INDEX_DIR, ReadMode.heap);
    _dictionaryMap = ((ColumnarSegment) _indexSegment).getDictionaryMap();
    _medataMap = ((ColumnarSegment) _indexSegment).getColumnMetadataMap();
    _indexSegmentList = new ArrayList<IndexSegment>();

  }

  private void setupSegment() throws Exception {
    String filePath = getClass().getClassLoader().getResource(AVRO_DATA).getFile();

    if (INDEX_DIR.exists()) {
      FileUtils.deleteQuietly(INDEX_DIR);
    }

    SegmentGeneratorConfiguration config =
        SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), INDEX_DIR, "daysSinceEpoch",
            SegmentTimeUnit.days, "test", "testTable");

    ColumnarSegmentCreator creator =
        (ColumnarSegmentCreator) SegmentCreatorFactory.get(SegmentVersion.v1, RecordReaderFactory.get(config));
    creator.init(config);
    creator.buildSegment();

    System.out.println("built at : " + INDEX_DIR.getAbsolutePath());
  }

  private void setupQuery() {
    _aggregationInfos = new ArrayList<AggregationInfo>();
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    AggregationInfo paramsInfo1 = new AggregationInfo();
    paramsInfo1.setAggregationType("count");
    paramsInfo1.setAggregationParams(params);
    _aggregationInfos.add(paramsInfo1);
    AggregationInfo paramsInfo2 = new AggregationInfo();
    paramsInfo2.setAggregationType("sum");
    paramsInfo2.setAggregationParams(params);
    _aggregationInfos.add(paramsInfo2);
    AggregationInfo paramsInfo3 = new AggregationInfo();
    paramsInfo3.setAggregationType("min");
    paramsInfo3.setAggregationParams(params);
    _aggregationInfos.add(paramsInfo3);
    AggregationInfo paramsInfo4 = new AggregationInfo();
    paramsInfo4.setAggregationType("max");
    paramsInfo4.setAggregationParams(params);
    _aggregationInfos.add(paramsInfo4);
    List<String> groupbyColumns = new ArrayList<String>();
    groupbyColumns.add("dim_memberGender");
    _groupBy = new GroupBy();
    _groupBy.setColumns(groupbyColumns);
    _groupBy.setTopN(10);
  }

  private void setupSegmentList(int numberOfSegments) throws ConfigurationException, IOException {
    for (int i = 0; i < numberOfSegments; ++i) {
      _indexSegmentList.add(ColumnarSegmentLoader.load(INDEX_DIR, ReadMode.heap));
    }
  }

  @Test
  public void testAggregationGroupBys() {
    BDocIdSetOperator docIdSetOperator = new BDocIdSetOperator(null, _indexSegment, 5000);

    List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    List<Operator> dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(0), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(1), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(2), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(3), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, docIdSetOperator,
            aggregationFunctionGroupByOperatorList);

    // Test aggregate

    System.out.println("running query: ");
    IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();

    System.out.println(block.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block.getAggregationGroupByOperatorResult().get(3));

  }

  @Test
  public void testAggregationGroupBysWithCombine() {
    BDocIdSetOperator docIdSetOperator = new BDocIdSetOperator(null, _indexSegment, 5000);

    List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    List<Operator> dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(0), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(1), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(2), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(3), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, docIdSetOperator,
            aggregationFunctionGroupByOperatorList);

    // Test aggregate

    IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();

    System.out.println("Result 1: ");
    System.out.println(block.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block.getAggregationGroupByOperatorResult().get(3));

    BDocIdSetOperator docIdSetOperator1 = new BDocIdSetOperator(null, _indexSegment, 5000);

    List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList1 =
        new ArrayList<AggregationFunctionGroupByOperator>();
    List<Operator> dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(0), _groupBy,
            dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(1), _groupBy,
            dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(2), _groupBy,
            dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(3), _groupBy,
            dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    MAggregationGroupByOperator aggregationGroupByOperator1 =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, docIdSetOperator1,
            aggregationFunctionGroupByOperatorList1);

    // Test aggregate

    IntermediateResultsBlock block1 = (IntermediateResultsBlock) aggregationGroupByOperator1.nextBlock();

    System.out.println("Result 2: ");
    System.out.println(block1.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(3));

    CombineService.mergeTwoBlocks(getAggregationGroupByNoFilterBrokerRequest(), block, block1);

    System.out.println("Combined Result : ");
    System.out.println(block.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block.getAggregationGroupByOperatorResult().get(3));

  }

  @Test
  public void testAggregationGroupBysWithDataTableEncodeAndDecode() throws Exception {
    BDocIdSetOperator docIdSetOperator = new BDocIdSetOperator(null, _indexSegment, 5000);

    List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    List<Operator> dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(0), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(1), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(2), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    dataSourceOpsList = new ArrayList<Operator>();
    dataSourceOpsList.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    dataSourceOpsList.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator)));
    aggregationFunctionGroupByOperator =
        new MAggregationFunctionGroupByWithDictionaryAndTrieTreeOperator(_aggregationInfos.get(3), _groupBy,
            dataSourceOpsList);
    aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);

    MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, docIdSetOperator,
            aggregationFunctionGroupByOperatorList);

    // Test aggregate

    IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();

    System.out.println("Result 1: ");
    System.out.println(block.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block.getAggregationGroupByOperatorResult().get(3));

    BDocIdSetOperator docIdSetOperator1 = new BDocIdSetOperator(null, _indexSegment, 5000);

    List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList1 =
        new ArrayList<AggregationFunctionGroupByOperator>();
    List<Operator> dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    MAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByOperator(_aggregationInfos.get(0), _groupBy, dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByOperator(_aggregationInfos.get(1), _groupBy, dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByOperator(_aggregationInfos.get(2), _groupBy, dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    dataSourceOpsList1 = new ArrayList<Operator>();
    dataSourceOpsList1.add(_indexSegment.getDataSource("dim_memberGender", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    dataSourceOpsList1.add(_indexSegment.getDataSource("met_impressionCount", new UReplicatedDocIdSetOperator(
        docIdSetOperator1)));
    aggregationFunctionGroupByOperator1 =
        new MAggregationFunctionGroupByOperator(_aggregationInfos.get(3), _groupBy, dataSourceOpsList1);
    aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);

    MAggregationGroupByOperator aggregationGroupByOperator1 =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, docIdSetOperator1,
            aggregationFunctionGroupByOperatorList1);

    // Test aggregate

    IntermediateResultsBlock block1 = (IntermediateResultsBlock) aggregationGroupByOperator1.nextBlock();

    System.out.println("Result 2: ");
    System.out.println(block1.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block1.getAggregationGroupByOperatorResult().get(3));

    CombineService.mergeTwoBlocks(getAggregationGroupByNoFilterBrokerRequest(), block, block1);

    System.out.println("Combined Result : ");
    System.out.println(block.getAggregationGroupByOperatorResult().get(0));
    System.out.println(block.getAggregationGroupByOperatorResult().get(1));
    System.out.println(block.getAggregationGroupByOperatorResult().get(2));
    System.out.println(block.getAggregationGroupByOperatorResult().get(3));

    DataTable dataTable = block.getAggregationGroupByResultDataTable();

    List<Map<String, Serializable>> results =
        AggregationGroupByOperatorService.transformDataTableToGroupByResult(dataTable);
    System.out.println("Decode AggregationResult from DataTable: ");
    System.out.println(results.get(0));
    System.out.println(results.get(1));
    System.out.println(results.get(2));
    System.out.println(results.get(3));

  }

  @Test
  public void testInnerSegmentPlanMakerForAggregationGroupByOperatorNoFilter() throws Exception {
    BrokerRequest brokerRequest = getAggregationGroupByNoFilterBrokerRequest();
    PlanMaker instancePlanMaker = new InstancePlanMakerImplV1();
    PlanNode rootPlanNode = instancePlanMaker.makeInnerSegmentPlan(_indexSegment, brokerRequest);
    rootPlanNode.showTree("");
    // UAggregationGroupByOperator operator = (UAggregationGroupByOperator) rootPlanNode.run();
    MAggregationGroupByOperator operator = (MAggregationGroupByOperator) rootPlanNode.run();
    IntermediateResultsBlock resultBlock = (IntermediateResultsBlock) operator.nextBlock();
    System.out.println("RunningTime : " + resultBlock.getTimeUsedMs());
    System.out.println("NumDocsScanned : " + resultBlock.getNumDocsScanned());
    System.out.println("TotalDocs : " + resultBlock.getTotalDocs());
    List<Map<String, Serializable>> combinedGroupByResult = resultBlock.getAggregationGroupByOperatorResult();

    //    System.out.println("********************************");
    //    for (int i = 0; i < combinedGroupByResult.size(); ++i) {
    //      Map<String, Serializable> groupByResult = combinedGroupByResult.get(i);
    //      System.out.println(groupByResult);
    //    }
    //    System.out.println("********************************");
    AggregationGroupByOperatorService aggregationGroupByOperatorService =
        new AggregationGroupByOperatorService(_aggregationInfos, brokerRequest.getGroupBy());

    Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:1111"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:2222"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:3333"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:4444"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:5555"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:6666"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:7777"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:8888"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:9999"), resultBlock.getAggregationGroupByResultDataTable());
    List<Map<String, Serializable>> reducedResults =
        aggregationGroupByOperatorService.reduceGroupByOperators(instanceResponseMap);
    //    System.out.println("********************************");
    //    for (int i = 0; i < reducedResults.size(); ++i) {
    //      Map<String, Serializable> groupByResult = reducedResults.get(i);
    //      System.out.println(groupByResult);
    //    }
    //    System.out.println("********************************");
    List<JSONObject> jsonResult = aggregationGroupByOperatorService.renderGroupByOperators(reducedResults);
    System.out.println(jsonResult);
    //    System.out.println("********************************");
  }

  @Test
  public void testInterSegmentAggregationGroupByPlanMakerAndRun() throws ConfigurationException, IOException,
      JSONException {
    int numSegments = 20;
    setupSegmentList(numSegments);
    PlanMaker instancePlanMaker = new InstancePlanMakerImplV1();
    BrokerRequest brokerRequest = getAggregationGroupByNoFilterBrokerRequest();
    ExecutorService executorService = Executors.newCachedThreadPool(new NamedThreadFactory("test-plan-maker"));
    Plan globalPlan = instancePlanMaker.makeInterSegmentPlan(_indexSegmentList, brokerRequest, executorService);
    globalPlan.print();
    globalPlan.execute();
    DataTable instanceResponse = globalPlan.getInstanceResponse();
    System.out.println(instanceResponse);

    DefaultReduceService defaultReduceService = new DefaultReduceService();
    Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    BrokerResponse brokerResponse = defaultReduceService.reduceOnDataTable(brokerRequest, instanceResponseMap);
    System.out.println(new JSONArray(brokerResponse.getAggregationResults()));
    System.out.println("Time used : " + brokerResponse.getTimeUsedMs());
    assertBrokerResponse(numSegments, brokerResponse);
  }

  private void assertBrokerResponse(int numSegments, BrokerResponse brokerResponse) throws JSONException {
    Assert.assertEquals(10001 * numSegments, brokerResponse.getNumDocsScanned());
    Assert.assertEquals(4, brokerResponse.getAggregationResults().size());
    Assert.assertEquals("[\"dim_memberGender\",\"dim_memberFunction\"]", brokerResponse.getAggregationResults().get(0)
        .getJSONArray("groupByColumns").toString());
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").length());
    Assert
        .assertEquals(
            (double) (1450 * numSegments),
            brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").getJSONObject(0)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (620 * numSegments),
            brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").getJSONObject(1)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (517 * numSegments),
            brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").getJSONObject(2)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (422 * numSegments),
            brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").getJSONObject(3)
                .getDouble("value"));
    Assert.assertEquals("[\"m\",\"\"]", brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult")
        .getJSONObject(0).getString("group"));
    Assert.assertEquals("[\"f\",\"\"]", brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult")
        .getJSONObject(1).getString("group"));
    Assert.assertEquals("[\"m\",\"eng\"]", brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult")
        .getJSONObject(2).getString("group"));
    Assert.assertEquals("[\"m\",\"ent\"]", brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult")
        .getJSONObject(3).getString("group"));
    Assert.assertEquals("[\"m\",\"acad\"]", brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult")
        .getJSONObject(7).getString("group"));
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(0).getJSONArray("groupByResult").length());
    Assert.assertEquals("count_star", brokerResponse.getAggregationResults().get(0).getString("function").toString());
    //////////////////////////////
    Assert.assertEquals("[\"dim_memberGender\",\"dim_memberFunction\"]", brokerResponse.getAggregationResults().get(1)
        .getJSONArray("groupByColumns").toString());
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").length());
    Assert
        .assertEquals(
            (double) (3848 * numSegments),
            brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").getJSONObject(0)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (1651 * numSegments),
            brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").getJSONObject(1)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (1161 * numSegments),
            brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").getJSONObject(2)
                .getDouble("value"));
    Assert
        .assertEquals(
            (double) (1057 * numSegments),
            brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").getJSONObject(3)
                .getDouble("value"));
    Assert.assertEquals("[\"m\",\"\"]", brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult")
        .getJSONObject(0).getString("group"));
    Assert.assertEquals("[\"f\",\"\"]", brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult")
        .getJSONObject(1).getString("group"));
    Assert.assertEquals("[\"m\",\"eng\"]", brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult")
        .getJSONObject(2).getString("group"));
    Assert.assertEquals("[\"m\",\"ent\"]", brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult")
        .getJSONObject(3).getString("group"));
    Assert.assertEquals("[\"m\",\"cre\"]", brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult")
        .getJSONObject(7).getString("group"));

    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").length());
    Assert.assertEquals("sum_met_impressionCount", brokerResponse.getAggregationResults().get(1).getString("function")
        .toString());

    //////////////////////////////
    Assert.assertEquals("[\"dim_memberGender\",\"dim_memberFunction\"]", brokerResponse.getAggregationResults().get(2)
        .getJSONArray("groupByColumns").toString());
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult").length());
    Assert.assertEquals((double) (53), brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(0).getDouble("value"));
    Assert.assertEquals((double) (22), brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(1).getDouble("value"));
    Assert.assertEquals((double) (18), brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(2).getDouble("value"));
    Assert.assertEquals((double) (16), brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(3).getDouble("value"));
    Assert.assertEquals("[\"m\",\"\"]", brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(0).getString("group"));
    Assert.assertEquals("[\"m\",\"pr\"]", brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(1).getString("group"));
    Assert.assertEquals("[\"m\",\"edu\"]", brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(2).getString("group"));
    Assert.assertEquals("[\"m\",\"bd\"]", brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(3).getString("group"));
    Assert.assertEquals("[\"f\",\"css\"]", brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult")
        .getJSONObject(7).getString("group"));

    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(2).getJSONArray("groupByResult").length());
    Assert.assertEquals("max_met_impressionCount", brokerResponse.getAggregationResults().get(2).getString("function")
        .toString());

    //////////////////////////////
    Assert.assertEquals("[\"dim_memberGender\",\"dim_memberFunction\"]", brokerResponse.getAggregationResults().get(1)
        .getJSONArray("groupByColumns").toString());
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(1).getJSONArray("groupByResult").length());
    for (int i = 0; i < 15; ++i) {
      Assert.assertEquals((double) (1), brokerResponse.getAggregationResults().get(3).getJSONArray("groupByResult")
          .getJSONObject(i).getDouble("value"));
    }
    Assert.assertEquals(15, brokerResponse.getAggregationResults().get(3).getJSONArray("groupByResult").length());
    Assert.assertEquals("min_met_impressionCount", brokerResponse.getAggregationResults().get(3).getString("function")
        .toString());

  }

  private static BrokerRequest getAggregationGroupByNoFilterBrokerRequest() {
    BrokerRequest brokerRequest = new BrokerRequest();
    List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(getCountAggregationInfo());
    aggregationsInfo.add(getSumAggregationInfo());
    aggregationsInfo.add(getMaxAggregationInfo());
    aggregationsInfo.add(getMinAggregationInfo());
    brokerRequest.setAggregationsInfo(aggregationsInfo);
    brokerRequest.setGroupBy(getGroupBy());
    return brokerRequest;
  }

  private static AggregationInfo getCountAggregationInfo() {
    String type = "count";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "*");
    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getSumAggregationInfo() {
    String type = "sum";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getMaxAggregationInfo() {
    String type = "max";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getMinAggregationInfo() {
    String type = "min";
    Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static GroupBy getGroupBy() {
    GroupBy groupBy = new GroupBy();
    List<String> columns = new ArrayList<String>();
    columns.add("dim_memberGender");
    columns.add("dim_memberFunction");
    groupBy.setColumns(columns);
    groupBy.setTopN(15);
    return groupBy;
  }

}