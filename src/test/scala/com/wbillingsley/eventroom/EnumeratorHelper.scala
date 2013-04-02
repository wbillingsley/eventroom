package com.wbillingsley.eventroom

import play.api.libs.iteratee.{ Iteratee, Enumerator, Enumeratee }
import org.specs2.mutable._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import org.specs2.matcher.Matcher
import org.specs2.matcher.Expectable

import scala.language.reflectiveCalls

object EnumeratorHelper {

  
  implicit class EnumeratorExpect[A](val e: Enumerator[A]) extends AnyVal {

    def expect(list: List[A]) = {
      var l = list
      val p = promise[Boolean]
      var overflowed = false

      val enumeratee = Enumeratee.map[A] { a =>
        
        try {
          assert(!l.isEmpty, "Expected nothing but received " + a)
        
          val test = l.head
          l = l.tail
        
          assert(a == test, "Expected " + test + ", but received " + a)
          if (l.isEmpty) p.success(a == test)
        } catch {
          case x:Throwable => {
            overflowed = true; 
            p.failure(x)
          }
        }
        true
      }

      val complete = (e &> enumeratee) |>>> Iteratee.fold(true)(_ && _)

      assert(Await.result(p.future, 1.seconds), "Didn't match expectation")
      
      assert(l.isEmpty, "Not all events were received: " + l)
    }
    
    def verify(list: List[(A) => Boolean]) = {
      var l = list
      val p = promise[Boolean]
      var overflowed = false

      val enumeratee = Enumeratee.map[A] { a =>
        
        try {
          println(a)
          
          assert(!l.isEmpty, "Expected nothing but received " + a)
        
          val test = l.head
          l = l.tail
        
          assert(test(a), "Test failed on " + a)
          
          if (l.isEmpty) p.success(true)
        } catch {
          case x:Throwable => {
            overflowed = true; 
            p.failure(x)
            throw x
          }
        }
        true
      }

      val complete = (e &> enumeratee) |>>> Iteratee.fold(true)(_ && _)

      assert(Await.result(p.future, 1.seconds), "Didn't match expectation")
      
      assert(l.isEmpty, "Not all events were received: " + l)
    }
    
  }
}