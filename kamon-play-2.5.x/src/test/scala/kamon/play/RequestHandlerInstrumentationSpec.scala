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

package kamon.play

import javax.inject.Inject

import akka.stream.Materializer
import kamon.Kamon
import kamon.play.action.OperationName
import kamon.testkit.BaseKamonSpec
import org.scalatest.Inside
import org.scalatestplus.play._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.HttpErrorHandler
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.mvc.Results.Ok
import play.api.mvc._
import play.api.routing.SimpleRouter
import play.api.test.Helpers._
import play.api.test._
import play.core.routing._

import scala.concurrent.Future

class RequestHandlerInstrumentationSpec extends PlaySpec with BaseKamonSpec with GuiceOneServerPerSuite with Inside {
  System.setProperty("config.file", "./kamon-play-2.5.x/src/test/resources/conf/application.conf")

  override lazy val port: Port = 19002

  val executor = scala.concurrent.ExecutionContext.Implicits.global

  val withRoutes: PartialFunction[(String, String), Handler] = {
    case ("GET", "/async") ⇒
      Action.async {
        Future {
          Ok("Async.async")
        }(executor)
      }
    case ("GET", "/notFound") ⇒
      Action {
        Results.NotFound
      }
    case ("GET", "/error") ⇒
      Action {
        throw new Exception("This page generates an error!")
        Ok("This page will generate an error!")
      }
    case ("GET", "/redirect") ⇒
      Action {
        Results.Redirect("/redirected", MOVED_PERMANENTLY)
      }
    case ("GET", "/default") ⇒
      Action {
        Ok("default")
      }
    case ("GET", "/async-renamed") ⇒
      OperationName("renamed-trace") {
        Action.async {
          Future {
            Ok("Async.async")
          }(executor)
        }
      }
    case ("GET", "/retrieve") ⇒
      Action {
        Ok("retrieve from TraceLocal")
      }
  }

  val additionalConfiguration : Map[String, _] = Map(
    ("play.http.requestHandler", "play.api.http.DefaultHttpRequestHandler"),
    ("logger.root", "OFF"),
    ("logger.play", "OFF"),
    ("logger.application", "OFF"))


  override def fakeApplication(): Application = new GuiceApplicationBuilder()
//    .configure(additionalConfiguration)
    .routes(withRoutes)
    .build

  val traceTokenValue = "kamon-trace-token-test"
  val traceTokenHeaderName = "X-Trace-Token"
  val expectedToken = Some(traceTokenValue)
  val traceTokenHeader = traceTokenHeaderName -> traceTokenValue
  val traceLocalStorageValue = "localStorageValue"
  val traceLocalStorageKey = "localStorageKey"
  val traceLocalStorageHeader = traceLocalStorageKey -> traceLocalStorageValue

  "the Request instrumentation" should {
    "respond to the Async Action with X-Trace-Token" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val myPublicAddress =  s"localhost:$port"

      val testSpan = spanWithBaggage(key = "propagate", value = "ws-client")
      val baggageInBody = Kamon.withSpan(testSpan) {
        val response = await(wsClient.url(s"http://$myPublicAddress/async").withHeaders(traceTokenHeader, traceLocalStorageHeader).get())
//        Kamon.activeSpan().getBaggageItem("propagate")
      }
    }

    "respond to the NotFound Action with X-Trace-Token" in {
      val Some(result) = route(FakeRequest(GET, "/notFound").withHeaders(traceTokenHeader))
      header(traceTokenHeaderName, result) must be(expectedToken)
    }
//
//    "respond to the Default Action with X-Trace-Token" in {
//      val Some(result) = route(FakeRequest(GET, "/default").withHeaders(traceTokenHeader))
//      header(traceTokenHeaderName, result) must be(expectedToken)
//    }
//
//    "respond to the Redirect Action with X-Trace-Token" in {
//      val Some(result) = route(FakeRequest(GET, "/redirect").withHeaders(traceTokenHeader))
//      header("Location", result) must be(Some("/redirected"))
//      header(traceTokenHeaderName, result) must be(expectedToken)
//    }
  }
}

