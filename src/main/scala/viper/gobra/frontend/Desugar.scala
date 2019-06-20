package viper.gobra.frontend

import viper.gobra.ast.frontend._
import viper.gobra.ast.internal._
import viper.gobra.ast.{internal => in}
import viper.gobra.frontend.info.base.Type.{BooleanT, DeclaredT, IntT, PointerT, Type, VoidType}
import viper.gobra.frontend.info.base.{SymbolTable => st}
import viper.gobra.util.{Writer, WriterUtil}

object Desugar {

  def desugar(program: PProgram, info: viper.gobra.frontend.info.TypeInfo)(config: Config): in.Program = {
    new Desugarer(program.positions, info).programD(program)
  }

  private class Desugarer(pom: PositionManager, info: viper.gobra.frontend.info.TypeInfo) {

    // TODO: clean initialization

    import viper.gobra.util.Violation._

    private val nm = new NameManager

    class FunctionContext(val ret: Vector[in.Expr] => Source => in.Stmt) {

      type VarIdentity = Source

      private def abstraction(id: PIdnNode): VarIdentity = {
        source(info.regular(id).rep)
      }

      private var substitutions: Map[VarIdentity, Source => in.BodyVar] = Map.empty

      def apply(id: PIdnNode): Option[in.BodyVar] =
        substitutions.get(abstraction(id)).map(_(source(id)))


      def addSubst(from: PIdnNode, to: Source => in.BodyVar): Unit =
        substitutions.updated(abstraction(from), to)
    }

    def programD(p: PProgram): in.Program = {
      val globalVarDecls = p.declarations.collect{ case x: PVarDecl => varDeclGD(x) }.flatten.toSet
      val globalConstDecls = p.declarations.collect{ case x: PConstDecl => constDeclD(x) }.flatten.toSet
      val methods = p.declarations.collect{ case x: PMethodDecl => methodD(x) }.toSet
      val functions = p.declarations collect { case x: PFunctionDecl => functionD(x) }
      p.declarations collect { case x: PTypeDef => typeDefD(x) }

      in.Program(
        types,
        globalVarDecls,
        globalConstDecls,
        methods,
        functions.toSet
      )(source(p))
    }

    def varDeclGD(decl: PVarDecl): Vector[in.GlobalVarDecl] = ???

    def constDeclD(decl: PConstDecl): Vector[in.GlobalConst] = ???

    def typeDefD(decl: PTypeDef): in.Type = typeD(DeclaredT(decl))

    def functionD(decl: PFunctionDecl): in.Function = {

      val name = idName(decl.id)

      val argLinks = decl.args map parameterD
      val arg = argLinks.unzip._1


      val returnLinks = decl.result match {
        case PVoidResult() => Vector.empty
        case PResultClause(outs) =>
          outs.map(parameterD).map{ case (p, r) => (in.LocalVar.Val(p.id,p.typ)(p.src), r) }
      }
      val (returnAssign, returnVars) = returnLinks.unzip


      def assignReturns(rets: Vector[in.Expr])(src: Source): in.Stmt = {
        if (rets.isEmpty) {
          in.Seq(
            returnLinks.map{
              case (_, None) => violation("found empty return and unnamed returns")
              case (p, Some(v)) => in.SingleAss(in.Assignee.Var(p), v(src))(src)
            } :+ in.Return()(src)
          )(src)
        } else if (rets.size == returnAssign.size) {
          in.Seq(
            returnAssign.zip(rets).map{
              case (p, v) => in.SingleAss(in.Assignee.Var(p), v)(src)
            } :+ in.Return()(src)
          )(src)
        } else if (rets.size == 1) { // multi assignment
          in.Seq(Vector(
            in.MultiAss(returnAssign.map(v => in.Assignee.Var(v)), rets.head)(src),
            in.Return()(src)
          ))(src)
        } else {
          violation(s"found ${rets.size} returns but expected 0, 1, or ${returnAssign.size}")
        }
      }

      // create context for body translation
      val ctx = new FunctionContext(assignReturns)

      // p1' := p1; ... ; pn' := pn
      val argInits = argLinks.flatMap{
        case (_, None) => None
        case (p, Some(q)) => Some(in.SingleAss(in.Assignee.Var(q(Source.Internal)), p)(Source.Internal))
      }

      (decl.args zip argLinks).foreach{
        case (PNamedParameter(id, _), (_, Some(q))) => ctx.addSubst(id, q)
        case _ =>
      }

      // r1' := _; ... ; rn' := _
      val returnInits = returnVars.flatten.map{l =>
        val sl = l(Source.Internal)
        in.SingleAss(in.Assignee.Var(sl), in.DfltVal(sl.typ)(Source.Internal))(sl.src)
      }

      (decl.args zip argLinks).foreach{
        case (PNamedParameter(id, _), (_, Some(q))) => ctx.addSubst(id, q)
        case _ =>
      }


      val bodyOpt = decl.body.map{ s =>
        val vars = info.variables(s) map localVarD(ctx)
        val body = argInits ++ returnInits :+ stmtD(ctx)(s)
        in.Block(vars, body)(source(s))
      }

      in.Function(name, arg, returnAssign, Vector.empty, Vector.empty, bodyOpt)(source(decl))
    }

