package viper.gobra.translator.interfaces.translator


import viper.gobra.translator.interfaces.Context
import viper.gobra.util.Violation

import scala.reflect.ClassTag

abstract class BaseTranslator[-I, +V] extends Generator {

  /**
    *
    * @param x entity to translate
    * @param ctx immutable translation context
    * @return translated entity
    */
  def translate(x: I)(ctx: Context): V
  def translateF(ctx: Context)(x: I): V = translate(x)(ctx)

  protected abstract class FromToContract[-X <: I, +Y: ClassTag] extends (X => Context => Y){
    def translateWithContract(x: X)(ctx: Context): Y = translate(x)(ctx) match {
      case y: Y => y
      case _ => Violation.violation(s"could not translate to expected type")
    }

    override def apply(x: X): Context => Y = (ctx: Context) => translateWithContract(x)(ctx)
  }

  def specific[Q <: I, R](x: Q)(ctx: Context)(implicit w: FromToContract[Q, R]): R =
    w.translateWithContract(x)(ctx)
}
