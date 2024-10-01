// SPDX-License-Identifier: Apache-2.0

package chisel3.util.experimental

import chisel3._
import chisel3.probe.{Probe, RWProbe}
import chisel3.reflect.DataMirror
import chisel3.Data.ProbeInfo
import chisel3.experimental.{annotate, requireIsHardware, skipPrefix, BaseModule, ChiselAnnotation, SourceInfo}
import chisel3.internal.{Builder, BuilderContextCache, NamedComponent, Namespace}
import chisel3.internal.binding.{CrossModuleBinding, PortBinding, ConditionalDeclarable}
import firrtl.transforms.{DontTouchAnnotation, NoDedupAnnotation}
import chisel3.internal.firrtl.ir.Block
import firrtl.passes.wiring.{SinkAnnotation, SourceAnnotation}
import firrtl.annotations.{ComponentName, ModuleName}

/** An exception related to BoringUtils
  * @param message the exception message
  */
class BoringUtilsException(message: String) extends Exception(message)

/** Utilities for generating synthesizable cross module references that "bore" through the hierarchy. The underlying
  * cross module connects are handled by FIRRTL's Wiring Transform.
  *
  * Consider the following example where you want to connect a component in one module to a component in another. Module
  * `Constant` has a wire tied to `42` and `Expect` will assert unless connected to `42`:
  * {{{
  * class Constant extends Module {
  *   val io = IO(new Bundle{})
  *   val x = Wire(UInt(6.W))
  *   x := 42.U
  * }
  * class Expect extends Module {
  *   val io = IO(new Bundle{})
  *   val y = Wire(UInt(6.W))
  *   y := 0.U
  *   // This assertion will fail unless we bore!
  *   chisel3.assert(y === 42.U, "y should be 42 in module Expect")
  * }
  * }}}
  *
  * We can then connect `x` to `y` using [[BoringUtils]] without modifiying the Chisel IO of `Constant`, `Expect`, or
  * modules that may instantiate them. There are two approaches to do this:
  *
  * 1. Hierarchical boring using BoringUtils.bore
  *
  * 2. Non-hierarchical boring using [[BoringUtils.addSink]]/[[BoringUtils.addSource]]
  *
  * ===Hierarchical Boring===
  *
  * Hierarchical boring involves connecting one sink instance to another source instance in a parent module. Below,
  * module `Top` contains an instance of `Constant` and `Expect`. Using BoringUtils.bore, we can connect
  * `constant.x` to `expect.y`.
  *
  * {{{
  * class Top extends Module {
  *   val io = IO(new Bundle{})
  *   val constant = Module(new Constant)
  *   val expect = Module(new Expect)
  *   BoringUtils.bore(constant.x, Seq(expect.y))
  * }
  * }}}
  *
  * Bottom-up boring involves boring a sink in a child instance to the current module, where it can be assigned from.
  * Using BoringUtils.bore, we can connect from `constant.x` to `mywire`.
  *
  * {{{
  * class Top extends Module {
  *   val io = IO(new Bundle { val foo = UInt(3.W) })
  *   val constant = Module(new Constant)
  *   io.foo := BoringUtils.bore(constant.x)
  * }
  * }}}
  *
  * ===Non-hierarchical Boring===
  *
  * Non-hierarchical boring involves connections from sources to sinks that cannot see each other. Here, `x` is
  * described as a source and given a name, `uniqueId`, and `y` is described as a sink with the same name. This is
  * equivalent to the hierarchical boring example above, but requires no modifications to `Top`.
  *
  * {{{
  * class Constant extends Module {
  *   val io = IO(new Bundle{})
  *   val x = Wire(UInt(6.W))
  *   x := 42.U
  *   BoringUtils.addSource(x, "uniqueId")
  * }
  * class Expect extends Module {
  *   val io = IO(new Bundle{})
  *   val y = Wire(UInt(6.W))
  *   y := 0.U
  *   // This assertion will fail unless we bore!
  *   chisel3.assert(y === 42.U, "y should be 42 in module Expect")
  *   BoringUtils.addSink(y, "uniqueId")
  * }
  * class Top extends Module {
  *   val io = IO(new Bundle{})
  *   val constant = Module(new Constant)
  *   val expect = Module(new Expect)
  * }
  * }}}
  *
  * ==Comments==
  *
  * Both hierarchical and non-hierarchical boring emit FIRRTL annotations that describe sources and sinks. These are
  * matched by a `name` key that indicates they should be wired together. Hierarchical boring safely generates this name
  * automatically. Non-hierarchical boring unsafely relies on user input to generate this name. Use of non-hierarchical
  * naming may result in naming conflicts that the user must handle.
  *
  * The automatic generation of hierarchical names relies on a global, mutable namespace. This is currently persistent
  * across circuit elaborations.
  */
