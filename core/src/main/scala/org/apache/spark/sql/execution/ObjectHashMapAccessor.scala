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

import scala.annotation.tailrec
import scala.collection.mutable

import com.gemstone.gemfire.internal.shared.ClientResolverUtils
import io.snappydata.collection.ObjectHashSet

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, BindReferences, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution.columnar.encoding.StringDictionary
import org.apache.spark.sql.execution.joins.{BuildLeft, BuildRight, BuildSide, HashJoinExec}
import org.apache.spark.sql.execution.row.RowTableScan
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SnappySession, SparkSupport}
import org.apache.spark.unsafe.array.ByteArrayMethods

/**
 * Provides helper methods for generated code to use ObjectHashSet with a
 * generated class (having key and value columns as corresponding java type
 * fields). This implementation saves the entire overhead of UnsafeRow
 * conversion for both key type (like in BytesToBytesMap) and value type
 * (like in BytesToBytesMap and VectorizedHashMapGenerator).
 * <p>
 * It has been carefully optimized to minimize memory reads/writes, with
 * minimalistic code to fit better in CPU instruction cache. Unlike the other
 * two maps used by HashAggregateExec, this has no limitations on the key or
 * value column types.
 * <p>
 * The basic idea being that all of the key and value columns will be
 * individual fields in a generated java class having corresponding java
 * types. Storage of a column value in the map is a simple matter of assignment
 * of incoming variable to the corresponding field of the class object and
 * access is likewise read from that field of class . Nullability information
 * is crammed in long bit-mask fields which are generated as many required
 * (instead of unnecessary overhead of something like a BitSet).
 * <p>
 * Hashcode and equals methods are generated for the key column fields.
 * Having both key and value fields in the same class object helps both in
 * cutting down of generated code as well as cache locality and reduces at
 * least one memory access for each row. In testing this alone has shown to
 * improve performance by ~25% in simple group by queries. Furthermore, this
 * class also provides for inline hashcode and equals methods so that incoming
 * register variables in generated code can be directly used (instead of
 * stuffing into a lookup key that will again read those fields inside). The
 * class hashcode method is supposed to be used only internally by rehashing
 * and that too is just a field cached in the class object that is filled in
 * during the initial insert (from the inline hashcode).
 * <p>
 * For memory management this uses a simple approach of starting with an
 * estimated size, then improving that estimate for future in a rehash where
 * the rehash will also collect the actual size of current entries.
 * If the rehash tells that no memory is available, then it will fallback
 * to dumping the current map into MemoryManager and creating a new one
 * with merge being done by an external sorter in a manner similar to
 * how UnsafeFixedWidthAggregationMap handles the situation. Caller can
 * instead decide to dump the entire map in that scenario like when using
 * for a HashJoin.
 * <p>
 * Overall this map is 5-10X faster than UnsafeFixedWidthAggregationMap
 * and 2-4X faster than VectorizedHashMapGenerator. It is generic enough
 * to be used for both group by aggregation as well as for HashJoins.
 */
