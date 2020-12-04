// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.gobra.translator.encodings.interfaces

import viper.gobra.translator.encodings.LeafTypeEncoding
import org.bitbucket.inkytonik.kiama.==>
import viper.gobra.ast.internal.theory.TypeHead
import viper.gobra.ast.{internal => in}
import viper.gobra.reporting.{DynamicValueNotASubtypeReason, SafeTypeAssertionsToInterfaceNotSucceedingReason, Source, TypeAssertionError}
import viper.gobra.theory.Addressability.{Exclusive, Shared}
import viper.gobra.translator.interfaces.{Collector, Context}
import viper.gobra.translator.util.ViperWriter.CodeWriter
import viper.silver.verifier.ErrorReason
import viper.silver.{ast => vpr}

class InterfaceEncoding extends LeafTypeEncoding {

  import viper.gobra.translator.util.ViperWriter.CodeLevel._
  import viper.gobra.translator.util.TypePatterns._

  private val poly: PolymorphValueComponent = new PolymorphValueComponentImpl
  private val types: TypeComponent = new TypeComponentImpl

  override def finalize(col: Collector): Unit = {
    poly.finalize(col)
    types.finalize(col)
  }

  /**
    * Translates a type into a Viper type.
    */
  override def typ(ctx: Context): in.Type ==> vpr.Type = {
    case ctx.Interface(_) / m =>
      m match {
        case Exclusive => ctx.tuple.typ(Vector(poly.typ()(ctx), types.typ()(ctx)))
        case Shared    => vpr.Ref: vpr.Type
      }

    case in.SortT / m =>
      m match {
        case Exclusive => types.typ()(ctx)
        case Shared    => vpr.Ref
      }
  }

  /**
    * Encodes the comparison of two expressions.
    * The first and second argument is the left-hand side and right-hand side, respectively.
    * An encoding for type T should be defined at left-hand sides of type T and exclusive *T.
    * (Except the encoding of pointer types, which is not defined at exclusive *T to avoid a conflict).
    *
    * The default implements:
    * [lhs: interface{...} == rhs: interface{...}] -> [lhs] == [rhs]
    * [lhs: *interface{...}° == rhs: *interface{...}] -> [lhs] == [rhs]
    *
    * [itf: interface{...} == oth: T] -> [itf == toInterface(oth)]
    * [oth: T == itf: interface{...}] -> [itf == toInterface(oth)]
    */
  override def equal(ctx: Context): (in.Expr, in.Expr, in.Node) ==> CodeWriter[vpr.Exp] = super.equal(ctx) orElse {
    case (itf :: ctx.Interface(_), oth :: ctx.NotInterface(), src) =>
      equal(ctx)(itf, in.ToInterface(oth, itf.typ)(src.info), src)

    case (oth :: ctx.NotInterface(), itf :: ctx.Interface(_), src) =>
      equal(ctx)(itf, in.ToInterface(oth, itf.typ)(src.info), src)
  }

  /**
    * Encodes expressions as values that do not occupy some identifiable location in memory.
    *
    * To avoid conflicts with other encodings, a leaf encoding for type T should be defined at:
    * (1) exclusive operations on T, which includes literals and default values
    *
    * R[ dflt(interface{...}°) ] -> tuple2(null, nilT)
    * R[ nil: interface{...} ] -> tuple2(null, nilT)
    * R[ toInterface(e: interface{...}, _) ] -> [e]
    * R[ toInterface(e: T, _) ] -> tuple2([e], TYPE(T))
    * R[ e.(interface{...}) ] -> assert behavioralSubtype(TYPE_OF([e]), T); [e]
    * R[ e.(T) ] -> assert behavioralSubtype(TYPE_OF([e]), T); VALUE_OF([e], [T])
    * R[ typeOf(e) ] -> TYPE_OF([e])
    * Encoding of type expression is straightforward
    */
  override def expr(ctx: Context): in.Expr ==> CodeWriter[vpr.Exp] = {

    def goE(x: in.Expr): CodeWriter[vpr.Exp] = ctx.expr.translate(x)(ctx)

    default(super.expr(ctx)){
      case n@ (  (_: in.DfltVal) :: ctx.Interface(_) / Exclusive
               | (_: in.NilLit) :: ctx.Interface(_)  ) =>
        val (pos, info, errT) = n.vprMeta
        val value = poly.box(vpr.NullLit()(pos, info, errT))(pos, info, errT)(ctx)
        val typ = types.typeApp(TypeHead.NilHD)(pos, info, errT)(ctx)
        unit(ctx.tuple.create(Vector(value, typ))(pos, info, errT)): CodeWriter[vpr.Exp]

      case in.ToInterface(exp :: ctx.Interface(_), _) =>
        goE(exp)

      case n@ in.ToInterface(exp, _) =>
        val (pos, info, errT) = n.vprMeta
        for {
          dynValue <- goE(exp)
          value = poly.box(dynValue)(pos, info, errT)(ctx)
          typ = types.typeToExpr(exp.typ)(pos, info, errT)(ctx)
        } yield ctx.tuple.create(Vector(value, typ))(pos, info, errT)

      case n@ in.TypeAssertion(exp, t) =>
        val (pos, info, errT) = n.vprMeta
        val errorT = (x: Source.Verifier.Info, _: ErrorReason) =>
          TypeAssertionError(x).dueTo(DynamicValueNotASubtypeReason(x))
        for {
          arg <- goE(exp)
          dynType = typeOf(arg)(pos, info, errT)(ctx)
          staticType = types.typeToExpr(t)(pos, info, errT)(ctx)
          _ <- assert(types.behavioralSubtype(dynType, staticType)(pos, info, errT)(ctx), errorT)
          res = t match {
            case ctx.Interface(_) => arg
            case _ => valueOf(arg, ctx.typeEncoding.typ(ctx)(t))(pos, info, errT)(ctx)
          }
        } yield res

      case n@ in.TypeOf(exp) =>
        val (pos, info, errT) = n.vprMeta
        for {
          arg <- goE(exp)
        } yield typeOf(arg)(pos, info, errT)(ctx)

      case n: in.TypeExpr =>
        for { es <- sequence(TypeHead.children(n) map goE) } yield withSrc(types.typeApp(TypeHead.typeHead(n), es), n, ctx)
    }
  }