object BoringUtils {
  /* A global namespace for boring ids */
  private[chisel3] case object CacheKey extends BuilderContextCache.Key[Namespace]
  private def boringNamespace = Builder.contextCache.getOrElseUpdate(CacheKey, Namespace.empty)

  /* Get a new name (value) from the namespace */
  private def newName(value: String): String = {
    boringNamespace.name(value)
  }
  /* True if the requested name (value) exists in the namespace */
  private def checkName(value: String): Boolean = boringNamespace.contains(value)

  /** Add a named source cross module reference
    * @param component source circuit component
    * @param name unique identifier for this source
    * @param disableDedup disable deduplication of this source component (this should be true if you are trying to wire
    * from specific identical sources differently)
    * @param uniqueName if true, this will use a non-conflicting name from the global namespace
    * @return the name used
    * @note if a uniqueName is not specified, the returned name may differ from the user-provided name
    */
  @deprecated(
    "Please use the new Boring API instead (BoringUtils.bore(source)). This will be removed in Chisel 7.0",
    "Chisel 6.0"
  )
  def addSource(
    component:    NamedComponent,
    name:         String,
    disableDedup: Boolean = false,
    uniqueName:   Boolean = false
  ): String = {

    val id = if (uniqueName) { newName(name) }
    else { name }
    val maybeDedup =
      if (disableDedup) { Seq(new ChiselAnnotation { def toFirrtl = NoDedupAnnotation(component.toNamed.module) }) }
      else { Seq[ChiselAnnotation]() }
    val annotations =
      Seq(
        new ChiselAnnotation {
          def toFirrtl = SourceAnnotation(component.toNamed, id)
        },
        new ChiselAnnotation { def toFirrtl = DontTouchAnnotation(component.toNamed) }
      ) ++ maybeDedup

    annotations.foreach(annotate(_))
    id
  }

  /** Add a named sink cross module reference. Multiple sinks may map to the same source.
    * @param component sink circuit component
    * @param name unique identifier for this sink that must resolve to
    * @param disableDedup disable deduplication of this sink component (this should be true if you are trying to wire
    * specific, identical sinks differently)
    * @param forceExists if true, require that the provided `name` parameter already exists in the global namespace
    * @throws BoringUtilsException if name is expected to exist and it doesn't
    */
  @deprecated(
    "Please use the new Boring API instead (BoringUtils.bore(source)). This will be removed in Chisel 7.0",
    "Chisel 6.0"
  )
  def addSink(
    component:    InstanceId,
    name:         String,
    disableDedup: Boolean = false,
    forceExists:  Boolean = false
  ): Unit = {

    if (forceExists && !checkName(name)) {
      throw new BoringUtilsException(s"Sink ID '$name' not found in BoringUtils ID namespace")
    }
    def moduleName = component.toNamed match {
      case c: ModuleName    => c
      case c: ComponentName => c.module
      case _ => throw new ChiselException("Can only add a Module or Component sink", null)
    }
    val maybeDedup =
      if (disableDedup) { Seq(new ChiselAnnotation { def toFirrtl = NoDedupAnnotation(moduleName) }) }
      else { Seq[ChiselAnnotation]() }
    val annotations =
      Seq(new ChiselAnnotation {
        def toFirrtl = SinkAnnotation(component.toNamed, name)
      }) ++ maybeDedup
    annotations.foreach(annotate(_))
  }

  /** Connect a source to one or more sinks
    * @param source a source component
    * @param sinks one or more sink components
    * @return the name of the signal used to connect the source to the
    * sinks
    * @note the returned name will be based on the name of the source
    * component
    */
  @deprecated(
    "Please use the new Boring API instead (BoringUtils.bore(source)). This will be removed in Chisel 7.0",
    "Chisel 6.0"
  )
  def bore(source: Data, sinks: Seq[Data]): String = {
    val boringName =
      try {
        source.instanceName
      } catch {
        case _: Exception => "bore"
      }
    val genName = addSource(source, boringName, true, true)
    sinks.foreach(addSink(_, genName, true, true))
    genName
  }

