/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.dstream

import org.apache.spark.streaming.StreamingContext
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.NextIterator

import scala.reflect.ClassTag

import java.io._
import java.net.{UnknownHostException, Socket}
import org.apache.spark.Logging
import org.apache.spark.streaming.receiver.Receiver

/**
 * Created by soh on 15/01/15.
 * This class is written to support specifying the preferred location for each socket receiver.
 * This is needed because otherwise, multiple receivers could be place in a single node.
 */
private[streaming]
class SocketInputDStreamWithReceiverLocation[T: ClassTag](
                                       @transient ssc_ : StreamingContext,
                                       host: String,
                                       port: Int,
                                       bytesToObjects: InputStream => Iterator[T],
                                       storageLevel: StorageLevel,
                                       receiverLocation: String
                                       ) extends ReceiverInputDStream[T](ssc_) {

  def getReceiver(): Receiver[T] = {
    new SocketReceiverWithLocation(host, port, bytesToObjects, storageLevel, receiverLocation)
  }
}



private[streaming]
class SocketReceiverWithLocation[T: ClassTag](
                                   host: String,
                                   port: Int,
                                   bytesToObjects: InputStream => Iterator[T],
                                   storageLevel: StorageLevel,
                                   receiverLocation: String
                                   ) extends Receiver[T](storageLevel) with Logging {

  def onStart() {
    // Start the thread that receives data over a connection
    new Thread("Socket Receiver") {
      setDaemon(true)
      override def run() { receive() }
    }.start()
  }

  def onStop() {
    // There is nothing much to do as the thread calling receive()
    // is designed to stop by itself isStopped() returns false
  }

  override def preferredLocation: Option[String] = Some(receiverLocation)

  /** Create a socket connection and receive data until receiver is stopped */
  def receive() {
    var socket: Socket = null
    try {
      logInfo("Connecting to " + host + ":" + port)
      socket = new Socket(host, port)
      logInfo("Connected to " + host + ":" + port)
      val iterator = bytesToObjects(socket.getInputStream())
      while(!isStopped && iterator.hasNext) {
        store(iterator.next)
      }
      logInfo("Stopped receiving")
      restart("Retrying connecting to " + host + ":" + port)
    } catch {
      case e: java.net.ConnectException =>
        restart("Error connecting to " + host + ":" + port, e)
      case t: Throwable =>
        restart("Error receiving data", t)
    } finally {
      if (socket != null) {
        socket.close()
        logInfo("Closed socket to " + host + ":" + port)
      }
    }
  }
}

private[streaming]
object SocketReceiverWithLocation  {

  /**
   * This methods translates the data from an inputstream (say, from a socket)
   * to '\n' delimited strings and returns an iterator to access the strings.
   */
  def bytesToLines(inputStream: InputStream): Iterator[String] = {
    val dataInputStream = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))
    new NextIterator[String] {
      protected override def getNext() = {
        val nextValue = dataInputStream.readLine()
        if (nextValue == null) {
          finished = true
        }
        nextValue
      }

      protected override def close() {
        dataInputStream.close()
      }
    }
  }
}

