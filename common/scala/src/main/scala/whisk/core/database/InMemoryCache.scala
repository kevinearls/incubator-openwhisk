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

package whisk.core.database

import scala.concurrent.Future
import spray.caching.Cache
import spray.caching.LruCache
import whisk.common.Logging
import whisk.common.TransactionId
import scala.concurrent.ExecutionContext
import whisk.common.LoggingMarkers

trait InMemoryCache[W] {

    /** Toggle to enable/disable caching. */
    protected def cacheEnabled = false

    protected def cacheKeys(w: W): Set[Any] = Set(w)

    protected def cacheInvalidate(keys: Set[Any])(
        implicit transid: TransactionId, logger: Logging): Unit = {
        if (cacheEnabled) {
            logger.info(this, s"invalidating $keys")
            keys foreach { k => cache remove k }
        }
    }

    protected def cacheLookup[Wsuper >: W](
        datastore: ArtifactStore[Wsuper],
        key: Any,
        future: => Future[W],
        fromCache: Boolean = cacheEnabled)(
            implicit transid: TransactionId, logger: Logging) = {
        if (fromCache) {
            implicit val ec = datastore.executionContext
            cache.get(key) map { v =>
                transid.mark(this, LoggingMarkers.DATABASE_CACHE_HIT, s"[GET] serving from cache: $key")(logger)
                v
            } getOrElse {
                transid.mark(this, LoggingMarkers.DATABASE_CACHE_MISS, s"[GET] serving from datastore: $key")(logger)
                future flatMap {
                    // cache result of future iff it was successful
                    cache(key)(_)
                }
            }
        } else future
    }

    protected def cacheUpdate(keys: Set[Any], w: W)(
        implicit transid: TransactionId, logger: Logging, ec: ExecutionContext) = {
        if (cacheEnabled) {
            logger.info(this, s"caching $keys")
            keys foreach { cache(_) { w } }
        }
    }

    private val cache: Cache[W] = LruCache()
}
