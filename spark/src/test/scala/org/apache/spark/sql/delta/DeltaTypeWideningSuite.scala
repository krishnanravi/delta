/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.util.concurrent.TimeUnit

import com.databricks.spark.util.Log4jUsageLogger
import org.apache.spark.sql.delta.DeltaOperations.ManualUpdate
import org.apache.spark.sql.delta.actions.{RemoveFile, TableFeatureProtocolUtils}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.commands.AlterTableDropFeatureDeltaCommand
import org.apache.spark.sql.delta.commands.cdc.CDCReader
import org.apache.spark.sql.delta.rowtracking.RowTrackingTestUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.delta.util.{DeltaFileOperations, JsonUtils}
import com.google.common.math.DoubleMath
import org.apache.hadoop.fs.Path

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.sql.{AnalysisException, DataFrame, Encoder, QueryTest, Row}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Cast, EqualTo, Expression, Literal}
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.errors.QueryErrorsBase
import org.apache.spark.sql.execution.datasources.parquet.ParquetTest
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.internal.{LegacyBehaviorPolicy, SQLConf}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.util.ManualClock

/**
 * Suite covering the type widening table feature.
 */
class DeltaTypeWideningSuite
  extends QueryTest
    with ParquetTest
    with RowTrackingTestUtils
    with DeltaSQLCommandTest
    with DeltaTypeWideningTestMixin
    with DeltaTypeWideningDropFeatureTestMixin
    with DeltaTypeWideningAlterTableTests
    with DeltaTypeWideningNestedFieldsTests
    with DeltaTypeWideningCompatibilityTests
    with DeltaTypeWideningMetadataTests
    with DeltaTypeWideningTableFeatureTests
    with DeltaTypeWideningColumnMappingTests
    with DeltaTypeWideningStatsTests
    with DeltaTypeWideningConstraintsTests
    with DeltaTypeWideningGeneratedColumnTests

/**
 * Test mixin that enables type widening by default for all tests in the suite.
 */
trait DeltaTypeWideningTestMixin extends SharedSparkSession with DeltaDMLTestUtils {
  self: QueryTest =>

  import testImplicits._

  protected override def sparkConf: SparkConf = {
    super.sparkConf
      .set(DeltaConfigs.ENABLE_TYPE_WIDENING.defaultTablePropertyKey, "true")
      .set(TableFeatureProtocolUtils.defaultPropertyKey(TimestampNTZTableFeature), "supported")
      // Ensure we don't silently cast test inputs to null on overflow.
      .set(SQLConf.ANSI_ENABLED.key, "true")
      // Rebase mode must be set explicitly to allow writing dates before 1582-10-15.
      .set(SQLConf.PARQUET_REBASE_MODE_IN_WRITE.key, LegacyBehaviorPolicy.CORRECTED.toString)
  }

  /** Enable (or disable) type widening for the table under the given path. */
  protected def enableTypeWidening(tablePath: String, enabled: Boolean = true): Unit =
    sql(s"ALTER TABLE delta.`$tablePath` " +
          s"SET TBLPROPERTIES('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = '${enabled.toString}')")

  /** Whether the test table supports the type widening table feature. */
  def isTypeWideningSupported: Boolean = {
    TypeWidening.isSupported(deltaLog.update().protocol)
  }

  /** Whether the type widening table property is enabled on the test table. */
  def isTypeWideningEnabled: Boolean = {
    val snapshot = deltaLog.update()
    TypeWidening.isEnabled(snapshot.protocol, snapshot.metadata)
  }

  /** Short-hand to create type widening metadata for struct fields. */
  protected def typeWideningMetadata(
      version: Long,
      from: AtomicType,
      to: AtomicType,
      path: Seq[String] = Seq.empty): Metadata =
    new MetadataBuilder()
      .putMetadataArray(
        "delta.typeChanges", Array(TypeChange(version, from, to, path).toMetadata))
      .build()

  def addSingleFile[T: Encoder](values: Seq[T], dataType: DataType): Unit =
      append(values.toDF("a").select(col("a").cast(dataType)).repartition(1))

  /**
   * Similar to `QueryTest.checkAnswer` but using fuzzy equality for double values. This is needed
   * because double partition values are serialized as string leading to loss of precision. Also
   * `checkAnswer` treats -0f and 0f as different values without tolerance.
   */
  def checkAnswerWithTolerance(
      actualDf: DataFrame,
      expectedDf: DataFrame,
      toType: DataType,
      tolerance: Double = 0.001)
    : Unit = {
    // Widening to float isn't supported so only handle double here.
    if (toType == DoubleType) {
      val actual = actualDf.sort("value").collect()
      val expected = expectedDf.sort("value").collect()
      assert(actual.length === expected.length, s"Wrong result: $actual did not equal $expected")

      actual.zip(expected).foreach { case (a, e) =>
        val expectedValue = e.getAs[Double]("value")
        val actualValue = a.getAs[Double]("value")
        val absTolerance = if (expectedValue.isNaN || expectedValue.isInfinity) {
          0
        } else {
          tolerance * Math.abs(expectedValue)
        }
        assert(
          DoubleMath.fuzzyEquals(actualValue, expectedValue, absTolerance),
          s"$actualValue did not equal $expectedValue"
        )
      }
    } else {
      checkAnswer(actualDf, expectedDf)
    }
  }
}

/**
 * Mixin trait containing helpers to test dropping the type widening table feature.
 */
trait DeltaTypeWideningDropFeatureTestMixin
    extends QueryTest
    with SharedSparkSession
    with DeltaDMLTestUtils {

  /** Expected outcome of dropping the type widening table feature. */
  object ExpectedOutcome extends Enumeration {
    val SUCCESS, FAIL_CURRENT_VERSION_USES_FEATURE, FAIL_HISTORICAL_VERSION_USES_FEATURE = Value
  }

  /**
   * Helper method to drop the type widening table feature and check for an expected outcome.
   * Validates in particular that the right number of files were rewritten and that the rewritten
   * files all contain the expected type for specified columns.
   */
  def dropTableFeature(
      expectedOutcome: ExpectedOutcome.Value,
      expectedNumFilesRewritten: Long,
      expectedColumnTypes: Map[String, DataType]): Unit = {
    val snapshot = deltaLog.update()
    // Need to directly call ALTER TABLE command to pass our deltaLog with manual clock.
    val dropFeature = AlterTableDropFeatureDeltaCommand(
      DeltaTableV2(spark, deltaLog.dataPath),
      TypeWideningTableFeature.name)

    expectedOutcome match {
      case ExpectedOutcome.SUCCESS =>
        dropFeature.run(spark)
      case ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE =>
        checkError(
          exception = intercept[DeltaTableFeatureException] { dropFeature.run(spark) },
          errorClass = "DELTA_FEATURE_DROP_WAIT_FOR_RETENTION_PERIOD",
          parameters = Map(
            "feature" -> TypeWideningTableFeature.name,
            "logRetentionPeriodKey" -> DeltaConfigs.LOG_RETENTION.key,
            "logRetentionPeriod" -> DeltaConfigs.LOG_RETENTION
              .fromMetaData(snapshot.metadata).toString,
            "truncateHistoryLogRetentionPeriod" ->
              DeltaConfigs.TABLE_FEATURE_DROP_TRUNCATE_HISTORY_LOG_RETENTION
                .fromMetaData(snapshot.metadata).toString)
        )
      case ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE =>
        checkError(
          exception = intercept[DeltaTableFeatureException] { dropFeature.run(spark) },
          errorClass = "DELTA_FEATURE_DROP_HISTORICAL_VERSIONS_EXIST",
          parameters = Map(
            "feature" -> TypeWideningTableFeature.name,
            "logRetentionPeriodKey" -> DeltaConfigs.LOG_RETENTION.key,
            "logRetentionPeriod" -> DeltaConfigs.LOG_RETENTION
              .fromMetaData(snapshot.metadata).toString,
            "truncateHistoryLogRetentionPeriod" ->
              DeltaConfigs.TABLE_FEATURE_DROP_TRUNCATE_HISTORY_LOG_RETENTION
                .fromMetaData(snapshot.metadata).toString)
        )
    }

    // Check the number of files rewritten.
    assert(getNumRemoveFilesSinceVersion(snapshot.version + 1) === expectedNumFilesRewritten,
      s"Expected $expectedNumFilesRewritten file(s) to be rewritten but found " +
        s"${getNumRemoveFilesSinceVersion(snapshot.version + 1)} rewritten file(s).")
    assert(!TypeWideningMetadata.containsTypeWideningMetadata(deltaLog.update().schema))

    // Check that all files now contain the expected data types.
    expectedColumnTypes.foreach { case (colName, expectedType) =>
      withSQLConf("spark.databricks.delta.formatCheck.enabled" -> "false") {
        deltaLog.update().filesForScan(Seq.empty, keepNumRecords = false).files.foreach { file =>
          val filePath = DeltaFileOperations.absolutePath(deltaLog.dataPath.toString, file.path)
          val data = spark.read.parquet(filePath.toString)
          val physicalColName = DeltaColumnMapping.getPhysicalName(snapshot.schema(colName))
          assert(data.schema(physicalColName).dataType === expectedType,
            s"File with values ${data.collect().mkString(", ")} wasn't rewritten.")
        }
      }
    }
  }

  /** Get the number of remove actions committed since the given table version (included). */
  def getNumRemoveFilesSinceVersion(version: Long): Long =
    deltaLog
      .getChanges(startVersion = version)
      .flatMap { case (_, actions) => actions }
      .collect { case r: RemoveFile => r }
      .size
}