  /** returns dynamic type of an interface expression. */
  private def typeOf(arg: vpr.Exp)(pos: vpr.Position = vpr.NoPosition, info: vpr.Info = vpr.NoInfo, errT: vpr.ErrorTrafo = vpr.NoTrafos)(ctx: Context): vpr.Exp = {
    ctx.tuple.get(arg, 1, 2)(pos, info, errT)
  }

  /** Returns dynamic value of an interface expression as a type 'typ'. */
  private def valueOf(arg: vpr.Exp, typ: vpr.Type)(pos: vpr.Position = vpr.NoPosition, info: vpr.Info = vpr.NoInfo, errT: vpr.ErrorTrafo = vpr.NoTrafos)(ctx: Context): vpr.Exp = {
    val polyVal = ctx.tuple.get(arg, 0, 2)(pos, info, errT)
    poly.unbox(polyVal, typ)(pos, info, errT)(ctx)
  }

  /**
    * Encodes statements.
    * This includes make-statements.
    *
    * The default implements:
    * [v: *T = make(lit)] -> var z (*T)°; inhale Footprint[*z] && [*z == lit]; [v = z]
    *
    * [x, ok = e.(T = interface{...})] ->
    *   assert behavioralSubtype(TYPE_OF([e]), [T])
    *   ok = true
    *   x = [e]
    *
    * [x, ok = e.(T)] ->
    *   ok = TYPE_OF([e]) == [T]
    *   if (ok) { x = valueOf([e], [T]) }
    *   else { x = [ dflt(T) ] }
    *
    *
    */
  override def statement(ctx: Context): in.Stmt ==> CodeWriter[vpr.Stmt] = {
    default(super.statement(ctx)){
      case n@ in.SafeTypeAssertion(resTarget, successTarget, expr, typ@ ctx.Interface(_)) =>
        val (pos, info, errT) = n.vprMeta
        val errorT = (x: Source.Verifier.Info, _: ErrorReason) =>
          TypeAssertionError(x).dueTo(SafeTypeAssertionsToInterfaceNotSucceedingReason(x))
        seqn(
          for {
            arg <- ctx.expr.translate(expr)(ctx)
            dynType = typeOf(arg)(pos, info, errT)(ctx)
            staticType = types.typeToExpr(typ)(pos, info, errT)(ctx)
            _ <- assert(types.behavioralSubtype(dynType, staticType)(pos, info, errT)(ctx), errorT)
            vResTarget = ctx.typeEncoding.variable(ctx)(resTarget).localVar
            vSuccessTarget = ctx.typeEncoding.variable(ctx)(successTarget).localVar
          } yield vpr.Seqn(Seq(
            vpr.LocalVarAssign(vResTarget, arg)(pos, info, errT),
            vpr.LocalVarAssign(vSuccessTarget, vpr.TrueLit()(pos, info, errT))(pos, info, errT)
          ), Seq.empty)(pos, info, errT)
        )

      case n@ in.SafeTypeAssertion(resTarget, successTarget, expr, typ) =>
        val (pos, info, errT) = n.vprMeta
        types.genPreciseEqualityAxioms(typ)(ctx)
        seqn(
          for {
            arg <- ctx.expr.translate(expr)(ctx)
            dynType = typeOf(arg)(pos, info, errT)(ctx)
            staticType = types.typeToExpr(typ)(pos, info, errT)(ctx)
            vResTarget = ctx.typeEncoding.variable(ctx)(resTarget).localVar
            vSuccessTarget = ctx.typeEncoding.variable(ctx)(successTarget).localVar
            _ <- bind(vSuccessTarget, vpr.EqCmp(dynType, staticType)(pos, info, errT))
            vDflt <- ctx.expr.translate(in.DfltVal(resTarget.typ)(n.info))(ctx)
            res = vpr.If(
              vSuccessTarget,
              vpr.Seqn(Seq(vpr.LocalVarAssign(vResTarget, valueOf(arg, ctx.typeEncoding.typ(ctx)(typ))(pos, info, errT)(ctx))(pos, info, errT)), Seq.empty)(pos, info, errT),
              vpr.Seqn(Seq(vpr.LocalVarAssign(vResTarget, vDflt)(pos, info, errT)), Seq.empty)(pos, info, errT)
            )(pos, info, errT)
          } yield res
        )
    }
  }



}