  private def boreOrTap[A <: Data](
    source:      A,
    createProbe: Option[ProbeInfo] = None,
    isDrive:     Boolean = false
  )(
    implicit si: SourceInfo
  ): A = {
    def parent(d: Data): BaseModule = d.topBinding.location.get
    def parentBlock(d: Data) = d.topBindingOpt.asInstanceOf[ConditionalDeclarable].parentBlock
    def purePortTypeBase = if (createProbe.nonEmpty) Output(chiselTypeOf(source))
    else if (DataMirror.hasOuterFlip(source)) Flipped(chiselTypeOf(source))
    else chiselTypeOf(source)
    def purePortType = createProbe match {
      case Some(pi) =>
        // If the source is already a probe, don't double wrap it in a probe.
        purePortTypeBase.probeInfo match {
          case Some(_)             => purePortTypeBase
          case None if pi.writable => RWProbe(purePortTypeBase)
          case None                => Probe(purePortTypeBase)
        }
      case None => purePortTypeBase
    }
    def isPort(d: Data): Boolean = d.topBindingOpt match {
      // case Some(PortBinding(_, b)) => { println(s"isPort(d=${d}), block=${b} (${b.map(_.commands)})"); true }
      case Some(PortBinding(_, _)) => true
      case _                    => false
    }
    def isDriveDone(d: Data): Boolean = {
      isDrive && isPort(d) && DataMirror.directionOf(d) == ActualDirection.Input
    }
    def boringError(module: BaseModule): Unit = {
      (module.fullyClosedErrorMessages ++ Seq(
        (si, s"Can only bore into modules that are not fully closed: ${module.name} was fully closed")
      )).foreach {
        case (sourceInfo, msg) => Builder.error(msg)(sourceInfo)
      }
    }
    def drill(source: A, path: Seq[BaseModule], connectionLocation: Seq[BaseModule], up: Boolean): A = {
      println(path.zip(connectionLocation))
      path.zip(connectionLocation).foldLeft(source) {
case x @ (rhs, (module, conLoc)) =>
  println(s"rhs=${rhs}, module=${module}, conLoc=${conLoc}")
  rhs.topBindingOpt match {
    case Some(cd: ConditionalDeclarable) => println(s"CD binding on rhs=${rhs}, block=${cd.parentBlock} (${cd.parentBlock.map(_.commands.result())})")
    case _ => ()
  }
  x match {
        case (rhs, (module, conLoc)) if (module.isFullyClosed) => boringError(module); DontCare.asInstanceOf[A]
        case (rhs, (module, _))
            if ((up || isDriveDone(rhs)) && module == path(0) && isPort(rhs) &&
              (!createProbe.nonEmpty || !createProbe.get.writable)) => {
          // When drilling from the original source, or driving to the sink, if it's already a port just return it.
          // As an exception, insist rwTaps are done from within the module and exported out.
          // require(false, "testing")
          rhs
        }
        case (rhs, (module, conLoc)) =>
          skipPrefix { // so `lcaSource` isn't in the name of the secret port
            if (!up && createProbe.nonEmpty && createProbe.get.writable) {
              Builder.error("Cannot drill writable probes upwards.")
            }

            /** create a port, and drill up. */
            // if drilling down, don't drill Probe types
            //Builder.pushBlock(module._block.get)
            println(s"conLoc == module: ${conLoc == module}")
            // val block = if (Builder.currentModule.isEmpty || Builder.currentModule.contains(conLoc)) Builder.currentBlock.get else module._block.get
            def createAndConnect : A = {
            val bore = if (up) module.createSecretIO(purePortType) else module.createSecretIO(Flipped(purePortTypeBase))
            println(s"created port ${bore} in ${module} ${if (up) "UP" else "DOWN"}")
            println(s"port parent: ${parent(bore)}")
            module.addSecretIO(bore)
            if (isDrive) {
              conLoc.asInstanceOf[RawModule].secretConnection(rhs, bore)
            } else {
              conLoc.asInstanceOf[RawModule].secretConnection(bore, rhs)
            }
             bore
            }
            
            // Connect goes at destination if down, source if up.
            //createAndConnect
            if (Builder.currentModule.isEmpty || Builder.currentModule.contains(conLoc)/* || Builder.currentBlock.contains(module._block)*/) {
              println("---------- direct createAndConnect")
              createAndConnect
            }
            else {
              println("---------- placement createAndConnect")
              val rwprobeTime = createProbe.nonEmpty && createProbe.get.writable
              println(s"rwprobeTime: ${rwprobeTime}")
              val block = (up, conLoc == module || rwprobeTime) match {
                case (_, false) => module._block.get
                case (true, _) => 
                  rhs.topBindingOpt match {
                    case Some(cd: ConditionalDeclarable) if cd.parentBlock.nonEmpty && !rwprobeTime => println(s"cd.parentBlock: ${cd.parentBlock}, ${cd.parentBlock.map(_.commands.result())})"); cd.parentBlock.get
                    case _ => module.asInstanceOf[RawModule]._body
                  }
                case _ => module.asInstanceOf[RawModule]._body
              }
              // (up, conLoc == module) match {
              // case (true, 
              // })) { createAndConnect }
              conLoc.asInstanceOf[RawModule].withRegion(block) { createAndConnect }
                // createAndConnect
            }
          }
      }
}
    }

    requireIsHardware(source)
    val thisModule = Builder.currentModule.get
    source.topBindingOpt match {
      case None =>
        Builder.error(s"Cannot bore from ${source._errorContext}")
      case Some(CrossModuleBinding) =>
        Builder.error(
          s"Cannot bore across a Definition/Instance boundary:${thisModule._errorContext} cannot access ${source}"
        )
      case _ => // Actually bore
    }
    if (parent(source) == thisModule) {
      // No boring to do
      if (createProbe.nonEmpty && !DataMirror.isFullyAligned(source)) {
        // Create aligned wire if source isn't aligned.  This ensures result has same type regardless of origin.
        val bore = Wire(purePortTypeBase)
        bore :#= source
        return bore
      }
      return source
    }

    val lcaResult = DataMirror.findLCAPaths(source, thisModule)
    if (lcaResult.isEmpty) {
      Builder.error(s"Cannot bore from $source to ${thisModule.name}, as they do not share a least common ancestor")
    }
    val (upPath, downPath) = lcaResult.get
    val lcaSource = drill(source, upPath.dropRight(1), upPath.dropRight(1), up = !isDrive)
    val sink = drill(lcaSource, downPath.reverse.tail, downPath.reverse, up = isDrive)

    if (
      createProbe.nonEmpty || DataMirror.hasProbeTypeModifier(purePortTypeBase) ||
      DataMirror.isProperty(purePortTypeBase)
    ) {
      sink
    } else {
      // Creating a wire to assign the result to.  We will return this.
      val bore = Wire(purePortTypeBase)
      if (isDrive) {
        thisModule.asInstanceOf[RawModule].secretConnection(sink, bore)
      } else {
        thisModule.asInstanceOf[RawModule].secretConnection(bore, sink)
      }
      bore
    }
  }