//    "respond to the Async Action with X-Trace-Token and the renamed trace" in {
//      val result = Await.result(route(FakeRequest(GET, "/async-renamed").withHeaders(traceTokenHeader)).get, 10 seconds)
//      Tracer.currentContext.name must be("renamed-trace")
//      Some(result.header.headers(traceTokenHeaderName)) must be(expectedToken)
//    }
//
//    "propagate the TraceContext and LocalStorage through of filters in the current request" in {
//      route(FakeRequest(GET, "/retrieve").withHeaders(traceTokenHeader, traceLocalStorageHeader))
//      TraceLocal.retrieve(TraceLocalKey).get must be(traceLocalStorageValue)
//    }
//
//    "propagate metadata generated in the async filters" in {
//      route(FakeRequest(GET, "/retrieve"))
//      Tracer.currentContext mustBe 'closed
//      inside(Tracer.currentContext) {
//        case ctx: MetricsOnlyContext =>
//          ctx.tags must contain("filter" -> "async")
//      }
//    }
//
//    "response to the getRouted Action and normalise the current TraceContext name" in {
//      Await.result(WS.url(s"http://localhost:$port/getRouted").get(), 10 seconds)
//      Kamon.metrics.find("getRouted.get", "trace", Map("filter" -> "async")) must not be empty
//    }
//
//    "response to the postRouted Action and normalise the current TraceContext name" in {
//      Await.result(WS.url(s"http://localhost:$port/postRouted").post("content"), 10 seconds)
//      Kamon.metrics.find("postRouted.post", "trace", Map("filter" -> "async")) must not be empty
//    }
//
//    "response to the showRouted Action and normalise the current TraceContext name" in {
//      Await.result(WS.url(s"http://localhost:$port/showRouted/2").get(), 10 seconds)
//      Kamon.metrics.find("show.some.id.get", "trace", Map("filter" -> "async")) must not be empty
//    }
//
//    "record http server metrics for all processed requests" in {
//      val collectionContext = CollectionContext(100)
//      Kamon.metrics.find("play-server", "http-server").get.collect(collectionContext)
//
//      for (repetition ← 1 to 10) {
//        Await.result(route(FakeRequest(GET, "/default").withHeaders(traceTokenHeader)).get, 10 seconds)
//      }
//
//      for (repetition ← 1 to 5) {
//        Await.result(route(FakeRequest(GET, "/notFound").withHeaders(traceTokenHeader)).get, 10 seconds)
//      }
//
//      for (repetition ← 1 to 5) {
//        Await.result(routeWithOnError(FakeRequest(GET, "/error").withHeaders(traceTokenHeader)).get, 10 seconds)
//      }
//
//      val snapshot = Kamon.metrics.find("play-server", "http-server").get.collect(collectionContext)
//      snapshot.counter("GET: /default_200").get.count must be(10)
//      snapshot.counter("GET: /notFound_404").get.count must be(5)
//      snapshot.counter("GET: /error_500").get.count must be(5)
//      snapshot.counter("200").get.count must be(10)
//      snapshot.counter("404").get.count must be(5)
//      snapshot.counter("500").get.count must be(5)
//    }
//  }
//
//  def routeWithOnError[T](req: Request[T])(implicit w: Writeable[T]): Option[Future[Result]] = {
//    route(req).map { result ⇒
//      result.recoverWith {
//        case t: Throwable ⇒ DefaultGlobal.onError(req, t)
//      }
//    }
//  }
//}
//
//object TraceLocalKey extends TraceLocal.TraceLocalKey[String]
//
//class TraceLocalFilter @Inject() (implicit val mat: Materializer) extends Filter {
//
//  val traceLocalStorageValue = "localStorageValue"
//  val traceLocalStorageKey = "localStorageKey"
//  val traceLocalStorageHeader = traceLocalStorageKey -> traceLocalStorageValue
//
//  override def apply(next: (RequestHeader) ⇒ Future[Result])(header: RequestHeader): Future[Result] = {
//    Tracer.withContext(Tracer.currentContext) {
//
//      TraceLocal.store(TraceLocalKey)(header.headers.get(traceLocalStorageKey).getOrElse("unknown"))
//
//      next(header).map {
//        result ⇒
//          {
//            result.withHeaders(traceLocalStorageKey -> TraceLocal.retrieve(TraceLocalKey).get)
//          }
//      }
//    }
//  }
//}
//
//class TraceAsyncFilter @Inject() extends EssentialFilter {
//  override def apply(next: EssentialAction): EssentialAction = EssentialAction { requestHeader =>
//    def onResult(result: Result): Result = {
//      if (Tracer.currentContext.status == Status.Open)
//        Tracer.currentContext.addTag("filter", "async")
//      result
//    }
//
//    val nextAccumulator = next(requestHeader)
//    nextAccumulator.map(onResult)
//  }
//}
//
//class TestHttpFilters @Inject() (traceLocalFilter: TraceLocalFilter, traceAsyncFilter: TraceAsyncFilter) extends HttpFilters {
//  val filters = Seq(traceLocalFilter, traceAsyncFilter)
//}
//
class Routes @Inject() (application: controllers.Application) extends GeneratedRouter with SimpleRouter {
  val prefix = "/"