case class ObjectHashMapAccessor(@transient session: SnappySession,
    @transient ctx: CodegenContext, @transient keyExprs: Seq[Expression],
    @transient valueExprs: Seq[Expression], classPrefix: String,
    hashMapTerm: String, dataTerm: String, maskTerm: String,
    multiMap: Boolean, @transient consumer: CodegenSupport,
    @transient cParent: CodegenSupport, override val child: SparkPlan)
    extends UnaryExecNode with CodegenSupportSnappy with SparkSupport {

  override def output: Seq[Attribute] = child.output

  private[execution] val keyExpressions = keyExprs.map(_.canonicalized)
  private[execution] val valueExpressions = valueExprs.map(_.canonicalized)
  private[execution] var dictionaryKey: Option[DictionaryCode] = None

  private[this] val valueIndex = keyExpressions.length

  // Key expressions are the key fields in the map, while value expressions
  // are the output values expected to be read from the map which may include
  // some or all or none of the keys. The maps used below are to find the common
  // expressions for value fields and re-use key variables if possible.
  // End output is Seq of (expression, index) where index will be negative if
  // found in the key list i.e. value expression index will be negative of that
  // in the key map if found there else its own running index for unique ones.
  @transient private[this] val (keyExprIndexes, valueExprIndexes) = {
    val keyExprIndexMap = keyExpressions.zipWithIndex.toMap
    var index = -1
    val valueExprIndexes = valueExpressions.map(e =>
      e -> keyExprIndexMap.get(e).map(-_ - 1).getOrElse {
        index += 1
        index
      })
    (keyExprIndexMap.toSeq, valueExprIndexes)
  }

  @transient lazy val (integralKeys, integralKeysMinVars, integralKeysMaxVars) =
    keyExprIndexes.collect {
      case (expr, index) if isIntegralType(expr.dataType) =>
        (index, ctx.freshName("minValue"), ctx.freshName("maxValue"))
    }.unzip3

  private[this] val hashingClass = classOf[ClientResolverUtils].getName
  private[this] val nullsMaskPrefix = "nullsMask"
  /**
   * Indicator value for "nullIndex" of a non-primitive nullable that can be
   * checked using its value rather than a separate bit mask.
   */
  private[this] val NULL_NON_PRIM = -2
  private[this] val numMultiValuesVar = "numMultiValues"
  private[this] val nextValueVar = "nextValue"

  private type ClassVar = (DataType, String, ExprCode, Int)

  @transient private[this] val (className, valueClassName, classVars, numNullVars) = initClass()

  private def initClass(): (String, String, IndexedSeq[ClassVar], Int) = {

    // Key columns will be first in the class.
    // Eliminate common expressions and re-use variables.
    // For multi-map case, the full entry class will extend value class
    // so common expressions will be in the entry key fields which will
    // be same for all values when the multi-value list is filled.
    val keyTypes = keyExpressions.map(e => e.dataType -> e.nullable)
    val valueTypes = valueExprIndexes.collect {
      case (e, i) if i >= 0 => e.dataType -> e.nullable
    }
    val entryTypes = if (multiMap) keyTypes else keyTypes ++ valueTypes
    // whether a separate value class is required or not
    val valClassTypes = if (multiMap) valueTypes else Nil
    // check for existing class with same schema
    val (valueClass, entryClass, exists) = session.getClass(ctx,
      valClassTypes, keyTypes, entryTypes, multiMap) match {
      case Some((v, e)) => (v, e, true)
      case None =>
        val entryClass = ctx.freshName(classPrefix)
        val valClass = if (valClassTypes.nonEmpty) "Val_" + entryClass else ""
        (valClass, entryClass, false)
    }

    // local variable name for other object in equals
    val other = "other"

    // For the case of multi-map, key fields cannot be null. Create a
    // separate value class that the main class will extend. The value class
    // object will have the next value class object reference (possibly null)
    // forming a list that holds any additional values.
    val (entryVars, valClassVars, numNulls, nullDecls) = createClassVars(
      entryTypes, valClassTypes)
    if (!exists) {
      // Generate equals code for key columns only.
      val keyVars = entryVars.take(valueIndex)
      val equalsCode = keyVars.map {
        case (dataType, _, ev, nullIndex) =>
          val nullVar = internals.exprCodeIsNull(ev)
          val varName = internals.exprCodeValue(ev)
          genEqualsCode("this", varName, nullVar, other,
            varName, nullVar, nullIndex, isPrimitiveType(dataType), dataType)
      }.mkString(" &&\n")
      val (valueClassCode, extendsCode, nulls, multiValues) =
        if (valClassVars.nonEmpty) {
          (s"""
            public static class $valueClass {
              $nullDecls
              ${valClassVars.map(e => s"${e._2} ${internals.exprCodeValue(e._3)};")
                .mkString("\n")}
              $valueClass $nextValueVar;
            }
          """, s" extends $valueClass", "", "")
        } else if (multiMap) {
          ("", "", nullDecls, s"int $numMultiValuesVar;")
        } else ("", "", nullDecls, "")
      val classCode =
        s"""
          public static final class $entryClass$extendsCode {
            $nulls
            ${entryVars.map(e => s"${e._2} ${internals.exprCodeValue(e._3)};").mkString("\n")}
            $multiValues
            final int hash;

            static final $entryClass EMPTY = new $entryClass(0);

            public $entryClass(int h) {
              this.hash = h;
            }

            public int hashCode() {
              return this.hash;
            }

            public boolean equals(Object o) {
              final $entryClass $other = ($entryClass)o;
              return $equalsCode;
            }
          }
        """
      // using addFunction to register the class since there is nothing
      // function specific in the addFunction method
      if (!valueClassCode.isEmpty) {
        internals.addFunction(ctx, valueClass, valueClassCode, inlineToOuterClass = true)
      }
      internals.addFunction(ctx, entryClass, classCode, inlineToOuterClass = true)
      session.addClass(ctx, valClassTypes, keyTypes, entryTypes,
        valueClass, entryClass, multiMap)
    }

    (entryClass, valueClass, entryVars ++ valClassVars, numNulls)
  }

  private def createClassVars(entryTypes: Seq[(DataType, Boolean)],
      valClassTypes: Seq[(DataType, Boolean)]): (IndexedSeq[ClassVar],
      IndexedSeq[ClassVar], Int, String) = {
    // collect the null field declarations (will be in baseClass if present)
    val nullMaskDeclarations = new StringBuilder

    var numNulls = -1
    var currNullVar = ""

    val numEntryVars = entryTypes.length
    val entryVars = new mutable.ArrayBuffer[ClassVar](numEntryVars)
    val valClassVars = new mutable.ArrayBuffer[ClassVar](valClassTypes.length)
    val allTypes = entryTypes ++ valClassTypes
    allTypes.indices.foreach { index =>
      val p = allTypes(index)
      val varName = s"field$index"
      val dataType = p._1
      val nullable = p._2
      val javaType = dataType match {
        // use raw byte arrays for strings to minimize overhead
        case StringType if !multiMap => "byte[]"
        case _ => internals.javaType(dataType, ctx)
      }
      val (nullVar, nullIndex) = if (nullable) {
        if (isPrimitiveType(dataType)) {
          // nullability will be stored in separate long bitmask fields
          numNulls += 1
          // each long can hold bit mask for 64 nulls
          val nullIndex = numNulls % 64
          if (nullIndex == 0) {
            currNullVar = s"$nullsMaskPrefix${numNulls / 64}"
            nullMaskDeclarations.append(s"long $currNullVar;\n")
          }
          (currNullVar, nullIndex)
        } else ("", NULL_NON_PRIM) // field itself is nullable
      } else ("", -1)
      if (index < numEntryVars) {
        entryVars += ((dataType, javaType, internals.newExprCode(code = "", nullVar, varName,
          dataType), nullIndex))
      } else {
        valClassVars += ((dataType, javaType, internals.newExprCode(code = "", nullVar, varName,
          dataType), nullIndex))
      }
    }
    val numNullVars = if (numNulls >= 0) (numNulls / 64) + 1 else 0
    (entryVars, valClassVars, numNullVars, nullMaskDeclarations.toString())
  }

  private def getExpressionVars(expressions: Seq[Expression],
      input: Seq[ExprCode],
      output: Seq[Attribute] = child.output): Seq[ExprCode] = {
    ctx.INPUT_ROW = null
    ctx.currentVars = input
    val vars = ctx.generateExpressions(expressions.map(e =>
      BindReferences.bindReference[Expression](e, output)))
    ctx.currentVars = null
    vars
  }

  override protected def doExecute(): RDD[InternalRow] =
    throw new UnsupportedOperationException("unexpected invocation")

  override def inputRDDs(): Seq[RDD[InternalRow]] = Nil

  override protected def doProduce(ctx: CodegenContext): String =
    throw new UnsupportedOperationException("unexpected invocation")

  override def needCopyResult: Boolean = false

  override def needStopCheck: Boolean = true

  private def doConsume(ctx: CodegenContext, keyExpressions: Seq[Expression],
      valueExpressions: Seq[Expression], input: Seq[ExprCode]): String = {
    // consume the data and populate the map
    val entryVar = "mapEntry" // local variable
    val hashVar = Array(ctx.freshName("hash"))
    val posVar = ctx.freshName("pos")
    val deltaVar = ctx.freshName("delta")
    val keyVars = getExpressionVars(keyExpressions, input)
    // skip expressions already in key variables (that are also skipped
    //   in the value class fields in class generation)
    val valueVars = getExpressionVars(valueExpressions, input)
    // Update min/max code for primitive type columns. Avoiding additional
    // index mapping here for mix of integral and non-integral keys
    // rather using key index since overhead of blanks will be negligible.
    val updateMinMax = integralKeys.map { index =>
      s"$hashMapTerm.updateLimits(${internals.exprCodeValue(keyVars(index))}, $index);"
    }.mkString("\n")

    val doCopy = !ObjectHashMapAccessor.providesImmutableObjects(child)

    val multiValuesUpdateCode = if (valueClassName.isEmpty) {
      s"""
        // no value field, only count
        // mark map as not unique on second insert for same key
        if ($entryVar.$numMultiValuesVar++ == 0) $hashMapTerm.setKeyIsUnique(false);"""
    } else {
      s"""
        // add to the start of multiValues list
        final $valueClassName newValue = new $valueClassName();
        newValue.$nextValueVar = $entryVar.$nextValueVar;
        $entryVar.$nextValueVar = newValue;
        ${generateUpdate("newValue", Nil, valueVars, forKey = false, doCopy)}

        // mark map as not unique on multiple inserts for same key
        $hashMapTerm.setKeyIsUnique(false);"""
    }
    val nullableKeys = keyVars.map(internals.exprCodeIsNull).filter(_ != "false")
    val (nullCheckStart, nullCheckEnd) =
      if (nullableKeys.isEmpty) ("", "")
      else {
        (s"// skip if a key is null\nif (${nullableKeys.mkString("!", " &&\n!", "")}) {\n", "\n}")
      }
    s"""
      // evaluate the key and value expressions
      ${evaluateVariables(keyVars)}${evaluateVariables(valueVars)}
      $nullCheckStart// generate hash code
      ${generateHashCode(hashVar, keyVars, register = false)}
      // lookup or insert the grouping key in map
      // using inline get call so that equals() is inline using
      // existing register variables instead of having to fill up
      // a lookup key fields and compare against those (thus saving
      //   on memory writes/reads vs just register reads)
      int $posVar = ${hashVar(0)} & $maskTerm;
      int $deltaVar = 1;
      while (true) {
        $className $entryVar = $dataTerm[$posVar];
        if ($entryVar != null) {
          if (${generateEquals(entryVar, keyVars)}) {
            $multiValuesUpdateCode
            break;
          } else {
            // quadratic probing with position increase by 1, 2, 3, ...
            $posVar = ($posVar + $deltaVar) & $maskTerm;
            $deltaVar++;
          }
        } else {
          $entryVar = new $className(${hashVar(0)});
          // initialize the key fields
          ${generateUpdate(entryVar, Nil, keyVars, forKey = true, doCopy)}
          // initialize the value fields
          ${generateUpdate(entryVar, Nil, valueVars, forKey = false, doCopy)}
          // insert into the map and rehash if required
          $dataTerm[$posVar] = $entryVar;
          if ($hashMapTerm.handleNewInsert($posVar)) {
            // map was rehashed
            $maskTerm = $hashMapTerm.mask();
            $dataTerm = ($className[])$hashMapTerm.data();
          }
          $updateMinMax

          break;
        }
      }$nullCheckEnd
    """
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    val valueExpressions = valueExprIndexes.filter(_._2 >= 0).map(_._1)
    val output = this.output
    // try to create a separate function for doConsume to reduce outer function size
    if (calculateParamLength(ctx, output) <= 255) {
      val doConsumeFunction = ctx.freshName("doConsume")
      val usedInput = AttributeSet(keyExpressions) ++ AttributeSet(valueExpressions)
      val usedInputCode = new mutable.ArrayBuffer[String]
      val args = new mutable.ArrayBuffer[String]
      val params = new mutable.ArrayBuffer[String]
      val newInput = new mutable.ArrayBuffer[ExprCode]()
      for (i <- input.indices) {
        val attr = output(i)
        val ev = input(i)
        if (usedInput.contains(attr)) {
          val varName = ctx.freshName("arg")
          val dataType = attr.dataType
          val evCode = ev.code.toString
          if (!evCode.isEmpty) usedInputCode += evCode
          args += internals.exprCodeValue(ev)
          params += s"${internals.javaType(dataType, ctx)} $varName"
          var isNull = internals.exprCodeIsNull(ev)
          if (isNull != "false") {
            args += isNull
            isNull = ctx.freshName("isNull")
            params += s"boolean $isNull"
          }
          newInput += internals.newExprCode(code = "", isNull, varName, dataType)
        } else {
          newInput += ev
        }
      }
      val functionName = internals.addFunction(ctx, doConsumeFunction,
        s"""
           |private void $doConsumeFunction(${params.mkString(", ")}) throws java.io.IOException {
           |  ${doConsume(ctx, keyExpressions, valueExpressions, newInput)}
           |}
        """.stripMargin)
      s"""
         |${usedInputCode.mkString("\n")}
         |$functionName(${args.mkString(", ")});
      """.stripMargin
    } else {
      doConsume(ctx, keyExpressions, valueExpressions, input)
    }
  }

  /**
   * Taken from CodeGenerator.calculateParamLength in Spark 2.4.x
   */
  private def calculateParamLength(ctx: CodegenContext, params: Seq[Expression]): Int = {
    def paramLengthForExpr(input: Expression): Int = {
      val javaParamLength = internals.javaType(input.dataType, ctx) match {
        case "long" | "double" => 2
        case _ => 1
      }
      // For a nullable expression, we need to pass in an extra boolean parameter.
      (if (input.nullable) 1 else 0) + javaParamLength
    }
    // Initial value is 1 for `this`.
    1 + params.map(paramLengthForExpr).sum
  }

  /** get the generated class name */
  def getClassName: String = className

  /**
   * Generate code to calculate the hash code for given column variables that
   * correspond to the key columns in this class.
   */
  def generateHashCode(hashVar: Array[String], keyVars: Seq[ExprCode],
      skipDeclaration: Boolean = false, register: Boolean = true): String = {
    var hash = hashVar(0)
    val hashDeclaration = if (skipDeclaration) "" else s"int $hash;\n"
    // check if hash has already been generated for keyExpressions
    var doRegister = register
    val vars = keyVars.map(internals.exprCodeValue)
    val (prefix, suffix) = session.getHashVar(ctx, vars) match {
      case Some(h) =>
        hashVar(0) = h
        hash = h
        doRegister = false
        (s"if ($hash == 0) {\n", "}\n")
      case _ => (hashDeclaration, "")
    }

    // register the hash variable for the key expressions
    if (doRegister) {
      session.addHashVar(ctx, vars, hash)
    }

    // optimize for first column to use fast hashing
    val nullVar = internals.exprCodeIsNull(keyVars.head)
    val colVar = internals.exprCodeValue(keyVars.head)
    val firstColumnHash = classVars(0)._1 match {
      case BooleanType =>
        hashSingleInt(s"($colVar) ? 1 : 0", nullVar, hash)
      case ByteType | ShortType | IntegerType | DateType =>
        hashSingleInt(colVar, nullVar, hash)
      case LongType | TimestampType =>
        hashSingleLong(colVar, nullVar, hash)
      case FloatType =>
        hashSingleInt(s"Float.floatToIntBits($colVar)", nullVar, hash)
      case DoubleType =>
        hashSingleLong(s"Double.doubleToLongBits($colVar)", nullVar, hash)
      case _: DecimalType =>
        hashSingleInt(s"$colVar.fastHashCode()", nullVar, hash)
      // single column types that use murmur hash already,
      // so no need to further apply mixing on top of it
      case _: StringType | _: ArrayType | _: StructType =>
        s"$hash = ${hashCodeSingleInt(s"$colVar.hashCode()", nullVar)};\n"
      case _ =>
        hashSingleInt(s"$colVar.hashCode()", nullVar, hash)
    }
    if (keyVars.length > 1) {
      classVars.tail.zip(keyVars.tail).map(p => (p._1._1,
          internals.exprCodeIsNull(p._2), internals.exprCodeValue(p._2))).map {
        case (BooleanType, evIsNull, evValue) =>
          addHashInt(s"$evValue ? 1 : 0", evIsNull, hash)
        case (ByteType | ShortType | IntegerType | DateType, evIsNull, evValue) =>
          addHashInt(evValue, evIsNull, hash)
        case (LongType | TimestampType, evIsNull, evValue) =>
          addHashLong(evValue, evIsNull, hash)
        case (FloatType, evIsNull, evValue) =>
          addHashInt(s"Float.floatToIntBits($evValue)", evIsNull, hash)
        case (DoubleType, evIsNull, evValue) =>
          addHashLong(s"Double.doubleToLongBits($evValue)", evIsNull,
            hash)
        case (_: DecimalType, evIsNull, evValue) =>
          addHashInt(s"$evValue.fastHashCode()", evIsNull, hash)
        case (_, evIsNull, evValue) =>
          addHashInt(s"$evValue.hashCode()", evIsNull, hash)
      }.mkString(prefix + firstColumnHash, "", suffix)
    } else prefix + firstColumnHash + suffix
  }

  // TODO: generate local variables for key fields for equals call and use
  // those variables for any consumers later
  /**
   * Generate code to compare equality of a given object (objVar) against
   * key column variables.
   */
  def generateEquals(objVar: String,
      keyVars: Seq[ExprCode]): String = classVars.zip(keyVars).map {
    case ((dataType, _, ev, nullIndex), colVar) =>
      genEqualsCode("", internals.exprCodeValue(colVar), internals.exprCodeIsNull(colVar),
        objVar, internals.exprCodeValue(ev), internals.exprCodeIsNull(ev), nullIndex,
        isPrimitiveType(dataType), dataType)
  }.mkString(" &&\n")

  /**
   * Get the ExprCode for the key and/or value columns given a class object
   * variable. This also returns an initialization code that should be inserted
   * in generated code first. The last element in the result tuple is the names
   * of null mask variables.
   */
  def getColumnVars(keyObjVar: String, localValObjVar: String,
      onlyKeyVars: Boolean, onlyValueVars: Boolean,
      checkNullObj: Boolean = false): (String, Seq[ExprCode], Array[String]) = {
    // no local value if no separate value class
    val valObjVar = if (valueClassName.isEmpty) keyObjVar else localValObjVar
    // Generate initial declarations for null masks to avoid reading those
    // repeatedly. Caller is supposed to insert the code at the start.
    val declarations = new StringBuilder
    val nullValMaskVars = new Array[String](numNullVars)
    val nullMaskVarMap = (0 until numNullVars).map { index =>
      val nullVar = s"$nullsMaskPrefix$index"
      // separate final variable for nulls mask of key columns because
      // value can change for multi-map case whose nullsMask may not
      // be properly set for key fields
      val nullMaskVar = ctx.freshName("localKeyNullsMask")
      val nullValMaskVar = ctx.freshName("localNullsMask")
      if (checkNullObj) {
        // for outer joins, check for null entry and set all bits to 1
        declarations.append(s"final long $nullMaskVar = " +
            s"$keyObjVar != null ? $keyObjVar.$nullVar : -1L;\n")
      } else {
        declarations.append(s"final long $nullMaskVar = $keyObjVar.$nullVar;\n")
      }
      declarations.append(s"long $nullValMaskVar = $nullMaskVar;\n")
      nullValMaskVars(index) = nullValMaskVar
      (nullVar, (nullMaskVar, nullValMaskVar))
    }.toMap

    val vars = if (onlyKeyVars) classVars.take(valueIndex)
    else {
      // for value variables that are part of common expressions with keys,
      // indicate the same as a null ExprCode with "nullIndex" pointing to
      // the index of actual key variable to use in classVars
      val valueVars = valueExprIndexes.collect {
        case (_, i) if i >= 0 => classVars(i + valueIndex)
        case (_, i) => (null, null, null, -i - 1) // i < 0
      }
      if (onlyValueVars) valueVars else classVars.take(valueIndex) ++ valueVars
    }

    // lookup common expressions in key for values if accumulated by
    // back to back calls to getColumnVars for keys, then columns
    val columnVars = new mutable.ArrayBuffer[ExprCode]
    vars.indices.foreach { index =>
      val (dataType, javaType, ev, nullIndex) = vars(index)
      val isKeyVar = index < valueIndex
      val objVar = if (isKeyVar) keyObjVar else valObjVar
      ev match {
        // nullIndex contains index of referenced key variable in this case
        case null if !onlyValueVars => columnVars += columnVars(nullIndex)
        case _ =>
          val evValue = internals.exprCodeValue(ev)
          val (localVar, localDeclaration) = {
            dataType match {
              case StringType if !multiMap =>
                // wrap the bytes in UTF8String
                val lv = ctx.freshName("localField")
                (lv, new StringBuilder().append(s"final UTF8String $lv = ").append(
                  if (checkNullObj) {
                    s"($objVar != null ? UTF8String.fromBytes(" +
                        s"$objVar.$evValue) : null);"
                  } else {
                    s"UTF8String.fromBytes($objVar.$evValue);"
                  }))
              case _ =>
                val lv = ctx.freshName("localField")
                (lv, new StringBuilder().append(s"final $javaType $lv = ").append(
                  if (checkNullObj) {
                    s"($objVar != null ? $objVar.$evValue " +
                        s" : ${internals.defaultValue(dataType, ctx)});"
                  } else {
                    s"$objVar.$evValue;"
                  }))
            }
          }
          val nullExpr = nullMaskVarMap.get(internals.exprCodeIsNull(ev))
              .map(p => if (isKeyVar) genNullCode(p._1, nullIndex)
              else genNullCode(p._2, nullIndex)).getOrElse(
            if (nullIndex == NULL_NON_PRIM) s"($localVar == null)"
            else "false")
          val nullVar = ctx.freshName("isNull")
          localDeclaration.append(s"\nboolean $nullVar = $nullExpr;")
          columnVars += internals.newExprCode(localDeclaration.toString, nullVar,
            localVar, dataType)
      }
    }
    (declarations.toString(), columnVars, nullValMaskVars)
  }

  private[execution] def mapLookup(objVar: String, hash: String,
      keyExpressions: Seq[Expression], keyVars: Seq[ExprCode],
      valueInit: String): String = {
    val pos = ctx.freshName("pos")
    val delta = ctx.freshName("delta")
    val mapKey = ctx.freshName("mapKey")

    // generate the variables for each of the key terms with proper types
    val (keyDecls, keyCalls, newKeyVars) = keyExpressions
        .zip(keyVars).map { case (expr, ev) =>
      val javaType = internals.javaType(expr.dataType, ctx)
      val newKeyVar = ctx.freshName("keyCol")
      val evIsNull = internals.exprCodeIsNull(ev)
      val evValue = internals.exprCodeValue(ev)
      if (evIsNull == "false") {
        (s"final $javaType $newKeyVar", evValue,
            internals.copyExprCode(ev, value = newKeyVar, dt = expr.dataType))
      } else {
        // new variable for nullability since isNull can be an expression
        val newNullVar = ctx.freshName("keyIsNull")
        (s"final $javaType $newKeyVar, final boolean $newNullVar",
            s"$evValue, $evIsNull",
            internals.copyExprCode(ev, isNull = newNullVar, value = newKeyVar, dt = expr.dataType))
      }
    }.unzip3
    val keyDeclarations = keyDecls.mkString(", ")

    val skipInit = valueInit eq null
    // check for existing function with matching null vars and skipInit
    val fnKey = className -> keyVars.map(internals.exprCodeIsNull(_) == "false")
    val fn = session.getContextObject[(String, String, Boolean)](ctx, "F", fnKey) match {
      case Some((_, functionName, skip)) if skipInit || !skip => functionName
      case f =>
        // re-use function for non-matching skipInit but change its body
        // to also handle insertion of new blank entry
        val function = f match {
          case None => ctx.freshName("mapLookup")
          case Some(p) => p._1
        }
        val hashMapArg = ctx.freshName("hashMap")
        val insertCode = if (skipInit) {
          s"""else {
             |  // key not found so return entry as null for consumption
             |  return null;
             |}""".stripMargin
        }
        else {
          s"""else if (skipInit) {
             |  // key not found so return entry as null for consumption
             |  return null;
             |} else {
             |  // initialize the value fields to defaults, key fields to
             |  // incoming values and return this new initialized entry
             |  final $className $objVar = new $className($hash);
             |  // initialize the value fields to defaults
             |  $valueInit
             |  // initialize the key fields
             |  ${generateUpdate(objVar, Nil, newKeyVars, forKey = true)}
             |  // insert into the map and rehash if required
             |  $dataTerm[$pos] = $objVar;
             |  if ($hashMapArg.handleNewInsert($pos)) {
             |    // return null to indicate map was rehashed
             |    return null;
             |  } else {
             |    return $objVar;
             |  }
             |}""".stripMargin
        }
        val functionName = internals.addFunction(ctx, function,
          s"""
             |private $className $function(final int $hash, $keyDeclarations,
             |    final $className[] $dataTerm, final int $maskTerm,
             |    final ${classOf[ObjectHashSet[_]].getName} $hashMapArg,
             |    final boolean skipInit) {
             |  // Lookup or insert the key in map (for group by).
             |  // Using inline get call so that equals() is inline using
             |  // existing register variables instead of having to fill up
             |  // a lookup key fields and compare against those (thus saving
             |  //   on memory writes/reads vs just register reads).
             |  int $pos = $hash & $maskTerm;
             |  int $delta = 1;
             |  while (true) {
             |    final $className $mapKey = $dataTerm[$pos];
             |    if ($mapKey != null) {
             |      if (${generateEquals(mapKey, newKeyVars)}) {
             |        return $mapKey;
             |      } else {
             |        // quadratic probing with position increase by 1, 2, 3, ...
             |        $pos = ($pos + $delta) & $maskTerm;
             |        $delta++;
             |      }
             |    } $insertCode
             |  }
             |}
          """.stripMargin)

        // register the new function
        session.addContextObject(ctx, "F", fnKey, (function, functionName, skipInit))
        functionName
    }

    val keyArgs = keyCalls.mkString(", ")
    // code to update the stack data/mask variables
    val updateMapVars = if (skipInit) ""
    else {
      s"""
         |if ($objVar == null) { // indicates map rehash
         |  $dataTerm = ($className[])$hashMapTerm.data();
         |  $maskTerm = $hashMapTerm.mask();
         |  // read new inserted value
         |  $objVar = $fn($hash, $keyArgs, $dataTerm, $maskTerm, $hashMapTerm, false);
         |}""".stripMargin
    }
    s"$objVar = $fn($hash, $keyArgs, $dataTerm, $maskTerm, $hashMapTerm, " +
        s"$skipInit);$updateMapVars"
  }

  private def initDictionaryCodeForSingleKeyCase(dictionaryArrayInit: String,
      input: Seq[ExprCode], keyExpressions: Seq[Expression] = keyExpressions,
      output: Seq[Attribute] = output): Boolean = {
    // make a copy of input key variables if required since this is used
    // only for lookup and the ExprCode's code should not be cleared
    dictionaryKey = DictionaryOptimizedMapAccessor.checkSingleKeyCase(
      keyExpressions, getExpressionVars(keyExpressions, input.map(_.copy()),
        output), ctx, session)
    dictionaryKey match {
      case Some(d@DictionaryCode(dictionary, _, _)) =>
        val dictValue = internals.exprCodeValue(dictionary)
        // initialize or reuse the array at batch level for join
        // null key will be placed at the last index of dictionary
        // and dictionary index will be initialized to that by ColumnTableScan
        internals.addClassField(ctx, classOf[StringDictionary].getName,
          dictValue, forceInline = true, useFreshName = false)
        internals.addFunction(ctx, dictionaryArrayInit,
          s"""
             |public $className[] $dictionaryArrayInit() {
             |  ${d.evaluateDictionaryCode()}
             |  if ($dictValue != null) {
             |    return new $className[$dictValue.size() + 1];
             |  } else {
             |    return null;
             |  }
             |}
           """.stripMargin, inlineToOuterClass = true)
        true
      case None => false
    }
  }

  /**
   * Generate code to lookup the map or insert a new key, value if not found.
   */
  def generateMapGetOrInsert(objVar: String, valueInitVars: Seq[ExprCode],
      valueInitCode: String, input: Seq[ExprCode], evalKeys: Seq[Boolean],
      dictArrayVar: String, dictArrayInitVar: String): String = {
    val hashVar = Array(ctx.freshName("hash"))
    val valueInit = valueInitCode + '\n' + generateUpdate(objVar, Nil,
      valueInitVars, forKey = false, doCopy = false)

    // optimized path for single key string column if dictionary is present
    def mapLookupCode(keyVars: Seq[ExprCode]): String = mapLookup(objVar,
      hashVar(0), keyExpressions, keyVars, valueInit)
    initDictionaryCodeForSingleKeyCase(dictArrayInitVar, input)
    dictionaryKey match {
      case Some(dictKey) =>
        val keyVars = getExpressionVars(keyExpressions, input)
        // materialize the key code explicitly if required by update expressions later (AQP-292:
        //   it can no longer access the code since keyVars has emptied the key codes in input)
        val evalKeyCode = if (evalKeys.isEmpty) ""
        else evaluateVariables(keyVars.indices.collect {
          case i if evalKeys(i) => keyVars(i)
        })
        val keyVar = keyVars.head
        s"""
          $evalKeyCode
          $className $objVar;
          ${DictionaryOptimizedMapAccessor.dictionaryArrayGetOrInsert(ctx,
            keyExpressions, keyVar, dictKey, dictArrayVar, objVar, valueInit,
            continueOnNull = false, this)} else {
            // evaluate the key expressions
            ${evaluateVariables(keyVars)}
            // evaluate hash code of the lookup key
            ${generateHashCode(hashVar, keyVars, register = false)}
            ${mapLookupCode(keyVars)}
          }
        """
      case None =>
        val inputEvals = evaluateVariables(input)
        val keyVars = getExpressionVars(keyExpressions, input)
        s"""
          // evaluate the key expressions
          $inputEvals
          ${evaluateVariables(keyVars)}
          // evaluate hash code of the lookup key
          ${generateHashCode(hashVar, keyVars)}
          $className $objVar;
          ${mapLookupCode(keyVars)}
         """
    }
  }

  private def getConsumeResultCode(numRows: String,
      resultVars: Seq[ExprCode]): String =
    s"$numRows++;\n${consumer.consume(ctx, resultVars)}"

  // scalastyle:off
  def generateMapLookup(entryVar: String, localValueVar: String, mapSize: String,
      keyIsUnique: String, initMap: String, initMapCode: String, numRows: String,
      nullMaskVars: Array[String], initCode: String, checkCond: (Option[ExprCode], String,
      Option[Expression]), streamKeys: Seq[Expression], streamOutput: Seq[Attribute],
      buildKeyVars: Seq[ExprCode], buildVars: Seq[ExprCode], input: Seq[ExprCode],
      dictArrayVar: String, dictArrayInitVar: String, joinType: JoinType,
      buildSide: BuildSide): String = {
    // scalastyle:on

    val hash = ctx.freshName("hash")
    val hashVar = Array(hash)

    // if consumer is a projection that will project away key columns,
    // then avoid materializing those
    // TODO: SW: should be removed once keyVars are handled in generateEquals
    // Also remove mapKeyCodes in join types.
    val mapKeyVars = cParent match {
      case ProjectExec(projection, _) =>
        buildKeyVars.zip(keyExpressions).collect {
          case (ev, ne: NamedExpression)
            if projection.exists(_.exprId == ne.exprId) => ev
          case (ev, expr)
            if projection.exists(_.semanticEquals(expr)) => ev
        }
      case _ => buildKeyVars
    }
    val mapKeyCodes = s"$initCode\n${evaluateVariables(mapKeyVars)}"

    // continue to next entry on no match
    val continueOnNull = joinType match {
      case Inner | LeftSemi => true
      case _ => false
    }
    // initialize dictionaryKey
    initDictionaryCodeForSingleKeyCase(dictArrayInitVar, input, streamKeys, streamOutput)

    // check if streamKeyVars need to be evaluated in the outer block in which case pre-evaluate
    // the used input variables in appropriate positions to avoid double variable initialization
    val inputKeysCode = if (dictionaryKey.isEmpty ||
        // determine if initFilters will be empty or not
        !continueOnNull || integralKeys.nonEmpty || streamKeys.exists(_.nullable)) {
      evaluateRequiredVariables(streamOutput, input, AttributeSet(streamKeys))
    } else ""
    val resultVars = buildSide match {
      case BuildLeft => buildVars ++ input
      case BuildRight => input ++ buildVars
    }

    ctx.INPUT_ROW = null
    ctx.currentVars = input
    val boundStreamKeys = streamKeys.map(BindReferences.bindReference(_, streamOutput))
    val streamKeyVars = ctx.generateExpressions(boundStreamKeys)

    // invoke generateHashCode before consume so that hash variables
    // can be re-used by consume if possible
    val streamHashCode = generateHashCode(hashVar, streamKeyVars, skipDeclaration = true)
    // if previous hash variable is being used then skip declaration
    val hashInit = if (hashVar(0) eq hash) s"int $hash = 0;" else ""
    // if a stream-side key is null then skip (or null for outer join)
    val nullStreamKeys = streamKeys.indices.collect {
      case i if streamKeys(i).nullable => s"!${internals.exprCodeIsNull(streamKeyVars(i))}"
    }
    // filter as per min/max if provided; the min/max variables will be
    // initialized by the caller outside the loop after creating the map
    val minMaxFilter = integralKeys.zipWithIndex.map { case (indexKey, index) =>
      val keyVar = internals.exprCodeValue(streamKeyVars(indexKey))
      val minVar = integralKeysMinVars(index)
      val maxVar = integralKeysMaxVars(index)
      s"$keyVar >= $minVar && $keyVar <= $maxVar"
    }
    // generate the initial filter condition from above two
    // also add a mapSize check but when continueOnNull is true, then emit a continue immediately
    val (checkMapSize, initFilters) = if (continueOnNull) {
      (s"if ($mapSize == 0) continue;\n", nullStreamKeys ++ minMaxFilter)
    } else ("", s"$mapSize != 0" +: (nullStreamKeys ++ minMaxFilter))
    val initFilterCode =
      if (initFilters.isEmpty) "" else initFilters.mkString("if (", " &&\n", ")")

    // common multi-value iteration code fragments
    val entryIndexVar = ctx.freshName("entryIndex")
    val numEntriesVar = ctx.freshName("numEntries")
    val declareLocalVars = if (valueClassName.isEmpty) {
      // one consume done when moveNextValue is hit, so initialize with 1
      s"""
        // key count iteration
        int $entryIndexVar = 0;
        int $numEntriesVar = -1;
      """
    } else {
      s"""
        // for first iteration, entry object itself has value fields
        $valueClassName $localValueVar = $entryVar;"""
    }
    val moveNextValue = if (valueClassName.isEmpty) {
      s"""
        if ($entryIndexVar < $numEntriesVar) {
          $entryIndexVar++;
        } else if ($numEntriesVar == -1) {
          // multi-entries count hit first time
          $numEntriesVar = $entryVar.$numMultiValuesVar;
          if ($numEntriesVar <= 0) break;
          $entryIndexVar = 1;
        } else {
          break;
        }"""
    } else {
      // code to update the null masks
      val nullsUpdate = (0 until numNullVars).map { index =>
        val nullVar = s"$nullsMaskPrefix$index"
        s"${nullMaskVars(index)} = $localValueVar.$nullVar;"
      }.mkString("\n")

      val dupRow =
        s"""
           |if (!currentRows.isEmpty()) {
           |  currentRows.addLast(((InternalRow)currentRows.pollLast()).copy());
           |}
         """.stripMargin
      s"""
        if (($localValueVar = $localValueVar.$nextValueVar) != null) {
          $nullsUpdate
          $dupRow
        } else {
          break;
        }"""
    }

    // optimized path for single key string column if dictionary is present
    val lookup = mapLookup(entryVar, hashVar(0), boundStreamKeys, streamKeyVars, valueInit = null)
    val preEvalKeys = if (initFilterCode.isEmpty) "" else evaluateVariables(streamKeyVars)
    var mapLookupCode = dictionaryKey match {
      case Some(dictKey) =>
        val keyVar = streamKeyVars.head
        val keyCode = keyVar.code.toString
        // don't call evaluateVariables for streamKeyVars for the else
        // part below because it is in else block and should be re-evaluated
        // if required outside the block
        val code = s"""
          ${DictionaryOptimizedMapAccessor.dictionaryArrayGetOrInsert(ctx,
            boundStreamKeys, keyVar, dictKey, dictArrayVar, entryVar,
            valueInit = null, continueOnNull, accessor = this)} else {
            // evaluate the string key expression
            ${if (keyCode.isEmpty) "" else keyCode.trim}
            // generate hash code from stream side key columns
            $streamHashCode
            $lookup
          }
        """
        // copy back the updated code to input if present
        if (keyCode.nonEmpty) input.find(_.value == keyVar.value)
            .foreach(_.code = keyVar.code)
        code
      case None =>
        s"""
          // evaluate the key expressions
          ${evaluateVariables(streamKeyVars)}
          // generate hash code from stream side key columns
          $streamHashCode
          $lookup
        """
    }
    if (initFilterCode.nonEmpty) {
      mapLookupCode = s"""$preEvalKeys
        // check if any join key is null or min/max for integral keys
        $initFilterCode {
          $mapLookupCode
        }"""
    }

    // evaluate inputs for use by consumer if required
    // the evaluations below match the input vars being sent to
    // getConsumeResultCode (except existence join having existsVar but that
    //    is always evaluated) -- see the evaluateRequiredVariables call in
    // base CodegenSupport.consume
    val checkCondition = checkCond._1
    val (checkCode, usedInputs) = if (checkCondition.isDefined) {
      // add any additional inputs used by check condition
      (checkCond._2, cParent.usedInputs ++ checkCond._3.get.references)
    } else ("", cParent.usedInputs)
    val inputCodes = buildSide match {
      case BuildRight =>
        // input streamed plan is on left
        evaluateRequiredVariables(consumer.output, input, usedInputs)
      case BuildLeft =>
        // input streamed plan is on right
        evaluateRequiredVariables(consumer.output.takeRight(input.size), input, usedInputs)
    }

    // Code fragments for different join types.
    // This is to ensure only a single parent.consume() because the branches
    // can be taken alternately in the worst case so then it can lead to
    // large increase in instruction cache misses even though most of the code
    // will be the common parent's consume call.
    val entryConsume = joinType match {
      case Inner => genInnerJoinCodes(entryVar, mapKeyCodes, checkCondition,
        checkCode, numRows, getConsumeResultCode(numRows, resultVars),
        keyIsUnique, declareLocalVars, moveNextValue, inputCodes)

      case LeftOuter | RightOuter =>
        // instantiate code for buildVars before calling getConsumeResultCode
        val buildInitCode = evaluateVariables(buildVars)
        genOuterJoinCodes(entryVar, buildVars, buildInitCode, mapKeyCodes,
          checkCondition, checkCode, numRows, getConsumeResultCode(numRows, resultVars),
          keyIsUnique, declareLocalVars, moveNextValue, inputCodes, localValueVar)

      case LeftSemi => genSemiJoinCodes(entryVar, mapKeyCodes, checkCondition,
        checkCode, numRows, getConsumeResultCode(numRows, input),
        keyIsUnique, declareLocalVars, moveNextValue, inputCodes)

      case LeftAnti => genAntiJoinCodes(entryVar, mapKeyCodes, checkCondition,
        checkCode, numRows, getConsumeResultCode(numRows, input),
        keyIsUnique, declareLocalVars, moveNextValue, inputCodes)

      case _: ExistenceJoin =>
        // declare and add the exists variable to resultVars
        val existsVar = ctx.freshName("exists")
        genExistenceJoinCodes(entryVar, existsVar, mapKeyCodes,
          checkCondition, checkCode, numRows, getConsumeResultCode(numRows,
            input :+ internals.newExprCode("", "false", existsVar, BooleanType)), keyIsUnique,
          declareLocalVars, moveNextValue, inputCodes)

      case _ => throw new IllegalArgumentException(
        s"HashJoin should not take $joinType as the JoinType")
    }

    // wrap in "do {...} while(false)" so that the code inside can break out with continue
    s"""
       |if (!$initMap) {
       |  $initMapCode
       |}
       |do {
       |  $checkMapSize$className $entryVar = null;
       |  $inputKeysCode
       |  $hashInit
       |  $mapLookupCode
       |  $entryConsume
       |} while (false);
    """.stripMargin
  }

  /**
   * Generate code to update a class object fields with given resultVars. If
   * accessors for fields have been generated (using <code>getColumnVars</code>)
   * then those can be passed for faster reads where required.
   *
   * @param objVar     the variable holding reference to the class object
   * @param columnVars accessors for object fields, if available
   * @param resultVars result values to be assigned to object fields
   * @param forKey     if true then update key fields else value fields
   * @param doCopy     if true then a copy of reference values is assigned
   *                   else only reference copy done
   * @param forInit    if true then this is for initialization of fields
   *                   after object creation so some checks can be skipped
   * @return code to assign objVar fields to given resultVars
   */
  def generateUpdate(objVar: String, columnVars: Seq[ExprCode],
      resultVars: Seq[ExprCode], forKey: Boolean,
      doCopy: Boolean = true, forInit: Boolean = true): String = {
    val fieldVars = if (forKey) classVars.take(valueIndex)
    else classVars.drop(valueIndex)

    val nullLocalVars = if (columnVars.isEmpty) {
      // get nullability from object fields
      fieldVars.map(e => genNullCode(s"$objVar.${internals.exprCodeIsNull(e._3)}", e._4))
    } else {
      // get nullability from already set local vars passed in columnVars
      columnVars.map(internals.exprCodeIsNull)
    }

    fieldVars.zip(nullLocalVars).zip(resultVars).map { case (((dataType, _,
    fieldVar, nullIdx), nullLocalVar), resultVar) =>
      if (nullIdx == -1) {
        // if incoming variable is null, then default will get assigned
        // because the variable will be initialized with the default
        genVarAssignCode(objVar, resultVar, internals.exprCodeValue(fieldVar),
          dataType, doCopy)
      } else if (nullIdx == NULL_NON_PRIM) {
        val varName = internals.exprCodeValue(fieldVar)
        s"""
          if (${internals.exprCodeIsNull(resultVar)}) {
            $objVar.$varName = null;
          } else {
            ${genVarAssignCode(objVar, resultVar, varName, dataType, doCopy)}
          }
        """
      } else {
        val nullVar = internals.exprCodeIsNull(fieldVar)
        // when initializing the object, no need to clear null mask
        val nullClear = if (forInit) ""
        else {
          s"""
            if ($nullLocalVar) {
              $objVar.$nullVar &= ~${genNullBitMask(nullIdx)};
            }
          """
        }
        s"""
          if (${internals.exprCodeIsNull(resultVar)}) {
            $objVar.$nullVar |= ${genNullBitMask(nullIdx)};
          } else {
            $nullClear
            ${genVarAssignCode(objVar, resultVar, internals.exprCodeValue(fieldVar),
                dataType, doCopy)}
          }
        """
      }
    }.mkString("\n")
  }

  private def genInnerJoinCodes(entryVar: String, mapKeyCodes: String,
      checkCondition: Option[ExprCode], checkCode: String, numRows: String,
      consumeResult: String, keyIsUnique: String, declareLocalVars: String,
      moveNextValue: String, inputCodes: String): String = {

    val consumeCode = checkCondition match {
      case None => consumeResult
      case Some(ev) =>
        s"""${ev.code.toString}
          if (!${internals.exprCodeIsNull(ev)} && ${internals.exprCodeValue(ev)}) {
            $consumeResult
          }"""
    }
    // loop through all the matches with moveNextValue
    s"""if ($entryVar == null) continue;

      $declareLocalVars

      $mapKeyCodes
      $inputCodes
      while (true) {
        $checkCode
        do { // single iteration loop meant for breaking out with "continue"
          $consumeCode
        } while (false);

        if ($keyIsUnique) break;

        // values will be repeatedly reassigned in the loop (if any)
        // while keys will remain the same
        $moveNextValue
      }"""
  }

  // scalastyle:off
  private def genOuterJoinCodes(entryVar: String, buildVars: Seq[ExprCode],
      buildInitCode: String, mapKeyCodes: String,
      checkCondition: Option[ExprCode], checkCode: String, numRows: String,
      consumeResult: String, keyIsUnique: String, declareLocalVars: String,
      moveNextValue: String, inputCodes: String, localValueVar: String): String = {
  // scalastyle:on
    val matchFailedCompletely = ctx.freshName("matchFailedCompletely")
    val consumeCode = checkCondition match {
      case None =>
        s"""$buildInitCode
          if ($entryVar == null) {
            // set null variables for outer join in failed match
            ${buildVars.map(ev => s"${internals.exprCodeIsNull(ev)} = true;").mkString("\n")}
          }
          $consumeResult"""

      case Some(ev) =>
        // assign null to entryVar if checkCondition fails so that it is
        // treated like an empty outer join match by subsequent code
        s"""
           ${ev.code.toString}
           if (${internals.exprCodeIsNull(ev)} || !${internals.exprCodeValue(ev)}) {
             if ($localValueVar.$nextValueVar != null) {
               continue;
             }
           } else {
             $matchFailedCompletely = false;
           }
          $buildInitCode

          //TODO:to tackle case where there is filter on build side
          //such that it is specifically looking for not null values
          // the outer join needs to be converted to inner join
          if ($entryVar == null || $matchFailedCompletely) {
            // set null variables for outer join in failed match
            ${buildVars.map(ev => s"${internals.exprCodeIsNull(ev)} = true;").mkString("\n")}
          }
          $consumeResult"""
    }
    // loop through all the matches with moveNextValue
    // null check for entryVar is already done inside mapKeyCodes
    s"""$declareLocalVars
      $mapKeyCodes
      $inputCodes
      ${checkCondition.map(_ => s"boolean $matchFailedCompletely = true;").getOrElse("")}
      while (true) {
        $checkCode
        do { // single iteration loop meant for breaking out with "continue"
          $consumeCode
        } while (false);

        if ($entryVar == null || $keyIsUnique) break;
        // values will be repeatedly reassigned in the loop (if any)
        // while keys will remain the same
        $moveNextValue
      }
      """
  }

  private def genSemiJoinCodes(entryVar: String, mapKeyCodes: String,
      checkCondition: Option[ExprCode], checkCode: String, numRows: String,
      consumeResult: String, keyIsUnique: String, declareLocalVars: String,
      moveNextValue: String, inputCodes: String): String = checkCondition match {

    case None =>
      // no key/value assignments required
      s"if ($entryVar == null) continue;\n$inputCodes\n$consumeResult"

    case Some(ev) =>
      val breakLoop = ctx.freshName("breakLoop")
      // need the key/value assignments for condition evaluation
      // loop through all the matches with moveNextValue
      s"""if ($entryVar == null) continue;

        $declareLocalVars

        $mapKeyCodes
        $inputCodes
        $breakLoop: while (true) {
          $checkCode
          do { // single iteration loop meant for breaking out with "continue"
            ${ev.code.toString}
            // consume only one result
            if (!${internals.exprCodeIsNull(ev)} && ${internals.exprCodeValue(ev)}) {
              $consumeResult
              break $breakLoop;
            }
          } while (false);

          if ($keyIsUnique) break;

          // values will be repeatedly reassigned in the loop (if any)
          // while keys will remain the same
          $moveNextValue
        }"""
  }

  private def genAntiJoinCodes(entryVar: String, mapKeyCodes: String,
      checkCondition: Option[ExprCode], checkCode: String, numRows: String,
      consumeResult: String, keyIsUnique: String, declareLocalVars: String,
      moveNextValue: String, inputCodes: String): String = checkCondition match {

    case None =>
      // success if no match for an anti-join (no value iteration)
      s"if ($entryVar != null) continue;\n$inputCodes\n$consumeResult"

    case Some(ev) =>
      val breakLoop = ctx.freshName("breakLoop")
      // need to check all failures for the condition outside the value
      // iteration loop, hence code layout is bit different from other joins
      val matched = ctx.freshName("matched")
      // need the key/value assignments for condition evaluation
      s"""$declareLocalVars

        $mapKeyCodes
        $inputCodes
        boolean $matched = false;
        if ($entryVar != null) {
          $breakLoop: while (true) {
            $checkCode
            do { // single iteration loop meant for breaking out with "continue"
              // fail if condition matches for any row
              ${ev.code.toString}
              if (!${internals.exprCodeIsNull(ev)} && ${internals.exprCodeValue(ev)}) {
                $matched = true;
                break $breakLoop;
              }
            } while (false);

            if ($keyIsUnique) break;

            // values will be repeatedly reassigned in the loop
            // while keys will remain the same
            $moveNextValue
          }
        }
        // anti-join failure if there is any match
        if ($matched) continue;

        $consumeResult"""
  }

  // scalastyle:off
  private def genExistenceJoinCodes(entryVar: String, existsVar: String,
      mapKeyCodes: String, checkCondition: Option[ExprCode], checkCode: String,
      numRows: String, consumeResult: String, keyIsUnique: String, declareLocalVars: String,
      moveNextValue: String, inputCodes: String): String = checkCondition match {
    // scalastyle:on

    case None =>
      // only one match needed, so no value iteration
      s"""final boolean $existsVar = ($entryVar != null);
        $inputCodes
        $consumeResult"""

    case Some(ev) =>
      val breakLoop = ctx.freshName("breakLoop")
      // need the key/value assignments for condition evaluation
      s"""$declareLocalVars

        $mapKeyCodes
        $inputCodes
        boolean $existsVar = false;
        if ($entryVar != null) {
          $breakLoop: while (true) {
            $checkCode
            do { // single iteration loop meant for breaking out with "continue"
              ${ev.code.toString}
              if (!${internals.exprCodeIsNull(ev)} && ${internals.exprCodeValue(ev)}) {
                // consume only one result
                $existsVar = true;
                break $breakLoop;
              }
            } while (false);

            if ($keyIsUnique) break;

            // values will be repeatedly reassigned in the loop (if any)
            // while keys will remain the same
            $moveNextValue
          }
        }
        $consumeResult"""
  }

  /**
   * Max, min for only integer types is supported. For non-primitives, the cost
   * of comparison will be significant so better to do a hash lookup.
   * Approximate types like float/double are not useful as hash key columns
   * and will be very rare, if at all, so ignored.
   */
  private def isIntegralType(dataType: DataType): Boolean = dataType match {
    case ByteType | ShortType | IntegerType | LongType |
         TimestampType | DateType => true
    case _ => false
  }

  private def isPrimitiveType(dataType: DataType): Boolean = dataType match {
    case BooleanType | ByteType | ShortType | IntegerType |
         LongType | FloatType | DoubleType | TimestampType | DateType => true
    case _ => false
  }

  private def genVarAssignCode(objVar: String, resultVar: ExprCode,
      varName: String, dataType: DataType, doCopy: Boolean): String = {
    // check for object field or local variable
    val colVar = if (varName.isEmpty) objVar
    else s"$objVar.$varName"
    genVarAssignCode(colVar, internals.exprCodeValue(resultVar), dataType, doCopy)
  }

  private def genVarAssignCode(colVar: String, resultVar: String,
      dataType: DataType, doCopy: Boolean): String = dataType match {
    // if doCopy is true, then create a copy of some non-primitives that just
    // holds a reference to UnsafeRow bytes (and can change under the hood)
    case StringType if doCopy && !multiMap =>
      s"$colVar = $resultVar.getBytes();"
    case StringType if !multiMap =>
      // copy just reference of the object if underlying byte[] is immutable
      val bytes = ctx.freshName("stringBytes")
      s"""byte[] $bytes = null;
        if ($resultVar == null || ($resultVar.getBaseOffset() == Platform.BYTE_ARRAY_OFFSET
            && ($bytes = (byte[])$resultVar.getBaseObject()).length == $resultVar.numBytes())) {
          $colVar = $bytes;
        } else {
          $colVar = $resultVar.getBytes();
        }"""
    // multimap holds a reference to UTF8String itself
    case StringType =>
      // copy just reference of the object if underlying byte[] is immutable
      ObjectHashMapAccessor.cloneStringIfRequired(resultVar, colVar, doCopy)
    case _: ArrayType | _: MapType | _: StructType if doCopy =>
      val javaType = internals.javaType(dataType, ctx)
      s"$colVar = ($javaType)($resultVar != null ? $resultVar.copy() : null);"
    case _: BinaryType if doCopy =>
      s"$colVar = (byte[])($resultVar != null ? $resultVar.clone() : null);"
    case _ =>
      s"$colVar = $resultVar;"
  }

  private def genNullBitMask(nullIdx: Int): String =
    if (nullIdx > 0) s"(1L << $nullIdx)" else "1L"

  private def genNullCode(colVar: String, nullIndex: Int): String = {
    if (nullIndex > 0) {
      s"(($colVar & (1L << $nullIndex)) != 0L)"
    } else if (nullIndex == 0) {
      s"(($colVar & 1L) == 1L)"
    } else if (nullIndex == NULL_NON_PRIM) {
      s"($colVar == null)"
    } else "false"
  }

  private def genNotNullCode(colVar: String, nullIndex: Int): String = {
    if (nullIndex > 0) {
      s"(($colVar & (1L << $nullIndex)) == 0L)"
    } else if (nullIndex == 0) {
      s"(($colVar & 1L) == 0L)"
    } else if (nullIndex == NULL_NON_PRIM) {
      s"($colVar != null)"
    } else "true"
  }

  private def hashSingleInt(colVar: String, nullVar: String,
      hashVar: String): String = {
    if (nullVar.isEmpty || nullVar == "false") {
      s"$hashVar = $hashingClass.fastHashInt($colVar);\n"
    } else {
      s"$hashVar = ($nullVar) ? -1 : $hashingClass.fastHashInt($colVar);\n"
    }
  }

  // noinspection SameParameterValue
  private def hashCodeSingleInt(hashExpr: String, nullVar: String): String = {
    if (nullVar.isEmpty || nullVar == "false") hashExpr
    else s"($nullVar) ? -1 : $hashExpr"
  }

  private def hashSingleLong(colVar: String, nullVar: String,
      hashVar: String): String = {
    if (nullVar.isEmpty || nullVar == "false") {
      s"$hashVar = $hashingClass.fastHashLong($colVar);\n"
    } else {
      s"$hashVar = ($nullVar) ? -1 : $hashingClass.fastHashLong($colVar);\n"
    }
  }

  private def addHashInt(hashExpr: String, nullVar: String,
      hashVar: String): String = {
    if (nullVar.isEmpty || nullVar == "false") {
      s"""
        $hashVar = ($hashVar ^ 0x9e3779b9) + ($hashExpr) +
            ($hashVar << 6) + ($hashVar >>> 2);
      """
    } else {
      s"""
        $hashVar = ($hashVar ^ 0x9e3779b9) + (($nullVar) ? -1 : ($hashExpr)) +
            ($hashVar << 6) + ($hashVar >>> 2);
      """
    }
  }

  private def addHashLong(hashExpr: String, nullVar: String,
      hashVar: String): String = {
    val longVar = ctx.freshName("longVar")
    if (nullVar.isEmpty || nullVar == "false") {
      s"""
        final long $longVar = $hashExpr;
        $hashVar = ($hashVar ^ 0x9e3779b9) + (int)($longVar ^ ($longVar >>> 32)) +
            ($hashVar << 6) + ($hashVar >>> 2);
      """
    } else {
      s"""
        final long $longVar;
        $hashVar = ($hashVar ^ 0x9e3779b9) + (($nullVar) ? -1
            : (int)(($longVar = ($hashExpr)) ^ ($longVar >>> 32))) +
            ($hashVar << 6) + ($hashVar >>> 2);
      """
    }
  }

  private def genEqualsCode(
      thisVar: String, thisColVar: String, thisNullVar: String,
      otherVar: String, otherColVar: String, otherNullVar: String,
      nullIndex: Int, isPrimitive: Boolean, dataType: DataType): String = {
    // check for object field or local variable
    val otherCol = if (otherColVar.isEmpty) otherVar
    else s"$otherVar.$otherColVar"
    val otherColNull = if (otherColVar.isEmpty) otherNullVar
    else s"$otherVar.$otherNullVar"
    val equalsCode = if (isPrimitive) s"($thisColVar == $otherCol)"
    else dataType match {
      // strings are stored as raw byte arrays
      case StringType if !multiMap =>
        val byteMethodsClass = classOf[ByteArrayMethods].getName
        if (thisVar.isEmpty) {
          // left side is a UTF8String while right side is byte array
          s"""$thisColVar.numBytes() == $otherCol.length
            && $byteMethodsClass.arrayEquals($thisColVar.getBaseObject(),
               $thisColVar.getBaseOffset(), $otherCol,
               Platform.BYTE_ARRAY_OFFSET, $otherCol.length)"""
        } else {
          // both sides are raw byte arrays
          s"""$thisColVar.length == $otherCol.length
            && $byteMethodsClass.arrayEquals($thisColVar,
               Platform.BYTE_ARRAY_OFFSET, $otherCol,
               Platform.BYTE_ARRAY_OFFSET, $otherCol.length)"""
        }
      case _ => s"$thisColVar.equals($otherCol)"
    }
    if (nullIndex == -1 || thisNullVar.isEmpty || thisNullVar == "false") {
      equalsCode
    } else if (nullIndex == NULL_NON_PRIM) {
      s"""($thisColVar != null ? ($otherCol != null && $equalsCode)
           : ($otherCol) == null)"""
    } else {
      val notNullCode = if (thisVar.isEmpty) s"!$thisNullVar"
      else genNotNullCode(thisNullVar, nullIndex)
      val otherNotNullCode = genNotNullCode(otherColNull, nullIndex)
      s"""($notNullCode ? ($otherNotNullCode && $equalsCode)
           : !$otherNotNullCode)"""
    }
  }
}