    def methodD(decl: PMethodDecl): in.Method = ???

    // Statements

    def stmtD(ctx: FunctionContext)(stmt: PStatement): in.Stmt = {

      def goS(s: PStatement): in.Stmt = stmtD(ctx)(s)
      def goE(e: PExpression): Agg[in.Expr] = exprD(ctx)(e)
      def goA(a: PAssertion): Agg[in.Assertion] = assertionD(ctx)(a)
      def goL(a: PAssignee): Agg[in.Assignee] = assigneeD(ctx)(a)

      import writerUtil.Syntax._

      val src: Source = source(stmt)

      stmt match {
        case _: PEmptyStmt => in.Seq(Vector.empty)(src)

        case PSeq(stmts) => in.Seq(stmts map goS)(src)

        case s@ PBlock(stmts) =>
          val vars = info.variables(s) map localVarD(ctx)
          in.Block(vars, stmts map goS)(src)

        case PExpressionStmt(e) => in.Seq(goE(e).written)(src) // TODO: check this translation

        case PAssignment(left, right) =>
          if (left.size == right.size) {
            in.Seq((left zip right).map{ case (l, r) =>
              complete(for{le <- goL(l); re <- goE(r)} yield in.SingleAss(le, re)(src))
            })(src)
          } else if (right.size == 1) {
            complete(
              for{les <- sequence(left map goL); re  <- goE(right.head)}
                yield in.MultiAss(les, re)(src)
            )
          } else { violation("invalid assignment") }

        case PShortVarDecl(right, left) =>

          if (left.size == right.size) {
            in.Seq((left zip right).map{ case (l, r) =>
              complete(for{
                le <- unit(Assignee.Var(localVarD(ctx)(l)))
                re <- goE(r)
              } yield in.SingleAss(le, re)(src))
            })(src)
          } else if (right.size == 1) {
            complete(for{
              les <- unit(left.map{l => Assignee.Var(localVarD(ctx)(l))})
              re  <- goE(right.head)
            } yield in.MultiAss(les, re)(src))
          } else { violation("invalid assignment") }

        case PVarDecl(typOpt, right, left) =>

          if (left.size == right.size) {
            in.Seq((left zip right).map{ case (l, r) =>
              complete(for{
                le <- unit(Assignee.Var(localVarD(ctx)(l)))
                re <- goE(r)
              } yield in.SingleAss(le, re)(src))
            })(src)
          } else if (right.size == 1) {
            complete(for{
              les <- unit(left.map{l =>  Assignee.Var(localVarD(ctx)(l))})
              re  <- goE(right.head)
            } yield in.MultiAss(les, re)(src))
          } else if (right.isEmpty && typOpt.nonEmpty) {
            val lelems = left.map{ l => Assignee.Var(localVarD(ctx)(l)) }
            val relems = left.map{ l => DfltVal(typeD(info.typ(typOpt.get)))(source(l)) }
            in.Seq((lelems zip relems).map{ case (l, r) => in.SingleAss(l, r)(src) })(src)

          } else { violation("invalid declaration") }

        case PReturn(exps) =>
          complete(for{es <- sequence(exps map goE)} yield ctx.ret(es)(src))
//            for{
//            elems <- sequence(exps map goE)
//          } yield ctx.ret(elems))


        case g: PGhostStatement => ghostStmtD(ctx)(g)

        case _ => ???
      }
    }



    // Expressions




