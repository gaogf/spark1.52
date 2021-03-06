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

package org.apache.spark.sql.hive.execution

import java.io.File
import java.util.{Locale, TimeZone}

import scala.util.Try

import org.scalatest.BeforeAndAfter

import org.apache.hadoop.hive.conf.HiveConf.ConfVars

import org.apache.spark.{SparkFiles, SparkException}
import org.apache.spark.sql.{AnalysisException, DataFrame, Row}
import org.apache.spark.sql.catalyst.expressions.Cast
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.hive._
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.hive.test.TestHive._

case class TestData(a: Int, b: String)

/**
 * A set of test cases expressed in Hive QL that are not covered by the tests
 * included in the hive distribution.
  * 一组测试用例用Hive QL覆盖不到的测试包括在hive分布
 */
class HiveQuerySuite extends HiveComparisonTest with BeforeAndAfter {
  private val originalTimeZone = TimeZone.getDefault
  private val originalLocale = Locale.getDefault

  import org.apache.spark.sql.hive.test.TestHive.implicits._

  override def beforeAll() {
    TestHive.cacheTables = true
    //Timezone is fixed to America/Los_Angeles for those timezone sensitive tests (timestamp_*)
    //时区是固定到美国/洛杉矶,那些时区敏感试验（timestamp_ *）
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
     // Add Locale setting
    //添加区域设置
    Locale.setDefault(Locale.US)
  }

  override def afterAll() {
    TestHive.cacheTables = false
    TimeZone.setDefault(originalTimeZone)
    Locale.setDefault(originalLocale)
    sql("DROP TEMPORARY FUNCTION udtf_count2")
  }
  //并发本hive地本地命令
  test("SPARK-4908: concurrent hive native commands") {
    (1 to 100).par.map { _ =>
      sql("USE default")
      sql("SHOW DATABASES")
    }
  }
  //GROUP BY 的 WITH ROLLUP 字句可以检索出更多的分组聚合信息，它不仅仅能像一般的 GROUP BY 语句那样检索出各组的聚合信息，还能检索出本组类的整体聚合信息

  /**
      select dep,pos,avg(sal) from employee group by dep,pos;
    * +------+------+-----------+
    * | dep | pos | avg(sal) |
    * +------+------+-----------+
    * | 01 | 01 | 1500.0000 |
    * | 01 | 02 | 1950.0000 |
    * | 02 | 01 | 1500.0000 |
    * | 02 | 02 | 2450.0000 |
    * | 03 | 01 | 2500.0000 |
    * | 03 | 02 | 2550.0000 |
    * +------+------+-----------+
      select dep,pos,avg(sal) from employee group by dep,pos with rollup;
    //ROLLUP 生成的结果集显示了所选列中值的某一层次结构的聚合。
    * +------+------+-----------+
    * | dep | pos | avg(sal) |
    * +------+------+-----------+
    * | 01 | 01 | 1500.0000 |
    * | 01 | 02 | 1950.0000 |
    * | 01 | NULL | 1725.0000 |
    * | 02 | 01 | 1500.0000 |
    * | 02 | 02 | 2450.0000 |
    * | 02 | NULL | 2133.3333 |
    * | 03 | 01 | 2500.0000 |
    * | 03 | 02 | 2550.0000 |
    * | 03 | NULL | 2533.3333 |
    * | NULL | NULL | 2090.0000 |
    * +------+------+-----------+
    */
  createQueryTest("SPARK-8976 Wrong Result for Rollup #1",
    """
      SELECT count(*) AS cnt, key % 5,GROUPING__ID FROM src group by key%5 WITH ROLLUP
    """.stripMargin)

  createQueryTest("SPARK-8976 Wrong Result for Rollup #2",
    """
      SELECT
        count(*) AS cnt,
        key % 5 as k1,
        key-5 as k2,
        GROUPING__ID as k3
      FROM src group by key%5, key-5
      WITH ROLLUP ORDER BY cnt, k1, k2, k3 LIMIT 10
    """.stripMargin)

  createQueryTest("SPARK-8976 Wrong Result for Rollup #3",
    """
      SELECT
        count(*) AS cnt,
        key % 5 as k1,
        key-5 as k2,
        GROUPING__ID as k3
      FROM (SELECT key, key%2, key - 5 FROM src) t group by key%5, key-5
      WITH ROLLUP ORDER BY cnt, k1, k2, k3 LIMIT 10
    """.stripMargin)

//CUBE 生成的结果集显示了所选列中值的所有组合的聚合。
//当用 CUBE 或 ROLLUP 运算符添加行时，附加的列输出值为1，当所添加的行不是由 CUBE 或 ROLLUP 产生时，附加列值为0。
  /**
    * SELECT  名称,出版商,SUM(价格1) AS 价格1,SUM(价格2) AS 价格2,
      GROUPING(名称) AS CHECK名称,GROUPING(出版商) AS CHECK出版商 FROM @T GROUP BY 名称,出版商 WITH CUBE
  名称   出版商      价格1      价格2   CHECK名称 CHECK出版商
  ---- ---------- ----------- ----------- ------- --------
  a    北京         11          22          0       0
  a    四川         22          33          0       0
  a    NULL        33          55          0       1
  b    北京         10          20          0       0
  b    昆明         20          30          0       0
  b    四川         12          23          0       0
  b    NULL        42          73          0       1
  NULL NULL        75          128         1       1
  NULL 北京         21          42          1       0
  NULL 昆明         20          30          1       0
  NULL 四川         34          56          1       0

--分析
group by 两列：名称有两个类别A,B;所有由CUBE运算而生成行的是
名称   出版商     价格1     价格2       CHECK名称 CHECK出版商
---- ---------- ----------- ----------- ------- --------
a    NULL       33          55          0       1
b    NULL       42          73          0       1
出版商有三个类别，所有由CUBE运算而生成行的是
  名称   出版商      价格1       价格2        CHECK名称 CHECK出版商
  ---- ---------- ----------- ----------- ------- --------
  NULL 北京         21          42          1       0
  NULL 昆明         20          30          1       0
  NULL 四川         34          56          1       0
  以及
  NULL NULL        75          128         1       1
**/
  //CUBE错误的结果
 createQueryTest("SPARK-8976 Wrong Result for CUBE #1",
    """
      SELECT count(*) AS cnt, key % 5,GROUPING__ID FROM src group by key%5 WITH CUBE
    """.stripMargin)

