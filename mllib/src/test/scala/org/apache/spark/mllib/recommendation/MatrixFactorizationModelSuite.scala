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

package org.apache.spark.mllib.recommendation

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils
/**
 * 矩阵分解模型 MatrixFactorizationModel
 */
class MatrixFactorizationModelSuite extends SparkFunSuite with MLlibTestSparkContext {

  val rank = 2 //相关特征因子
  //userFeatures用户特征
  var userFeatures: RDD[(Int, Array[Double])] = _
  //prodFeatures 产品特征
  var prodFeatures: RDD[(Int, Array[Double])] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    //userFeatures用户特征
    userFeatures = sc.parallelize(Seq((0, Array(1.0, 2.0)), (1, Array(3.0, 4.0))))
    //prodFeatures 产品特征
    prodFeatures = sc.parallelize(Seq((2, Array(5.0, 6.0))))
    /**
      +---+----------+
      | _1|        _2|
      +---+----------+
      |  0|[1.0, 2.0]|
      |  1|[3.0, 4.0]|
      +---+----------+*/
    sqlContext.createDataFrame(userFeatures).show()
    /**
    +---+----------+
    | _1|        _2|
    +---+----------+
    |  2|[5.0, 6.0]|
    +---+----------+*/
    sqlContext.createDataFrame(prodFeatures).show()
  }

  test("constructor") {//构造函数
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    //预测得分,用户ID,产品ID
    println("========"+model.predict(0, 2))
    //17.0
    assert(model.predict(0, 2) ~== 17.0 relTol 1e-14)

    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(1, userFeatures, prodFeatures)
    }
    //userFeatures 用户特征
    val userFeatures1 = sc.parallelize(Seq((0, Array(1.0)), (1, Array(3.0))))
    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(rank, userFeatures1, prodFeatures)
    }
   //prodFeatures 产品特征
    val prodFeatures1 = sc.parallelize(Seq((2, Array(5.0))))
    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(rank, userFeatures, prodFeatures1)
    }
  }

  test("save/load") {//保存/加载
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString
    def collect(features: RDD[(Int, Array[Double])]): Set[(Int, Seq[Double])] = {
      features.mapValues(_.toSeq).collect().toSet
    }
    try {
      model.save(sc, path)
      val newModel = MatrixFactorizationModel.load(sc, path)
      assert(newModel.rank === rank)
      //用户特征
      assert(collect(newModel.userFeatures) === collect(userFeatures))
      //产品特征
      assert(collect(newModel.productFeatures) === collect(prodFeatures))
    } finally {
      Utils.deleteRecursively(tempDir)
    }
  }

  test("batch predict API recommendProductsForUsers") {//批量预测API recommendproductsforusers
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val topK = 10
    //为用户推荐个数为num的商品
    val recommendations = model.recommendProductsForUsers(topK).collectAsMap()

    assert(recommendations(0)(0).rating ~== 17.0 relTol 1e-14)
    assert(recommendations(1)(0).rating ~== 39.0 relTol 1e-14)
  }

  test("batch predict API recommendUsersForProducts") {
    
    //userFeatures用户因子,prodFeatures商品因子,rank因子个数,因子个数一般越多越好,普通取值10到200
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val topK = 10
    //为用户推荐个数为num的商品
    val recommendations = model.recommendUsersForProducts(topK).collectAsMap()

    assert(recommendations(2)(0).user == 1)
    assert(recommendations(2)(0).rating ~== 39.0 relTol 1e-14)
    assert(recommendations(2)(1).user == 0)
    assert(recommendations(2)(1).rating ~== 17.0 relTol 1e-14)
  }
}
