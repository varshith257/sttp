package sttp.client3.asynchttpclient

import sttp.client3._
import _root_.zio._
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.impl.zio.{WebSocketStreamBackendExtendEnv, WebSocketStreamClientStubbing}

package object zio {

  /** ZIO-environment service definition, which is an SttpBackend.
    */
  type SttpClient = Has[SttpClient.Service]
  type SttpClientStubbing = Has[SttpClientStubbing.Service]

  object SttpClient {
    type Service = WebSocketStreamBackend[Task, ZioStreams]
  }

  /** Sends the request. Only requests for which the method & URI are specified can be sent.
    *
    * @return
    *   An effect resulting in a [[Response]], containing the body, deserialized as specified by the request (see
    *   [[RequestT.response]]), if the request was successful (1xx, 2xx, 3xx response codes), or if there was a
    *   protocol-level failure (4xx, 5xx response codes).
    *
    * A failed effect, if an exception occurred when connecting to the target host, writing the request or reading the
    * response.
    *
    * Known exceptions are converted to one of [[SttpClientException]]. Other exceptions are kept unchanged.
    */
  def send[T](
      request: AbstractRequest[T, Effect[Task] with ZioStreams with WebSockets]
  ): ZIO[SttpClient, Throwable, Response[T]] =
    ZIO.accessM(env => env.get[SttpClient.Service].send(request))

  /** A variant of [[send]] which allows the effects that are part of the response handling specification (when using
    * websockets or resource-safe streaming) to use an `R` environment.
    */
  def sendR[T, R](
      request: AbstractRequest[T, Effect[RIO[R, *]] with ZioStreams with WebSockets]
  ): ZIO[SttpClient with R, Throwable, Response[T]] =
    ZIO.accessM(env => env.get[SttpClient.Service].extendEnv[R].send(request))

  object SttpClientStubbing extends WebSocketStreamClientStubbing[Any, ZioStreams] {
    override private[sttp] def serviceTag: Tag[SttpClientStubbing.Service] = implicitly
    override private[sttp] def sttpBackendTag: Tag[SttpClient.Service] = implicitly
  }

  object stubbing {
    import SttpClientStubbing.StubbingWhenRequest

    def whenRequestMatches(p: AbstractRequest[_, _] => Boolean): StubbingWhenRequest =
      StubbingWhenRequest(p)

    val whenAnyRequest: StubbingWhenRequest =
      StubbingWhenRequest(_ => true)

    def whenRequestMatchesPartial(
        partial: PartialFunction[AbstractRequest[_, _], Response[_]]
    ): URIO[SttpClientStubbing, Unit] =
      ZIO.accessM(_.get.whenRequestMatchesPartial(partial))
  }
}