  createQueryTest("SPARK-8976 Wrong Result for CUBE #2",
    """
      SELECT
        count(*) AS cnt,
        key % 5 as k1,
        key-5 as k2,
        GROUPING__ID as k3
      FROM (SELECT key, key%2, key - 5 FROM src) t group by key%5, key-5
      WITH CUBE ORDER BY cnt, k1, k2, k3 LIMIT 10
    """.stripMargin)
//grouping sets就是由多个group by联合起来,GroupingSet错误的结果
  createQueryTest("SPARK-8976 Wrong Result for GroupingSet",
    """
      SELECT
        count(*) AS cnt,
        key % 5 as k1,
        key-5 as k2,
        GROUPING__ID as k3
      FROM (SELECT key, key%2, key - 5 FROM src) t group by key%5, key-5
      GROUPING SETS (key%5, key-5) ORDER BY cnt, k1, k2, k3 LIMIT 10
    """.stripMargin)
//插入带有列名称的生成器的表
  createQueryTest("insert table with generator with column name",
    """
      |  CREATE TABLE gen_tmp (key Int);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(array(1,2,3)) AS val FROM src LIMIT 3;
      |  SELECT key FROM gen_tmp ORDER BY key ASC;
    """.stripMargin)
//插入具有多个列名称的生成器的表
  createQueryTest("insert table with generator with multiple column names",
    """
      |  CREATE TABLE gen_tmp (key Int, value String);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(map(key, value)) as (k1, k2) FROM src LIMIT 3;
      |  SELECT key, value FROM gen_tmp ORDER BY key, value ASC;
    """.stripMargin)
  //插入表与发生器没有列名称
  createQueryTest("insert table with generator without column name",
    """
      |  CREATE TABLE gen_tmp (key Int);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(array(1,2,3)) FROM src LIMIT 3;
      |  SELECT key FROM gen_tmp ORDER BY key ASC;
    """.stripMargin)
  // explode(ARRAY)  列表中的每个元素生成一行
  // explode(MAP) map中每个key-value对,生成一行,key为一列,value为一列
  test("multiple generators in projection") {
    intercept[AnalysisException] {
      sql("SELECT explode(array(key, key)), explode(array(key, key)) FROM src").collect()
    }

    intercept[AnalysisException] {
      sql("SELECT explode(array(key, key)) as k1, explode(array(key, key)) FROM src").collect()
    }
  }

  createQueryTest("! operator",
    """
      |SELECT a FROM (
      |  SELECT 1 AS a UNION ALL SELECT 2 AS a) t
      |WHERE !(a>1)
    """.stripMargin)
  //常规对象检查器，用于通用udf
  createQueryTest("constant object inspector for generic udf",
    """SELECT named_struct(
      lower("AA"), "10",
      repeat(lower("AA"), 3), "11",
      lower(repeat("AA", 3)), "12",
      printf("bb%d", 12), "13",
      repeat(printf("s%d", 14), 2), "14") FROM src LIMIT 1""")
  //NaN到十进制
  createQueryTest("NaN to Decimal",
    "SELECT CAST(CAST('NaN' AS DOUBLE) AS DECIMAL(1,1)) FROM src LIMIT 1")
  //常数null测试
  createQueryTest("constant null testing",
    """SELECT
      |IF(FALSE, CAST(NULL AS STRING), CAST(1 AS STRING)) AS COL1,
      |IF(TRUE, CAST(NULL AS STRING), CAST(1 AS STRING)) AS COL2,
      |IF(FALSE, CAST(NULL AS INT), CAST(1 AS INT)) AS COL3,
      |IF(TRUE, CAST(NULL AS INT), CAST(1 AS INT)) AS COL4,
      |IF(FALSE, CAST(NULL AS DOUBLE), CAST(1 AS DOUBLE)) AS COL5,
      |IF(TRUE, CAST(NULL AS DOUBLE), CAST(1 AS DOUBLE)) AS COL6,
      |IF(FALSE, CAST(NULL AS BOOLEAN), CAST(1 AS BOOLEAN)) AS COL7,
      |IF(TRUE, CAST(NULL AS BOOLEAN), CAST(1 AS BOOLEAN)) AS COL8,
      |IF(FALSE, CAST(NULL AS BIGINT), CAST(1 AS BIGINT)) AS COL9,
      |IF(TRUE, CAST(NULL AS BIGINT), CAST(1 AS BIGINT)) AS COL10,
      |IF(FALSE, CAST(NULL AS FLOAT), CAST(1 AS FLOAT)) AS COL11,
      |IF(TRUE, CAST(NULL AS FLOAT), CAST(1 AS FLOAT)) AS COL12,
      |IF(FALSE, CAST(NULL AS SMALLINT), CAST(1 AS SMALLINT)) AS COL13,
      |IF(TRUE, CAST(NULL AS SMALLINT), CAST(1 AS SMALLINT)) AS COL14,
      |IF(FALSE, CAST(NULL AS TINYINT), CAST(1 AS TINYINT)) AS COL15,
      |IF(TRUE, CAST(NULL AS TINYINT), CAST(1 AS TINYINT)) AS COL16,
      |IF(FALSE, CAST(NULL AS BINARY), CAST("1" AS BINARY)) AS COL17,
      |IF(TRUE, CAST(NULL AS BINARY), CAST("1" AS BINARY)) AS COL18,
      |IF(FALSE, CAST(NULL AS DATE), CAST("1970-01-01" AS DATE)) AS COL19,
      |IF(TRUE, CAST(NULL AS DATE), CAST("1970-01-01" AS DATE)) AS COL20,
      |IF(FALSE, CAST(NULL AS TIMESTAMP), CAST(1 AS TIMESTAMP)) AS COL21,
      |IF(TRUE, CAST(NULL AS TIMESTAMP), CAST(1 AS TIMESTAMP)) AS COL22,
      |IF(FALSE, CAST(NULL AS DECIMAL), CAST(1 AS DECIMAL)) AS COL23,
      |IF(TRUE, CAST(NULL AS DECIMAL), CAST(1 AS DECIMAL)) AS COL24
      |FROM src LIMIT 1""".stripMargin)
  //常数数组
  createQueryTest("constant array",
  """
    |SELECT sort_array(
    |  sort_array(
    |    array("hadoop distributed file system",
    |          "enterprise databases", "hadoop map-reduce")))
    |FROM src LIMIT 1;
  """.stripMargin)