/**
 * Trait collecting supported and unsupported type change test cases.
 */
trait DeltaTypeWideningTestCases extends TypeWideningTestCasesShims { self: SharedSparkSession =>
  import testImplicits._

  /**
   * Represents the input of a type change test.
   * @param fromType         The original type of the column 'value' in the test table.
   * @param toType           The type to use when changing the type of column 'value'.
   */
  abstract class TypeEvolutionTestCase(
      val fromType: DataType,
      val toType: DataType) {
    /** The initial values to insert with type `fromType` in column 'value' after table creation. */
    def initialValuesDF: DataFrame
    /** Additional values to insert after changing the type of the column 'value' to `toType`. */
    def additionalValuesDF: DataFrame
    /** Expected content of the table after inserting the additional values. */
    def expectedResult: DataFrame
  }

  /**
   * Represents the input of a supported type change test. Handles converting the test values from
   * scala types to a dataframe.
   */
  case class SupportedTypeEvolutionTestCase[
      FromType  <: DataType, ToType <: DataType,
      FromVal: Encoder, ToVal: Encoder
    ](
      override val fromType: FromType,
      override val toType: ToType,
      initialValues: Seq[FromVal],
      additionalValues: Seq[ToVal]
  ) extends TypeEvolutionTestCase(fromType, toType) {
    override def initialValuesDF: DataFrame =
      initialValues.toDF("value").select($"value".cast(fromType))

    override def additionalValuesDF: DataFrame =
      additionalValues.toDF("value").select($"value".cast(toType))

    override def expectedResult: DataFrame =
      initialValuesDF.union(additionalValuesDF).select($"value".cast(toType))
  }

  /**
   * Represents the input of an unsupported type change test. Handles converting the test values
   * from scala types to a dataframe. Additional values to insert are always empty since the type
   * change is expected to fail.
   */
  case class UnsupportedTypeEvolutionTestCase[
    FromType  <: DataType, ToType <: DataType, FromVal : Encoder](
      override val fromType: FromType,
      override val toType: ToType,
      initialValues: Seq[FromVal]) extends TypeEvolutionTestCase(fromType, toType) {
    override def initialValuesDF: DataFrame =
      initialValues.toDF("value").select($"value".cast(fromType))

    override def additionalValuesDF: DataFrame =
      spark.createDataFrame(
        sparkContext.emptyRDD[Row],
        new StructType().add(StructField("value", toType)))

    override def expectedResult: DataFrame =
      initialValuesDF.select($"value".cast(toType))
  }
}

/**
 * Trait collecting a subset of tests providing core coverage for type widening using ALTER TABLE
 * CHANGE COLUMN TYPE.
 */
trait DeltaTypeWideningAlterTableTests extends QueryErrorsBase with DeltaTypeWideningTestCases {
  self: QueryTest with ParquetTest with DeltaTypeWideningTestMixin =>

  import testImplicits._

  for {
    testCase <- supportedTestCases ++ alterTableOnlySupportedTestCases
    partitioned <- BOOLEAN_DOMAIN
  } {
    test(s"type widening ${testCase.fromType.sql} -> ${testCase.toType.sql}, " +
      s"partitioned=$partitioned") {
      def writeData(df: DataFrame): Unit = if (partitioned) {
        // The table needs to have at least 1 non-partition column, use a dummy one.
        append(df.withColumn("dummy", lit(1)), partitionBy = Seq("value"))
      } else {
        append(df)
      }

      writeData(testCase.initialValuesDF)
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN value TYPE ${testCase.toType.sql}")
      withAllParquetReaders {
        assert(readDeltaTable(tempPath).schema("value").dataType === testCase.toType)
        checkAnswerWithTolerance(
          actualDf = readDeltaTable(tempPath).select("value"),
          expectedDf = testCase.initialValuesDF.select($"value".cast(testCase.toType)),
          toType = testCase.toType
        )
      }
      writeData(testCase.additionalValuesDF)
      withAllParquetReaders {
        checkAnswerWithTolerance(
          actualDf = readDeltaTable(tempPath).select("value"),
          expectedDf = testCase.expectedResult.select($"value".cast(testCase.toType)),
          toType = testCase.toType
        )
      }
    }
  }

  for {
    testCase <- unsupportedTestCases
    partitioned <- BOOLEAN_DOMAIN
  } {
    test(s"unsupported type changes ${testCase.fromType.sql} -> ${testCase.toType.sql}, " +
      s"partitioned=$partitioned") {
      if (partitioned) {
        // The table needs to have at least 1 non-partition column, use a dummy one.
        append(testCase.initialValuesDF.withColumn("dummy", lit(1)), partitionBy = Seq("value"))
      } else {
        append(testCase.initialValuesDF)
      }

      val alterTableSql =
        s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN value TYPE ${testCase.toType.sql}"

      // Type changes that aren't upcast are rejected early during analysis by Spark, while upcasts
      // are rejected in Delta when the ALTER TABLE command is executed.
      if (Cast.canUpCast(testCase.fromType, testCase.toType)) {
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql(alterTableSql)
          },
          errorClass = "DELTA_UNSUPPORTED_ALTER_TABLE_CHANGE_COL_OP",
          sqlState = None,
          parameters = Map(
            "fieldPath" -> "value",
            "oldField" -> testCase.fromType.sql,
            "newField" -> testCase.toType.sql)
        )
      } else {
        checkError(
          exception = intercept[AnalysisException] {
            sql(alterTableSql)
          },
          errorClass = "NOT_SUPPORTED_CHANGE_COLUMN",
          sqlState = None,
          parameters = Map(
            "table" -> s"`spark_catalog`.`delta`.`$tempPath`",
            "originName" -> toSQLId("value"),
            "originType" -> toSQLType(testCase.fromType),
            "newName" -> toSQLId("value"),
            "newType" -> toSQLType(testCase.toType)),
          context = ExpectedContext(
            fragment = alterTableSql,
            start = 0,
            stop = alterTableSql.length - 1)
        )
      }
    }
  }

  test("type widening using ALTER TABLE REPLACE COLUMNS") {
    append(Seq(1, 2).toDF("value").select($"value".cast(ShortType)))
    assert(readDeltaTable(tempPath).schema === new StructType().add("value", ShortType))
    sql(s"ALTER TABLE delta.`$tempPath` REPLACE COLUMNS (value INT)")
    assert(readDeltaTable(tempPath).schema ===
      new StructType()
        .add("value", IntegerType, nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "short")
              .putLong("tableVersion", 1)
              .build()
          )).build()))
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2)))
    append(Seq(3, 4).toDF("value"))
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2), Row(3), Row(4)))
  }

  test("type widening type change metrics") {
    sql(s"CREATE TABLE delta.`$tempDir` (a byte) USING DELTA")
    val usageLogs = Log4jUsageLogger.track {
      sql(s"ALTER TABLE delta.`$tempDir` CHANGE COLUMN a TYPE int")
    }

    val metrics = filterUsageRecords(usageLogs, "delta.typeWidening.typeChanges")
      .map(r => JsonUtils.fromJson[Map[String, Seq[Map[String, String]]]](r.blob))
      .head

    assert(metrics("changes") === Seq(
      Map(
        "fromType" -> "TINYINT",
        "toType" -> "INT"
      ))
    )
  }
}

/**
 * Tests covering type changes on nested fields in structs, maps and arrays.
 */
trait DeltaTypeWideningNestedFieldsTests {
  self: QueryTest with ParquetTest with DeltaTypeWideningTestMixin
    with SharedSparkSession =>

  import testImplicits._

