package com.wbillingsley.eventroom

import play.api.libs.iteratee.{ Iteratee, Enumerator, Enumeratee, Step, Input }
import org.specs2.mutable._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import org.specs2.matcher.Matcher
import org.specs2.matcher.Expectable

import scala.language.reflectiveCalls

object EnumeratorHelper {
  
  /**
   * This is our iteratee for checking the contents of the enumeration.
   * Note that it relies on receiving an EOF matching the end of the list
   * of checks.
   */
  def iterChecker[E](checks:List[(E) => Boolean]):Iteratee[E, Boolean] = {
     
     println("Cont")
     Step.Cont[E, Boolean]( inp => {
              
       println("checking " + inp)
       
       inp match {
         case Input.El(el) => {
           if (checks.isEmpty) {
             Step.Error("Element received after checks exhausted: " + el, Input.El(el)).it
           } else {
             if (checks.head(el)) {
               iterChecker(checks.tail)
             } else {
               println("Error")
               Step.Error("Element failed check: " + el, Input.El(el)).it
             }
           }
         }
         case Input.EOF => {
           if (checks.isEmpty) {
             println("Done")
             Step.Done(true, Input.Empty).it
           } else {
             println("Error")
             Step.Error("EOF received before checks were exhausted. Remaining: " + checks.length, Input.EOF).it
           }
         }
         case Input.Empty => {
           Step.Error("Empty received. Remaining checks: " + checks.length, Input.Empty).it
         }
       }
     }).it
  }
  
  
  implicit class EnumeratorExpect[A](val e: Enumerator[A]) extends AnyVal {

    def expect(list: List[A]) = {
      var l = list.map(item => (i:A) => i == item)
      verify(l)
    }
    
    def verify(list: List[(A) => Boolean]) = {
      var l = list
      var overflowed = false

      val checker = iterChecker(list)
      val complete = e |>>> checker

      assert(Await.result(complete, 1.seconds), "Didn't match expectation")
    }
    
  }
}