  createQueryTest("null case",
    "SELECT case when(true) then 1 else null end FROM src LIMIT 1")

  createQueryTest("single case",
    """SELECT case when true then 1 else 2 end FROM src LIMIT 1""")

  createQueryTest("double case",
    """SELECT case when 1 = 2 then 1 when 2 = 2 then 3 else 2 end FROM src LIMIT 1""")

  createQueryTest("case else null",
    """SELECT case when 1 = 2 then 1 when 2 = 2 then 3 else null end FROM src LIMIT 1""")
  /*  createQueryTest("having no references",
      "SELECT key FROM src GROUP BY key HAVING COUNT(*) > 1")*/
  //没有from 条件
  createQueryTest("no from clause",
    "SELECT 1, +1, -1")

  createQueryTest("boolean = number",
    """
      |SELECT
      |  1 = true, 1L = true, 1Y = true, true = 1, true = 1L, true = 1Y,
      |  0 = true, 0L = true, 0Y = true, true = 0, true = 0L, true = 0Y,
      |  1 = false, 1L = false, 1Y = false, false = 1, false = 1L, false = 1Y,
      |  0 = false, 0L = false, 0Y = false, false = 0, false = 0L, false = 0Y,
      |  2 = true, 2L = true, 2Y = true, true = 2, true = 2L, true = 2Y,
      |  2 = false, 2L = false, 2Y = false, false = 2, false = 2L, false = 2Y
      |FROM src LIMIT 1
    """.stripMargin)
  //CREATE TABLE AS运行一次
  test("CREATE TABLE AS runs once") {
    sql("CREATE TABLE foo AS SELECT 1 FROM src LIMIT 1").collect()
    assert(sql("SELECT COUNT(*) FROM foo").collect().head.getLong(0) === 1,
      "Incorrect number of rows in created table")
  }

  createQueryTest("between",
    "SELECT * FROM src WHERE key Between 1 and 2")

  createQueryTest("div",
    "SELECT 1 DIV 2, 1 div 2, 1 dIv 2, 100 DIV 51, 100 DIV 49 FROM src LIMIT 1")

  // Jdk version leads to different query output for double, so not use createQueryTest here
  //Jdk版本导致不同的查询输出为双，所以在这里不使用createQueryTest
  test("division") {
    val res = sql("SELECT 2 / 1, 1 / 2, 1 / 3, 1 / COUNT(*) FROM src LIMIT 1").collect().head
    Seq(2.0, 0.5, 0.3333333333333333, 0.002).zip(res.toSeq).foreach( x =>
      assert(x._1 == x._2.asInstanceOf[Double]))
  }

  createQueryTest("modulus",
    "SELECT 11 % 10, IF((101.1 % 100.0) BETWEEN 1.01 AND 1.11, \"true\", \"false\"), " +
      "(101 / 2) % 10 FROM src LIMIT 1")
  //以SQL表示的查询
  test("Query expressed in SQL") {
    setConf("spark.sql.dialect", "sql")
    assert(sql("SELECT 1").collect() === Array(Row(1)))
    setConf("spark.sql.dialect", "hiveql")
  }

  test("Query expressed in HiveQL") {
    sql("FROM src SELECT key").collect()
  }
  //查询与常量折叠CAST
  test("Query with constant folding the CAST") {
    sql("SELECT CAST(CAST('123' AS binary) AS binary) FROM src LIMIT 1").collect()
  }
  //AVG(平均) SUM(总和) COUNT(计数)的恒定折叠优化
  createQueryTest("Constant Folding Optimization for AVG_SUM_COUNT",
    "SELECT AVG(0), SUM(0), COUNT(null), COUNT(value) FROM src GROUP BY key")
  //Cast类型转换,如果转换失败返回NULL
  // DATEDIFF返回 Variant (Long) 的值,表示两个指定日期间的时间间隔数目,
  createQueryTest("Cast Timestamp to Timestamp in UDF",
    """
      | SELECT DATEDIFF(CAST(value AS timestamp), CAST('2002-03-21 00:00:00' AS timestamp))
      | FROM src LIMIT 1
    """.stripMargin)
  //日期比较测试1
  createQueryTest("Date comparison test 1",
    //CAST 强制类型转换
    """
      | SELECT
      | CAST(CAST('1970-01-01 22:00:00' AS timestamp) AS date) ==
      | CAST(CAST('1970-01-01 23:00:00' AS timestamp) AS date)
      | FROM src LIMIT 1
    """.stripMargin)
  //简单平均
  createQueryTest("Simple Average",
    "SELECT AVG(key) FROM src")

  createQueryTest("Simple Average + 1",
    "SELECT AVG(key) + 1.0 FROM src")
  //简单平均+ 1与组
  createQueryTest("Simple Average + 1 with group",
    "SELECT AVG(key) + 1.0, value FROM src group by value")
  //字符串字面量
  createQueryTest("string literal",
    "SELECT 'test' FROM src")
  //转义序列
  createQueryTest("Escape sequences",
    """SELECT key, '\\\t\\' FROM src WHERE key = 86""")
  //忽略解释
  createQueryTest("IgnoreExplain",
    """EXPLAIN SELECT key FROM src""")

  //简单加入where子句
  createQueryTest("trivial join where clause",
    "SELECT * FROM src a JOIN src b WHERE a.key = b.key")
  //简单的加入ON子句
  createQueryTest("trivial join ON clause",
    "SELECT * FROM src a JOIN src b ON a.key = b.key")
  //小笛卡尔
  createQueryTest("small.cartesian",
    "SELECT a.key, b.key FROM (SELECT key FROM src WHERE key < 1) a JOIN " +
      "(SELECT key FROM src WHERE key = 2) b")

  createQueryTest("length.udf",
    "SELECT length(\"test\") FROM src LIMIT 1")
  //分区表扫描
  createQueryTest("partitioned table scan",
    "SELECT ds, hr, key, value FROM srcpart")

