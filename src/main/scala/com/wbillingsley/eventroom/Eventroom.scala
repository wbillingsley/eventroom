package com.wbillingsley.eventroom

import scala.collection.mutable
import scala.concurrent.duration._

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Concurrent.Channel
import play.api.Play.current

import com.wbillingsley.handy._
import Ref._

import scala.language.reflectiveCalls

/**
 * A member of the EventRoom. Wrap your user class in one of these
 */
trait Member {
  /**
   * The JSON you want to appear for each member in the list of those in the room.
   * 
   * This is defined to return Option not Ref to avoid a performance trap: if turning a 
   * Mem to JSON involved a future (particularly if it involved a database lookup)
   * then every time a new person joins the event room, each of the other members
   * would end up being looked up individually -- which could total many trips to the
   * database.
   * 
   * If I ever find a need to look up Mem's on demand (rather than just keeping the
   * few details we might want to show in the presence list in memory) then I'll 
   * revisit how this can be done more efficiently.
   */
  def toJson:Option[JsValue]
}

/** A key saying what the listener is listening to */
trait ListenTo {
  def onSubscribe(listenerName: String, room: EventRoom) = { /* do nothing */ }
  
  def onUnsubscribe(listenerName: String, room: EventRoom) = { /* do nothing */ }
}

/** A bit of state that events can update */
trait State

/** Type of event sent in the room. */
trait EREvent {
  
  /** 
   * Override this to give a JSON representation of your event.
   * As this is defined as a Ref[JsValue], it can be a RefFuture, etc. 
   */
  def toJson: Ref[JsValue] = RefNone
  
  def toJsonFor(m:Member) = toJson

  /** The action the EventRoom should perform when it receives this event. */
  def action(room: EventRoom) {
    throw new UnsupportedOperationException("This event has no defined action")
  }
}

/** 
 * Joining the event room.  Note that context is not used, but is kept as it may be useful for debugging. 
 */
case class Join(listenerName: String, member: Member, session: String, context: String, channel:Channel[EREvent], listenTo: ListenTo*) extends EREvent

case class Subscribe(listenerName: String, listenTo: ListenTo) extends EREvent

case class Unsubscribe(listenerName: String, listenTo: ListenTo) extends EREvent

/** Leaving the event room */
case class Quit(listenerName: String) extends EREvent

/** Successfully connected, returning an enumerator of the broadcast events. */
case class Connected(listenerName: String) extends EREvent {
  override val toJson = Json.obj("type" -> "connected", "listenerName" -> listenerName).itself
}

/** Can't connect */
case class CannotConnect(msg: String) extends EREvent {
  override val toJson = Json.obj("error" -> msg).itself
}

/** Who else is in the same event room. */
case class MemberList(members: Iterable[Member]) extends EREvent {
  override def toJson = {
    val memberlist = (for (m <- members; mj <- m.toJson) yield mj)
    
    Json.obj(
      "type" -> JsString("members"),
      "members" -> memberlist.toStream,
      "date" -> Json.toJson(System.currentTimeMillis())      
    ).itself
  }
}

/**
 * Instantiate one of these for your EventRoom
 */
class EventRoomGateway {

  implicit val timeout = Timeout(1.second)

  lazy val default = {
    val roomActor = Akka.system.actorOf(Props[EventRoom])
    roomActor
  }
  
  /**
   * Enumeratee for converting JSON events into format for Server Sent Events
   */
  val toEventSource = Enumeratee.map[JsValue] { msg =>
    val d = "data: "+ msg.toString +"\n\n"
    //val m = "" + d.length + "\n" + d
    d
  }

  /**
   * Join the event room, returning an enumerator of events
   */
  def join(listenerName: String, member: Member, session: String, context: String, listenTo: ListenTo*) = {
    
    val enumerator = Concurrent.unicast[EREvent](
      onStart = { channel =>
        default ! Join(listenerName, member, session, context, channel, listenTo: _*)
      },
      onComplete = {
        default ! Quit(listenerName) 
      },
      onError = (string, input) => {
        println("Error " + string)
        default ! Quit(listenerName) 
      }
    )
    enumerator
  }
  
  /**
   * Join the event room, returning an enumerator of JSON values
   */  
  def joinJson(listenerName: String, member: Member, session: String, context: String, listenTo: ListenTo*) = {
    import com.wbillingsley.handyplay.RefEnumerator
    import com.wbillingsley.handyplay.RefConversions._
    val enumerator = join(listenerName, member, session, context, listenTo: _*)
    
    //val j = enumerator flatMap (_.toJsonFor(member).enumerate)
    (for {
      e <- new RefEnumerator(enumerator)
      j <- e.toJsonFor(member)
    } yield j).enumerate
    //j
  }

  def notifyEventRoom(e: EREvent) = default ! e

