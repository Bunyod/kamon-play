/* =========================================================================================
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

import io.netty.handler.codec.http.{HttpRequest, HttpResponse}
import io.opentracing.propagation.Format.Builtin.HTTP_HEADERS
import io.opentracing.propagation.TextMap
import kamon.Kamon
import kamon.play.KamonFilter
import kamon.util.CallingThreadExecutionContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import play.api.mvc.EssentialFilter

import scala.concurrent.Future

@Aspect
class RequestHandlerInstrumentation {

  private lazy val filter: EssentialFilter = new KamonFilter()

  @Around("execution(* play.core.server.netty.PlayRequestHandler.handle(..)) && args(*, request)")
  def onHandle(pjp: ProceedingJoinPoint, request: HttpRequest): Any = {
    val incomingSpanContext = Kamon.extract(HTTP_HEADERS, readOnlyTextMapFromHttpRequest(request))
    val span = Kamon.buildSpan("unknown-operation")
      .asChildOf(incomingSpanContext)
      .withTag("span.kind", "server")
      .startActive()
    val continuation = span.capture()

    val responseFuture = pjp.proceed().asInstanceOf[Future[HttpResponse]]
    span.deactivate()

    responseFuture.transform(
      s = response => {
        val requestSpan = continuation.activate()
        if(isError(response.getStatus.code())) {
          requestSpan.setTag("error", "true")
        }

        requestSpan.deactivate()
        response
      },

      f = error => {
        val requestSpan = continuation.activate()
        requestSpan.setTag("error", "true")
        requestSpan.deactivate()
        error
      }
    )(CallingThreadExecutionContext)
  }

  def readOnlyTextMapFromHttpRequest(request: HttpRequest): TextMap = new TextMap {
    override def put(key: String, value: String): Unit = {}
    override def iterator(): util.Iterator[util.Map.Entry[String, String]] =
      request.headers().entries().iterator()
  }

  def isError(statusCode: Int): Boolean =
    statusCode >= 500 && statusCode < 600

  @Around("call(* play.api.http.HttpFilters.filters(..))")
  def filters(pjp: ProceedingJoinPoint): Any = {
    filter +: pjp.proceed().asInstanceOf[Seq[EssentialFilter]]
  }
}
