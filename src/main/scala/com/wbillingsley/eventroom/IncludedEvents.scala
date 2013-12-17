package com.wbillingsley.eventroom

import play.api.libs.json._
import com.wbillingsley.handy.Ref._

/**
 * An event that should be broadcast.
 */
abstract class BroadcastEvent[T](lt:ListenTo) extends EREvent {
  override def action(room:EventRoom) = {
    room.broadcast(lt, this)
  }
}

/**
 * A broadcast event containing pre-calculated JSON that does not vary for each member
 */
abstract class JsonEvent[T](lt:ListenTo, json:JsValue) extends BroadcastEvent(lt) {
  val ji = json.itself
  override def toJson = ji 
}