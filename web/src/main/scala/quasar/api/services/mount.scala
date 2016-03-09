/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.api.services

import quasar.Predef._
import quasar.api._
import quasar.fp._
import quasar.fs.{AbsPath, APath, PathError2, sandboxAbs}
import quasar.fs.mount._

import argonaut._
import org.http4s._, Method.MOVE
import org.http4s.headers.Accept
import org.http4s.dsl.{Path => HPath, _}
import pathy.Path, Path._
import scalaz._, Scalaz._
import scalaz.concurrent.Task

object mount {
  import Mounting.PathTypeMismatch
  import ToQResponse.ops._
  import posixCodec._

  def service[S[_]: Functor](implicit M: Mounting.Ops[S], S0: Task :<: S): QHttpService[S] =
    QHttpService {
      case GET -> AsPath(path) =>
        def err = s"There is no mount point at ${printPath(path)}"
        respond(M.lookup(path).toRight(QResponse.error[S](NotFound, err)).run)

      case req @ MOVE -> AsPath(src) =>
        requiredHeader(Destination, req).map(_.value).fold(
          (_: QResponse[S]).point[Free[S, ?]],
          dst => refineType(src).fold(
            srcDir  => move(srcDir,  dst, parseAbsDir,  "directory"),
            srcFile => move(srcFile, dst, parseAbsFile, "file")))

      case req @ POST -> AsDirPath(parent) => respond((for {
        hdr <- EitherT.fromDisjunction[M.F](requiredHeader[S](XFileName, req))
        fn  =  hdr.value
        dst <- EitherT.fromDisjunction[M.F](
                (parseRelDir(fn) orElse parseRelFile(fn))
                  .flatMap(sandbox(currentDir, _))
                  .map(parent </> _)
                  .toRightDisjunction(QResponse.error[S](BadRequest, s"Not a relative path: $fn")))
        _   <- mount[S](dst, req, replaceIfExists = false)
      } yield s"added ${printPath(dst)}").run)

      case req @ PUT -> AsPath(path) =>
        respond(
          mount[S](path, req, replaceIfExists = true)
            .map(upd => s"${upd ? "updated" | "added"} ${printPath(path)}")
            .run)

      case DELETE -> AsPath(p) =>
        respond(M.unmount(p).as(s"deleted ${printPath(p)}").run)
    }

  ////

  private def move[S[_]: Functor, F[_], T](
    src: AbsPath[T],
    dstStr: String,
    parse: String => Option[Path[Abs, T, Unsandboxed]],
    typeStr: String
  )(implicit
    M: Mounting.Ops[S]
  ): Free[S, QResponse[F]] =
    parse(dstStr).map(sandboxAbs).cata(dst =>
      respond(M.remount[T](src, dst).as(s"moved ${printPath(src)} to $dstStr").run),
      QResponse.error(BadRequest, s"Not an absolute $typeStr path: $dstStr").point[M.F])

  private def mount[S[_]: Functor](
    path: APath,
    req: Request,
    replaceIfExists: Boolean
  )(implicit
    M: Mounting.Ops[S],
    S0: Task :<: S
  ): EitherT[Free[S, ?], QResponse[S], Boolean] = {
    type FreeS[A] = Free[S, A]

    for {
      body  <- EitherT.right(injectFT[Task, S].apply(EntityDecoder.decodeString(req)): FreeS[String])
      bConf <- EitherT.fromDisjunction[FreeS](Parse.decodeWith(
                  body,
                  (_: MountConfig).right[QResponse[S]],
                  parseErrorMsg => QResponse.error[S](BadRequest, s"input error: $parseErrorMsg").left,
                  (msg, _) => QResponse.error[S](BadRequest, msg).left))
      exists <- EitherT.right(M.lookup(path).isDefined)
      mnt    =  if (replaceIfExists && exists) M.replace(path, bConf) else M.mount(path, bConf)
      r      <- mnt.leftMap(_.toResponse[S])
      _      <- EitherT.fromDisjunction[FreeS](r.leftMap(_.toResponse[S]))
    } yield exists
  }
}