  /** Access a source [[Data]] that may or may not be in the current module.  If
    * this is in a child module, then create ports to allow access to the
    * requested source.
    */
  def bore[A <: Data](source: A)(implicit si: SourceInfo): A = {
    boreOrTap(source, createProbe = None)
  }

  /** Access a sink [[Data]] for driving that may or may not be in the current module.
    *
    * If the sink is in a child module, than create input ports to allow driving the requested sink.
    *
    * Note that the sink may not be a probe, and [[rwTap]] should be used instead.
    */
  def drive[A <: Data](sink: A)(implicit si: SourceInfo): A = {
    require(!DataMirror.hasProbeTypeModifier(sink), "cannot drive a probe from BoringUtils.drive")
    boreOrTap(sink, createProbe = None, isDrive = true)
  }

  /** Access a source [[Data]] that may or may not be in the current module.  If
    * this is in a child module, then create read-only probe ports to allow
    * access to the requested source.
    *
    * Returns a probe Data type.
    */
  def tap[A <: Data](source: A)(implicit si: SourceInfo): A = {
    val tapIntermediate = skipPrefix {
      boreOrTap(source, createProbe = Some(ProbeInfo(writable = false, color = None)))
    }
    if (tapIntermediate.probeInfo.nonEmpty) {
      tapIntermediate
    } else {
      probe.ProbeValue(tapIntermediate)
    }
  }

  /** Access a source [[Data]] that may or may not be in the current module.  If
    * this is in a child module, then create write-only probe ports to allow
    * access to the requested source. Supports downward accesses only.
    *
    * Returns a probe Data type.
    */
  def rwTap[A <: Data](source: A)(implicit si: SourceInfo): A = {
    val tapIntermediate = skipPrefix { boreOrTap(source, createProbe = Some(ProbeInfo(writable = true, color = None))) }
    if (tapIntermediate.probeInfo.nonEmpty) {
      tapIntermediate
    } else {
      probe.RWProbeValue(tapIntermediate)
    }
  }

  /** Access a source [[Data]] that may or may not be in the current module.  If
    * this is in a child module, then create read-only probe ports to allow
    * access to the requested source.
    *
    * Returns a non-probe Data type.
    */
  def tapAndRead[A <: Data](source: A)(implicit si: SourceInfo): A = {
    val tapIntermediate = skipPrefix {
      boreOrTap(source, createProbe = Some(ProbeInfo(writable = false, color = None)))
    }
    println(s"tapAndRead, after boreOrTap: ${tapIntermediate}")
    if (tapIntermediate.probeInfo.nonEmpty) {
      println("read!")
      probe.read(tapIntermediate)
    } else {
      tapIntermediate
    }
  }

}
