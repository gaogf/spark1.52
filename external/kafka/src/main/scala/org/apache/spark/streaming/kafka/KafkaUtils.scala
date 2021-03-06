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

package org.apache.spark.streaming.kafka

import java.lang.{Integer => JInt, Long => JLong}
import java.util.{List => JList, Map => JMap, Set => JSet}

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import kafka.common.TopicAndPartition
import kafka.message.MessageAndMetadata
import kafka.serializer.{Decoder, DefaultDecoder, StringDecoder}

import org.apache.spark.api.java.function.{Function => JFunction}
import org.apache.spark.api.java.{JavaPairRDD, JavaRDD, JavaSparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.api.java.{JavaInputDStream, JavaPairInputDStream, JavaPairReceiverInputDStream, JavaStreamingContext}
import org.apache.spark.streaming.dstream.{InputDStream, ReceiverInputDStream}
import org.apache.spark.streaming.util.WriteAheadLogUtils
import org.apache.spark.{SparkContext, SparkException}

object KafkaUtils {
  /**
   * Create an input stream that pulls messages from Kafka Brokers.
    * 创建一个从Kafka Brokers提取消息的输入流
   * @param ssc       StreamingContext object StreamingContext对象
   * @param zkQuorum  Zookeeper quorum (hostname:port,hostname:port,..)Zookeeper quorum（主机名：port，hostname：port，..）
   * @param groupId   The group id for this consumer 该消费者的组ID
   * @param topics    Map of (topic_name -> numPartitions) to consume. Each partition is consumed
   *                  in its own thread (topic_name - > numPartitions)的映射消费,每个分区都在自己的线程中使用
   * @param storageLevel  Storage level to use for storing the received objects
   *                      (default: StorageLevel.MEMORY_AND_DISK_SER_2)
    *                     用于存储接收到的对象的存储级别(默认值：StorageLevel.MEMORY_AND_DISK_SER_2)
   */
  def createStream(
      ssc: StreamingContext,
      zkQuorum: String,
      groupId: String,
      topics: Map[String, Int],
      storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK_SER_2
    ): ReceiverInputDStream[(String, String)] = {
    val kafkaParams = Map[String, String](
      "zookeeper.connect" -> zkQuorum, "group.id" -> groupId,
      "zookeeper.connection.timeout.ms" -> "10000")
    createStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topics, storageLevel)
  }

  /**
   * Create an input stream that pulls messages from Kafka Brokers.
    * 创建一个从卡夫卡经Brokers提取消息的输入流
   * @param ssc         StreamingContext object StreamingContext对象
   * @param kafkaParams Map of kafka configuration parameters,kafka配置参数
   *                    see http://kafka.apache.org/08/configuration.html
   * @param topics      Map of (topic_name -> numPartitions) to consume. Each partition is consumed
   *                    in its own thread.
    *                   (topic_name - > numPartitions)的映射消费,每个分区都在自己的线程中使用
   * @param storageLevel Storage level to use for storing the received objects
    *                     用于存储接收到的对象的存储级别
   */
  def createStream[K: ClassTag, V: ClassTag, U <: Decoder[_]: ClassTag, T <: Decoder[_]: ClassTag](
      ssc: StreamingContext,
      kafkaParams: Map[String, String],
      topics: Map[String, Int],
      storageLevel: StorageLevel
    ): ReceiverInputDStream[(K, V)] = {
    val walEnabled = WriteAheadLogUtils.enableReceiverLog(ssc.conf)
    new KafkaInputDStream[K, V, U, T](ssc, kafkaParams, topics, walEnabled, storageLevel)
  }

  /**
   * Create an input stream that pulls messages from Kafka Brokers.
   * Storage level of the data will be the default StorageLevel.MEMORY_AND_DISK_SER_2.
    * 创建从Kafka Brokers提取消息的输入流,数据的存储级别将是默认的StorageLevel.MEMORY_AND_DISK_SER_2
   * @param jssc      JavaStreamingContext object JavaStreamingContext对象
   * @param zkQuorum  Zookeeper quorum (hostname:port,hostname:port,..)Zookeeper quorum（主机名：port，hostname：port，..）
   * @param groupId   The group id for this consumer 该消费者的组ID
   * @param topics    Map of (topic_name -> numPartitions) to consume. Each partition is consumed
   *                  in its own thread,(topic_name - > numPartitions)的映射消费,每个分区都被消耗在自己的线程
   */
  def createStream(
      jssc: JavaStreamingContext,
      zkQuorum: String,
      groupId: String,
      topics: JMap[String, JInt]
    ): JavaPairReceiverInputDStream[String, String] = {
    createStream(jssc.ssc, zkQuorum, groupId, Map(topics.mapValues(_.intValue()).toSeq: _*))
  }

  /**
   * Create an input stream that pulls messages from Kafka Brokers.
    * 创建一个从Kafka Brokers提取消息的输入流
   * @param jssc      JavaStreamingContext object JavaStreamingContext对象
   * @param zkQuorum  Zookeeper quorum (hostname:port,hostname:port,..).（主机名：port，hostname：port，..）
   * @param groupId   The group id for this consumer.该消费者的组ID
   * @param topics    Map of (topic_name -> numPartitions) to consume. Each partition is consumed
   *                  in its own thread.的映射消费,每个分区都被消耗在自己的线程
   * @param storageLevel RDD storage level.RDD存储级别
   */
  def createStream(
      jssc: JavaStreamingContext,
      zkQuorum: String,
      groupId: String,
      topics: JMap[String, JInt],
      storageLevel: StorageLevel
    ): JavaPairReceiverInputDStream[String, String] = {
    createStream(jssc.ssc, zkQuorum, groupId, Map(topics.mapValues(_.intValue()).toSeq: _*),
      storageLevel)
  }

  /**
   * Create an input stream that pulls messages from Kafka Brokers.
    * 创建一个从Kafka Brokers提取消息的输入流
   * @param jssc      JavaStreamingContext object
   * @param keyTypeClass Key type of DStream
   * @param valueTypeClass value type of Dstream
   * @param keyDecoderClass Type of kafka key decoder
   * @param valueDecoderClass Type of kafka value decoder
   * @param kafkaParams Map of kafka configuration parameters,
   *                    see http://kafka.apache.org/08/configuration.html
   * @param topics  Map of (topic_name -> numPartitions) to consume. Each partition is consumed
   *                in its own thread
   * @param storageLevel RDD storage level.
   */
  def createStream[K, V, U <: Decoder[_], T <: Decoder[_]](
      jssc: JavaStreamingContext,
      keyTypeClass: Class[K],
      valueTypeClass: Class[V],
      keyDecoderClass: Class[U],
      valueDecoderClass: Class[T],
      kafkaParams: JMap[String, String],
      topics: JMap[String, JInt],
      storageLevel: StorageLevel
    ): JavaPairReceiverInputDStream[K, V] = {
    implicit val keyCmt: ClassTag[K] = ClassTag(keyTypeClass)
    implicit val valueCmt: ClassTag[V] = ClassTag(valueTypeClass)

    implicit val keyCmd: ClassTag[U] = ClassTag(keyDecoderClass)
    implicit val valueCmd: ClassTag[T] = ClassTag(valueDecoderClass)

    createStream[K, V, U, T](
      jssc.ssc, kafkaParams.toMap, Map(topics.mapValues(_.intValue()).toSeq: _*), storageLevel)
  }

  /** get leaders for the given offset ranges, or throw an exception
    * 获得给定偏移范围的领导者,或抛出异常 */
  private def leadersForRanges(
      kc: KafkaCluster,
      offsetRanges: Array[OffsetRange]): Map[TopicAndPartition, (String, Int)] = {
    val topics = offsetRanges.map(o => TopicAndPartition(o.topic, o.partition)).toSet
    val leaders = kc.findLeaders(topics)
    KafkaCluster.checkErrors(leaders)
  }

  /** Make sure offsets are available in kafka, or throw an exception
    * 确保kafka中的偏移量可用,或者抛出异常*/
  private def checkOffsets(
      kc: KafkaCluster,
      offsetRanges: Array[OffsetRange]): Unit = {
    val topics = offsetRanges.map(_.topicAndPartition).toSet
    val result = for {
      low <- kc.getEarliestLeaderOffsets(topics).right
      high <- kc.getLatestLeaderOffsets(topics).right
    } yield {
      offsetRanges.filterNot { o =>
        low(o.topicAndPartition).offset <= o.fromOffset &&
        o.untilOffset <= high(o.topicAndPartition).offset
      }
    }
    val badRanges = KafkaCluster.checkErrors(result)
    if (!badRanges.isEmpty) {
      throw new SparkException("Offsets not available on leader: " + badRanges.mkString(","))
    }
  }

  /**
   * Create a RDD from Kafka using offset ranges for each topic and partition.
    * 使用每个主题和分区的偏移范围从卡夫卡创建一个RDD
   *
   * @param sc SparkContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *    configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *    to be set with Kafka broker(s) (NOT zookeeper servers) specified in
   *    host1:port1,host2:port2 form.
    *    Kafka配置参数,需要使用host1中指定的Kafka代理(非zookeeper服务器)设置“metadata.broker.list”
    *    或“bootstrap.servers”：port1，host2：port2表单
   * @param offsetRanges Each OffsetRange in the batch corresponds to a
   *   range of offsets for a given Kafka topic/partition
    *   批次中的每个OffsetRange对应于给定Kafka主题/分区的一系列偏移量
   */
  def createRDD[
    K: ClassTag,
    V: ClassTag,
    KD <: Decoder[K]: ClassTag,
    VD <: Decoder[V]: ClassTag](
      sc: SparkContext,
      kafkaParams: Map[String, String],
      offsetRanges: Array[OffsetRange]
    ): RDD[(K, V)] = sc.withScope {
    val messageHandler = (mmd: MessageAndMetadata[K, V]) => (mmd.key, mmd.message)
    val kc = new KafkaCluster(kafkaParams)
    val leaders = leadersForRanges(kc, offsetRanges)
    checkOffsets(kc, offsetRanges)
    new KafkaRDD[K, V, KD, VD, (K, V)](sc, kafkaParams, offsetRanges, leaders, messageHandler)
  }

  /**
   * Create a RDD from Kafka using offset ranges for each topic and partition. This allows you
   * specify the Kafka leader to connect to (to optimize fetching) and access the message as well
   * as the metadata.
    * 使用每个主题和分区的偏移范围从卡夫卡创建一个RDD,这允许您指定连接到(以优化提取)和访问消息以及元数据的Kafka领导者
   *
   * @param sc SparkContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *    configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *    to be set with Kafka broker(s) (NOT zookeeper servers) specified in
   *    host1:port1,host2:port2 form.
   * @param offsetRanges Each OffsetRange in the batch corresponds to a
   *   range of offsets for a given Kafka topic/partition
    *   批次中的每个OffsetRange对应于给定Kafka主题/分区的一系列偏移量
   * @param leaders Kafka brokers for each TopicAndPartition in offsetRanges.  May be an empty map,
   *   in which case leaders will be looked up on the driver.
   * @param messageHandler Function for translating each message and metadata into the desired type
    *                       将每个消息和元数据转换为所需类型的功能
   */
  def createRDD[
    K: ClassTag,
    V: ClassTag,
    KD <: Decoder[K]: ClassTag,
    VD <: Decoder[V]: ClassTag,
    R: ClassTag](
      sc: SparkContext,
      kafkaParams: Map[String, String],
      offsetRanges: Array[OffsetRange],
      leaders: Map[TopicAndPartition, Broker],
      messageHandler: MessageAndMetadata[K, V] => R
    ): RDD[R] = sc.withScope {
    val kc = new KafkaCluster(kafkaParams)
    val leaderMap = if (leaders.isEmpty) {
      leadersForRanges(kc, offsetRanges)
    } else {
      // This could be avoided by refactoring KafkaRDD.leaders and KafkaCluster to use Broker
      //这可以通过重构KafkaRDD.leaders和KafkaCluster来使用Broker来避免
      leaders.map {
        case (tp: TopicAndPartition, Broker(host, port)) => (tp, (host, port))
      }.toMap
    }
    val cleanedHandler = sc.clean(messageHandler)
    checkOffsets(kc, offsetRanges)
    new KafkaRDD[K, V, KD, VD, R](sc, kafkaParams, offsetRanges, leaderMap, cleanedHandler)
  }

  /**
   * Create a RDD from Kafka using offset ranges for each topic and partition.
    * 使用每个主题和分区的偏移范围从卡夫卡创建一个RDD
   *
   * @param jsc JavaSparkContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *    configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *    to be set with Kafka broker(s) (NOT zookeeper servers) specified in
   *    host1:port1,host2:port2 form.
   * @param offsetRanges Each OffsetRange in the batch corresponds to a
   *   range of offsets for a given Kafka topic/partition
   */
  def createRDD[K, V, KD <: Decoder[K], VD <: Decoder[V]](
      jsc: JavaSparkContext,
      keyClass: Class[K],
      valueClass: Class[V],
      keyDecoderClass: Class[KD],
      valueDecoderClass: Class[VD],
      kafkaParams: JMap[String, String],
      offsetRanges: Array[OffsetRange]
    ): JavaPairRDD[K, V] = jsc.sc.withScope {
    implicit val keyCmt: ClassTag[K] = ClassTag(keyClass)
    implicit val valueCmt: ClassTag[V] = ClassTag(valueClass)
    implicit val keyDecoderCmt: ClassTag[KD] = ClassTag(keyDecoderClass)
    implicit val valueDecoderCmt: ClassTag[VD] = ClassTag(valueDecoderClass)
    new JavaPairRDD(createRDD[K, V, KD, VD](
      jsc.sc, Map(kafkaParams.toSeq: _*), offsetRanges))
  }

  /**
   * Create a RDD from Kafka using offset ranges for each topic and partition. This allows you
   * specify the Kafka leader to connect to (to optimize fetching) and access the message as well
   * as the metadata.
   *
   * @param jsc JavaSparkContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *    configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *    to be set with Kafka broker(s) (NOT zookeeper servers) specified in
   *    host1:port1,host2:port2 form.
   * @param offsetRanges Each OffsetRange in the batch corresponds to a
   *   range of offsets for a given Kafka topic/partition
   * @param leaders Kafka brokers for each TopicAndPartition in offsetRanges.  May be an empty map,
   *   in which case leaders will be looked up on the driver.
   * @param messageHandler Function for translating each message and metadata into the desired type
   */
  def createRDD[K, V, KD <: Decoder[K], VD <: Decoder[V], R](
      jsc: JavaSparkContext,
      keyClass: Class[K],
      valueClass: Class[V],
      keyDecoderClass: Class[KD],
      valueDecoderClass: Class[VD],
      recordClass: Class[R],
      kafkaParams: JMap[String, String],
      offsetRanges: Array[OffsetRange],
      leaders: JMap[TopicAndPartition, Broker],
      messageHandler: JFunction[MessageAndMetadata[K, V], R]
    ): JavaRDD[R] = jsc.sc.withScope {
    implicit val keyCmt: ClassTag[K] = ClassTag(keyClass)
    implicit val valueCmt: ClassTag[V] = ClassTag(valueClass)
    implicit val keyDecoderCmt: ClassTag[KD] = ClassTag(keyDecoderClass)
    implicit val valueDecoderCmt: ClassTag[VD] = ClassTag(valueDecoderClass)
    implicit val recordCmt: ClassTag[R] = ClassTag(recordClass)
    val leaderMap = Map(leaders.toSeq: _*)
    createRDD[K, V, KD, VD, R](
      jsc.sc, Map(kafkaParams.toSeq: _*), offsetRanges, leaderMap, messageHandler.call _)
  }

  /**
   * Create an input stream that directly pulls messages from Kafka Brokers
   * without using any receiver. This stream can guarantee that each message
   * from Kafka is included in transformations exactly once (see points below).
   *
   * Points to note:
   *  - No receivers: This stream does not use any receiver. It directly queries Kafka
   *  - Offsets: This does not use Zookeeper to store offsets. The consumed offsets are tracked
   *    by the stream itself. For interoperability with Kafka monitoring tools that depend on
   *    Zookeeper, you have to update Kafka/Zookeeper yourself from the streaming application.
   *    You can access the offsets used in each batch from the generated RDDs (see
   *    [[org.apache.spark.streaming.kafka.HasOffsetRanges]]).
   *  - Failure Recovery: To recover from driver failures, you have to enable checkpointing
   *    in the [[StreamingContext]]. The information on consumed offset can be
   *    recovered from the checkpoint. See the programming guide for details (constraints, etc.).
   *  - End-to-end semantics: This stream ensures that every records is effectively received and
   *    transformed exactly once, but gives no guarantees on whether the transformed data are
   *    outputted exactly once. For end-to-end exactly-once semantics, you have to either ensure
   *    that the output operation is idempotent, or use transactions to output records atomically.
   *    See the programming guide for more details.
   *
   * @param ssc StreamingContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *    configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *    to be set with Kafka broker(s) (NOT zookeeper servers) specified in
   *    host1:port1,host2:port2 form.
   * @param fromOffsets Per-topic/partition Kafka offsets defining the (inclusive)
   *    starting point of the stream
   * @param messageHandler Function for translating each message and metadata into the desired type
   */
  def createDirectStream[
    K: ClassTag,
    V: ClassTag,
    KD <: Decoder[K]: ClassTag,
    VD <: Decoder[V]: ClassTag,
    R: ClassTag] (
      ssc: StreamingContext,
      kafkaParams: Map[String, String],
      fromOffsets: Map[TopicAndPartition, Long],
      messageHandler: MessageAndMetadata[K, V] => R
  ): InputDStream[R] = {
    val cleanedHandler = ssc.sc.clean(messageHandler)
    new DirectKafkaInputDStream[K, V, KD, VD, R](
      ssc, kafkaParams, fromOffsets, cleanedHandler)
  }

  /**
   * Create an input stream that directly pulls messages from Kafka Brokers
   * without using any receiver. This stream can guarantee that each message
   * from Kafka is included in transformations exactly once (see points below).
   *
   * Points to note:
   *  - No receivers: This stream does not use any receiver. It directly queries Kafka
   *  - Offsets: This does not use Zookeeper to store offsets. The consumed offsets are tracked
   *    by the stream itself. For interoperability with Kafka monitoring tools that depend on
   *    Zookeeper, you have to update Kafka/Zookeeper yourself from the streaming application.
   *    You can access the offsets used in each batch from the generated RDDs (see
   *    [[org.apache.spark.streaming.kafka.HasOffsetRanges]]).
   *  - Failure Recovery: To recover from driver failures, you have to enable checkpointing
   *    in the [[StreamingContext]]. The information on consumed offset can be
   *    recovered from the checkpoint. See the programming guide for details (constraints, etc.).
   *  - End-to-end semantics: This stream ensures that every records is effectively received and
   *    transformed exactly once, but gives no guarantees on whether the transformed data are
   *    outputted exactly once. For end-to-end exactly-once semantics, you have to either ensure
   *    that the output operation is idempotent, or use transactions to output records atomically.
   *    See the programming guide for more details.
   *
   * @param ssc StreamingContext object
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *   configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *   to be set with Kafka broker(s) (NOT zookeeper servers), specified in
   *   host1:port1,host2:port2 form.
   *   If not starting from a checkpoint, "auto.offset.reset" may be set to "largest" or "smallest"
   *   to determine where the stream starts (defaults to "largest")
   * @param topics Names of the topics to consume
   */
  def createDirectStream[
    K: ClassTag,
    V: ClassTag,
    KD <: Decoder[K]: ClassTag,
    VD <: Decoder[V]: ClassTag] (
      ssc: StreamingContext,
      kafkaParams: Map[String, String],
      topics: Set[String]
  ): InputDStream[(K, V)] = {
    val messageHandler = (mmd: MessageAndMetadata[K, V]) => (mmd.key, mmd.message)
    val kc = new KafkaCluster(kafkaParams)
    val reset = kafkaParams.get("auto.offset.reset").map(_.toLowerCase)

    val result = for {
      topicPartitions <- kc.getPartitions(topics).right
      leaderOffsets <- (if (reset == Some("smallest")) {
        kc.getEarliestLeaderOffsets(topicPartitions)
      } else {
        kc.getLatestLeaderOffsets(topicPartitions)
      }).right
    } yield {
      val fromOffsets = leaderOffsets.map { case (tp, lo) =>
          (tp, lo.offset)
      }
      new DirectKafkaInputDStream[K, V, KD, VD, (K, V)](
        ssc, kafkaParams, fromOffsets, messageHandler)
    }
    KafkaCluster.checkErrors(result)
  }

  /**
   * Create an input stream that directly pulls messages from Kafka Brokers
   * without using any receiver. This stream can guarantee that each message
   * from Kafka is included in transformations exactly once (see points below).
   *
   * Points to note:
   *  - No receivers: This stream does not use any receiver. It directly queries Kafka
   *  - Offsets: This does not use Zookeeper to store offsets. The consumed offsets are tracked
   *    by the stream itself. For interoperability with Kafka monitoring tools that depend on
   *    Zookeeper, you have to update Kafka/Zookeeper yourself from the streaming application.
   *    You can access the offsets used in each batch from the generated RDDs (see
   *    [[org.apache.spark.streaming.kafka.HasOffsetRanges]]).
   *  - Failure Recovery: To recover from driver failures, you have to enable checkpointing
   *    in the [[StreamingContext]]. The information on consumed offset can be
   *    recovered from the checkpoint. See the programming guide for details (constraints, etc.).
   *  - End-to-end semantics: This stream ensures that every records is effectively received and
   *    transformed exactly once, but gives no guarantees on whether the transformed data are
   *    outputted exactly once. For end-to-end exactly-once semantics, you have to either ensure
   *    that the output operation is idempotent, or use transactions to output records atomically.
   *    See the programming guide for more details.
   *
   * @param jssc JavaStreamingContext object
   * @param keyClass Class of the keys in the Kafka records
   * @param valueClass Class of the values in the Kafka records
   * @param keyDecoderClass Class of the key decoder
   * @param valueDecoderClass Class of the value decoder
   * @param recordClass Class of the records in DStream
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *   configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *   to be set with Kafka broker(s) (NOT zookeeper servers), specified in
   *   host1:port1,host2:port2 form.
   * @param fromOffsets Per-topic/partition Kafka offsets defining the (inclusive)
   *    starting point of the stream
   * @param messageHandler Function for translating each message and metadata into the desired type
   */
  def createDirectStream[K, V, KD <: Decoder[K], VD <: Decoder[V], R](
      jssc: JavaStreamingContext,
      keyClass: Class[K],
      valueClass: Class[V],
      keyDecoderClass: Class[KD],
      valueDecoderClass: Class[VD],
      recordClass: Class[R],
      kafkaParams: JMap[String, String],
      fromOffsets: JMap[TopicAndPartition, JLong],
      messageHandler: JFunction[MessageAndMetadata[K, V], R]
    ): JavaInputDStream[R] = {
    implicit val keyCmt: ClassTag[K] = ClassTag(keyClass)
    implicit val valueCmt: ClassTag[V] = ClassTag(valueClass)
    implicit val keyDecoderCmt: ClassTag[KD] = ClassTag(keyDecoderClass)
    implicit val valueDecoderCmt: ClassTag[VD] = ClassTag(valueDecoderClass)
    implicit val recordCmt: ClassTag[R] = ClassTag(recordClass)
    val cleanedHandler = jssc.sparkContext.clean(messageHandler.call _)
    createDirectStream[K, V, KD, VD, R](
      jssc.ssc,
      Map(kafkaParams.toSeq: _*),
      Map(fromOffsets.mapValues { _.longValue() }.toSeq: _*),
      cleanedHandler
    )
  }

  /**
   * Create an input stream that directly pulls messages from Kafka Brokers
   * without using any receiver. This stream can guarantee that each message
   * from Kafka is included in transformations exactly once (see points below).
   *
   * Points to note:
   *  - No receivers: This stream does not use any receiver. It directly queries Kafka
   *  - Offsets: This does not use Zookeeper to store offsets. The consumed offsets are tracked
   *    by the stream itself. For interoperability with Kafka monitoring tools that depend on
   *    Zookeeper, you have to update Kafka/Zookeeper yourself from the streaming application.
   *    You can access the offsets used in each batch from the generated RDDs (see
   *    [[org.apache.spark.streaming.kafka.HasOffsetRanges]]).
   *  - Failure Recovery: To recover from driver failures, you have to enable checkpointing
   *    in the [[StreamingContext]]. The information on consumed offset can be
   *    recovered from the checkpoint. See the programming guide for details (constraints, etc.).
   *  - End-to-end semantics: This stream ensures that every records is effectively received and
   *    transformed exactly once, but gives no guarantees on whether the transformed data are
   *    outputted exactly once. For end-to-end exactly-once semantics, you have to either ensure
   *    that the output operation is idempotent, or use transactions to output records atomically.
   *    See the programming guide for more details.
   *
   * @param jssc JavaStreamingContext object
   * @param keyClass Class of the keys in the Kafka records
   * @param valueClass Class of the values in the Kafka records
   * @param keyDecoderClass Class of the key decoder
   * @param valueDecoderClass Class type of the value decoder
   * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
   *   configuration parameters</a>. Requires "metadata.broker.list" or "bootstrap.servers"
   *   to be set with Kafka broker(s) (NOT zookeeper servers), specified in
   *   host1:port1,host2:port2 form.
   *   If not starting from a checkpoint, "auto.offset.reset" may be set to "largest" or "smallest"
   *   to determine where the stream starts (defaults to "largest")
   * @param topics Names of the topics to consume
   */
  def createDirectStream[K, V, KD <: Decoder[K], VD <: Decoder[V]](
      jssc: JavaStreamingContext,
      keyClass: Class[K],
      valueClass: Class[V],
      keyDecoderClass: Class[KD],
      valueDecoderClass: Class[VD],
      kafkaParams: JMap[String, String],
      topics: JSet[String]
    ): JavaPairInputDStream[K, V] = {
    implicit val keyCmt: ClassTag[K] = ClassTag(keyClass)
    implicit val valueCmt: ClassTag[V] = ClassTag(valueClass)
    implicit val keyDecoderCmt: ClassTag[KD] = ClassTag(keyDecoderClass)
    implicit val valueDecoderCmt: ClassTag[VD] = ClassTag(valueDecoderClass)
    createDirectStream[K, V, KD, VD](
      jssc.ssc,
      Map(kafkaParams.toSeq: _*),
      Set(topics.toSeq: _*)
    )
  }
}