  createQueryTest("hash",
    "SELECT hash('test') FROM src LIMIT 1")
  //创建表
  createQueryTest("create table as",
    """
      |CREATE TABLE createdtable AS SELECT * FROM src;
      |SELECT * FROM createdtable
    """.stripMargin)
  //用db命名创建表
  createQueryTest("create table as with db name",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE testdb.createdtable AS SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)
  //在反引号中创建表与db名称
  createQueryTest("create table as with db name within backticks",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE `testdb`.`createdtable` AS SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)
  //使用db名称插入表
  createQueryTest("insert table with db name",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE testdb.createdtable like default.src;
      |INSERT INTO TABLE testdb.createdtable SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)
  //插入并插入覆盖
  createQueryTest("insert into and insert overwrite",
    """
      |CREATE TABLE createdtable like src;
      |INSERT INTO TABLE createdtable SELECT * FROM src;
      |INSERT INTO TABLE createdtable SELECT * FROM src1;
      |SELECT * FROM createdtable;
      |INSERT OVERWRITE TABLE createdtable SELECT * FROM src WHERE key = 86;
      |SELECT * FROM createdtable;
    """.stripMargin)
  //比较表输出时考虑动态分区
  test("SPARK-7270: consider dynamic partition when comparing table output") {
    sql(s"CREATE TABLE test_partition (a STRING) PARTITIONED BY (b BIGINT, c STRING)")
    sql(s"CREATE TABLE ptest (a STRING, b BIGINT, c STRING)")

    val analyzedPlan = sql(
      """
        |INSERT OVERWRITE table test_partition PARTITION (b=1, c)
        |SELECT 'a', 'c' from ptest
      """.stripMargin).queryExecution.analyzed

    assertResult(false, "Incorrect cast detected\n" + analyzedPlan) {
      var hasCast = false
      analyzedPlan.collect {
        case p: Project => p.transformExpressionsUp { case c: Cast => hasCast = true; c }
      }
      hasCast
    }
  }
  //转换
  createQueryTest("transform",
    "SELECT TRANSFORM (key) USING 'cat' AS (tKey) FROM src")
  //无模式的变换
  createQueryTest("schema-less transform",
    """
      |SELECT TRANSFORM (key, value) USING 'cat' FROM src;
      |SELECT TRANSFORM (*) USING 'cat' FROM src;
    """.stripMargin)