  /** Create a table with a struct, map and array for each test. */
  protected def createNestedTable(): Unit = {
    sql(s"CREATE TABLE delta.`$tempPath` " +
      "(s struct<a: byte>, m map<byte, short>, a array<short>) USING DELTA")
    append(Seq((1, 2, 3, 4))
      .toDF("a", "b", "c", "d")
      .selectExpr(
        "named_struct('a', cast(a as byte)) as s",
        "map(cast(b as byte), cast(c as short)) as m",
        "array(cast(d as short)) as a"))

    assert(readDeltaTable(tempPath).schema === new StructType()
      .add("s", new StructType().add("a", ByteType))
      .add("m", MapType(ByteType, ShortType))
      .add("a", ArrayType(ShortType)))
  }

  test("unsupported ALTER TABLE CHANGE COLUMN on non-leaf fields") {
    createNestedTable()
    // Running ALTER TABLE CHANGE COLUMN on non-leaf fields is invalid.
    var alterTableSql = s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN s TYPE struct<a: short>"
    checkError(
      exception = intercept[AnalysisException] { sql(alterTableSql) },
      errorClass = "CANNOT_UPDATE_FIELD.STRUCT_TYPE",
      parameters = Map(
        "table" -> s"`spark_catalog`.`delta`.`$tempPath`",
        "fieldName" -> "`s`"
      ),
      context = ExpectedContext(
        fragment = alterTableSql,
        start = 0,
        stop = alterTableSql.length - 1)
    )

    alterTableSql = s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN m TYPE map<int, int>"
    checkError(
      exception = intercept[AnalysisException] { sql(alterTableSql) },
      errorClass = "CANNOT_UPDATE_FIELD.MAP_TYPE",
      parameters = Map(
        "table" -> s"`spark_catalog`.`delta`.`$tempPath`",
        "fieldName" -> "`m`"
      ),
      context = ExpectedContext(
        fragment = alterTableSql,
        start = 0,
        stop = alterTableSql.length - 1)
    )

    alterTableSql = s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE array<int>"
    checkError(
      exception = intercept[AnalysisException] { sql(alterTableSql) },
      errorClass = "CANNOT_UPDATE_FIELD.ARRAY_TYPE",
      parameters = Map(
        "table" -> s"`spark_catalog`.`delta`.`$tempPath`",
        "fieldName" -> "`a`"
      ),
      context = ExpectedContext(
        fragment = alterTableSql,
        start = 0,
        stop = alterTableSql.length - 1)
    )
  }

  test("type widening with ALTER TABLE on nested fields") {
    createNestedTable()
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN s.a TYPE short")
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN m.key TYPE int")
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN m.value TYPE int")
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a.element TYPE int")

    assert(readDeltaTable(tempPath).schema === new StructType()
      .add("s", new StructType()
        .add("a", ShortType, nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "short")
              .putString("fromType", "byte")
              .putLong("tableVersion", 2)
              .build()
          )).build()))
      .add("m", MapType(IntegerType, IntegerType), nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "byte")
              .putLong("tableVersion", 3)
              .putString("fieldPath", "key")
              .build(),
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "short")
              .putLong("tableVersion", 4)
              .putString("fieldPath", "value")
              .build()
          )).build())
      .add("a", ArrayType(IntegerType), nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "short")
              .putLong("tableVersion", 5)
              .putString("fieldPath", "element")
              .build()
          )).build()))

    append(Seq((5, 6, 7, 8))
      .toDF("a", "b", "c", "d")
        .selectExpr("named_struct('a', cast(a as short)) as s", "map(b, c) as m", "array(d) as a"))

    checkAnswer(
      readDeltaTable(tempPath),
      Seq((1, 2, 3, 4), (5, 6, 7, 8))
        .toDF("a", "b", "c", "d")
        .selectExpr("named_struct('a', cast(a as short)) as s", "map(b, c) as m", "array(d) as a"))
  }

  test("type widening using ALTER TABLE REPLACE COLUMNS on nested fields") {
    createNestedTable()
    sql(s"ALTER TABLE delta.`$tempPath` REPLACE COLUMNS " +
      "(s struct<a: short>, m map<int, int>, a array<int>)")
    assert(readDeltaTable(tempPath).schema === new StructType()
      .add("s", new StructType()
        .add("a", ShortType, nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "short")
              .putString("fromType", "byte")
              .putLong("tableVersion", 2)
              .build()
          )).build()))
      .add("m", MapType(IntegerType, IntegerType), nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "byte")
              .putLong("tableVersion", 2)
              .putString("fieldPath", "key")
              .build(),
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "short")
              .putLong("tableVersion", 2)
              .putString("fieldPath", "value")
              .build()
          )).build())
      .add("a", ArrayType(IntegerType), nullable = true, metadata = new MetadataBuilder()
          .putMetadataArray("delta.typeChanges", Array(
            new MetadataBuilder()
              .putString("toType", "integer")
              .putString("fromType", "short")
              .putLong("tableVersion", 2)
              .putString("fieldPath", "element")
              .build()
          )).build()))

    append(Seq((5, 6, 7, 8))
      .toDF("a", "b", "c", "d")
        .selectExpr("named_struct('a', cast(a as short)) as s", "map(b, c) as m", "array(d) as a"))

    checkAnswer(
      readDeltaTable(tempPath),
      Seq((1, 2, 3, 4), (5, 6, 7, 8))
        .toDF("a", "b", "c", "d")
        .selectExpr("named_struct('a', cast(a as short)) as s", "map(b, c) as m", "array(d) as a"))
  }
}

/** Tests covering type widening compatibility with other delta features. */
trait DeltaTypeWideningCompatibilityTests {
  self: DeltaTypeWideningTestMixin with QueryTest with DeltaDMLTestUtils =>

  import testImplicits._

  test("reading CDF with a type change") {
    withSQLConf((DeltaConfigs.CHANGE_DATA_FEED.defaultTablePropertyKey, "true")) {
      sql(s"CREATE TABLE delta.`$tempPath` (a smallint) USING DELTA")
    }
    append(Seq(1, 2).toDF("a").select($"a".cast(ShortType)))
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE int")
    append(Seq(3, 4).toDF("a"))

    def readCDF(start: Long, end: Long): DataFrame =
      CDCReader
        .changesToBatchDF(deltaLog, start, end, spark)
        .drop(CDCReader.CDC_COMMIT_TIMESTAMP)
        .drop(CDCReader.CDC_COMMIT_VERSION)

    checkErrorMatchPVals(
      exception = intercept[DeltaUnsupportedOperationException] {
        readCDF(start = 1, end = 1).collect()
      },
      errorClass = "DELTA_CHANGE_DATA_FEED_INCOMPATIBLE_DATA_SCHEMA",
      parameters = Map(
        "start" -> "1",
        "end" -> "1",
        "readSchema" -> ".*",
        "readVersion" -> "3",
        "incompatibleVersion" -> "1",
        "config" -> ".*defaultSchemaModeForColumnMappingTable"
      )
    )
    checkAnswer(readCDF(start = 3, end = 3), Seq(Row(3, "insert"), Row(4, "insert")))
  }

  test("reading CDF with a type change using read schema from before the change") {
    withSQLConf((DeltaConfigs.CHANGE_DATA_FEED.defaultTablePropertyKey, "true")) {
      sql(s"CREATE TABLE delta.`$tempPath` (a smallint) USING DELTA")
    }
    append(Seq(1, 2).toDF("a").select($"a".cast(ShortType)))
    val readSchemaSnapshot = deltaLog.update()
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE int")
    append(Seq(3, 4).toDF("a"))

    def readCDF(start: Long, end: Long): DataFrame =
      CDCReader
        .changesToBatchDF(
          deltaLog,
          start,
          end,
          spark,
          readSchemaSnapshot = Some(readSchemaSnapshot)
        )
        .drop(CDCReader.CDC_COMMIT_TIMESTAMP)
        .drop(CDCReader.CDC_COMMIT_VERSION)

    checkAnswer(readCDF(start = 1, end = 1), Seq(Row(1, "insert"), Row(2, "insert")))
    checkErrorMatchPVals(
      exception = intercept[DeltaUnsupportedOperationException] {
        readCDF(start = 1, end = 3)
      },
      errorClass = "DELTA_CHANGE_DATA_FEED_INCOMPATIBLE_SCHEMA_CHANGE",
      parameters = Map(
        "start" -> "1",
        "end" -> "3",
        "readSchema" -> ".*",
        "readVersion" -> "1",
        "incompatibleVersion" -> "2"
      )
    )
  }

  test("time travel read before type change") {
    sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
    append(Seq(1).toDF("a").select($"a".cast(ByteType)))
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE smallint")
    append(Seq(2).toDF("a").select($"a".cast(ShortType)))

    val previousVersion = sql(s"SELECT a FROM delta.`$tempPath` VERSION AS OF 1")
    assert(previousVersion.schema("a").dataType === ByteType)
    checkAnswer(previousVersion, Seq(Row(1)))

    val latestVersion = sql(s"SELECT a FROM delta.`$tempPath`")
    assert(latestVersion.schema("a").dataType === ShortType)
    checkAnswer(latestVersion, Seq(Row(1), Row(2)))
  }
}

