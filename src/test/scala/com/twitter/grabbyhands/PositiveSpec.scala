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

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object PositiveSpec extends SpecBase(4) {

  "positive" should {
    doBefore {
      noDetailedDiffs()  // else large string compare goes berzerk
      val meta = new MetaRequest(hostPort, None)
      queues.foreach(queue => meta.deleteQueue(queue))

      defaults()
      grab = null
    }

    doAfter {
      if (grab != null) {
        grab.join()
        grab.counters.threads.get must be_==(0)
      }
    }

    "write one, read one" in {
      ctor()
      grab must notBeNull
      val send = grab.getSendQueue(queue)
      val recv = grab.getRecvQueue(queue)

      val sendText = "text"
      val write = new Write(sendText)
      write.written must beFalse
      write.cancelled must beFalse

      send.put(write)

      val buffer = recv.poll(2, TimeUnit.SECONDS)
      buffer must notBeNull

      val recvText = new String(buffer.array)
      recvText must be_==(sendText)

      write.written must beTrue
      write.cancelled must beFalse

      val serverCount = grab.serverCounters(hostPort)
      serverCount.protocolError.get must be_==(0)
      serverCount.connectionWriteTimeout.get must be_==(0)
      serverCount.connectionReadTimeout.get must be_==(0)
      serverCount.messagesSent.get must be_==(1)
      serverCount.bytesSent.get must be_==(sendText.length)
      serverCount.messagesRecv.get must be_==(1)
      serverCount.bytesRecv.get must be_==(sendText.length)

      val queueCount = grab.queueCounters(queue)
      queueCount.protocolError.get must be_==(0)
      queueCount.messagesSent.get must be_==(1)
      queueCount.bytesSent.get must be_==(sendText.length)
      queueCount.messagesRecv.get must be_==(1)
      queueCount.bytesRecv.get must be_==(sendText.length)
      queueCount.kestrelGetTimeouts.get must be_==(0)

      val cs = grab.countersToString("f")
      cs must include("f.server." + hostPort + ".protocolError: 0")
      cs must include("f.server." + hostPort + ".connectionWriteTimeout: 0")
      cs must include("f.server." + hostPort + ".connectionReadTimeout: 0")
      cs must include("f.server." + hostPort + ".messagesSent: 1")
      cs must include("f.server." + hostPort + ".bytesSent: " + sendText.length)
      cs must include("f.server." + hostPort + ".messagesRecv: 1")
      cs must include("f.server." + hostPort + ".bytesRecv: " + sendText.length)

      cs must include("f.queue." + queue + ".protocolError: 0")
      cs must include("f.queue." + queue + ".messagesSent: 1")
      cs must include("f.queue." + queue + ".bytesSent: " + sendText.length)
      cs must include("f.queue." + queue + ".messagesRecv: 1")
      cs must include("f.queue." + queue + ".bytesRecv: " + sendText.length)
      cs must include("f.queue." + queue + ".kestrelGetTimeouts: 0")
    }

    "connection counters" in {
      ctor()
      grab must notBeNull
      val serverCount = grab.serverCounters(hostPort)
      var retries = 20
      while (retries > 0 && serverCount.connectionOpenAttempt.get != 2) {
        retries -= 1
        Thread.sleep(25)
      }
      serverCount.connectionOpenAttempt.get must be_==(2) // One for each direction
      serverCount.connectionOpenSuccess.get must be_==(2)
      serverCount.connectionOpenTimeout.get must be_==(0)
      serverCount.connectionCurrent.get must be_==(2)
      serverCount.connectionExceptions.get must be_==(0)

      val cs = grab.countersToString()
      cs must include("server." + hostPort + ".connectionOpenAttempt: 2")
      cs must include("server." + hostPort + ".connectionOpenSuccess: 2")
      cs must include("server." + hostPort + ".connectionOpenTimeout: 0")
      cs must include("server." + hostPort + ".connectionCurrent: 2")
      cs must include("server." + hostPort + ".connectionExceptions: 0")

      grab.join()
      serverCount.connectionCurrent.get must be_==(0)
      grab = null
    }

    "read empty queue" in {
      // Make kestrelReadTimeout much larger than readTimeout to avoid confusion
      val baseMs = 250
      val factor = 6
      config.readTimeoutMs = baseMs
      config.kestrelReadTimeoutMs = config.readTimeoutMs * factor

      val rounds = 2
      var sleepMs: Int = rounds * config.kestrelReadTimeoutMs
      // Normal read timeout is also included
      sleepMs += config.readTimeoutMs
      // Add a little bit more for slop
      sleepMs += config.readTimeoutMs >> 2
      sleepMs must be_<((rounds + 1) * config.kestrelReadTimeoutMs)

      ctor()
      grab.config.kestrelReadTimeoutMs must be_==(baseMs * factor)
      Thread.sleep(sleepMs)

      val queueCount = grab.queueCounters(queue)
      queueCount.kestrelGetTimeouts.get must be_==(rounds)
      queueCount.bytesRecv.get must be_==(0)
      queueCount.messagesRecv.get must be_==(0)
      queueCount.bytesSent.get must be_==(0)
      queueCount.messagesSent.get must be_==(0)
      queueCount.protocolError.get must be_==(0)

      val cs = grab.countersToString()
      cs must include(queue + ".kestrelGetTimeouts: " + rounds)
    }

    "messages of varying length beyond internal queue depth" in {
      ctor()
      val maxLen = 25
      maxLen must be_>=(config.queues(queue).getSendQueueDepth)
      val save = new Array[String](maxLen + 1)
      for (length <- 1 to maxLen) {
        val sendText = genAsciiString(length)
        sendText.length must be_==(length)
        save(length) = sendText
        grab.getSendQueue(queue).put(new Write(sendText))
      }
      for (length <- 1 to maxLen) {
        val buffer = grab.getRecvQueue(queue).poll(2, TimeUnit.SECONDS)
        buffer must notBeNull
        val recvText = new String(buffer.array)
        recvText must be_==(save(length))
      }
    }

    "messages with reserved tokens" in {
      // newlines, END\r\n, VALUE, etc.
      ctor()
      val sendText = "\r\n\n\n\r\rEND\r\nVALUE\r\n"
      grab.getSendQueue(queue).put(new Write(sendText))
      val buffer = grab.getRecvQueue(queue).poll(2, TimeUnit.SECONDS)
      buffer must notBeNull
      val recvText = new String(buffer.array)
      recvText must be_==(sendText)
    }

    "message up to length limit" in {
      ctor()
      val sendText = genAsciiString(shortMessageMax)
      sendText.length must be_==(shortMessageMax)
      grab.getSendQueue(queue).put(new Write(sendText))
      val buffer = grab.getRecvQueue(queue).poll(2, TimeUnit.SECONDS)
      buffer must notBeNull
      val recvText = new String(buffer.array)
      recvText must be_==(sendText)
    }

    "huge message" in {
      config.maxMessageBytes = 524288
      ctor()
      val sendText = genAsciiString(config.maxMessageBytes)
      sendText.length must be_==(config.maxMessageBytes)
      grab.getSendQueue(queue).put(new Write(sendText))
      val buffer = grab.getRecvQueue(queue).poll(12, TimeUnit.SECONDS)
      buffer must notBeNull
      val recvText = new String(buffer.array)
      recvText.equals(sendText) must be_==(true)
      noDetailedDiffs()  // else large string compare goes berzerk
      recvText must be_==(sendText)
      detailedDiffs()


      val queueCount = grab.queueCounters(queue)
      queueCount.protocolError.get must be_==(0)
      queueCount.messagesSent.get must be_==(1)
      queueCount.bytesSent.get must be_==(sendText.length)
      queueCount.messagesRecv.get must be_==(1)
      queueCount.bytesRecv.get must be_==(sendText.length)
      queueCount.kestrelGetTimeouts.get must be_==(0)
    }

    "binary messages" in {
      val len = 256 * 2
      config.maxMessageBytes = len
      ctor()
      val sendArray = genBinaryArray(len)
      sendArray.length must be_==(len)
      grab.getSendQueue(queue).put(new Write(sendArray))
      val buffer = grab.getRecvQueue(queue).poll(2, TimeUnit.SECONDS)
      buffer must notBeNull
      buffer.position must be_==(0)
      buffer.capacity must be_==(len)
      buffer.limit must be_==(len)
      for (idx <- 0 to len - 1) {
        buffer.get must be_==(sendArray(idx))
      }

      val queueCount = grab.queueCounters(queue)
      queueCount.protocolError.get must be_==(0)
      queueCount.messagesSent.get must be_==(1)
      queueCount.bytesSent.get must be_==(len)
      queueCount.messagesRecv.get must be_==(1)
      queueCount.bytesRecv.get must be_==(len)
      queueCount.kestrelGetTimeouts.get must be_==(0)
    }

    "pause and resume" in {
      // Speed up test
      config.kestrelReadTimeoutMs = config.kestrelReadTimeoutMs >> 4
      config.readTimeoutMs = config.readTimeoutMs >> 4

      ctor()
      val queueCount = grab.queueCounters(queue)
      grab.pause()
      grab.counters.pausedThreads.get must be_==(2)
      val idled = queueCount.kestrelGetTimeouts.get
      // Verify that thread is indeed paused.
      Thread.sleep(config.kestrelReadTimeoutMs + config.readTimeoutMs)
      queueCount.kestrelGetTimeouts.get must be_==(idled)
      grab.resume()

      var retries = 10
      while (retries > 0 && grab.counters.pausedThreads.get != 0) {
        retries -= 1
        Thread.sleep(25)
      }
      grab.counters.pausedThreads.get must be_==(0)

      // Validate that things basically work after a pause.
      val sendText = "text"
      grab.getSendQueue(queue).put(new Write(sendText))
      val buffer = grab.getRecvQueue(queue).poll(2, TimeUnit.SECONDS)
      buffer must notBeNull
      val recvText = new String(buffer.array)
      recvText must be_==(sendText)

      grab.counters.pausedThreads.get must be_==(0)
    }

    "deep internal queues" in {
      val depth = 20
      config = new Config()
      config.addServer(host + ":" + port)
      config.maxMessageBytes = shortMessageMax
      config.sendQueueDepth = depth
      config.recvQueueDepth = depth
      config.addQueue(queues(0))
      ctor()

      grab.pause()
      grab.counters.pausedThreads.get must be_==(2)
      val send = grab.getSendQueue(queue)
      send.remainingCapacity must be_==(depth)
      val recv = grab.getRecvQueue(queue)
      recv.remainingCapacity must be_==(depth)

      val text = new Array[String](depth + 1)
      val writes = new Array[Write](depth + 1)
      for (idx <- 1 to depth) {
        text(idx) = "text" + idx
        writes(idx) = new Write(text(idx))
        send.remainingCapacity() must be_==(1 + depth - idx)
        send.put(writes(idx))
      }
      send.remainingCapacity must be_==(0)
      recv.remainingCapacity must be_==(depth)

      for (idx <- 1 to depth) {
        writes(idx).written must beFalse
      }
      grab.resume()

      var retries = 100
      while (retries > 0 && recv.remainingCapacity != 0) {
        retries -= 1
        Thread.sleep(25)
      }
      recv.remainingCapacity must be_==(0)
      grab.counters.pausedThreads.get must be_==(0)

      for (idx <- 1 to depth) {
        val buffer = recv.poll()
        buffer must notBeNull
        new String(buffer.array) must be_==(text(idx))
        writes(idx).written must beTrue
      }
    }

    "cancel a write" in {
      ctor()

      grab.pause()
      grab.counters.pausedThreads.get must be_==(2)

      val send = grab.getSendQueue(queue)
      val recv = grab.getRecvQueue(queue)
      val recvCapacity = recv.remainingCapacity
      recvCapacity must be_>(0)
      val sendCapacity = send.remainingCapacity
      sendCapacity must be_>(0)

      val text = "text"
      val write = new Write(text)
      send.put(write)
      send.remainingCapacity must be_==(sendCapacity - 1)

      Thread.sleep(100)
      write.written must beFalse
      write.cancelled must beFalse
      write.cancel
      write.cancelled must beTrue

      Thread.sleep(100)
      write.written must beFalse
      write.cancelled must beTrue

      grab.resume()
      var retries = 10
      while (retries > 0 && grab.counters.pausedThreads.get != 0) {
        retries -= 1
        Thread.sleep(25)
      }
      grab.counters.pausedThreads.get must be_==(0)

      // Sleep more to ensure that nothing leaks through
      Thread.sleep(250)

      recv.remainingCapacity must be_==(recvCapacity)
      val queueCount = grab.queueCounters(queue)
      queueCount.protocolError.get must be_==(0)
      queueCount.messagesSent.get must be_==(0)
      queueCount.bytesSent.get must be_==(0)
      queueCount.messagesRecv.get must be_==(0)
      queueCount.bytesRecv.get must be_==(0)
      queueCount.sendCancelled.get must be_==(1)

      val cs = grab.countersToString()
      cs must include(queue + ".sendCancelled: 1")

      write.written must beFalse
      write.cancelled must beTrue
    }

    "support multiple queues" in {
      val num = 4
      num must be_<=(queues.size)
      for (idx <- 1 to num - 1) {
        config.addQueue(queues(idx))
      }
      ctor()

      val sendText = new Array[String](num)
      val recvText = new Array[String](num)
      val buffer = new Array[ByteBuffer](num)
      for (idx <- 1 to num - 1) {
        sendText(idx) = "text" + idx
        grab.getSendQueue(queues(idx)).put(new Write(sendText(idx)))
      }

      for (idx <- 1 to num - 1) {
        buffer(idx) = grab.getRecvQueue(queues(idx)).poll(2, TimeUnit.SECONDS)
        buffer(idx) must notBeNull
        recvText(idx) = new String(buffer(idx).array)
        recvText(idx) must be_==(sendText(idx))
      }
    }

    "transactional reads" in {
      config = new Config()
      config.addServer(host + ":" + port)
      config.maxMessageBytes = shortMessageMax
      config.recvTransactional = true
      config.addQueue(queues(0))
      ctor()
      grab must notBeNull
      val send = grab.getSendQueue(queue)
      val recv = grab.getRecvTransQueue(queue)

      val emptyRead = recv.poll(1, TimeUnit.SECONDS)
      emptyRead must beNull

      val emptyCount = grab.serverCounters(hostPort)
      emptyCount.protocolError.get must be_==(0)
      emptyCount.connectionWriteTimeout.get must be_==(0)
      emptyCount.connectionReadTimeout.get must be_==(0)
      emptyCount.messagesSent.get must be_==(0)
      emptyCount.bytesSent.get must be_==(0)
      emptyCount.messagesRecv.get must be_==(0)
      emptyCount.bytesRecv.get must be_==(0)

      val sendText = "text"
      val write = new Write(sendText)
      send.put(write)

      val cancel = recv.poll(2, TimeUnit.SECONDS)
      write.written must beTrue
      cancel must notBeNull
      cancel.cancelled must beFalse
      cancel.open must beTrue

      val cancelText = new String(cancel.message.array)
      cancelText must be_==(sendText)

      grab.queueCounters(queue).sendCancelled.get must be_==(0)
      grab.queueCounters(queue).recvCancelled.get must be_==(0)

      cancel.cancel
      cancel.cancelled must beTrue
      cancel.open must beFalse
      grab.queueCounters(queue).recvCancelled.get must be_==(1)

      val close = recv.poll(2, TimeUnit.SECONDS)
      close must notBeNull
      close.cancelled must beFalse
      close.open must beTrue

      val closeText = new String(close.message.array)
      closeText must be_==(sendText)

      close.close
      close.cancelled must beFalse
      close.open must beFalse
      grab.queueCounters(queue).recvCancelled.get must be_==(1)

    }
  }
}
