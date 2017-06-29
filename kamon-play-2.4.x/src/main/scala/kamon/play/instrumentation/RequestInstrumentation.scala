/* =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
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

import io.netty.handler.codec.http.HttpRequest
import io.opentracing.propagation.TextMap
import kamon.Kamon.tracer
import kamon.play.{KamonFilter, PlayExtension}
import kamon.trace._
import kamon.util.{CallingThreadExecutionContext, HasContinuation}
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.handler.codec.http.HttpRequest
import play.api.mvc.Results._
import play.api.mvc._

@Aspect
class RequestInstrumentation {

  private lazy val filter: EssentialFilter = new KamonFilter()

  @DeclareMixin("play.api.mvc.RequestHeader+")
  def mixinHasContinuationToRequestHeader: HasContinuation = HasContinuation.fromTracerActiveSpan()


//  @Around("execution(* play.core.server.netty.PlayDefaultUpstreamHandler.messageReceived(..)) && args(*, message)")
//  def onHandle(pjp: ProceedingJoinPoint, message: MessageEvent): Any = {
//    if(!message.getMessage.isInstanceOf[HttpRequest]) pjp.proceed()
//    else {
//      val request = message.getMessage.asInstanceOf[HttpRequest]
//
//    }


//  }
  @Before("call(* play.api.http.DefaultHttpRequestHandler.routeRequest(..)) && args(requestHeader)")
  def routeRequest(requestHeader: RequestHeader): Unit = {
    val token = if (PlayExtension.includeTraceToken) {
      requestHeader.headers.get(PlayExtension.traceTokenHeaderName)
    } else None

    Tracer.setCurrentContext(tracer.newContext("UnnamedTrace", token))
  }


  def readOnlyTextMapFromHttpRequest(request: RequestHeader): TextMap = new TextMap {
    override def put(key: String, value: String): Unit = {}
    override def iterator(): util.Iterator[util.Map.Entry[String, String]] = Collections.emptyIterator()
//      val a = request.headers.toSimpleMap.
  }

  def isError(statusCode: Int): Boolean =
    statusCode >= 500 && statusCode < 600


  @Around("call(* play.api.http.HttpFilters.filters(..))")
  def filters(pjp: ProceedingJoinPoint): Any = {
    filter +: pjp.proceed().asInstanceOf[Seq[EssentialFilter]]
  }
}
