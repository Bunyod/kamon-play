/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play.instrumentation

import java.util
import java.util.Collections

import io.opentracing.propagation.Format.Builtin.HTTP_HEADERS
import io.opentracing.propagation.TextMap
import kamon.Kamon
import kamon.play.Play
import kamon.util.CallingThreadExecutionContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect, Pointcut}
import play.api.libs.ws.StandaloneWSRequest

import scala.collection.mutable
import scala.concurrent.Future

@Aspect
class WSInstrumentation {

  @Pointcut("execution(* play.api.libs.ws.WSRequestExecutor$$anon$2.apply(..)) && args(request)")
  def onExecuteWSRequest(request: StandaloneWSRequest): Unit = {}

  @Around("onExecuteWSRequest(request)")
  def aroundExecuteRequest(pjp: ProceedingJoinPoint, request: StandaloneWSRequest): Any = {
    val activeSpan = Kamon.activeSpan()
    if(activeSpan == null)
      pjp.proceed()
    else {
      val operationName = Play.generateHttpClientOperationName(request)
      val clientRequestSpan = Kamon.buildSpan(operationName).asChildOf(activeSpan.context()).startManual()
      clientRequestSpan.setTag("span.kind", "client")

      val maybeHeaders = mutable.Map.empty[String, String]
      Kamon.inject(clientRequestSpan.context(), HTTP_HEADERS, writeOnlyTextMapFromMap(maybeHeaders))
      val injectedRequest = request.withHttpHeaders(maybeHeaders.toSeq: _*)
      val responseFuture = pjp.proceed(Array(injectedRequest)).asInstanceOf[Future[play.api.libs.ws.StandaloneWSResponse]]

      responseFuture.transform(
        s = response => {
          clientRequestSpan.finish()
          response
        },
        f = error => {
          clientRequestSpan.setTag("error", "true").finish()
          error
        }
      )(CallingThreadExecutionContext)
    }
  }

  def writeOnlyTextMapFromMap(map: scala.collection.mutable.Map[String, String]): TextMap = new TextMap {
    override def put(key: String, value: String): Unit =
      map.put(key, value)

    override def iterator(): util.Iterator[util.Map.Entry[String, String]] =
      Collections.emptyIterator()
  }
}
