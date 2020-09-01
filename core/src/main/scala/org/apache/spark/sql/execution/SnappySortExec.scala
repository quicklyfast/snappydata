/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution

import scala.collection.AbstractIterator

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, SortOrder, UnsafeRow}
import org.apache.spark.sql.catalyst.plans.physical.{Distribution, Partitioning}
import org.apache.spark.sql.execution.metric.SQLMetric

/**
 * Custom Sort plan. Currently this enables lazy sorting i.e. sort only when
 * iterator is consumed the first time. Useful for SMJ when the left-side
 * is empty. Useful only for non code-generated plans, since latter are already
 * lazy (SortExec checks for "needToSort" so happens only on first processNext).
 */
case class SnappySortExec(sortPlan: SortExec, child: SparkPlan)
    extends UnaryExecNode with CodegenSupportSnappy {

  override def nodeName: String = "SnappySort"

  override def output: Seq[Attribute] = child.output

  override def outputOrdering: Seq[SortOrder] = sortPlan.outputOrdering

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def requiredChildDistribution: Seq[Distribution] = sortPlan.requiredChildDistribution

  override def metrics: Map[String, SQLMetric] = sortPlan.metrics

  protected override def doExecute(): RDD[InternalRow] = {
    val peakMemory = longMetric("peakMemory")
    val spillSize = longMetric("spillSize")
    val sortTime = longMetric("sortTime")

    import org.apache.spark.sql.snappy._

    child.execute().mapPartitionsPreserveInternal(itr =>

      new AbstractIterator[InternalRow] {

        private lazy val sortedIterator: Iterator[InternalRow] = {
          val sorter = sortPlan.createSorter()
          val metrics = TaskContext.get().taskMetrics()
          // Remember spill data size of this task before execute this operator so that we can
          // figure out how many bytes we spilled for this operator.
          val spillSizeBefore = metrics.memoryBytesSpilled
          val sortedIterator = sorter.sort(itr.asInstanceOf[Iterator[UnsafeRow]])
          sortTime += sorter.getSortTimeNanos / 1000000
          peakMemory += sorter.getPeakMemoryUsage
          spillSize += metrics.memoryBytesSpilled - spillSizeBefore
          metrics.incPeakExecutionMemory(sorter.getPeakMemoryUsage)
          sortedIterator
        }

        override def hasNext: Boolean = sortedIterator.hasNext

        override def next(): InternalRow = sortedIterator.next()
      })
  }

  override def usedInputs: AttributeSet = AttributeSet(Nil)

  override def inputRDDs(): Seq[RDD[InternalRow]] =
    child.asInstanceOf[CodegenSupport].inputRDDs()

  // The result rows come from the sort buffer, so this operator doesn't need to copy its result
  // even if its child does.
  override def needCopyResult: Boolean = false

  // Sort operator always consumes all the input rows before outputting any result, so we don't need
  // a stop check before sorting.
  override def needStopCheck: Boolean = false

  override protected def doProduce(ctx: CodegenContext): String = {
    val plan = if (child ne sortPlan.child) {
      sortPlan.copy(child = child)
    } else sortPlan
    plan.produce(ctx, this.parent)
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String =
    sortPlan.doConsume(ctx, input, row)
}