/**
 * Tests related to recording type change information as metadata in the table schema. For
 * lower-level tests, see [[DeltaTypeWideningMetadataSuite]].
 */
trait DeltaTypeWideningMetadataTests {
  self: QueryTest with ParquetTest with DeltaTypeWideningTestMixin =>

  def testTypeWideningMetadata(name: String)(
      initialSchema: String,
      typeChanges: Seq[(String, String)],
      expectedJsonSchema: String): Unit =
    test(name) {
      sql(s"CREATE TABLE delta.`$tempPath` ($initialSchema) USING DELTA")
      typeChanges.foreach { case (fieldName, newType) =>
        sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN $fieldName TYPE $newType")
      }

      // Parse the schemas as JSON to ignore whitespaces and field order.
      val actualSchema = JsonUtils.fromJson[Map[String, Any]](readDeltaTable(tempPath).schema.json)
      val expectedSchema = JsonUtils.fromJson[Map[String, Any]](expectedJsonSchema)
      assert(actualSchema === expectedSchema,
        s"${readDeltaTable(tempPath).schema.prettyJson} did not equal $expectedJsonSchema"
      )
    }

  testTypeWideningMetadata("change top-level column type short->int")(
    initialSchema = "a short",
    typeChanges = Seq("a" -> "int"),
    expectedJsonSchema =
      """{
      "type": "struct",
      "fields": [{
        "name": "a",
        "type": "integer",
        "nullable": true,
        "metadata": {
          "delta.typeChanges": [{
            "toType": "integer",
            "fromType": "short",
            "tableVersion": 1
          }]
        }
      }]}""".stripMargin)

  testTypeWideningMetadata("change top-level column type twice byte->short->int")(
    initialSchema = "a byte",
    typeChanges = Seq("a" -> "short", "a" -> "int"),
    expectedJsonSchema =
      """{
      "type": "struct",
      "fields": [{
        "name": "a",
        "type": "integer",
        "nullable": true,
        "metadata": {
          "delta.typeChanges": [{
            "toType": "short",
            "fromType": "byte",
            "tableVersion": 1
          },{
            "toType": "integer",
            "fromType": "short",
            "tableVersion": 2
          }]
        }
      }]}""".stripMargin)

  testTypeWideningMetadata("change type in map key and in struct in map value")(
    initialSchema = "a map<byte, struct<b: byte>>",
    typeChanges = Seq("a.key" -> "int", "a.value.b" -> "short"),
    expectedJsonSchema =
      """{
      "type": "struct",
      "fields": [{
        "name": "a",
        "type": {
          "type": "map",
          "keyType": "integer",
          "valueType": {
            "type": "struct",
            "fields": [{
              "name": "b",
              "type": "short",
              "nullable": true,
              "metadata": {
                "delta.typeChanges": [{
                  "toType": "short",
                  "fromType": "byte",
                  "tableVersion": 2
                }]
              }
            }]
          },
          "valueContainsNull": true
        },
        "nullable": true,
        "metadata": {
          "delta.typeChanges": [{
            "toType": "integer",
            "fromType": "byte",
            "tableVersion": 1,
            "fieldPath": "key"
          }]
        }
      }
    ]}""".stripMargin)


  testTypeWideningMetadata("change type in array and in struct in array")(
    initialSchema = "a array<byte>, b array<struct<c: short>>",
    typeChanges = Seq("a.element" -> "short", "b.element.c" -> "int"),
    expectedJsonSchema =
      """{
      "type": "struct",
      "fields": [{
        "name": "a",
        "type": {
          "type": "array",
          "elementType": "short",
          "containsNull": true
        },
        "nullable": true,
        "metadata": {
          "delta.typeChanges": [{
            "toType": "short",
            "fromType": "byte",
            "tableVersion": 1,
            "fieldPath": "element"
          }]
        }
      },
      {
        "name": "b",
        "type": {
          "type": "array",
          "elementType":{
            "type": "struct",
            "fields": [{
              "name": "c",
              "type": "integer",
              "nullable": true,
              "metadata": {
                "delta.typeChanges": [{
                  "toType": "integer",
                  "fromType": "short",
                  "tableVersion": 2
                }]
              }
            }]
          },
          "containsNull": true
        },
        "nullable": true,
        "metadata": { }
      }
    ]}""".stripMargin)
}

/**
 * Trait collecting tests for stats and data skipping with type changes.
 */
trait DeltaTypeWideningStatsTests {
  self: QueryTest with DeltaTypeWideningTestMixin =>
  import testImplicits._

  /**
   * Helper to create a table and run tests while enabling/disabling storing stats as JSON string or
   * strongly-typed structs in checkpoint files. Creates a
   */
  def testStats(
      name: String,
      partitioned: Boolean,
      jsonStatsEnabled: Boolean,
      structStatsEnabled: Boolean)(
      body: => Unit): Unit =
    test(s"$name, partitioned=$partitioned, jsonStatsEnabled=$jsonStatsEnabled, " +
        s"structStatsEnabled=$structStatsEnabled") {
      withSQLConf(
        DeltaConfigs.CHECKPOINT_WRITE_STATS_AS_JSON.defaultTablePropertyKey ->
          jsonStatsEnabled.toString,
        DeltaConfigs.CHECKPOINT_WRITE_STATS_AS_STRUCT.defaultTablePropertyKey ->
          structStatsEnabled.toString
      ) {
        val partitionStr = if (partitioned) "PARTITIONED BY (a)" else ""
        sql(s"""
            |CREATE TABLE delta.`$tempPath` (a smallint, dummy int DEFAULT 1)
            |USING DELTA
            |$partitionStr
            |TBLPROPERTIES('delta.feature.allowColumnDefaults' = 'supported')
          """.stripMargin)
        body
      }
    }

  /** Returns the latest checkpoint for the test table. */
  def getLatestCheckpoint: LastCheckpointInfo =
    deltaLog.readLastCheckpointFile().getOrElse {
      fail("Expected the table to have a checkpoint but it didn't")
    }

  /** Returns the type used to store JSON stats in the checkpoint if JSON stats are present. */
  def getJsonStatsType(checkpoint: LastCheckpointInfo): Option[DataType] =
    checkpoint.checkpointSchema.flatMap {
      _.findNestedField(Seq("add", "stats"))
    }.map(_._2.dataType)

  /**
   * Returns the type used to store parsed partition values for the given column in the checkpoint
   * if these are present.
   */
  def getPartitionValuesType(checkpoint: LastCheckpointInfo, colName: String)
    : Option[DataType] = {
    checkpoint.checkpointSchema.flatMap {
      _.findNestedField(Seq("add", "partitionValues_parsed", colName))
    }.map(_._2.dataType)
  }

  /**
   * Checks that stats and parsed partition values are stored in the checkpoint when enabled and
   * that their type matches the expected type.
   */
  def checkCheckpointStats(
      checkpoint: LastCheckpointInfo,
      colName: String,
      colType: DataType,
      partitioned: Boolean,
      jsonStatsEnabled: Boolean,
      structStatsEnabled: Boolean): Unit = {
    val expectedJsonStatsType = if (jsonStatsEnabled) Some(StringType) else None
    assert(getJsonStatsType(checkpoint) === expectedJsonStatsType)

    val expectedPartitionStats = if (partitioned && structStatsEnabled) Some(colType) else None
    assert(getPartitionValuesType(checkpoint, colName) === expectedPartitionStats)
  }

  /**
   * Reads the test table filtered by the given value and checks that files are skipped as expected.
   */
  def checkFileSkipping(filterValue: Any, expectedFilesRead: Long): Unit = {
    val dataFilter: Expression =
      EqualTo(AttributeReference("a", IntegerType)(), Literal(filterValue))
    val files = deltaLog.update().filesForScan(Seq(dataFilter), keepNumRecords = false).files
    assert(files.size === expectedFilesRead, s"Expected $expectedFilesRead files to be " +
      s"read but read ${files.size} files.")
  }

