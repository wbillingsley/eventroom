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
abstract class ListenTo {
  def onSubscribe(listenerName: String, room: EventRoom) = { /* do nothing */ }
}

/** A bit of state that events can update */
abstract class State

/** Type of event sent in the room. */
abstract class EREvent {
  
  /** 
   * Override this to give a JSON representation of your event.
   * As this is defined as a Ref[JsValue], it can be a RefFuture, etc. 
   */
  def toJson: Ref[JsValue] = RefNone

  /** The action the EventRoom should perform when it receives this event. */
  def action(room: EventRoom) {
    throw new UnsupportedOperationException("This event has no defined action")
  }
}

/** 
 * Joining the event room.  Note that context is not used, but is kept as it may be useful for debugging. 
 */
case class Join(listenerName: String, member: Member, session: String, context: String, listenTo: ListenTo*) extends EREvent

case class Subscribe(listenerName: String, listenTo: ListenTo) extends EREvent

case class Unsubscribe(listenerName: String, listenTo: ListenTo) extends EREvent

/** Leaving the event room */
case class Quit(listenerName: String) extends EREvent

/** Successfully connected, returning an enumerator of the broadcast events. */
case class Connected(listenerName: String, enumerator: Enumerator[JsValue]) extends EREvent

/** Can't connect */
case class CannotConnect(msg: String) extends EREvent {
  
  def json = Json.obj("error" -> msg)
  
  override def toJson = json.itself
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
   * Join the event room, returning an enumerator of events as JSON objects
   */
  def join(listenerName: String, member: Member, session: String, context: String, listenTo: ListenTo*) = {
    (default ? Join(listenerName, member, session, context, listenTo: _*)).map {

      case Connected(listenerName, enumerator) => enumerator

      case CannotConnect(error) =>
        // Send an error and close the socket
        Enumerator[JsValue](CannotConnect(error).json).andThen(Enumerator.enumInput(Input.EOF))
    }
  }

  def notifyEventRoom(e: EREvent) = default ! e
  
  /**
   * Connects a listener to the event room and returns a (future) server sent event stream for that listener.
   */
  def serverSentEvents(listenerName:String, u:Member, session:String, context:String, lt:ListenTo*) = {
    
    import play.api.mvc.{ChunkedResult, ResponseHeader}
    import play.api.http.HeaderNames
    
    val promise = join(listenerName, u, session, context, lt:_*)
    promise.map(enumerator => {
      /*
       We pad the enumerator to send some initial data so that iOS will act upon the Connection: Close header
       to ensure it does not pipeline other requests behind this one.  See HTTP Pipelining, Head Of Line blocking
        */
      val paddedEnumerator = Enumerator[JsValue](Json.toJson(Map("type" -> Json.toJson("ignore")))).andThen(enumerator)
      val eventSource = paddedEnumerator &> toEventSource	          	         

      // Apply the iteratee
      val result = ChunkedResult[String](
        header = ResponseHeader(play.api.http.Status.OK, Map(
          HeaderNames.CONNECTION -> "close",
          HeaderNames.CONTENT_LENGTH -> "-1",
          HeaderNames.CONTENT_TYPE -> "text/event-stream"
        )),
        chunks = {iteratee:Iteratee[String, Unit] => eventSource.apply(iteratee) });
      
      result
    })     
  }
  
  /**
   * Receives incoming events from WebSockets. Override this if you want to act on them.
   */
  def handleIncomingEvent(j:JsValue) = {}
    
  /**
   * Connects a listener and returns a (Iteratee, Enumerator) tuple suitable for a Play websocket
   */
  def websocketTuple(listenerName:String, u:Member, session:String, context:String, lt:ListenTo*) = {      
    val f = join(listenerName, u, session, context, lt:_*)
    val jsIteratee = (Iteratee.foreach[JsValue] { j => handleIncomingEvent(j)})
    f.map(e => (jsIteratee, e))
  }  

}

class EventRoom extends Actor {

  /**
   * The members of the chat room and their streams
   */
  var members = Map.empty[String, Channel[JsValue]]

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
    broadcast(listenTo, MemberList(membersByLT(listenTo)))
  }

  /**
   * Unsubscribe a listener to something that can be listened to
   */
  private def unsubscribe(listenerName: String, listenTo: ListenTo) {
    subscribers = subscribers.updated(listenTo, subscribers.getOrElse(listenTo, Set.empty[String]) - listenerName)
    subscriptions = subscriptions.updated(listenerName, subscriptions.getOrElse(listenerName, Set.empty[ListenTo]) - listenTo)
    broadcast(listenTo, MemberList(membersByLT(listenTo)))
  }

  def receive = {

    case ej: Join => {

      val enumerator = Concurrent.unicast[JsValue](
        onStart = { channel =>
          if (members.contains(ej.listenerName)) {
            channel push CannotConnect("This username is already used").json
          } else {
            members = members + (ej.listenerName -> channel)
            memberJoins = memberJoins + (ej.listenerName -> ej)

            // Push out the name of the listener
            channel push Json.obj("type" -> "connected", "listenerName" -> ej.listenerName)

            for (lt <- ej.listenTo) {
              subscribe(ej.listenerName, lt)
            }
          }
        },
        onComplete = { self.!(Quit(ej.listenerName)) })
      sender ! Connected(ej.listenerName, enumerator)

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
      for (subscription <- subscriptions.getOrElse(listenerName, Set.empty[ListenTo])) {
        subscribers = subscribers.updated(subscription, subscribers.getOrElse(subscription, Set.empty[String]) - listenerName)
        broadcast(subscription, MemberList(membersByLT(subscription)))
      }
      subscriptions = subscriptions - listenerName
      
      for (channel <- ch) channel.end
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
      json <- event.toJson;
      listenerSet <- subscribers.get(key);
      listenerName <- listenerSet;
      listener <- members.get(listenerName)
    ) {
      listener.push(json)
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


