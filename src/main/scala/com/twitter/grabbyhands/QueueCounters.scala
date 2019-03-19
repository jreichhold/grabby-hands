/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.grabbyhands

import java.util.concurrent.atomic.AtomicLong
import scala.collection.Map
import scala.collection.mutable.HashMap

class QueueCounters() {
  val bytesRecv = new AtomicLong()
  val bytesSent = new AtomicLong()
  val messagesRecv = new AtomicLong()
  val messagesSent = new AtomicLong()

  val kestrelGetTimeouts = new AtomicLong()
  val protocolError = new AtomicLong()
  val sendCancelled = new AtomicLong()
  val recvCancelled = new AtomicLong()

  def toMap(): Map[String, Long] = {
    val rv = new HashMap[String, Long]()
    rv += ("bytesRecv" -> bytesRecv.get)
    rv += ("bytesSent" -> bytesSent.get)
    rv += ("messagesRecv" -> messagesRecv.get)
    rv += ("messagesSent" -> messagesSent.get)
    rv += ("kestrelGetTimeouts" -> kestrelGetTimeouts.get)
    rv += ("protocolError" -> protocolError.get)
    rv += ("sendCancelled" -> sendCancelled.get)
    scala.collection.immutable.Map() ++ rv
  }
}
