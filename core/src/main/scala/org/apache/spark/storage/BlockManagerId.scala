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

package org.apache.spark.storage

import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.SparkContext
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 * This class represent an unique identifier for a BlockManager.
  *
 * 此类表示BlockManager的唯一标识符,BlockManagerId表示executor计算的中间结果实际数据在那个位置(BlockManager)
  *
 * The first 2 constructors of this class is made private to ensure that BlockManagerId objects
 * can be created only using the apply method in the companion object. This allows de-duplication
 * of ID objects. Also, constructor parameters are private to ensure that parameters cannot be
 * modified from outside this class.
  * 这个类的前2个构造函数是私有的,以确保只能使用companion对象中的apply方法来创建BlockManagerId对象。
  * 这允许ID对象的重复数据删除,此外，构造函数参数是私有的,以确保不能从此类外部修改参数。
 * 包括标识Slave的ExecutorId,块管理器的主机名称和端口
 */
@DeveloperApi
//executor计算的中间结果实际数据所在位置
class BlockManagerId private (
    private var executorId_ : String,//executorId
    private var host_ : String,//块管理器的主机名称
    private var port_ : Int)//端口
  extends Externalizable {
  //仅用于反序列化
  private def this() = this(null, null, 0)  // For deserialization only

  def executorId: String = executorId_

  if (null != host_){
    Utils.checkHost(host_, "Expected hostname")
    assert (port_ > 0)
  }

  def hostPort: String = {
    // DEBUG code
    Utils.checkHost(host)
    assert (port > 0)
    host + ":" + port
  }

  def host: String = host_

  def port: Int = port_

  def isDriver: Boolean = {
    executorId == SparkContext.DRIVER_IDENTIFIER ||
      executorId == SparkContext.LEGACY_DRIVER_IDENTIFIER
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    //writeUTF java专用的字符序列格式,格式：2个字节的记录长度n字节数 跟着n长度的utf8字节
    out.writeUTF(executorId_)
    out.writeUTF(host_)
    out.writeInt(port_)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {

    executorId_ = in.readUTF()
    host_ = in.readUTF()
    port_ = in.readInt()
  }

  @throws(classOf[IOException]) //注意抛出异常注释处理
  private def readResolve(): Object = BlockManagerId.getCachedBlockManagerId(this)

  override def toString: String = s"BlockManagerId($executorId, $host, $port)"

  override def hashCode: Int = (executorId.hashCode * 41 + host.hashCode) * 41 + port

  override def equals(that: Any): Boolean = that match {
    case id: BlockManagerId =>
      executorId == id.executorId && port == id.port && host == id.host
    case _ =>
      false
  }
}


private[spark] object BlockManagerId {

  /**
   * Returns a [[org.apache.spark.storage.BlockManagerId]] for the given configuration.
   *  包括标识Slave的ExecutorId,块管理器的主机名称和端口及节点的最大可用内存数
   * @param execId ID of the executor.
   * @param host Host name of the block manager.块管理器的主机名
   * @param port Port of the block manager. 块管理器的端口
   * @return A new [[org.apache.spark.storage.BlockManagerId]].
   */
  def apply(execId: String, host: String, port: Int): BlockManagerId =
    getCachedBlockManagerId(new BlockManagerId(execId, host, port))

  def apply(in: ObjectInput): BlockManagerId = {
    val obj = new BlockManagerId()
    obj.readExternal(in)//读取ObjectInput对象host和port_,executorId_
    getCachedBlockManagerId(obj)//获取BlockManagerId
  }

  val blockManagerIdCache = new ConcurrentHashMap[BlockManagerId, BlockManagerId]()

  def getCachedBlockManagerId(id: BlockManagerId): BlockManagerId = {
    blockManagerIdCache.putIfAbsent(id, id)//put和putIfAbsent的区别就是一个是直接放入并替换,另一个是有就不替换
    blockManagerIdCache.get(id)//返回BlockManagerId对象
  }
}