  for {
    partitioned <- BOOLEAN_DOMAIN
    jsonStatsEnabled <- BOOLEAN_DOMAIN
    structStatsEnabled <- BOOLEAN_DOMAIN
  }
  testStats(s"data skipping after type change", partitioned, jsonStatsEnabled, structStatsEnabled) {
    addSingleFile(Seq(1), ShortType)
    addSingleFile(Seq(2), ShortType)
    deltaLog.checkpoint()
    assert(readDeltaTable(tempPath).schema("a").dataType === ShortType)
    val initialCheckpoint = getLatestCheckpoint
    checkCheckpointStats(
      initialCheckpoint, "a", ShortType, partitioned, jsonStatsEnabled, structStatsEnabled)

    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    addSingleFile(Seq(Int.MinValue), IntegerType)

    var checkpoint = getLatestCheckpoint
    // Ensure there's no new checkpoint after the type change.
    assert(getLatestCheckpoint.semanticEquals(initialCheckpoint))

    val canSkipFiles = jsonStatsEnabled || partitioned

    // The last file added isn't part of the checkpoint, it always has stats that can be used for
    // skipping even when checkpoint stats can't be used for skipping.
    checkFileSkipping(filterValue = 1, expectedFilesRead = if (canSkipFiles) 1 else 2)
    checkAnswer(readDeltaTable(tempPath).filter("a = 1"), Row(1, 1))

    checkFileSkipping(filterValue = Int.MinValue, expectedFilesRead = if (canSkipFiles) 1 else 3)
    checkAnswer(readDeltaTable(tempPath).filter(s"a = ${Int.MinValue}"), Row(Int.MinValue, 1))

    // Trigger a new checkpoint after the type change and re-check data skipping.
    deltaLog.checkpoint()
    checkpoint = getLatestCheckpoint
    assert(!checkpoint.semanticEquals(initialCheckpoint))
    checkCheckpointStats(
      checkpoint, "a", IntegerType, partitioned, jsonStatsEnabled, structStatsEnabled)
    // When checkpoint stats are completely disabled, the last file added can't be skipped anymore.
    checkFileSkipping(filterValue = 1, expectedFilesRead = if (canSkipFiles) 1 else 3)
    checkFileSkipping(filterValue = Int.MinValue, expectedFilesRead = if (canSkipFiles) 1 else 3)
  }

  for {
    partitioned <- BOOLEAN_DOMAIN
    jsonStatsEnabled <- BOOLEAN_DOMAIN
    structStatsEnabled <- BOOLEAN_DOMAIN
  }
  testStats(s"metadata-only query", partitioned, jsonStatsEnabled, structStatsEnabled) {
    addSingleFile(Seq(1), ShortType)
    addSingleFile(Seq(2), ShortType)
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    addSingleFile(Seq(Int.MinValue), IntegerType)
    addSingleFile(Seq(Int.MaxValue), IntegerType)

    // Check that collecting aggregates using a metadata-only query works after the type change.
    val resultDf = sql(s"SELECT MIN(a), MAX(a), COUNT(*) FROM delta.`$tempPath`")
    val isMetadataOnly = resultDf.queryExecution.optimizedPlan.collectFirst {
      case l: LocalRelation => l
    }.nonEmpty
    assert(isMetadataOnly, "Expected the query to be metadata-only")
    checkAnswer(resultDf, Row(Int.MinValue, Int.MaxValue, 4))
  }
}

/**
 * Tests covering adding and removing the type widening table feature. Dropping the table feature
 * also includes rewriting data files with the old type and removing type widening metadata.
 */
trait DeltaTypeWideningTableFeatureTests {
  self: QueryTest
    with ParquetTest
    with RowTrackingTestUtils
    with DeltaTypeWideningTestMixin
    with DeltaTypeWideningDropFeatureTestMixin =>

  import testImplicits._

  /** Clock used to advance past the retention period when dropping the table feature. */
  var clock: ManualClock = _

  protected def setupManualClock(): Unit = {
    clock = new ManualClock(System.currentTimeMillis())
    // Override the (cached) delta log with one using our manual clock.
    DeltaLog.clearCache()
    deltaLog = DeltaLog.forTable(spark, new Path(tempPath), clock)
  }

  /**
   * Use this after dropping the table feature to artificially move the current time to after
   * the table retention period.
   */
  def advancePastRetentionPeriod(): Unit = {
    assert(clock != null, "Must call setupManualClock in tests that are using this method.")
    clock.advance(
      deltaLog.deltaRetentionMillis(deltaLog.update().metadata) +
        TimeUnit.MINUTES.toMillis(5))
  }

  test("enable type widening at table creation then disable it") {
    sql(s"CREATE TABLE delta.`$tempPath` (a int) USING DELTA " +
      s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'true')")
    assert(isTypeWideningSupported)
    assert(isTypeWideningEnabled)
    enableTypeWidening(tempPath, enabled = false)
    assert(isTypeWideningSupported)
    assert(!isTypeWideningEnabled)
  }

