package chisel3

import scala.language.experimental.macros

import chisel3._
import chisel3.experimental.{requireIsChiselType, Param, SourceInfo}
import chisel3.internal.firrtl.ir._
import chisel3.internal.{Builder, OpBinding}
import chisel3.internal.Builder.{pushCommand, pushOp}

object Intrinsic {
  def apply(intrinsic: String, params: Map[String, Param], data: Data*)(implicit sourceInfo: SourceInfo): Unit = {
    pushCommand(DefIntrinsic(sourceInfo, intrinsic, data.map(_.ref), params))
  }
  def apply(intrinsic: String, data: Data*)(implicit sourceInfo: SourceInfo): Unit = {
    apply(intrinsic, Map[String, Param](), data: _*)
  }
}

object IntrinsicExpr {
  def apply[T <: Data](
    intrinsic: String,
    ret:       => T,
    params:    Map[String, Param],
    data:      Data*
  )(
    implicit sourceInfo: SourceInfo
  ): T = {
    val prevId = Builder.idGen.value
    val t = ret // evaluate once (passed by name)
    requireIsChiselType(t, "intrinsic type")
    val int = if (!t.mustClone(prevId)) t else t.cloneTypeFull

    int.bind(OpBinding(Builder.forcedUserModule, Builder.currentWhen))
    pushCommand(DefIntrinsicExpr(sourceInfo, intrinsic, int, data.map(_.ref), params))
    int
  }

  def apply[T <: Data](intrinsic: String, ret: => T, data: Data*)(implicit sourceInfo: SourceInfo): T = {
    apply(intrinsic, ret, Map[String, Param](), data: _*)
  }
}