    def assigneeD(ctx: FunctionContext)(expr: PExpression): Agg[in.Assignee] = {

      import writerUtil.Syntax._

      val src: Source = source(expr)

      val typ: in.Type = typeD(info.typ(expr))

      expr match {
        case PNamedOperand(id) => unit[in.Assignee](Assignee.Var(varD(ctx)(id)))
        case PDereference(exp) => for { e <- exprD(ctx)(exp) } yield Assignee.Pointer(in.Deref(e, typ)(src))

        case _ => ???
      }
    }

    def exprD(ctx: FunctionContext)(expr: PExpression): Agg[in.Expr] = {

      def go(e: PExpression): Agg[in.Expr] = exprD(ctx)(e)

      import writerUtil.Syntax._

      val src: Source = source(expr)

      val typ: in.Type = typeD(info.typ(expr))

      expr match {
        case PNamedOperand(id) => unit[in.Expr](varD(ctx)(id))
        case PDereference(exp) => go(exp) map (in.Deref(_, typ)(src))

        case g: PGhostExpression => ghostExprD(ctx)(g)

        case _ => ???
      }
    }



    def litD(ctx: FunctionContext)(lit: PLiteral): Agg[in.Expr] = {

      import writerUtil.Syntax._

      val src: Source = source(lit)
      def single[E <: in.Expr](gen: Source => E): Agg[in.Expr] = unit[in.Expr](gen(src))

      lit match {
        case PIntLit(v)  => single(in.IntLit(v))
        case PBoolLit(b) => single(in.BoolLit(b))
        case _ => ???
      }
    }

    // Type

    var types: Set[in.TopType] = Set.empty

    // TODO split into two phases to aggregate inlined struct and interface types
    def typeD(t: Type): in.TopType = {

      def typeDInner(t: Type): in.TopType = t match {
        case BooleanT => in.BoolT
        case IntT => in.IntT
        case PointerT(st) => in.PointerT(typeD(st))
        case VoidType => in.VoidT
        case DeclaredT(decl) => in.DefinedT(idName(decl.left), typeDInnermost(info.typ(decl.right)))
        case _ => ???
      }

      def typeDInnermost(t: Type): in.Type = t match { // TODO translate struct and interface type
        case _ => typeDInner(t)
      }

      val inT = t match {
          // if struct then change it to declared type
        case _ => typeDInner(t)
      }

      if (!(types contains inT)) {
        types += inT
      }

      inT
    }


    // Identifier

    def idName(id: PIdnNode): String = info.regular(id) match {
      case _: st.Function => nm.function(id.name, info.scope(id))
      case _: st.Variable => nm.variable(id.name, info.scope(id))
      case _: st.NamedType => nm.typ(id.name, info.scope(id))
      case _ => ???
    }

    def varD(ctx: FunctionContext)(id: PIdnNode): in.BodyVar = {
      require(info.regular(id).isInstanceOf[st.Variable])

      localVarD(ctx)(id)
    }

    def localVarD(ctx: FunctionContext)(id: PIdnNode): in.LocalVar = {
      require(info.regular(id).isInstanceOf[st.Variable]) // TODO: add local check

      ctx(id) match {
        case Some(v: in.LocalVar) => v
        case None => localVarContextFreeD(id)
        case _ => violation("expected local variable")
      }
    }

    def localVarContextFreeD(id: PIdnNode): in.LocalVar = {
      require(info.regular(id).isInstanceOf[st.Variable]) // TODO: add local check

      val src: Source = source(id)

      val typ = typeD(info.typ(id))

      if (info.addressed(id)) {
        LocalVar.Ref(idName(id), typ)(src)
      } else {
        LocalVar.Val(idName(id), typ)(src)
      }
    }

    // Miscellaneous

    def parameterD(p: PParameter): (in.Parameter, Option[Source => in.LocalVar]) = p match {
      case PNamedParameter(id, typ) =>
        val ip = in.Parameter(idName(id), typeD(info.typ(typ)))(source(p))
        (ip, Some(Sourced.unsource(Factory(() => localVarContextFreeD(id))))) // TODO: replace with copy

      case PUnnamedParameter(typ) =>
        (in.Parameter(nm.fresh, typeD(info.typ(typ)))(source(p)), None)
    }

    // Ghost Statements

    def complete[X <: in.Stmt](w: Agg[X]): in.Stmt = {val (xs, x) = w.run; in.Seq(xs :+ x)(x.src)}

