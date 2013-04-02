package com.wbillingsley.eventroom;

import play.api.libs.json._
import play.api.libs.iteratee.{Iteratee, Enumerator, Enumeratee}
import play.api.test.WithApplication

import org.specs2.mutable._

import akka.testkit.TestKit
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


import EnumeratorHelper._

class EventRoomSpec extends Specification {
  
  /** Users for our test */
  case class User(name:String)
  
  val algernon = User("Algernon Moncrieff")
  val bertie = User("Bertram Wooster")
  
  
  /** In our test, we can optionally subscribe anonymously (no user) */
  case class Mem(u: Option[User]) extends Member {
    def toJson = u match {
      case Some(u) => Json.toJson(u.name)
      case _ => Json.toJson("Anonymous")
    }
  }
  
  /** In our test, the channels are just numbers. */
  case class LTNum(num:Int) extends ListenTo
  
  /** A test event to send */
  case class TestEvent(text: String, num:Int) extends EREvent {
    override def toJson = Json.obj("text" -> text)
    
    override def action(r:EventRoom) = {
      println("Actioning " + this)
      r.broadcast(LTNum(num), this)
    }
  }


  "EventRoom" should {

    "respond with connected and the member list when connecting with a subscription" in new WithApplication {  
      
      val er = new EventRoomGateway
      
      val enum = Enumerator.flatten(er.join("aaa1", Mem(Some(bertie)), "sess1", "", LTNum(1)))      
      er.default ! Quit("aaa1")
      
      enum.verify(List(
          // Connected
          _ == Json.obj("type" -> "connected", "listenerName" -> "aaa1"),
          
          // Member list
          j => ((j \ "type").asOpt[String] == Some("members")) && ((j \ "members").as[List[String]] == List("Bertram Wooster")) 
      )) must be equalTo(true)
      
    }


    "broadcast events on the subscribed channel" in new WithApplication {
      
      val er = new EventRoomGateway
      
      val enum = Enumerator.flatten(er.join("aaa2", Mem(Some(algernon)), "sess2", "", LTNum(2)))      
      
      enum.verify(List(
          j => {            
            val connectedCheck = j == Json.obj("type" -> "connected", "listenerName" -> "aaa2")
            
            // Now we're connected, send the test message
            er.default ! TestEvent("Hi-De-Hi", 2)
            connectedCheck
          },
          
          // Member list
          j => ((j \ "type").asOpt[String] == Some("members")) && ((j \ "members").as[List[String]] == List("Algernon Moncrieff")),
          
          // Test message
          _ == Json.obj("text" -> "Hi-De-Hi")
      )) must be equalTo(true)
      
    }

  }
}