object ObjectHashMapAccessor {

  @tailrec
  /** return true if the plan returns immutable objects so copy can be avoided */
  def providesImmutableObjects(plan: SparkPlan): Boolean = plan match {
    // For SnappyData row tables there is no need to make a copy of
    // non-primitive values into the map since they are immutable.
    // Also checks for other common plans known to provide immutable objects.
    // Cannot do the same for column tables since it will end up holding
    // the whole column buffer for a single value which can cause doom
    // for eviction. For off-heap it will not work at all and can crash
    // unless a reference to the original column ByteBuffer is retained.
    // TODO: can be extended for more plans as per their behaviour.
    case _: RowTableScan | _: HashJoinExec => true
    case FilterExec(_, c) => providesImmutableObjects(c)
    case ProjectExec(_, c) => providesImmutableObjects(c)
    case _ => false
  }

  def cloneStringIfRequired(stringVar: String, colVar: String, doCopy: Boolean): String = {
    // If UTF8String is just a wrapper around a byte array then the underlying
    // bytes are immutable, so can use a reference copy. This is because all cases
    // of underlying bytes being mutable are when the UTF8String is part of a larger
    // structure like UnsafeRow or byte array using ColumnEncoding or from Parquet buffer
    // and in all those cases the UTF8String will point to a portion of the full buffer.
    if (doCopy) {
      s"""if ($stringVar == null || ($stringVar.getBaseOffset() == Platform.BYTE_ARRAY_OFFSET
            && ((byte[])$stringVar.getBaseObject()).length == $stringVar.numBytes())) {
          $colVar = $stringVar;
        } else {
          $colVar = $stringVar.clone();
        }"""
    } else s"$colVar = $stringVar;"
  }
}