    def ghostStmtD(ctx: FunctionContext)(stmt: PGhostStatement): in.Stmt = {

      def goA(ass: PAssertion): Agg[in.Assertion] = assertionD(ctx)(ass)

      val src: Source = source(stmt)

      stmt match {
        case PAssert(exp) => complete(for {e <- goA(exp)} yield in.Assert(e)(src))
        case PAssume(exp) => complete(for {e <- goA(exp)} yield in.Assume(e)(src))
        case PInhale(exp) => complete(for {e <- goA(exp)} yield in.Inhale(e)(src))
        case PExhale(exp) => complete(for {e <- goA(exp)} yield in.Exhale(e)(src))
        case _ => ???
      }
    }

    // Ghost Expression

    def ghostExprD(ctx: FunctionContext)(expr: PGhostExpression): Agg[in.Expr] = {

      def go(e: PExpression): Agg[in.Expr] = exprD(ctx)(e)

      val src: Source = source(expr)

      val typ = typeD(info.typ(expr))

      expr match {
        case _ => ???
      }
    }


    // Assertion

//    import cats.data.Writer
//    import cats.instances.vector._
    type Agg[R] = Writer[in.Stmt, R]
    val writerUtil: WriterUtil[in.Stmt] = new WriterUtil[in.Stmt]

    def assertionD(ctx: FunctionContext)(ass: PAssertion): Agg[in.Assertion] = {

      def goE(e: PExpression): Agg[in.Expr] = exprD(ctx)(e)
      def goA(a: PAssertion): Agg[in.Assertion] = assertionD(ctx)(a)

      val src: Source = source(ass)

      ass match {
        case PStar(left, right) =>        for {l <- goA(left); r <- goA(right)} yield in.Star(l, r)(src)
        case PExprAssertion(exp) =>       for {e <- goE(exp)}                   yield in.ExprAssertion(e)(src)
        case PImplication(left, right) => for {l <- goE(left); r <- goA(right)} yield in.Implication(l, r)(src)
        case PAccess(acc) =>              for {e <- accessibleD(ctx)(acc)}      yield in.Access(e)(src)

        case _ => ???
      }
    }

    def accessibleD(ctx: FunctionContext)(acc: PAccessible): Agg[in.Accessible] = {

      def goE(e: PExpression): Agg[in.Expr] = exprD(ctx)(e)

      val src: Source = source(acc)

      acc match {
        case n@ PDereference(e) =>
          for { e <- goE(e); t = typeD(info.typ(n))}
            yield in.Accessible.Ref(in.Deref(e, t)(src))

        case _ => ???
      }
    }



//    private def origin(n: PNode): in.Origin = {
//      val start = pom.positions.getStart(n).get
//      val finish = pom.positions.getFinish(n).get
//      val pos = pom.translate(start, finish)
//      val code = pom.positions.substring(start, finish).get
//      in.Origin(code, pos)
//    }

    private def source(n: PNode): Source = {
      val start = pom.positions.getStart(n).get
      val finish = pom.positions.getFinish(n).get
      val pos = pom.translate(start, finish)
//      val code = pom.positions.substring(start, finish).get
      Source.Single(in.Origin(pos))
    }
  }

  private class NameManager {

    private val FRESH_PREFIX = "N"
    private val VARIABLE_PREFIX = "V"
    private val COPY_PREFIX = "C"
    private val FUNCTION_PREFIX = "F"
//    private val METHOD_PREFIX = "M"
    private val TYPE_PREFIX = "T"

    private var counter = 0

    private var scopeCounter = 0
    private var scopeMap: Map[PScope, Int] = Map.empty

    private def maybeRegister(s: PScope): Unit = {
      if (!(scopeMap contains s)) {
        scopeMap += (s -> scopeCounter)
        scopeCounter += 1
      }
    }

    private var substitutions: Map[(String, PScope), String] = Map.empty

    private def name(pre: String)(n: String, s: PScope): String = {
      maybeRegister(s)
      pre + scopeMap(s) + "_" + n // deterministic
    }

    def variable(n: String, s: PScope): String = name(VARIABLE_PREFIX)(n, s)
    def function(n: String, s: PScope): String = name(FUNCTION_PREFIX)(n, s)
    def typ     (n: String, s: PScope): String = name(TYPE_PREFIX)(n, s)

    def method  (n: String, t: Type): String = ???
    def field   (n: String, t: Type): String = ???

    def copyExt(n: String): String = COPY_PREFIX + n

    def fresh: String = {
      val f = FRESH_PREFIX + counter
      counter += 1
      f
    }




  }
}