  test("enable type widening after table creation then disable it") {
    sql(s"CREATE TABLE delta.`$tempPath` (a int) USING DELTA " +
      s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")
    assert(!isTypeWideningSupported)
    assert(!isTypeWideningEnabled)
    // Setting the property to false shouldn't add the table feature if it's not present.
    enableTypeWidening(tempPath, enabled = false)
    assert(!isTypeWideningSupported)
    assert(!isTypeWideningEnabled)

    enableTypeWidening(tempPath)
    assert(isTypeWideningSupported)
    assert(isTypeWideningEnabled)
    enableTypeWidening(tempPath, enabled = false)
    assert(isTypeWideningSupported)
    assert(!isTypeWideningEnabled)
  }

  test("set table property to incorrect value") {
    val ex = intercept[IllegalArgumentException] {
      sql(s"CREATE TABLE delta.`$tempPath` (a int) USING DELTA " +
        s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'bla')")
    }
    assert(ex.getMessage.contains("For input string: \"bla\""))
    sql(s"CREATE TABLE delta.`$tempPath` (a int) USING DELTA " +
       s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")
    checkError(
      exception = intercept[SparkException] {
        sql(s"ALTER TABLE delta.`$tempPath` " +
          s"SET TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'bla')")
      },
      errorClass = "_LEGACY_ERROR_TEMP_2045",
      parameters = Map(
        "message" -> "For input string: \"bla\""
      )
    )
    assert(!isTypeWideningSupported)
    assert(!isTypeWideningEnabled)
  }

  test("change column type without table feature") {
    sql(s"CREATE TABLE delta.`$tempPath` (a TINYINT) USING DELTA " +
      s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")

    checkError(
      exception = intercept[AnalysisException] {
        sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE SMALLINT")
      },
      errorClass = "DELTA_UNSUPPORTED_ALTER_TABLE_CHANGE_COL_OP",
      parameters = Map(
        "fieldPath" -> "a",
        "oldField" -> "TINYINT",
        "newField" -> "SMALLINT"
      )
    )
  }

  test("change column type with type widening table feature supported but table property set to " +
    "false") {
    sql(s"CREATE TABLE delta.`$tempPath` (a SMALLINT) USING DELTA")
    sql(s"ALTER TABLE delta.`$tempPath` " +
      s"SET TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")

    checkError(
      exception = intercept[AnalysisException] {
        sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
      },
      errorClass = "DELTA_UNSUPPORTED_ALTER_TABLE_CHANGE_COL_OP",
      parameters = Map(
        "fieldPath" -> "a",
        "oldField" -> "SMALLINT",
        "newField" -> "INT"
      )
    )
  }

  test("no-op type changes are always allowed") {
    sql(s"CREATE TABLE delta.`$tempPath` (a int) USING DELTA " +
      s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    enableTypeWidening(tempPath, enabled = true)
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    enableTypeWidening(tempPath, enabled = false)
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
  }

  test("drop unused table feature on empty table") {
    sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
    dropTableFeature(
      expectedOutcome = ExpectedOutcome.SUCCESS,
      expectedNumFilesRewritten = 0,
      expectedColumnTypes = Map("a" -> ByteType)
    )
    checkAnswer(readDeltaTable(tempPath), Seq.empty)
  }

  test("drop feature using sql with multipart identifier") {
    withTempDatabase { databaseName =>
      val tableName = "test_table"
      withTable(tableName) {
        sql(s"CREATE TABLE $databaseName.$tableName (a byte) USING DELTA " +
          s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'true')")
        sql(s"INSERT INTO  $databaseName.$tableName VALUES (1)")
        sql(s"ALTER TABLE $databaseName.$tableName CHANGE COLUMN a TYPE INT")
        sql(s"INSERT INTO  $databaseName.$tableName VALUES (2)")

        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName, Some(databaseName)))

        checkError(
          exception = intercept[DeltaTableFeatureException] {
            sql(s"ALTER TABLE $databaseName.$tableName " +
              s"DROP FEATURE '${TypeWideningTableFeature.name}'"
            ).collect()
          },
          errorClass = "DELTA_FEATURE_DROP_WAIT_FOR_RETENTION_PERIOD",
          parameters = Map(
            "feature" -> TypeWideningTableFeature.name,
            "logRetentionPeriodKey" -> DeltaConfigs.LOG_RETENTION.key,
            "logRetentionPeriod" -> DeltaConfigs.LOG_RETENTION
              .fromMetaData(deltaLog.unsafeVolatileMetadata).toString,
            "truncateHistoryLogRetentionPeriod" ->
              DeltaConfigs.TABLE_FEATURE_DROP_TRUNCATE_HISTORY_LOG_RETENTION
                .fromMetaData(deltaLog.unsafeVolatileMetadata).toString)
        )
      }
    }
  }

  // Rewriting the data when dropping the table feature relies on the default row commit version
  // being set even when row tracking isn't enabled.
  for(rowTrackingEnabled <- BOOLEAN_DOMAIN) {
    test(s"drop unused table feature on table with data, rowTrackingEnabled=$rowTrackingEnabled") {
      sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
      addSingleFile(Seq(1, 2, 3), ByteType)
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.SUCCESS,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> ByteType)
      )
      checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2), Row(3)))
    }

    test(s"drop unused table feature on table with data inserted before adding the table feature," +
      s"rowTrackingEnabled=$rowTrackingEnabled") {
      setupManualClock()
      sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA " +
        s"TBLPROPERTIES ('${DeltaConfigs.ENABLE_TYPE_WIDENING.key}' = 'false')")
      addSingleFile(Seq(1, 2, 3), ByteType)
      enableTypeWidening(tempPath)
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE int")

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 1,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      advancePastRetentionPeriod()
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.SUCCESS,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
    }

    test(s"drop table feature on table with data added only after type change, " +
      s"rowTrackingEnabled=$rowTrackingEnabled") {
      setupManualClock()
      sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE int")
      addSingleFile(Seq(1, 2, 3), IntegerType)

      // We could actually drop the table feature directly here instead of failing by checking that
      // there were no files added before the type change. This may be an expensive check for a rare
      // scenario so we don't do it.
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      advancePastRetentionPeriod()
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.SUCCESS,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2), Row(3)))
    }

    test(s"drop table feature on table with data added before type change, " +
      s"rowTrackingEnabled=$rowTrackingEnabled") {
      setupManualClock()
      sql(s"CREATE TABLE delta.`$tempDir` (a byte) USING DELTA")
      addSingleFile(Seq(1, 2, 3), ByteType)
      sql(s"ALTER TABLE delta.`$tempDir` CHANGE COLUMN a TYPE int")

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 1,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      advancePastRetentionPeriod()
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.SUCCESS,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2), Row(3)))
    }

    test(s"drop table feature on table with data added before type change and fully rewritten " +
      s"after, rowTrackingEnabled=$rowTrackingEnabled") {
      setupManualClock()
      sql(s"CREATE TABLE delta.`$tempDir` (a byte) USING DELTA")
      addSingleFile(Seq(1, 2, 3), ByteType)
      sql(s"ALTER TABLE delta.`$tempDir` CHANGE COLUMN a TYPE int")
      sql(s"UPDATE delta.`$tempDir` SET a = a + 10")

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        // The file was already rewritten in UPDATE.
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )

      advancePastRetentionPeriod()
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.SUCCESS,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      checkAnswer(readDeltaTable(tempPath), Seq(Row(11), Row(12), Row(13)))
    }

    test(s"drop table feature on table with data added before type change and partially " +
      s"rewritten after, rowTrackingEnabled=$rowTrackingEnabled") {
      setupManualClock()
      withRowTrackingEnabled(rowTrackingEnabled) {
        sql(s"CREATE TABLE delta.`$tempDir` (a byte) USING DELTA")
        addSingleFile(Seq(1, 2, 3), ByteType)
        addSingleFile(Seq(4, 5, 6), ByteType)
        sql(s"ALTER TABLE delta.`$tempDir` CHANGE COLUMN a TYPE int")
        sql(s"UPDATE delta.`$tempDir` SET a = a + 10 WHERE a < 4")

        dropTableFeature(
          expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
          // One file was already rewritten in UPDATE, leaving 1 file to rewrite.
          expectedNumFilesRewritten = 1,
          expectedColumnTypes = Map("a" -> IntegerType)
        )

        dropTableFeature(
          expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
          expectedNumFilesRewritten = 0,
          expectedColumnTypes = Map("a" -> IntegerType)
        )

        advancePastRetentionPeriod()
        dropTableFeature(
          expectedOutcome = ExpectedOutcome.SUCCESS,
          expectedNumFilesRewritten = 0,
          expectedColumnTypes = Map("a" -> IntegerType)
        )
        checkAnswer(
          readDeltaTable(tempPath),
          Seq(Row(11), Row(12), Row(13), Row(4), Row(5), Row(6)))
      }
    }
  }

  test("drop feature after a type change with schema evolution") {
    setupManualClock()
    sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
    addSingleFile(Seq(1), ByteType)

    withSQLConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> "true") {
      addSingleFile(Seq(1024), IntegerType)
    }
    assert(readDeltaTable(tempPath).schema("a").dataType === IntegerType)
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(1024)))

    dropTableFeature(
      expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
      expectedNumFilesRewritten = 1,
      expectedColumnTypes = Map("a" -> IntegerType)
    )

    advancePastRetentionPeriod()
    dropTableFeature(
      expectedOutcome = ExpectedOutcome.SUCCESS,
      expectedNumFilesRewritten = 0,
      expectedColumnTypes = Map("a" -> IntegerType)
    )
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(1024)))
  }

  test("unsupported type changes applied to the table") {
    sql(s"CREATE TABLE delta.`$tempDir` (a array<int>) USING DELTA")
    val metadata = new MetadataBuilder()
      .putMetadataArray("delta.typeChanges", Array(
        new MetadataBuilder()
          .putString("toType", "string")
          .putString("fromType", "int")
          .putLong("tableVersion", 2)
          .putString("fieldPath", "element")
          .build()
      )).build()

    // Add an unsupported type change to the table schema. Only an implementation that isn't
    // compliant with the feature specification would allow this.
    deltaLog.withNewTransaction { txn =>
      txn.commit(
        Seq(txn.snapshot.metadata.copy(
          schemaString = new StructType()
            .add("a", StringType, nullable = true, metadata).json
        )),
        ManualUpdate)
    }

    checkError(
      exception = intercept[DeltaIllegalStateException] {
        readDeltaTable(tempPath).collect()
      },
      errorClass = "DELTA_UNSUPPORTED_TYPE_CHANGE_IN_SCHEMA",
      parameters = Map(
        "fieldName" -> "a.element",
        "fromType" -> "INT",
        "toType" -> "STRING"
      )
    )
  }

  test("type widening rewrite metrics") {
    sql(s"CREATE TABLE delta.`$tempDir` (a byte) USING DELTA")
    addSingleFile(Seq(1, 2, 3), ByteType)
    addSingleFile(Seq(4, 5, 6), ByteType)
    sql(s"ALTER TABLE delta.`$tempDir` CHANGE COLUMN a TYPE int")
    // Update a row from the second file to rewrite it. Only the first file still contains the old
    // data type after this.
    sql(s"UPDATE delta.`$tempDir` SET a = a + 10 WHERE a < 4")
    val usageLogs = Log4jUsageLogger.track {
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 1,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
    }

    val metrics = filterUsageRecords(usageLogs, "delta.typeWidening.featureRemoval")
      .map(r => JsonUtils.fromJson[Map[String, String]](r.blob))
      .head

    assert(metrics("downgradeTimeMs").toLong > 0L)
    // Only the first file should get rewritten here since the second file was already rewritten
    // during the UPDATE.
    assert(metrics("numFilesRewritten").toLong === 1L)
    assert(metrics("metadataRemoved").toBoolean)
  }

  test("dropping feature after CLONE correctly rewrite files with old type") {
    withTable("source") {
      sql("CREATE TABLE source (a byte) USING delta")
      sql("INSERT INTO source VALUES (1)")
      sql("INSERT INTO source VALUES (2)")
      sql(s"ALTER TABLE source CHANGE COLUMN a TYPE INT")
      sql("INSERT INTO source VALUES (200)")
      sql(s"CREATE OR REPLACE TABLE delta.`$tempPath` SHALLOW CLONE source")
      checkAnswer(readDeltaTable(tempPath),
        Seq(Row(1), Row(2), Row(200)))

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 3,
        expectedColumnTypes = Map("a" -> IntegerType)
      )
      checkAnswer(readDeltaTable(tempPath),
        Seq(Row(1), Row(2), Row(200)))
    }
  }
  test("RESTORE to before type change") {
    addSingleFile(Seq(1), ShortType)
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    sql(s"UPDATE delta.`$tempPath` SET a = ${Int.MinValue} WHERE a = 1")

    // RESTORE to version 0, before the type change was applied.
    sql(s"RESTORE TABLE delta.`$tempPath` VERSION AS OF 0")
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1)))
    dropTableFeature(
      // There should be no files to rewrite but versions before RESTORE still use the feature.
      expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
      expectedNumFilesRewritten = 0,
      expectedColumnTypes = Map("a" -> ShortType)
    )
  }

  test("dropping feature after RESTORE correctly rewrite files with old type") {
    addSingleFile(Seq(1), ShortType)
    addSingleFile(Seq(2), ShortType)
    sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE INT")
    // Delete the first file which will be added back again with RESTORE.
    sql(s"DELETE FROM delta.`$tempPath` WHERE a = 1")
    addSingleFile(Seq(Int.MinValue), IntegerType)

    // RESTORE to version 2 -> ALTER TABLE CHANGE COLUMN TYPE.
    // The type change is then still present and the first file initially added at version 0 is
    // added back during RESTORE (version 5). That file contains the old type and must be rewritten
    // when the feature is dropped.
    sql(s"RESTORE TABLE delta.`$tempPath` VERSION AS OF 2")
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2)))
    dropTableFeature(
      expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
      // Both files added before the type change must be rewritten.
      expectedNumFilesRewritten = 2,
      expectedColumnTypes = Map("a" -> IntegerType)
    )
    checkAnswer(readDeltaTable(tempPath), Seq(Row(1), Row(2)))
  }
}

