package org.skyluc.aws.utils

import scala.util.Success
import scala.util.Try

object TryUtils {
  def map[A, B](l: List[A])(f: A => Try[B]): Try[List[B]] = {
    def loop(l: List[A], acc: List[B]): Try[List[B]] = {
      l match {
        case head :: tail =>
          f(head).flatMap{ b =>
            loop(tail, b :: acc)
          }
        case Nil =>
          Success(acc.reverse)
      }
    }
    loop(l, Nil)
  }
}