  lazy val defaultPrefix = {
    if (prefix.endsWith("/")) "" else "/"
  }

  // Gets
  private[this] lazy val Application_getRouted =
    Route("GET", PathPattern(List(StaticPart(prefix), StaticPart(defaultPrefix), StaticPart("getRouted"))))

  private[this] lazy val Application_show =
    Route("GET", PathPattern(List(StaticPart(prefix), StaticPart(defaultPrefix), StaticPart("showRouted/"), DynamicPart("id", """[^/]+""", encodeable = true))))

  //Posts
  private[this] lazy val Application_postRouted =
    Route("POST", PathPattern(List(StaticPart(prefix), StaticPart(defaultPrefix), StaticPart("postRouted"))))

  def routes: PartialFunction[RequestHeader, Handler] = {
    case Application_getRouted(params) ⇒ call {
      createInvoker(application.getRouted,
        HandlerDef(this.getClass.getClassLoader, "", "controllers.Application", "getRouted", Nil, "GET", """some comment""", prefix + """getRouted""")).call(application.getRouted)
    }
    case Application_postRouted(params) ⇒ call {
      createInvoker(application.postRouted,
        HandlerDef(this.getClass.getClassLoader, "", "controllers.Application", "postRouted", Nil, "POST", """some comment""", prefix + """postRouted""")).call(application.postRouted)
    }
    case Application_show(params) ⇒ call(params.fromPath[Int]("id", None)) { (id) ⇒
      createInvoker(application.showRouted(id),
        HandlerDef(this.getClass.getClassLoader, "", "controllers.Application", "showRouted", Seq(classOf[Int]), "GET", """""", prefix + """show/some/$id<[^/]+>""")).call(application.showRouted(id))
    }
  }

  override def errorHandler: HttpErrorHandler = new HttpErrorHandler() {
    override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = Future.successful(Results.InternalServerError)
    override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = Future.successful(Results.InternalServerError)
  }
}

object controllers {
  import play.api.mvc._

  class Application extends Controller {
    val postRouted = Action {
      Ok("invoked postRouted")
    }
    val getRouted = Action {
      Ok("invoked getRouted")
    }
    def showRouted(id: Int) = Action {
      Ok("invoked show with: " + id)
    }
  }
}


class TestNameGenerator extends NameGenerator {
  import java.util.Locale

  import play.api.routing.Router

  import scala.collection.concurrent.TrieMap
  import kamon._

  private val cache = TrieMap.empty[String, String]
  private val normalizePattern = """\$([^<]+)<[^>]+>""".r

  def generateOperationName(requestHeader: RequestHeader): String = requestHeader.tags.get(Router.Tags.RouteVerb).map { verb ⇒
    val path = requestHeader.tags(Router.Tags.RoutePattern)
    cache.atomicGetOrElseUpdate(s"$verb$path", {
      val traceName = {
        // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
        val p = normalizePattern.replaceAllIn(path, "$1").replace('/', '.').dropWhile(_ == '.')
        val normalisedPath = {
          if (p.lastOption.exists(_ != '.')) s"$p."
          else p
        }
        s"$normalisedPath${verb.toLowerCase(Locale.ENGLISH)}"
      }
      traceName
    })
  } getOrElse s"${requestHeader.method}: ${requestHeader.uri}"

  def generateHttpClientOperationName(request: WSRequest): String = request.url
}