/**
 * This is a helper class that wraps the KafkaUtils.createStream() into more
 * Python-friendly class and function so that it can be easily
 * instantiated and called from Python's KafkaUtils (see SPARK-6027).
 *
 * The zero-arg constructor helps instantiate this class from the Class object
 * classOf[KafkaUtilsPythonHelper].newInstance(), and the createStream()
 * takes care of known parameters instead of passing them from Python
 */
private[kafka] class KafkaUtilsPythonHelper {
  def createStream(
      jssc: JavaStreamingContext,
      kafkaParams: JMap[String, String],
      topics: JMap[String, JInt],
      storageLevel: StorageLevel): JavaPairReceiverInputDStream[Array[Byte], Array[Byte]] = {
    KafkaUtils.createStream[Array[Byte], Array[Byte], DefaultDecoder, DefaultDecoder](
      jssc,
      classOf[Array[Byte]],
      classOf[Array[Byte]],
      classOf[DefaultDecoder],
      classOf[DefaultDecoder],
      kafkaParams,
      topics,
      storageLevel)
  }

  def createRDD(
      jsc: JavaSparkContext,
      kafkaParams: JMap[String, String],
      offsetRanges: JList[OffsetRange],
      leaders: JMap[TopicAndPartition, Broker]): JavaPairRDD[Array[Byte], Array[Byte]] = {
    val messageHandler = new JFunction[MessageAndMetadata[Array[Byte], Array[Byte]],
      (Array[Byte], Array[Byte])] {
      def call(t1: MessageAndMetadata[Array[Byte], Array[Byte]]): (Array[Byte], Array[Byte]) =
        (t1.key(), t1.message())
    }

    val jrdd = KafkaUtils.createRDD[
      Array[Byte],
      Array[Byte],
      DefaultDecoder,
      DefaultDecoder,
      (Array[Byte], Array[Byte])](
        jsc,
        classOf[Array[Byte]],
        classOf[Array[Byte]],
        classOf[DefaultDecoder],
        classOf[DefaultDecoder],
        classOf[(Array[Byte], Array[Byte])],
        kafkaParams,
        offsetRanges.toArray(new Array[OffsetRange](offsetRanges.size())),
        leaders,
        messageHandler
      )
    new JavaPairRDD(jrdd.rdd)
  }

  def createDirectStream(
      jssc: JavaStreamingContext,
      kafkaParams: JMap[String, String],
      topics: JSet[String],
      fromOffsets: JMap[TopicAndPartition, JLong]
    ): JavaPairInputDStream[Array[Byte], Array[Byte]] = {

    if (!fromOffsets.isEmpty) {
      import scala.collection.JavaConversions._
      val topicsFromOffsets = fromOffsets.keySet().map(_.topic)
      if (topicsFromOffsets != topics.toSet) {
        throw new IllegalStateException(s"The specified topics: ${topics.toSet.mkString(" ")} " +
          s"do not equal to the topic from offsets: ${topicsFromOffsets.mkString(" ")}")
      }
    }

    if (fromOffsets.isEmpty) {
      KafkaUtils.createDirectStream[Array[Byte], Array[Byte], DefaultDecoder, DefaultDecoder](
        jssc,
        classOf[Array[Byte]],
        classOf[Array[Byte]],
        classOf[DefaultDecoder],
        classOf[DefaultDecoder],
        kafkaParams,
        topics)
    } else {
      val messageHandler = new JFunction[MessageAndMetadata[Array[Byte], Array[Byte]],
        (Array[Byte], Array[Byte])] {
        def call(t1: MessageAndMetadata[Array[Byte], Array[Byte]]): (Array[Byte], Array[Byte]) =
          (t1.key(), t1.message())
      }

      val jstream = KafkaUtils.createDirectStream[
        Array[Byte],
        Array[Byte],
        DefaultDecoder,
        DefaultDecoder,
        (Array[Byte], Array[Byte])](
          jssc,
          classOf[Array[Byte]],
          classOf[Array[Byte]],
          classOf[DefaultDecoder],
          classOf[DefaultDecoder],
          classOf[(Array[Byte], Array[Byte])],
          kafkaParams,
          fromOffsets,
          messageHandler)
      new JavaPairInputDStream(jstream.inputDStream)
    }
  }

  def createOffsetRange(topic: String, partition: JInt, fromOffset: JLong, untilOffset: JLong
    ): OffsetRange = OffsetRange.create(topic, partition, fromOffset, untilOffset)

  def createTopicAndPartition(topic: String, partition: JInt): TopicAndPartition =
    TopicAndPartition(topic, partition)

  def createBroker(host: String, port: JInt): Broker = Broker(host, port)

  def offsetRangesOfKafkaRDD(rdd: RDD[_]): JList[OffsetRange] = {
    val parentRDDs = rdd.getNarrowAncestors
    val kafkaRDDs = parentRDDs.filter(rdd => rdd.isInstanceOf[KafkaRDD[_, _, _, _, _]])

    require(
      kafkaRDDs.length == 1,
      "Cannot get offset ranges, as there may be multiple Kafka RDDs or no Kafka RDD associated" +
        "with this RDD, please call this method only on a Kafka RDD.")

    val kafkaRDD = kafkaRDDs.head.asInstanceOf[KafkaRDD[_, _, _, _, _]]
    kafkaRDD.offsetRanges.toSeq
  }
}