/** Trait collecting tests covering type widening + column mapping. */
trait DeltaTypeWideningColumnMappingTests {
    self: QueryTest
    with DeltaTypeWideningTestMixin
    with DeltaTypeWideningDropFeatureTestMixin =>

  import testImplicits._

  for (mappingMode <- Seq(IdMapping.name, NameMapping.name)) {
    test(s"change column type and rename it, mappingMode=$mappingMode") {
      withSQLConf((DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey, mappingMode)) {
        sql(s"CREATE TABLE delta.`$tempPath` (a byte) USING DELTA")
      }
      // Add some data and change type of column `a`.
      addSingleFile(Seq(1), ByteType)
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN a TYPE smallint")
      addSingleFile(Seq(2), ShortType)
      assert(readDeltaTable(tempPath).schema("a").dataType === ShortType)
      checkAnswer(sql(s"SELECT a FROM delta.`$tempPath`"), Seq(Row(1), Row(2)))

      // Rename column `a` to `a (with reserved characters)`, add more data.
      val newColumnName = "a (with reserved characters)"
      sql(s"ALTER TABLE delta.`$tempPath` RENAME COLUMN a TO `$newColumnName`")
      assert(readDeltaTable(tempPath).schema(newColumnName).dataType === ShortType)
      checkAnswer(
        sql(s"SELECT `$newColumnName` FROM delta.`$tempPath`"), Seq(Row(1), Row(2))
      )
      append(Seq(3).toDF(newColumnName).select(col(newColumnName).cast(ShortType)))
      checkAnswer(
        sql(s"SELECT `$newColumnName` FROM delta.`$tempPath`"), Seq(Row(1), Row(2), Row(3))
      )

      // Change column type again, add more data.
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN `$newColumnName` TYPE int")
      assert(
        readDeltaTable(tempPath).schema(newColumnName).dataType === IntegerType)
      append(Seq(4).toDF(newColumnName).select(col(newColumnName).cast(IntegerType)))
      checkAnswer(
        sql(s"SELECT `$newColumnName` FROM delta.`$tempPath`"),
        Seq(Row(1), Row(2), Row(3), Row(4))
      )

      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        // All files except the last one should be rewritten.
        expectedNumFilesRewritten = 3,
        expectedColumnTypes = Map(newColumnName -> IntegerType)
      )
    }

    test(s"dropped column shouldn't cause files to be rewritten, mappingMode=$mappingMode") {
      withSQLConf((DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey, mappingMode)) {
        sql(s"CREATE TABLE delta.`$tempPath` (a byte, b byte) USING DELTA")
      }
      sql(s"INSERT INTO delta.`$tempPath` VALUES (1, 1)")
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN b TYPE int")
      sql(s"INSERT INTO delta.`$tempPath` VALUES (2, 2)")
      sql(s"ALTER TABLE delta.`$tempPath` DROP COLUMN b")
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_HISTORICAL_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 0,
        expectedColumnTypes = Map("a" -> ByteType)
      )
    }

    test(s"swap column names and change type, mappingMode=$mappingMode") {
      withSQLConf((DeltaConfigs.COLUMN_MAPPING_MODE.defaultTablePropertyKey, mappingMode)) {
        sql(s"CREATE TABLE delta.`$tempPath` (a byte, b byte) USING DELTA")
      }
      sql(s"INSERT INTO delta.`$tempPath` VALUES (1, 1)")
      sql(s"ALTER TABLE delta.`$tempPath` CHANGE COLUMN b TYPE int")
      sql(s"INSERT INTO delta.`$tempPath` VALUES (2, 2)")
      sql(s"ALTER TABLE delta.`$tempPath` RENAME COLUMN b TO c")
      sql(s"ALTER TABLE delta.`$tempPath` RENAME COLUMN a TO b")
      sql(s"ALTER TABLE delta.`$tempPath` RENAME COLUMN c TO a")
      sql(s"INSERT INTO delta.`$tempPath` VALUES (3, 3)")
      dropTableFeature(
        expectedOutcome = ExpectedOutcome.FAIL_CURRENT_VERSION_USES_FEATURE,
        expectedNumFilesRewritten = 1,
        expectedColumnTypes = Map(
          "a" -> IntegerType,
          "b" -> ByteType
        )
      )
    }
  }
}

trait DeltaTypeWideningConstraintsTests {
  self: QueryTest with SharedSparkSession =>

  test("not null constraint with type change") {
    withTable("t") {
      sql("CREATE TABLE t (a byte NOT NULL) USING DELTA")
      sql("INSERT INTO t VALUES (1)")
      checkAnswer(sql("SELECT * FROM t"), Row(1))

      // Changing the type of a column with a NOT NULL constraint is allowed.
      sql("ALTER TABLE t CHANGE COLUMN a TYPE SMALLINT")
      assert(sql("SELECT * FROM t").schema("a").dataType === ShortType)

      sql("INSERT INTO t VALUES (2)")
      checkAnswer(sql("SELECT * FROM t"), Seq(Row(1), Row(2)))
    }
  }

  test("check constraint with type change") {
    withTable("t") {
      sql("CREATE TABLE t (a byte, b byte) USING DELTA")
      sql("ALTER TABLE t ADD CONSTRAINT ck CHECK (hash(a) > 0)")
      sql("INSERT INTO t VALUES (2, 2)")
      checkAnswer(sql("SELECT hash(a) FROM t"), Row(1765031574))

      // Changing the type of a column that a CHECK constraint depends on is not allowed.
      checkError(
        exception = intercept[DeltaAnalysisException] {
          sql("ALTER TABLE t CHANGE COLUMN a TYPE SMALLINT")
        },
        errorClass = "DELTA_CONSTRAINT_DEPENDENT_COLUMN_CHANGE",
        parameters = Map(
          "columnName" -> "a",
          "constraints" -> "delta.constraints.ck -> hash ( a ) > 0"
      ))

      // Changing the type of `b` is allowed as it's not referenced by the constraint.
      sql("ALTER TABLE t CHANGE COLUMN b TYPE SMALLINT")
      assert(sql("SELECT * FROM t").schema("b").dataType === ShortType)
      checkAnswer(sql("SELECT * FROM t"), Row(2, 2))
    }
  }

  test("check constraint on nested field with type change") {
    withTable("t") {
      sql("CREATE TABLE t (a struct<x: byte, y: byte>) USING DELTA")
      sql("ALTER TABLE t ADD CONSTRAINT ck CHECK (hash(a.x) > 0)")
      sql("INSERT INTO t (a) VALUES (named_struct('x', 2, 'y', 3))")
      checkAnswer(sql("SELECT hash(a.x) FROM t"), Row(1765031574))

      checkError(
        exception = intercept[DeltaAnalysisException] {
          sql("ALTER TABLE t CHANGE COLUMN a.x TYPE SMALLINT")
        },
        errorClass = "DELTA_CONSTRAINT_DEPENDENT_COLUMN_CHANGE",
        parameters = Map(
          "columnName" -> "a.x",
          "constraints" -> "delta.constraints.ck -> hash ( a . x ) > 0"
      ))

      // Changing the type of a.y is allowed since it's not referenced by the CHECK constraint.
      sql("ALTER TABLE t CHANGE COLUMN a.y TYPE SMALLINT")
      checkAnswer(sql("SELECT * FROM t"), Row(Row(2, 3)))
    }
  }

