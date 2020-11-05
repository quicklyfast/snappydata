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
package org.apache.spark.sql.kafka010

import org.apache.spark.SparkContext
import org.apache.spark.sql.SnappySession
import org.apache.spark.sql.test.{SharedSnappySessionContext, SnappySparkTestUtil, TestSnappySession}

class SnappyKafkaContinuousSourceSuite extends KafkaContinuousSourceSuite
    with SnappyKafkaContinuousTest with SnappySparkTestUtil

class SnappyKafkaContinuousSourceTopicDeletionSuite
    extends KafkaContinuousSourceTopicDeletionSuite
        with SnappyKafkaContinuousTest with SnappySparkTestUtil

class SnappyKafkaMicroBatchSourceSuiteBase extends KafkaMicroBatchSourceSuiteBase
    with SharedSnappySessionContext with SnappySparkTestUtil

class SnappyKafkaMicroBatchV1SourceSuite extends KafkaMicroBatchV1SourceSuite
    with SharedSnappySessionContext with SnappySparkTestUtil

class SnappyKafkaMicroBatchV2SourceSuite extends KafkaMicroBatchV2SourceSuite
    with SharedSnappySessionContext with SnappySparkTestUtil

class SnappyKafkaSourceStressSuite extends KafkaSourceStressSuite
    with SharedSnappySessionContext with SnappySparkTestUtil

class SnappyKafkaSourceStressForDontFailOnDataLossSuite
    extends KafkaSourceStressForDontFailOnDataLossSuite
        with SnappyKafkaMissingOffsetsTest with SnappySparkTestUtil

class SnappyKafkaDontFailOnDataLossSuite extends KafkaDontFailOnDataLossSuite
    with SnappyKafkaMissingOffsetsTest with SnappySparkTestUtil

trait SnappyKafkaContinuousTest extends SharedSnappySessionContext {
  override protected def createSparkSession: SnappySession = {
    // We need more than the default local[2] to be able to schedule all partitions simultaneously.
    val session = new TestSnappySession(
      new SparkContext("local[10]", "continuous-stream-test-sql-context",
        snappySparkConf.set("spark.sql.testkey", "true")))
    session.setCurrentSchema("default")
    session
  }
}

trait SnappyKafkaMissingOffsetsTest extends SharedSnappySessionContext {
  override def createSparkSession: SnappySession = {
    // Set maxRetries to 3 to handle NPE from `poll` when deleting a topic
    val session = new TestSnappySession(
      new SparkContext("local[4,3]", "test-sql-context", snappySparkConf))
    session.setCurrentSchema("default")
    session
  }
}
