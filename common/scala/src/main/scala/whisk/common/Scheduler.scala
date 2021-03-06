/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props

/**
 * Scheduler utility functions to execute tasks in a repetitive way with controllable behavior
 * even for asynchronous tasks.
 */
object Scheduler {
    private case object Work

    /**
     * Sets up an Actor to send itself a message to mimic schedulers behavior in a more controllable way.
     *
     * @param interval the time to wait between two runs
     * @param alwaysWait always wait for the given amount of time or calculate elapsed time to wait
     * @param closure the closure to be executed
     */
    private class Worker(interval: FiniteDuration, alwaysWait: Boolean, closure: () => Future[Any])(implicit logging: Logging) extends Actor {
        implicit val ec = context.dispatcher

        override def preStart() = {
            self ! Work
        }

        def receive = {
            case Work =>
                val deadline = interval.fromNow
                Try(closure()) match {
                    case Success(result) =>
                        result onComplete { _ =>
                            val timeToWait = if (alwaysWait) interval else deadline.timeLeft.max(Duration.Zero)
                            // context might be null here if a PoisonPill is sent while doing computations
                            Option(context) foreach { _.system.scheduler.scheduleOnce(timeToWait, self, Work) }
                        }
                    case Failure(e) => logging.error(this, s"next iteration could not be scheduled because of ${e.getMessage}. Scheduler is halted")
                }
        }
    }

    /**
     * Schedules a closure to run continuously scheduled, with at least the given interval in between runs.
     * This waits until the Future of the closure has finished, ignores its result and waits for at most the
     * time specified. If the closure took as long or longer than the time specified, the next iteration
     * is immediately fired.
     *
     * @param interval the time to wait at most between two runs of the closure
     * @param f the function to run
     */
    def scheduleWaitAtMost(interval: FiniteDuration)(f: () => Future[Any])(implicit system: ActorSystem, logging: Logging) = {
        require(interval > Duration.Zero)
        system.actorOf(Props(new Worker(interval, false, f)))
    }

    /**
     * Schedules a closure to run continuously scheduled, with at least the given interval in between runs.
     * This waits until the Future of the closure has finished, ignores its result and then waits for the
     * given interval.
     *
     * @param interval the time to wait between two runs of the closure
     * @param f the function to run
     */
    def scheduleWaitAtLeast(interval: FiniteDuration)(f: () => Future[Any])(implicit system: ActorSystem, logging: Logging) = {
        require(interval > Duration.Zero)
        system.actorOf(Props(new Worker(interval, true, f)))
    }
}
