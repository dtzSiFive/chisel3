// SPDX-License-Identifier: Apache-2.0

package chiselTests

import chiselTests.{ChiselFlatSpec, MatchesAndOmits}
import circt.stage.ChiselStage

import chisel3._

class IntrinsicSpec extends ChiselFlatSpec with MatchesAndOmits {
  behavior.of("Intrinsics")

  it should "support a simple intrinsic statement" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new RawModule {
      val test = Intrinsic("test")
    })

    matchesAndOmits(chirrtl)("intrinsic(test)")()
  }

  it should "support intrinsic statements with arguments" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new RawModule {
      val f = 5.U + 3.U
      val g = f + 2.U
      val test2 = Intrinsic("test", f, g)
    })

    matchesAndOmits(chirrtl)("intrinsic(test, f, g)")()
  }
  it should "support intrinsic statements with parameters and arguments" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new RawModule {
      val f = 5.U + 3.U
      val g = f + 2.U
      // TODO: `params = `, implicit conversions, overloads, mixing, oh my!
      val test2 = Intrinsic("test", params = Map("Foo" -> 5), f, g)
    })

    matchesAndOmits(chirrtl)("intrinsic(test<Foo = 5>, f, g)")()
  }

  it should "support intrinsic expressions" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new RawModule {
      val f = 5.U + 3.U
      val g = f + 2.U
      val test2 = IntrinsicExpr("test", UInt(32.W), f, g) + 3.U
    })

    matchesAndOmits(chirrtl)(" = intrinsic(test : UInt<32>, f, g)")()
  }

  it should "support intrinsic expressions with parameters and arguments" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new RawModule {
      val f = 5.U + 3.U
      val g = f + 2.U
      val test2 = IntrinsicExpr("test", UInt(32.W), params = Map("foo" -> "bar", "x" -> 5), f, g) + 3.U
    })

    matchesAndOmits(chirrtl)(" = intrinsic(test<foo = \"bar\", x = 5> : UInt<32>, f, g)")()
  }
}
