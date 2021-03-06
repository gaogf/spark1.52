/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle

/**
 * Obtained inside a reduce task to read combined records from the mappers.
  *在reduce任务中读取mappers组合记录
  *
 * ShuffleReader实现了下游的Task如何读取上游的ShuffleMapTask的Shuffle输出的逻辑
 * 这个逻辑比较复杂,简单来说就是通过org.apache.spark.MapOutputTracker获得数据的位置信息,
 * 然后如果数据在本地那么调用org.apache.spark.storage.BlockManager的getBlockData读取本地数据
 */
private[spark] trait ShuffleReader[K, C] {
  /** Read the combined key-values for this reduce task
    * 读取此reduce任务的组合键值 */
  def read(): Iterator[Product2[K, C]]

  /**
   * Close this reader.
   * TODO: Add this back when we make the ShuffleReader a developer API that others can implement
   * (at which point this will likely be necessary).
   */
  // def stop(): Unit
}
