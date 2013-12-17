package com.wbillingsley.eventroom;

import play.api.libs.json._
import play.api.libs.iteratee.{Iteratee, Enumerator, Enumeratee}
import play.api.test.WithApplication

import org.specs2.mutable._

import scala.concurrent.ExecutionContext.Implicits.global
import com.wbillingsley.handy.Ref._


import com.wbillingsley.handyplay.EnumeratorHelper._

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
 
@RunWith(classOf[JUnitRunner])
class EventRoomSpec extends Specification {
  
  sequential
  
  /** Users for our test */
  case class User(name:String)
  
  val algernon = User("Algernon Moncrieff")
  val bertie = User("Bertram Wooster")
  
  
  /** In our test, we can optionally subscribe anonymously (no user) */
  case class Mem(u: Option[User]) extends Member {
    def toJson = u match {
      case Some(u) => Some(Json.toJson(u.name))
      case _ => Some(Json.toJson("Anonymous"))
    }
  }
  
  /** In our test, the channels are just numbers. */
  case class LTNum(num:Int) extends ListenTo {
    
    override def onSubscribe(ln:String, room:EventRoom) {
      room.broadcast(this, MemberList(room.membersByLT(this)))
    }
    
  }
  
  /** A test event to send */
  case class TestEvent(text: String, num:Int) extends EREvent {
    override def toJson = Json.obj("text" -> text).itself
    
    override def action(r:EventRoom) = {
      println("Actioning " + this)
      r.broadcast(LTNum(num), this)
    }
  }

  
  
  def hilf[E](checks: (E) => Boolean*) = {
    var ch = checks
    val en = Enumeratee.map[E] { x => 
      if (ch.head(x)) {
        println("checked " + x)
        ch = ch.tail
        true
      } else {
        println("failed " + x)
        throw new RuntimeException("Didn't meet expectation on " + x)
      }
    }
    val bool = Iteratee.fold[Boolean, Boolean](true)(_ && _)
    en transform bool
  }

  "EventRoom" should {

    "respond with connected and the member list when connecting with a subscription" in new WithApplication {  
      
      val er = new EventRoomGateway
      
      val enum = er.joinJson("aaa1", Mem(Some(bertie)), "sess1", "", LTNum(1))
      
      val v = (enum |>>> hilf(
          // Connected
          j => {
            println("Checking " + j.toString)
            
            j == Json.obj("type" -> "connected", "listenerName" -> "aaa1")
          },
          
          // Member list
          j => {
            println("Checking " + j.toString)            
            val checked = ((j \ "type").asOpt[String] == Some("members")) && ((j \ "members").as[List[String]] == List("Bertram Wooster"))
            
            // Send the quit message after receiving the member list
            er.default ! Quit("aaa1")
      
            checked
          }
      )) must be_==(true).await
    }

    "broadcast events on the subscribed channel" in new WithApplication {
      
      val er = new EventRoomGateway
      
      val enum = er.joinJson("aaa2", Mem(Some(algernon)), "sess2", "", LTNum(2))      
      
      enum.verify(
          _ == {                        
            // Now we're connected, send the test message
            er.default ! TestEvent("Hi-De-Hi", 2)
            
            // And check it really was the connected message
            Json.obj("type" -> "connected", "listenerName" -> "aaa2")
          },
          
          // Member list
          j => ((j \ "type").asOpt[String] == Some("members")) && ((j \ "members").as[List[String]] == List("Algernon Moncrieff")),
          
          // Test message
          _ == {
            er.default ! Quit("aaa2")
            
            // And check it really was the test message
            Json.obj("text" -> "Hi-De-Hi")
          }
      ) must be_==(true).await
      
    }
    
    "broadcast events over a websocket" in new WithApplication {
       import scala.concurrent.Await
       import scala.concurrent.duration._
      
       val er = new EventRoomGateway
       val f = er.websocketTuple("aaa3", Mem(None), "sess3", "", LTNum(3))
       val en = f.map(_._2)

       en.verify(
          _ == {            
            
            // Now we're connected, send the test message
            er.default ! TestEvent("Ho-Di-Ho", 3)
            
            // And check the received event really was the connected message
            Json.obj("type" -> "connected", "listenerName" -> "aaa3")            
          },
          
          // Member list
          j => ((j \ "type").asOpt[String] == Some("members")) && ((j \ "members").as[List[String]] == List("Anonymous")),
          
          // Test message
          _ == {
            er.default ! Quit("aaa3")
            
            Json.obj("text" -> "Ho-Di-Ho")
          }
      ) must be_==(true).await
    }
    
    
  }
}