  /**
   * Connects a listener to the event room and returns a (future) server sent event stream for that listener.
   */
  def serverSentEvents(listenerName: String, u: Member, session: String, context: String, lt: ListenTo*) = {

    import play.api.mvc.{ Results, ResponseHeader }
    import play.api.http.HeaderNames

    val enumerator = joinJson(listenerName, u, session, context, lt: _*)
    
    /*
       We pad the enumerator to send some initial data so that iOS will act upon the Connection: Close header
       to ensure it does not pipeline other requests behind this one.  See HTTP Pipelining, Head Of Line blocking
        
       UPDATE: A Heroku issue means I've experimentally changed the content headers as below, dropping "Content-Length: -1" 
       changing to "Connection: keep-alive"  
        */
    val paddedEnumerator = Enumerator[JsValue](Json.toJson(Map("type" -> Json.toJson("ignore")))).andThen(enumerator)
    val eventSource = paddedEnumerator &> toEventSource

    // Apply the iteratee
    val result = Results.Ok.chunked[String](eventSource).withHeaders(
        HeaderNames.CONNECTION -> "close",
        HeaderNames.CACHE_CONTROL -> "no-cache",
        HeaderNames.CONTENT_TYPE -> "text/event-stream"
      )
      
    result
  }
  
  /**
   * Receives incoming events from WebSockets. Override this if you want to act on them.
   */
  def handleIncomingEvent(j:JsValue) = {}
    
  /**
   * Connects a listener and returns a (Iteratee, Enumerator) tuple suitable for a Play websocket
   */
  def websocketTuple(listenerName:String, u:Member, session:String, context:String, lt:ListenTo*) = {      
    val f = joinJson(listenerName, u, session, context, lt:_*)
    val jsIteratee = (Iteratee.foreach[JsValue] { j => handleIncomingEvent(j)})
    f.map(e => (jsIteratee, e))
  }  

}

class EventRoom extends Actor {

  /**
   * The members of the chat room and their streams
   */
  var members = Map.empty[String, Channel[EREvent]]

  /**
   * A map of listenTo -> listenerName
   */
  var subscribers = Map.empty[ListenTo, Set[String]]

  /**
   * A map of listenerName -> listenTo
   */
  var subscriptions = Map.empty[String, Set[ListenTo]]

  /**
   * The join request of the members of the event room
   */
  var memberJoins = Map.empty[String, Join]

  /**
   * The various states that can be listened to in the event room
   */
  var states = Map.empty[ListenTo, State]

  /**
   * Subscribe a listener to something that can be listened to
   */
  private def subscribe(listenerName: String, listenTo: ListenTo) {
    subscribers = subscribers.updated(listenTo, subscribers.getOrElse(listenTo, Set.empty[String]) + listenerName)
    subscriptions = subscriptions.updated(listenerName, subscriptions.getOrElse(listenerName, Set.empty[ListenTo]) + listenTo)
    listenTo.onSubscribe(listenerName, this)
  }

  /**
   * Unsubscribe a listener to something that can be listened to
   */
  private def unsubscribe(listenerName: String, listenTo: ListenTo) {
    subscribers = subscribers.updated(listenTo, subscribers.getOrElse(listenTo, Set.empty[String]) - listenerName)
    subscriptions = subscriptions.updated(listenerName, subscriptions.getOrElse(listenerName, Set.empty[ListenTo]) - listenTo)
    listenTo.onUnsubscribe(listenerName, this)
  }

  def receive = {

    case ej: Join => {
      
      if (members.contains(ej.listenerName)) {
        ej.channel push CannotConnect("This username is already used")
      } else {
        members = members + (ej.listenerName -> ej.channel)
        memberJoins = memberJoins + (ej.listenerName -> ej)
        
        ej.channel push Connected(ej.listenerName)
        
        for (lt <- ej.listenTo) {
          subscribe(ej.listenerName, lt)
        }
        
      }

    }

    // Subscribe to a ListenTo
    case Subscribe(listenerName, listenTo) => subscribe(listenerName, listenTo)

    // Unsubscribe from a ListenTo
    case Unsubscribe(listenerName, listenTo) => unsubscribe(listenerName, listenTo)

    // Quit the room 
    case Quit(listenerName) => {
      
      val ch = members.get(listenerName)
      
      members = members - listenerName
      memberJoins = memberJoins - listenerName

      // Unsubscribe this listener from all streams
      subscriptions = subscriptions - listenerName
      for (subscription <- subscriptions.getOrElse(listenerName, Set.empty[ListenTo])) {
        subscribers = subscribers.updated(subscription, subscribers.getOrElse(subscription, Set.empty[String]) - listenerName)
        subscription.onUnsubscribe(listenerName, this)
      }

      for (channel <- ch) {
        channel.end
      }
    }

    // For other events, do what the event says
    case event: EREvent => {
      event.action(this)
    }

  }

  /**
   * Sends an event out to everyone who is listening to the same thing
   */
  def broadcast(key: ListenTo, event: EREvent) {
    for (
      listenerSet <- subscribers.get(key);
      listenerName <- listenerSet;
      listener <- members.get(listenerName)
    ) {
      listener.push(event)
    }
  }

  /**
   * Who's listening to this?
   */
  def membersByLT(lt: ListenTo): Iterable[Member] = {
    val ltListeners = subscribers.get(lt).getOrElse(Set.empty[String])
    val joins = for (listenerName <- ltListeners; join <- memberJoins.get(listenerName)) yield join

    // Collect a list of all sessions and their associated readers
    var sessionReader = mutable.Map.empty[String, Member]
    for (j <- joins) {
      if (!sessionReader.contains(j.session))
        sessionReader(j.session) = j.member
    }

    // Return the sequence of readers (which may include many RefNone entries)
    sessionReader.values
  }

}