  val delimiter = "'\t'"
  //使用自定义字段分隔符转换
  createQueryTest("transform with custom field delimiter",
    s"""
      |SELECT TRANSFORM (key) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' AS (tKey) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))
  //自定义字段delimiter2变换
  createQueryTest("transform with custom field delimiter2",
    s"""
      |SELECT TRANSFORM (key, value) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))
  //自定义字段delimiter3变换
  createQueryTest("transform with custom field delimiter3",
    s"""
      |SELECT TRANSFORM (*) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))

  createQueryTest("transform with SerDe",
    """
      |SELECT TRANSFORM (key, value) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
      |USING 'cat' AS (tKey, tValue) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))

  test("transform with SerDe2") {

    sql("CREATE TABLE small_src(key INT, value STRING)")
    sql("INSERT OVERWRITE TABLE small_src SELECT key, value FROM src LIMIT 10")

    val expected = sql("SELECT key FROM small_src").collect().head
    val res = sql(
      """
        |SELECT TRANSFORM (key) ROW FORMAT SERDE
        |'org.apache.hadoop.hive.serde2.avro.AvroSerDe'
        |WITH SERDEPROPERTIES ('avro.schema.literal'='{"namespace":
        |"testing.hive.avro.serde","name": "src","type": "record","fields":
        |[{"name":"key","type":"int"}]}') USING 'cat' AS (tKey INT) ROW FORMAT SERDE
        |'org.apache.hadoop.hive.serde2.avro.AvroSerDe' WITH SERDEPROPERTIES
        |('avro.schema.literal'='{"namespace": "testing.hive.avro.serde","name":
        |"src","type": "record","fields": [{"name":"key","type":"int"}]}')
        |FROM small_src
      """.stripMargin.replaceAll(System.lineSeparator(), " ")).collect().head

    assert(expected(0) === res(0))
  }

  createQueryTest("transform with SerDe3",
    """
      |SELECT TRANSFORM (*) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') USING 'cat' AS (tKey, tValue)
      |ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
      |WITH SERDEPROPERTIES ('serialization.last.column.takes.rest'='true') FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))

  createQueryTest("transform with SerDe4",
    """
      |SELECT TRANSFORM (*) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') USING 'cat' ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))
//
  createQueryTest("LIKE",
    "SELECT * FROM src WHERE value LIKE '%1%'")

  createQueryTest("DISTINCT",
    "SELECT DISTINCT key, value FROM src")
  //空总输入
  createQueryTest("empty aggregate input",
    "SELECT SUM(key) FROM (SELECT * FROM src LIMIT 0) a")
  //LATERAL VIEW explode它能够将一行数据拆成多行数据，在此基础上可以对拆分后的数据进行聚合
  createQueryTest("lateral view1",
    "SELECT tbl.* FROM src LATERAL VIEW explode(array(1,2)) tbl as a")

  createQueryTest("lateral view2",
    "SELECT * FROM src LATERAL VIEW explode(array(1,2)) tbl")

  createQueryTest("lateral view3",
    "FROM src SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX")

  // scalastyle:off
  //多表插入
  createQueryTest("lateral view4",
    """
      |create table src_lv1 (key string, value string);
      |create table src_lv2 (key string, value string);
      |
      |FROM src
      |insert overwrite table src_lv1 SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX
      |insert overwrite table src_lv2 SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX
    """.stripMargin)
  // scalastyle:on
  //
  createQueryTest("lateral view5",
    "FROM src SELECT explode(array(key+3, key+4))")

  createQueryTest("lateral view6",
    "SELECT * FROM src LATERAL VIEW explode(map(key+3,key+4)) D as k, v")

  createQueryTest("Specify the udtf output",
    "SELECT d FROM (SELECT explode(array(1,1)) d FROM src LIMIT 1) t")

  test("sampling") {
    sql("SELECT * FROM src TABLESAMPLE(0.1 PERCENT) s")
    sql("SELECT * FROM src TABLESAMPLE(100 PERCENT) s")
  }

  test("DataFrame toString") {
    sql("SHOW TABLES").toString
    sql("SELECT * FROM src").toString
  }
  //case语句的关键# 1
  createQueryTest("case statements with key #1",
    "SELECT (CASE 1 WHEN 2 THEN 3 END) FROM src where key < 15")
  //case语句的关键# 2
  createQueryTest("case statements with key #2",
    "SELECT (CASE key WHEN 2 THEN 3 ELSE 0 END) FROM src WHERE key < 15")
  //case语句的关键# 3
  createQueryTest("case statements with key #3",
    "SELECT (CASE key WHEN 2 THEN 3 WHEN NULL THEN 4 END) FROM src WHERE key < 15")
  //case语句的关键# 4
  createQueryTest("case statements with key #4",
    "SELECT (CASE key WHEN 2 THEN 3 WHEN NULL THEN 4 ELSE 0 END) FROM src WHERE key < 15")
  //case语句没有关键WITHOUT＃1
  createQueryTest("case statements WITHOUT key #1",
    "SELECT (CASE WHEN key > 2 THEN 3 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #2",
    "SELECT (CASE WHEN key > 2 THEN 3 ELSE 4 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #3",
    "SELECT (CASE WHEN key > 2 THEN 3 WHEN 2 > key THEN 2 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #4",
    "SELECT (CASE WHEN key > 2 THEN 3 WHEN 2 > key THEN 2 ELSE 0 END) FROM src WHERE key < 15")

  // Jdk version leads to different query output for double, so not use createQueryTest here
  //Jdk版本导致不同的查询输出为双，所以在这里不使用createQueryTest
  test("timestamp cast #1") {
    val res = sql("SELECT CAST(CAST(1 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1").collect().head
    assert(0.001 == res.getDouble(0))
  }
  //时间戳强制类型转换
  createQueryTest("timestamp cast #2",
    "SELECT CAST(CAST(1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #3",
    "SELECT CAST(CAST(1200 AS TIMESTAMP) AS INT) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #4",
    "SELECT CAST(CAST(1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #5",
    "SELECT CAST(CAST(-1 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #6",
    "SELECT CAST(CAST(-1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #7",
    "SELECT CAST(CAST(-1200 AS TIMESTAMP) AS INT) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("timestamp cast #8",
    "SELECT CAST(CAST(-1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")
  //时间戳强制类型转换
  createQueryTest("select null from table",
    "SELECT null FROM src LIMIT 1")

  createQueryTest("CTE feature #1",
    "with q1 as (select key from src) select * from q1 where key = 5")

  createQueryTest("CTE feature #2",
    """with q1 as (select * from src where key= 5),
      |q2 as (select * from src s2 where key = 4)
      |select value from q1 union all select value from q2
    """.stripMargin)

  createQueryTest("CTE feature #3",
    """with q1 as (select key from src)
      |from q1
      |select * where key = 4
    """.stripMargin)

  // test get_json_object again Hive, because the HiveCompatabilitySuite cannot handle result
  // with newline in it.
  //再次测试get_json_object Hive,因为HiveCompatabilitySuite无法处理带有换行符的结果
  createQueryTest("get_json_object #1",
    "SELECT get_json_object(src_json.json, '$') FROM src_json")

  createQueryTest("get_json_object #2",
    "SELECT get_json_object(src_json.json, '$.owner'), get_json_object(src_json.json, '$.store')" +
      " FROM src_json")

  createQueryTest("get_json_object #3",
    "SELECT get_json_object(src_json.json, '$.store.bicycle'), " +
      "get_json_object(src_json.json, '$.store.book') FROM src_json")

  createQueryTest("get_json_object #4",
    "SELECT get_json_object(src_json.json, '$.store.book[0]'), " +
      "get_json_object(src_json.json, '$.store.book[*]') FROM src_json")

  createQueryTest("get_json_object #5",
    "SELECT get_json_object(src_json.json, '$.store.book[0].category'), " +
      "get_json_object(src_json.json, '$.store.book[*].category'), " +
      "get_json_object(src_json.json, '$.store.book[*].isbn'), " +
      "get_json_object(src_json.json, '$.store.book[*].reader') FROM src_json")

  createQueryTest("get_json_object #6",
    "SELECT get_json_object(src_json.json, '$.store.book[*].reader[0].age'), " +
      "get_json_object(src_json.json, '$.store.book[*].reader[*].age') FROM src_json")

  createQueryTest("get_json_object #7",
    "SELECT get_json_object(src_json.json, '$.store.basket[0][1]'), " +
      "get_json_object(src_json.json, '$.store.basket[*]'), " +
      // Hive returns wrong result with [*][0], so this expression is change to make test pass
      "get_json_object(src_json.json, '$.store.basket[0][0]'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*]'), " +
      "get_json_object(src_json.json, '$.store.basket[*][*]'), " +
      "get_json_object(src_json.json, '$.store.basket[0][2].b'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*].b') FROM src_json")

  createQueryTest("get_json_object #8",
    "SELECT get_json_object(src_json.json, '$.non_exist_key'), " +
      "get_json_object(src_json.json, '$..no_recursive'), " +
      "get_json_object(src_json.json, '$.store.book[10]'), " +
      "get_json_object(src_json.json, '$.store.book[0].non_exist_key'), " +
      "get_json_object(src_json.json, '$.store.basket[*].non_exist_key'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*].non_exist_key') FROM src_json")

  createQueryTest("get_json_object #9",
    "SELECT get_json_object(src_json.json, '$.zip code') FROM src_json")

  createQueryTest("get_json_object #10",
    "SELECT get_json_object(src_json.json, '$.fb:testid') FROM src_json")
  //谓词包含一个空的AttributeSet()引用
  test("predicates contains an empty AttributeSet() references") {
    sql(
      """
        |SELECT a FROM (
        |  SELECT 1 AS a FROM src LIMIT 1 ) t
        |WHERE abs(20141202) is not null
      """.stripMargin).collect()
  }
  //使用case语句实现唯一函数
  test("implement identity function using case statement") {
    val actual = sql("SELECT (CASE key WHEN key THEN key END) FROM src")
      .map { case Row(i: Int) => i }
      .collect()
      .toSet

    val expected = sql("SELECT key FROM src")
      .map { case Row(i: Int) => i }
      .collect()
      .toSet

    assert(actual === expected)
  }

  // TODO: adopt this test when Spark SQL has the functionality / framework to report errors.
  // See https://github.com/apache/spark/pull/1055#issuecomment-45820167 for a discussion.
  //case中的非布尔条件是非法的
  ignore("non-boolean conditions in a CaseWhen are illegal") {
    intercept[Exception] {
      sql("SELECT (CASE WHEN key > 2 THEN 3 WHEN 1 THEN 2 ELSE 0 END) FROM src").collect()
    }
  }
  //查询Hive表时区分大小写
  createQueryTest("case sensitivity when query Hive table",
    "SELECT srcalias.KEY, SRCALIAS.value FROM sRc SrCAlias WHERE SrCAlias.kEy < 15")
  //区分大小写：注册表
  test("case sensitivity: registered table") {
    val testData =
      TestHive.sparkContext.parallelize(
        TestData(1, "str1") ::
        TestData(2, "str2") :: Nil)
    testData.toDF().registerTempTable("REGisteredTABle")

    assertResult(Array(Row(2, "str2"))) {
      sql("SELECT tablealias.A, TABLEALIAS.b FROM reGisteredTABle TableAlias " +
        "WHERE TableAliaS.a > 1").collect()
    }
  }

  def isExplanation(result: DataFrame): Boolean = {
    val explanation = result.select('plan).collect().map { case Row(plan: String) => plan }
    explanation.contains("== Physical Plan ==")
  }
  //将命令解释为DataFrame
  test("SPARK-1704: Explain commands as a DataFrame") {
    sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING)")

    val df = sql("explain select key, count(value) from src group by key")
    assert(isExplanation(df))

    TestHive.reset()
  }
  //在GROUP BY子句中支持（正）
  test("SPARK-2180: HAVING support in GROUP BY clauses (positive)") {
    val fixture = List(("foo", 2), ("bar", 1), ("foo", 4), ("bar", 3))
      .zipWithIndex.map {case Pair(Pair(value, attr), key) => HavingRow(key, value, attr)}
    TestHive.sparkContext.parallelize(fixture).toDF().registerTempTable("having_test")
    val results =
      sql("SELECT value, max(attr) AS attr FROM having_test GROUP BY value HAVING attr > 3")
      .collect()
      .map(x => Pair(x.getString(0), x.getInt(1)))

    assert(results === Array(Pair("foo", 4)))
    TestHive.reset()
  }
  //拥有非布尔子句不会引发异常。
  test("SPARK-2180: HAVING with non-boolean clause raises no exceptions") {
    sql("select key, count(*) c from src group by key having c").collect()
  }
  //无组转为简单过滤器
  test("SPARK-2225: turn HAVING without GROUP BY into a simple filter") {
    assert(sql("select key from src having key > 490").collect().size < 100)
  }

  test("SPARK-5383 alias for udfs with multi output columns") {
    assert(
      sql("select stack(2, key, value, key, value) as (a, b) from src limit 5")
        .collect()
        .size == 5)

    assert(
      sql("select a, b from (select stack(2, key, value, key, value) as (a, b) from src) t limit 5")
        .collect()
        .size == 5)
  }
  //在udf中解析星号表达式
  test("SPARK-5367: resolve star expression in udf") {
    assert(sql("select concat(*) from src limit 5").collect().size == 5)
    assert(sql("select array(*) from src limit 5").collect().size == 5)
    assert(sql("select concat(key, *) from src limit 5").collect().size == 5)
    assert(sql("select array(key, *) from src limit 5").collect().size == 5)
  }
  //查询Hive本机命令执行结果
  test("Query Hive native command execution result") {
    val databaseName = "test_native_commands"

    assertResult(0) {
      sql(s"DROP DATABASE IF EXISTS $databaseName").count()
    }

    assertResult(0) {
      sql(s"CREATE DATABASE $databaseName").count()
    }

    assert(
      sql("SHOW DATABASES")
        .select('result)
        .collect()
        .map(_.getString(0))
        .contains(databaseName))

    assert(isExplanation(sql(s"EXPLAIN SELECT key, COUNT(*) FROM src GROUP BY key")))

    TestHive.reset()
  }
  //完全一次DDL和命令语句的语义
  test("Exactly once semantics for DDL and command statements") {
    val tableName = "test_exactly_once"
    val q0 = sql(s"CREATE TABLE $tableName(key INT, value STRING)")

    // If the table was not created, the following assertion would fail
    //如果表未创建，则以下断言将失败
    assert(Try(table(tableName)).isSuccess)

    // If the CREATE TABLE command got executed again, the following assertion would fail
    //如果再次执行CREATE TABLE命令，则以下断言将失败
    assert(Try(q0.count()).isSuccess)
  }
  //DESCRIBE命令
  test("DESCRIBE commands") {
    sql(s"CREATE TABLE test_describe_commands1 (key INT, value STRING) PARTITIONED BY (dt STRING)")

    sql(
      """FROM src INSERT OVERWRITE TABLE test_describe_commands1 PARTITION (dt='2008-06-08')
        |SELECT key, value
      """.stripMargin)

    // Describe a table
    assertResult(
      Array(
        Row("key", "int", null),
        Row("value", "string", null),
        Row("dt", "string", null),
        Row("# Partition Information", "", ""),
        Row("# col_name", "data_type", "comment"),
        Row("dt", "string", null))
    ) {
      sql("DESCRIBE test_describe_commands1")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }

    // Describe a table with a fully qualified table name
    //描述具有完全限定表名称的表
    assertResult(
      Array(
        Row("key", "int", null),
        Row("value", "string", null),
        Row("dt", "string", null),
        Row("# Partition Information", "", ""),
        Row("# col_name", "data_type", "comment"),
        Row("dt", "string", null))
    ) {
      sql("DESCRIBE default.test_describe_commands1")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }

    // Describe a column is a native command
    //描述一个列是一个本机命令
    assertResult(Array(Array("value", "string", "from deserializer"))) {
      sql("DESCRIBE test_describe_commands1 value")
        .select('result)
        .collect()
        .map(_.getString(0).split("\t").map(_.trim))
    }

    // Describe a column is a native command
    //描述一个列是一个本机命令
    assertResult(Array(Array("value", "string", "from deserializer"))) {
      sql("DESCRIBE default.test_describe_commands1 value")
        .select('result)
        .collect()
        .map(_.getString(0).split("\t").map(_.trim))
    }

    // Describe a partition is a native command
    //描述一个分区是一个本机命令
    assertResult(
      Array(
        Array("key", "int"),
        Array("value", "string"),
        Array("dt", "string"),
        Array(""),
        Array("# Partition Information"),
        Array("# col_name", "data_type", "comment"),
        Array(""),
        Array("dt", "string"))
    ) {
      sql("DESCRIBE test_describe_commands1 PARTITION (dt='2008-06-08')")
        .select('result)
        .collect()
        .map(_.getString(0).replaceAll("None", "").trim.split("\t").map(_.trim))
    }

    // Describe a registered temporary table.
    //描述一个注册的临时表
    val testData =
      TestHive.sparkContext.parallelize(
        TestData(1, "str1") ::
        TestData(1, "str2") :: Nil)
    testData.toDF().registerTempTable("test_describe_commands2")

    assertResult(
      Array(
        Row("a", "int", ""),
        Row("b", "string", ""))
    ) {
      sql("DESCRIBE test_describe_commands2")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }
  }
  //插入Map<K，V>值
  test("SPARK-2263: Insert Map<K, V> values") {
    sql("CREATE TABLE m(value MAP<INT, STRING>)")
    sql("INSERT OVERWRITE TABLE m SELECT MAP(key, value) FROM src LIMIT 10")
    sql("SELECT * FROM m").collect().zip(sql("SELECT * FROM src LIMIT 10").collect()).map {
      case (Row(map: Map[_, _]), Row(key: Int, value: String)) =>
        assert(map.size === 1)
        assert(map.head === (key, value))
    }
  }
  //ADD JAR命令
  test("ADD JAR command") {
    val testJar = TestHive.getHiveFile("data/files/TestSerDe.jar").getCanonicalPath
    sql("CREATE TABLE alter1(a INT, b INT)")
    intercept[Exception] {
      sql(
        """ALTER TABLE alter1 SET SERDE 'org.apache.hadoop.hive.serde2.TestSerDe'
          |WITH serdeproperties('s1'='9')
        """.stripMargin)
    }
    sql("DROP TABLE alter1")
  }

  test("ADD JAR command 2") {
    // this is a test case from mapjoin_addjar.q
    val testJar = TestHive.getHiveFile("hive-hcatalog-core-0.13.1.jar").getCanonicalPath
    val testData = TestHive.getHiveFile("data/files/sample.json").getCanonicalPath
    sql(s"ADD JAR $testJar")
    sql(
      """CREATE TABLE t1(a string, b string)
      |ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'""".stripMargin)
    sql(s"""LOAD DATA LOCAL INPATH "$testData" INTO TABLE t1""")
    sql("select * from src join t1 on src.key = t1.a")
    sql("DROP TABLE t1")
  }
  //添加文件
  test("ADD FILE command") {
    val testFile = TestHive.getHiveFile("data/files/v1.txt").getCanonicalFile

    sql(s"ADD FILE $testFile")

    val checkAddFileRDD = sparkContext.parallelize(1 to 2, 1).mapPartitions { _ =>
      //获得文件
      Iterator.single(new File(SparkFiles.get("v1.txt")).canRead)
    }

   // assert(checkAddFileRDD.first())
  }

  case class LogEntry(filename: String, message: String)
  case class LogFile(name: String)
  //动态分区
 createQueryTest("dynamic_partition",
   //使用动态分区要先设置hive.exec.dynamic.partition参数值为true，默认值为false
   //假设我想向stat_date='20110728'这个分区下面插入数据，至于province插入到哪个子分区下面让数据库自己来判断
   //
    """
      |DROP TABLE IF EXISTS dynamic_part_table;
      |CREATE TABLE dynamic_part_table(intcol INT) PARTITIONED BY (partcol1 INT, partcol2 INT);
      |
      |SET hive.exec.dynamic.partition.mode=nonstrict;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, 1, 1 FROM src WHERE key=150;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, NULL, 1 FROM src WHERE key=150;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, 1, NULL FROM src WHERE key=150;
      |
      |INSERT INTO TABLe dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, NULL, NULL FROM src WHERE key=150;
      |
      |DROP TABLE IF EXISTS dynamic_part_table;
    """.stripMargin)
  //动态分区文件夹布局
  ignore("Dynamic partition folder layout") {
    sql("DROP TABLE IF EXISTS dynamic_part_table")
    sql("CREATE TABLE dynamic_part_table(intcol INT) PARTITIONED BY (partcol1 INT, partcol2 INT)")
    //hive.exec.dynamic.partition.mode设置为nonstrict。当然，Hive也支持insert overwrite方式来插入数据
    sql("SET hive.exec.dynamic.partition.mode=nonstrict")

    val data = Map(
      Seq("1", "1") -> 1,
      Seq("1", "NULL") -> 2,
      Seq("NULL", "1") -> 3,
      Seq("NULL", "NULL") -> 4)

    data.foreach { case (parts, value) =>
      sql(
        s"""INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
           |SELECT $value, ${parts.mkString(", ")} FROM src WHERE key=150
         """.stripMargin)

      val partFolder = Seq("partcol1", "partcol2")
        .zip(parts)
        .map { case (k, v) =>
          if (v == "NULL") {
            s"$k=${ConfVars.DEFAULTPARTITIONNAME.defaultStrVal}"
          } else {
            s"$k=$v"
          }
        }
        .mkString("/")

      // Loads partition data to a temporary table to verify contents
      //将分区数据加载到临时表以验证内容
      val path = s"$warehousePath/dynamic_part_table/$partFolder/part-00000"

      sql("DROP TABLE IF EXISTS dp_verify")
      sql("CREATE TABLE dp_verify(intcol INT)")
      sql(s"LOAD DATA LOCAL INPATH '$path' INTO TABLE dp_verify")

      assert(sql("SELECT * FROM dp_verify").collect() === Array(Row(value)))
    }
  }

  ignore("SPARK-5592: get java.net.URISyntaxException when dynamic partitioning") {
    sql("""
      |create table sc as select *
      |from (select '2011-01-11', '2011-01-11+14:18:26' from src tablesample (1 rows)
      |union all
      |select '2011-01-11', '2011-01-11+15:18:26' from src tablesample (1 rows)
      |union all
      |select '2011-01-11', '2011-01-11+16:18:26' from src tablesample (1 rows) ) s
    """.stripMargin)
    sql("create table sc_part (key string) partitioned by (ts string) stored as rcfile")
    sql("set hive.exec.dynamic.partition=true")
    sql("set hive.exec.dynamic.partition.mode=nonstrict")
    sql("insert overwrite table sc_part partition(ts) select * from sc")
    sql("drop table sc_part")
  }
  //分区规范验证
  test("Partition spec validation") {
    sql("DROP TABLE IF EXISTS dp_test")
    sql("CREATE TABLE dp_test(key INT, value STRING) PARTITIONED BY (dp INT, sp INT)")
    sql("SET hive.exec.dynamic.partition.mode=strict")

    // Should throw when using strict dynamic partition mode without any static partition
    //使用严格的动态分区模式时,应该抛出任何静态分区
    intercept[SparkException] {
      sql(
        """INSERT INTO TABLE dp_test PARTITION(dp)
          |SELECT key, value, key % 5 FROM src
        """.stripMargin)
    }

    sql("SET hive.exec.dynamic.partition.mode=nonstrict")

    // Should throw when a static partition appears after a dynamic partition
    //动态分区后静态分区出现时应该抛出
    intercept[SparkException] {
      sql(
        """INSERT INTO TABLE dp_test PARTITION(dp, sp = 1)
          |SELECT key, value, key % 5 FROM src
        """.stripMargin)
    }
  }
  //回归：在注册临时表时应该存储分析的逻辑计划
  test("SPARK-3414 regression: should store analyzed logical plan when registering a temp table") {
    sparkContext.makeRDD(Seq.empty[LogEntry]).toDF().registerTempTable("rawLogs")
    sparkContext.makeRDD(Seq.empty[LogFile]).toDF().registerTempTable("logFiles")

    sql(
      """
      SELECT name, message
      FROM rawLogs
      JOIN (
        SELECT name
        FROM logFiles
      ) files
      ON rawLogs.filename = files.name
      """).registerTempTable("boom")

    // This should be successfully analyzed
    //应该成功分析
    sql("SELECT * FROM boom").queryExecution.analyzed
  }
  //插入前静态分区支持
  ignore("SPARK-3810: PreInsertionCasts static partitioning support") {
    val analyzedPlan = {
      loadTestTable("srcpart")
      sql("DROP TABLE IF EXISTS withparts")
      sql("CREATE TABLE withparts LIKE srcpart")
      sql("INSERT INTO TABLE withparts PARTITION(ds='1', hr='2') SELECT key, value FROM src")
        .queryExecution.analyzed
    }

    assertResult(1, "Duplicated project detected\n" + analyzedPlan) {
      analyzedPlan.collect {
        case _: Project => ()
      }.size
    }
  }
//插入前动态分区支持
  ignore("SPARK-3810: PreInsertionCasts dynamic partitioning support") {
    val analyzedPlan = {
      loadTestTable("srcpart")
      sql("DROP TABLE IF EXISTS withparts")
      sql("CREATE TABLE withparts LIKE srcpart")
      sql("SET hive.exec.dynamic.partition.mode=nonstrict")

      sql("CREATE TABLE IF NOT EXISTS withparts LIKE srcpart")
      sql("INSERT INTO TABLE withparts PARTITION(ds, hr) SELECT key, value FROM src")
        .queryExecution.analyzed
    }

    assertResult(1, "Duplicated project detected\n" + analyzedPlan) {
      analyzedPlan.collect {
        case _: Project => ()
      }.size
    }
  }
  //解析HQL集命令
  test("parse HQL set commands") {
    // Adapted from its SQL counterpart.
    //改编自其SQL对应
    val testKey = "spark.sql.key.usedfortestonly"
    val testVal = "val0,val_1,val2.3,my_table"

    sql(s"set $testKey=$testVal")
    assert(getConf(testKey, testVal + "_") == testVal)

    sql("set some.property=20")
    assert(getConf("some.property", "0") == "20")
    sql("set some.property = 40")
    assert(getConf("some.property", "0") == "40")

    sql(s"set $testKey=$testVal")
    assert(getConf(testKey, "0") == testVal)

    sql(s"set $testKey=")
    assert(getConf(testKey, "0") == "")
  }
  //SET命令HiveContext的语义
  test("SET commands semantics for a HiveContext") {
    // Adapted from its SQL counterpart.
    val testKey = "spark.sql.key.usedfortestonly"
    val testVal = "test.val.0"
    val nonexistentKey = "nonexistent"
    def collectResults(df: DataFrame): Set[Any] =
      df.collect().map {
        case Row(key: String, value: String) => key -> value
        case Row(key: String, defaultValue: String, doc: String) => (key, defaultValue, doc)
      }.toSet
    conf.clear()

    val expectedConfs = conf.getAllDefinedConfs.toSet
    assertResult(expectedConfs)(collectResults(sql("SET -v")))

    // "SET" itself returns all config variables currently specified in SQLConf.
    //“SET”本身返回SQLConf中当前指定的所有配置变量
    // TODO: Should we be listing the default here always? probably...
    assert(sql("SET").collect().size == 0)

    assertResult(Set(testKey -> testVal)) {
      collectResults(sql(s"SET $testKey=$testVal"))
    }

    assert(hiveconf.get(testKey, "") == testVal)
    assertResult(Set(testKey -> testVal))(collectResults(sql("SET")))

    sql(s"SET ${testKey + testKey}=${testVal + testVal}")
    assert(hiveconf.get(testKey + testKey, "") == testVal + testVal)
    assertResult(Set(testKey -> testVal, (testKey + testKey) -> (testVal + testVal))) {
      collectResults(sql("SET"))
    }

    // "SET key"
    assertResult(Set(testKey -> testVal)) {
      collectResults(sql(s"SET $testKey"))
    }

    assertResult(Set(nonexistentKey -> "<undefined>")) {
      collectResults(sql(s"SET $nonexistentKey"))
    }

    conf.clear()
  }

  createQueryTest("select from thrift based table",
    "SELECT * from src_thrift")

  // Put tests that depend on specific Hive settings before these last two test,
  // since they modify /clear stuff.
  //在最后两次测试之前进行依赖于具体Hive设置的测试,因为他们修改/清除东西
}

// for SPARK-2180 test
case class HavingRow(key: Int, value: String, attr: Int)
