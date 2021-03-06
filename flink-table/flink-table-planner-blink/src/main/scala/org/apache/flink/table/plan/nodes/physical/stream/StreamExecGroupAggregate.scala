/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.plan.nodes.physical.stream

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.{RelNode, RelWriter}
import org.apache.flink.streaming.api.operators.KeyedProcessOperator
import org.apache.flink.streaming.api.transformations.{OneInputTransformation, StreamTransformation}
import org.apache.flink.table.`type`.TypeConverters.createInternalTypeFromTypeInfo
import org.apache.flink.table.api.{StreamTableEnvironment, TableConfigOptions}
import org.apache.flink.table.calcite.FlinkTypeFactory
import org.apache.flink.table.codegen.agg.AggsHandlerCodeGenerator
import org.apache.flink.table.codegen.{CodeGeneratorContext, EqualiserCodeGenerator}
import org.apache.flink.table.dataformat.BaseRow
import org.apache.flink.table.plan.PartialFinalType
import org.apache.flink.table.plan.nodes.exec.{ExecNode, StreamExecNode}
import org.apache.flink.table.plan.rules.physical.stream.StreamExecRetractionRules
import org.apache.flink.table.plan.util._
import org.apache.flink.table.runtime.aggregate.{GroupAggFunction, MiniBatchGroupAggFunction}
import org.apache.flink.table.runtime.bundle.KeyedMapBundleOperator

import java.util

import scala.collection.JavaConversions._

/**
  * Stream physical RelNode for unbounded group aggregate.
  *
  * This node does support un-splittable aggregate function (e.g. STDDEV_POP).
  *
  * @see [[StreamExecGroupAggregateBase]] for more info.
  */
class StreamExecGroupAggregate(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    outputRowType: RelDataType,
    val grouping: Array[Int],
    val aggCalls: Seq[AggregateCall],
    var partialFinalType: PartialFinalType = PartialFinalType.NONE)
  extends StreamExecGroupAggregateBase(cluster, traitSet, inputRel)
  with StreamExecNode[BaseRow] {

  val aggInfoList: AggregateInfoList = {
    val needRetraction = StreamExecRetractionRules.isAccRetract(getInput)
    // TODO supports RelModifiedMonotonicity
    val needRetractionArray = AggregateUtil.getNeedRetractions(
      grouping.length, needRetraction, aggCalls)
    AggregateUtil.transformToStreamAggregateInfoList(
      aggCalls,
      getInput.getRowType,
      needRetractionArray,
      needInputCount = needRetraction,
      isStateBackendDataViews = true)
  }

  override def producesUpdates = true

  override def needsUpdatesAsRetraction(input: RelNode) = true

  override def consumesRetractions = true

  override def producesRetractions: Boolean = false

  override def requireWatermark: Boolean = false

  override def deriveRowType(): RelDataType = outputRowType

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    new StreamExecGroupAggregate(
      cluster,
      traitSet,
      inputs.get(0),
      outputRowType,
      grouping,
      aggCalls,
      partialFinalType)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    val inputRowType = getInput.getRowType
    super.explainTerms(pw)
      .itemIf("groupBy",
        RelExplainUtil.fieldToString(grouping, inputRowType), grouping.nonEmpty)
      .itemIf("partialFinalType", partialFinalType, partialFinalType != PartialFinalType.NONE)
      .item("select", RelExplainUtil.streamGroupAggregationToString(
        inputRowType,
        getRowType,
        aggInfoList,
        grouping))
  }

  override def getInputNodes: util.List[ExecNode[StreamTableEnvironment, _]] = {
    getInputs.map(_.asInstanceOf[ExecNode[StreamTableEnvironment, _]])
  }

  override protected def translateToPlanInternal(
      tableEnv: StreamTableEnvironment): StreamTransformation[BaseRow] = {

    val tableConfig = tableEnv.getConfig

    if (grouping.length > 0 && tableConfig.getMinIdleStateRetentionTime < 0) {
      LOG.warn("No state retention interval configured for a query which accumulates state. " +
        "Please provide a query configuration with valid retention interval to prevent excessive " +
        "state size. You may specify a retention time of 0 to not clean up the state.")
    }

    val inputTransformation = getInputNodes.get(0).translateToPlan(tableEnv)
      .asInstanceOf[StreamTransformation[BaseRow]]

    val outRowType = FlinkTypeFactory.toInternalRowType(outputRowType)
    val inputRowType = FlinkTypeFactory.toInternalRowType(getInput.getRowType)

    val generateRetraction = StreamExecRetractionRules.isAccRetract(this)
    val needRetraction = StreamExecRetractionRules.isAccRetract(getInput)

    val generator = new AggsHandlerCodeGenerator(
      CodeGeneratorContext(tableConfig),
      tableEnv.getRelBuilder,
      inputRowType.getFieldTypes,
      needRetraction,
      // TODO: heap state backend do not copy key currently, we have to copy input field
      // TODO: copy is not need when state backend is rocksdb, improve this in future
      // TODO: but other operators do not copy this input field.....
      copyInputField = true)

    val aggsHandler = generator.generateAggsHandler("GroupAggsHandler", aggInfoList)
    val accTypes = aggInfoList.getAccTypes.map(createInternalTypeFromTypeInfo)
    val aggValueTypes = aggInfoList.getActualValueTypes.map(createInternalTypeFromTypeInfo)
    val recordEqualiser = new EqualiserCodeGenerator(aggValueTypes)
      .generateRecordEqualiser("GroupAggValueEqualiser")
    val inputCountIndex = aggInfoList.getIndexOfCountStar

    val isMiniBatchEnabled = tableConfig.getConf.contains(
      TableConfigOptions.SQL_EXEC_MINIBATCH_ALLOW_LATENCY)

    val operator = if (isMiniBatchEnabled) {
      val aggFunction = new MiniBatchGroupAggFunction(
        aggsHandler,
        recordEqualiser,
        accTypes,
        inputRowType,
        inputCountIndex,
        generateRetraction)

      new KeyedMapBundleOperator(
        aggFunction,
        AggregateUtil.createMiniBatchTrigger(tableConfig))
    } else {
      val aggFunction = new GroupAggFunction(
        tableConfig.getMinIdleStateRetentionTime,
        tableConfig.getMaxIdleStateRetentionTime,
        aggsHandler,
        recordEqualiser,
        accTypes,
        inputCountIndex,
        generateRetraction)

      val operator = new KeyedProcessOperator[BaseRow, BaseRow, BaseRow](aggFunction)
      operator
    }

    val selector = KeySelectorUtil.getBaseRowSelector(grouping, inputRowType.toTypeInfo)

    // partitioned aggregation
    val ret = new OneInputTransformation(
      inputTransformation,
      "GroupAggregate",
      operator,
      outRowType.toTypeInfo,
      tableEnv.execEnv.getParallelism)

    if (grouping.isEmpty) {
      ret.setParallelism(1)
      ret.setMaxParallelism(1)
    }

    // set KeyType and Selector for state
    ret.setStateKeySelector(selector)
    ret.setStateKeyType(selector.getProducedType)
    ret
  }
}
