package sttp.client

import java.net.URI

import sttp.client.monad.MonadError
import sttp.client.ws.WebSocketResponse
import sttp.model.{Method, StatusCode, _}

class FollowRedirectsBackend[F[_], P, WS_HANDLER[_]](
    delegate: SttpBackend[F, P, WS_HANDLER],
    contentHeaders: Set[String] = HeaderNames.ContentHeaders,
    sensitiveHeaders: Set[String] = HeaderNames.SensitiveHeaders
) extends SttpBackend[F, P, WS_HANDLER] {
  type PE = P with Effect[F]

  override def send[T, R >: PE](request: Request[T, R]): F[Response[T]] = {
    sendWithCounter(request, 0)
  }

  override def openWebsocket[T, WS_RESULT, R >: PE](
      request: Request[T, R],
      handler: WS_HANDLER[WS_RESULT]
  ): F[WebSocketResponse[WS_RESULT]] = {
    delegate.openWebsocket(request, handler) // TODO
  }

  private def sendWithCounter[T, R >: PE](request: Request[T, R], redirects: Int): F[Response[T]] = {
    // if there are nested follow redirect backends, disabling them and handling redirects here
    val resp = delegate.send(request.followRedirects(false))
    if (request.options.followRedirects) {
      responseMonad.flatMap(resp) { (response: Response[T]) =>
        if (response.isRedirect) {
          followRedirect(request, response, redirects)
        } else {
          responseMonad.unit(response)
        }
      }
    } else {
      resp
    }
  }

  private def followRedirect[T, R >: PE](
      request: Request[T, R],
      response: Response[T],
      redirects: Int
  ): F[Response[T]] = {
    response.header(HeaderNames.Location).fold(responseMonad.unit(response)) { loc =>
      if (redirects >= request.options.maxRedirects) {
        responseMonad.error(TooManyRedirectsException(request.uri, redirects))
      } else {
        followRedirect(request, response, redirects, loc)
      }
    }
  }

  private def followRedirect[T, R >: PE](
      request: Request[T, R],
      response: Response[T],
      redirects: Int,
      loc: String
  ): F[Response[T]] = {
    val uri = if (FollowRedirectsBackend.isRelative(loc)) {
      // using java's URI to resolve a relative URI
      uri"${new URI(request.uri.toString).resolve(loc).toString}"
    } else {
      uri"$loc"
    }

    val redirectResponse =
      ((stripSensitiveHeaders[T, R](_)) andThen
        (changePostPutToGet[T, R](_, response.code)) andThen
        (sendWithCounter(_, redirects + 1)))
        .apply(request.copy[Identity, T, R](uri = uri))

    responseMonad.map(redirectResponse) { rr =>
      val responseNoBody = response.copy(body = ())
      rr.copy(history = responseNoBody :: rr.history)
    }
  }

  private def stripSensitiveHeaders[T, R](request: Request[T, R]): Request[T, R] = {
    request.copy[Identity, T, R](
      headers = request.headers.filterNot(h => sensitiveHeaders.contains(h.name.toLowerCase()))
    )
  }

  private def changePostPutToGet[T, R](r: Request[T, R], statusCode: StatusCode): Request[T, R] = {
    val applicable = r.method == Method.POST || r.method == Method.PUT
    val alwaysChanged = statusCode == StatusCode.SeeOther
    val neverChanged = statusCode == StatusCode.TemporaryRedirect || statusCode == StatusCode.PermanentRedirect
    if (applicable && (r.options.redirectToGet || alwaysChanged) && !neverChanged) {
      // when transforming POST or PUT into a get, content is dropped, also filter out content-related request headers
      r.method(Method.GET, r.uri)
        .copy(
          body = NoBody,
          headers = r.headers.filterNot(header => contentHeaders.contains(header.name.toLowerCase()))
        )
    } else r
  }

  override def close(): F[Unit] = delegate.close()

  override def responseMonad: MonadError[F] = delegate.responseMonad
}

object FollowRedirectsBackend {
  private[client] val MaxRedirects = 32

  private[client] def isRelative(uri: String): Boolean = uri.trim.startsWith("/")
}

case class TooManyRedirectsException(uri: Uri, redirects: Int) extends Exception
