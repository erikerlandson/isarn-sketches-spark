/*
Copyright 2017-2020 Erik Erlandson
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.isarnproject.sketches.spark {

import org.apache.spark.sql.types.SQLUserDefinedType
import org.apache.spark.sql.expressions.Aggregator
import org.apache.spark.sql.{ Encoder, Encoders }
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import org.isarnproject.sketches.java.{ TDigest => BaseTD }

import org.apache.spark.isarnproject.sketches.udtdev.TDigestUDT

class TDigestAggregator[V](compression: Double, maxDiscrete: Int)(
  implicit
    vnum: Numeric[V])
  extends
    Aggregator[V, TDigest, TDigest]
{
  def zero: TDigest = new TDigest(compression, maxDiscrete)
  def reduce(td: TDigest, v: V): TDigest = {
    td.update(vnum.toDouble(v))
    td
  }
  def merge(td1: TDigest, td2: TDigest): TDigest = {
    td1.merge(td2)
    td1
  }
  def finish(td: TDigest): TDigest = td
  def bufferEncoder: Encoder[TDigest] = ExpressionEncoder[TDigest]()
  def outputEncoder: Encoder[TDigest] = ExpressionEncoder[TDigest]()
}

// the only reason for this shim class is to link it to TDigestUDT
@SQLUserDefinedType(udt = classOf[TDigestUDT])
class TDigest(
    compression: Double,
    maxDiscrete: Int,
    cent: Array[Double],
    mass: Array[Double])
  extends
    BaseTD(compression, maxDiscrete, cent, mass) {

  def this(
      compression: Double = TDigest.compressionDefault,
      maxDiscrete: Int = TDigest.maxDiscreteDefault) {
    this(compression, maxDiscrete, null, null)
  }
}

object TDigest {
  val compressionDefault: Double = 0.5
  val maxDiscreteDefault: Int = 0
}

} // package

// I need to accept that Spark is never going to fix this.
package org.apache.spark.isarnproject.sketches.udtdev {
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeArrayData}

import org.isarnproject.sketches.spark.TDigest

class TDigestUDT extends UserDefinedType[TDigest] {
  def userClass: Class[TDigest] = classOf[TDigest]

  override def pyUDT: String = "isarnproject.sketches.udt.tdigest.TDigestUDT"

  override def typeName: String = "tdigest"

  override def equals(obj: Any): Boolean = {
    obj match {
      case _: TDigestUDT => true
      case _ => false
    }
  }

  override def hashCode(): Int = classOf[TDigestUDT].getName.hashCode()

  private[spark] override def asNullable: TDigestUDT = this

  def sqlType: DataType = StructType(
    StructField("compression", DoubleType, false) ::
    StructField("maxDiscrete", IntegerType, false) ::
    StructField("cent", ArrayType(DoubleType, false), false) ::
    StructField("mass", ArrayType(DoubleType, false), false) ::
    Nil)

  def serialize(td: TDigest): Any = {
    val row = new GenericInternalRow(4)
    row.setDouble(0, td.getCompression())
    row.setInt(1, td.getMaxDiscrete())
    val clustX = java.util.Arrays.copyOf(td.getCentUnsafe(), td.size())
    row.update(2, UnsafeArrayData.fromPrimitiveArray(clustX))
    val clustM = java.util.Arrays.copyOf(td.getMassUnsafe(), td.size())
    row.update(3, UnsafeArrayData.fromPrimitiveArray(clustM))
    row
  }

  def deserialize(datum: Any): TDigest = datum match {
    case row: InternalRow =>
      require(row.numFields == 4, s"expected row length 4, got ${row.numFields}")
      val compression = row.getDouble(0)
      val maxDiscrete = row.getInt(1)
      val clustX = row.getArray(2).toDoubleArray()
      val clustM = row.getArray(3).toDoubleArray()
      new TDigest(compression, maxDiscrete, clustX, clustM)
    case u => throw new Exception(s"failed to deserialize: $u")
  }
}

} // package
