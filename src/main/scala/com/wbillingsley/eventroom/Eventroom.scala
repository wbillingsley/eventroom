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

/**
 * A member of the EventRoom. Wrap your user class in one of these
 */
trait Member {
  /**
   * The JSON you want to appear for each member in the list of those in the room
   */
  def toJson:JsValue
}

/** A key saying what the listener is listening to */
abstract class ListenTo {
  def onSubscribe(listenerName: String, room: EventRoom) = { /* do nothing */ }
}

/** A bit of state that events can update */
abstract class State

/** Type of event sent in the room. */
abstract class EREvent {
  def toJson: JsValue = Json.obj("unexpected" -> "This event would not normally be broadcast")

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
  override def toJson = Json.obj("error" -> msg)
}

/** Who else is in the same event room. */
case class MemberList(members: Iterable[Member]) extends EREvent {
  override def toJson = Json.obj(
    "type" -> JsString("members"),
    "members" -> Json.toJson(members.map(r => r.toJson)),
    "date" -> Json.toJson(System.currentTimeMillis()))
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
        Enumerator[JsValue](CannotConnect(error).toJson).andThen(Enumerator.enumInput(Input.EOF))
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
            channel push CannotConnect("This username is already used").toJson
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
    val json = event.toJson

    for (
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