  test(s"check constraint with type evolution") {
    withTable("t") {
      sql(s"CREATE TABLE t (a byte) USING DELTA")
      sql("ALTER TABLE t ADD CONSTRAINT ck CHECK (hash(a) > 0)")
      sql("INSERT INTO t VALUES (2)")
      checkAnswer(sql("SELECT hash(a) FROM t"), Row(1765031574))

      withSQLConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> "true") {
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql("INSERT INTO t VALUES (200)")
          },
          errorClass = "DELTA_CONSTRAINT_DATA_TYPE_MISMATCH",
          parameters = Map(
            "columnName" -> "a",
            "columnType" -> "TINYINT",
            "dataType" -> "INT",
            "constraints" -> "delta.constraints.ck -> hash ( a ) > 0"
        ))
      }
    }
  }

  test("check constraint on nested field with type evolution") {
    withTable("t") {
      sql("CREATE TABLE t (a struct<x: byte, y: byte>) USING DELTA")
      sql("ALTER TABLE t ADD CONSTRAINT ck CHECK (hash(a.x) > 0)")
      sql("INSERT INTO t (a) VALUES (named_struct('x', 2, 'y', 3))")
      checkAnswer(sql("SELECT hash(a.x) FROM t"), Row(1765031574))

      withSQLConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> "true") {
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql("INSERT INTO t (a) VALUES (named_struct('x', 200, 'y', CAST(5 AS byte)))")
          },
          errorClass = "DELTA_CONSTRAINT_DATA_TYPE_MISMATCH",
          parameters = Map(
            "columnName" -> "a",
            "columnType" -> "STRUCT<x: TINYINT, y: TINYINT>",
            "dataType" -> "STRUCT<x: INT, y: TINYINT>",
            "constraints" -> "delta.constraints.ck -> hash ( a . x ) > 0"
        ))

        // We're currently too strict and reject changing the type of struct field a.y even though
        // it's not the field referenced by the CHECK constraint.
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql("INSERT INTO t (a) VALUES (named_struct('x', CAST(2 AS byte), 'y', 500))")
          },
          errorClass = "DELTA_CONSTRAINT_DATA_TYPE_MISMATCH",
          parameters = Map(
            "columnName" -> "a",
            "columnType" -> "STRUCT<x: TINYINT, y: TINYINT>",
            "dataType" -> "STRUCT<x: TINYINT, y: INT>",
            "constraints" -> "delta.constraints.ck -> hash ( a . x ) > 0"
        ))
      }
    }
  }

  test("add constraint after type change then RESTORE") {
    withTable("t") {
      sql("CREATE TABLE t (a byte) USING DELTA")
      sql("INSERT INTO t VALUES (2)")
      sql("ALTER TABLE t CHANGE COLUMN a TYPE INT")
      sql("INSERT INTO t VALUES (5)")
      checkAnswer(sql("SELECT a, hash(a) FROM t"), Seq(Row(2, 1765031574), Row(5, 1023896466)))
      sql("ALTER TABLE t ADD CONSTRAINT ck CHECK (hash(a) > 0)")
      // Constraints are stored in the table metadata, RESTORE removes the constraint so the type
      // change can't get in the way.
      sql(s"RESTORE TABLE t VERSION AS OF 1")
      sql("INSERT INTO t VALUES (1)")
      checkAnswer(sql("SELECT a, hash(a) FROM t"), Seq(Row(2, 1765031574), Row(1, -559580957)))
    }
  }
}

trait DeltaTypeWideningGeneratedColumnTests extends GeneratedColumnTest {
  self: QueryTest with SharedSparkSession =>

  test("generated column with type change") {
    withTable("t") {
      createTable(
        tableName = "t",
        path = None,
        schemaString = "a byte, b byte, gen int",
        generatedColumns = Map("gen" -> "hash(a)"),
        partitionColumns = Seq.empty
      )
      sql("INSERT INTO t (a, b) VALUES (2, 2)")
      checkAnswer(sql("SELECT hash(a) FROM t"), Row(1765031574))

      // Changing the type of a column that a generated column depends on is not allowed.
      checkError(
        exception = intercept[DeltaAnalysisException] {
          sql("ALTER TABLE t CHANGE COLUMN a TYPE SMALLINT")
        },
        errorClass = "DELTA_GENERATED_COLUMNS_DEPENDENT_COLUMN_CHANGE",
        parameters = Map(
          "columnName" -> "a",
          "generatedColumns" -> "gen -> hash(a)"
        ))

      // Changing the type of `b` is allowed as it's not referenced by the generated column.
      sql("ALTER TABLE t CHANGE COLUMN b TYPE SMALLINT")
      assert(sql("SELECT * FROM t").schema("b").dataType === ShortType)
      checkAnswer(sql("SELECT * FROM t"), Row(2, 2, 1765031574))
    }
  }

  test("generated column on nested field with type change") {
    withTable("t") {
      createTable(
        tableName = "t",
        path = None,
        schemaString = "a struct<x: byte, y: byte>, gen int",
        generatedColumns = Map("gen" -> "hash(a.x)"),
        partitionColumns = Seq.empty
      )
      sql("INSERT INTO t (a) VALUES (named_struct('x', 2, 'y', 3))")
      checkAnswer(sql("SELECT hash(a.x) FROM t"), Row(1765031574))

      checkError(
        exception = intercept[DeltaAnalysisException] {
          sql("ALTER TABLE t CHANGE COLUMN a.x TYPE SMALLINT")
        },
        errorClass = "DELTA_GENERATED_COLUMNS_DEPENDENT_COLUMN_CHANGE",
        parameters = Map(
          "columnName" -> "a.x",
          "generatedColumns" -> "gen -> hash(a.x)"
      ))

      // Changing the type of a.y is allowed since it's not referenced by the CHECK constraint.
      sql("ALTER TABLE t CHANGE COLUMN a.y TYPE SMALLINT")
      checkAnswer(sql("SELECT * FROM t"), Row(Row(2, 3), 1765031574) :: Nil)
    }
  }

  test("generated column with type evolution") {
    withTable("t") {
      createTable(
        tableName = "t",
        path = None,
        schemaString = "a byte, gen int",
        generatedColumns = Map("gen" -> "hash(a)"),
        partitionColumns = Seq.empty
      )
      sql("INSERT INTO t (a) VALUES (2)")
      checkAnswer(sql("SELECT hash(a) FROM t"), Row(1765031574))

      withSQLConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> "true") {
        checkError(
        exception = intercept[DeltaAnalysisException] {
          sql("INSERT INTO t (a) VALUES (200)")
        },
        errorClass = "DELTA_GENERATED_COLUMNS_DATA_TYPE_MISMATCH",
        parameters = Map(
          "columnName" -> "a",
          "columnType" -> "TINYINT",
          "dataType" -> "INT",
          "generatedColumns" -> "gen -> hash(a)"
        ))
      }
    }
  }

  test("generated column on nested field with type evolution") {
    withTable("t") {
      createTable(
        tableName = "t",
        path = None,
        schemaString = "a struct<x: byte, y: byte>, gen int",
        generatedColumns = Map("gen" -> "hash(a.x)"),
        partitionColumns = Seq.empty
      )
      sql("INSERT INTO t (a) VALUES (named_struct('x', 2, 'y', 3))")
      checkAnswer(sql("SELECT hash(a.x) FROM t"), Row(1765031574))

      withSQLConf(DeltaSQLConf.DELTA_SCHEMA_AUTO_MIGRATE.key -> "true") {
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql("INSERT INTO t (a) VALUES (named_struct('x', 200, 'y', CAST(5 AS byte)))")
          },
          errorClass = "DELTA_GENERATED_COLUMNS_DATA_TYPE_MISMATCH",
          parameters = Map(
            "columnName" -> "a",
            "columnType" -> "STRUCT<x: TINYINT, y: TINYINT>",
            "dataType" -> "STRUCT<x: INT, y: TINYINT>",
            "generatedColumns" -> "gen -> hash(a.x)"
        ))

        // We're currently too strict and reject changing the type of struct field a.y even though
        // it's not the field referenced by the generated column.
        checkError(
          exception = intercept[DeltaAnalysisException] {
            sql("INSERT INTO t (a) VALUES (named_struct('x', CAST(2 AS byte), 'y', 200))")
          },
          errorClass = "DELTA_GENERATED_COLUMNS_DATA_TYPE_MISMATCH",
          parameters = Map(
            "columnName" -> "a",
            "columnType" -> "STRUCT<x: TINYINT, y: TINYINT>",
            "dataType" -> "STRUCT<x: TINYINT, y: INT>",
            "generatedColumns" -> "gen -> hash(a.x)"
        ))
      }
    }
  }
}
