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

package org.apache.spark.deploy.master

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rpc.RpcEnv

import scala.reflect.ClassTag

/**
 * Allows Master to persist any state that is necessary in order to recover from a failure.
 * 允许Master持久化的状态,以从失败中恢复
 * The following semantics are required:
 * 以下的语义是必需的：
 *   - addApplication and addWorker are called before completing registration of a new app/worker.
 *   - removeApplication and removeWorker are called at any time.
 * Given these two requirements, we will have all apps and workers persisted, but
 * we might not have yet deleted apps or workers that finished (so their liveness must be verified
 * during recovery).
 *
 * The implementation of this trait defines how name-object pairs are stored or retrieved.
 */
@DeveloperApi
abstract class PersistenceEngine {

  /**
   * Defines how the object is serialized and persisted. Implementation will
   * depend on the store used.
   * 对象序列化并持久化,取决于所使用的存储。
   * 
   */
  def persist(name: String, obj: Object)

  /**
   * Defines how the object referred by its name is removed from the store.
   * 根据名称所引用的对象从存储中删除
   */
  def unpersist(name: String)

  /**
   * Gives all objects, matching a prefix. This defines how objects are
   * read/deserialized back.
   * 给所有对象,匹配前缀,读取反序列化块
   * 
   */
  def read[T: ClassTag](prefix: String): Seq[T]

  final def addApplication(app: ApplicationInfo): Unit = {
    persist("app_" + app.id, app)
  }

  final def removeApplication(app: ApplicationInfo): Unit = {
    unpersist("app_" + app.id)
  }

  final def addWorker(worker: WorkerInfo): Unit = {
    persist("worker_" + worker.id, worker)
  }

  final def removeWorker(worker: WorkerInfo): Unit = {
    unpersist("worker_" + worker.id)
  }

  final def addDriver(driver: DriverInfo): Unit = {
    persist("driver_" + driver.id, driver)
  }

  final def removeDriver(driver: DriverInfo): Unit = {
    unpersist("driver_" + driver.id)
  }

  /**
   * Returns the persisted data sorted by their respective ids (which implies that they're
   * sorted by time of creation).  
   * 返回由它们各自的标识进行排序的持久化数据,(这意味着他们被创造的时间排序)
   */
  final def readPersistedData(
      rpcEnv: RpcEnv): (Seq[ApplicationInfo], Seq[DriverInfo], Seq[WorkerInfo]) = {
    rpcEnv.deserialize { () =>
      (read[ApplicationInfo]("app_"), read[DriverInfo]("driver_"), read[WorkerInfo]("worker_"))
    }
  }

  def close() {}
}

private[master] class BlackHolePersistenceEngine extends PersistenceEngine {

  override def persist(name: String, obj: Object): Unit = {}

  override def unpersist(name: String): Unit = {}

  override def read[T: ClassTag](name: String): Seq[T] = Nil

}
