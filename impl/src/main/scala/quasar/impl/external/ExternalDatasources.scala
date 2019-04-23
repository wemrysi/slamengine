/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.impl.external

import slamdata.Predef.{Stream => _, _}
import quasar.concurrent.BlockingContext
import quasar.connector.{HeavyweightDatasourceModule, LightweightDatasourceModule}
import quasar.fp.ski.κ
import quasar.impl.DatasourceModule

import java.lang.{
  Class,
  ClassCastException,
  ClassLoader,
  ExceptionInInitializerError,
  IllegalAccessException,
  IllegalArgumentException,
  NoSuchFieldException,
  NullPointerException
}

import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.syntax.applicativeError._
import fs2.Stream

object ExternalDatasources extends StreamLogging {
  def apply[F[_]: ContextShift: Timer](
      config: ExternalConfig,
      blockingPool: BlockingContext)(
      implicit F: ConcurrentEffect[F])
      : Stream[F, List[DatasourceModule]] = {
    val datasourceModuleStream: Stream[F, DatasourceModule] =
      ExternalModules(config, blockingPool).flatMap((loadDatasourceModule[F](_, _)).tupled)

    for {
      ds <- datasourceModuleStream.fold(List.empty[DatasourceModule])((m, d) => d :: m)
      _ <- infoStream[F](s"Loaded ${ds.length} datasource(s)")
    } yield ds
  }

  ////

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def loadDatasourceModule[F[_]: Sync](
    className: String, classLoader: ClassLoader): Stream[F, DatasourceModule] = {
    def handleFailedDatasource[A](s: Stream[F, A]): Stream[F, A] =
      s recoverWith {
        case e @ (_: NoSuchFieldException | _: IllegalAccessException | _: IllegalArgumentException | _: NullPointerException) =>
          warnStream[F](s"Datasource module '$className' does not appear to be a singleton object", Some(e))

        case e: ExceptionInInitializerError =>
          warnStream[F](s"Datasource module '$className' failed to load with exception", Some(e))

        case _: ClassCastException =>
          warnStream[F](s"Datasource module '$className' is not actually a subtype of LightweightDatasourceModule or HeavyweightDatasourceModule", None)
      }

    def loadLightweight(clazz: Class[_]): Stream[F, DatasourceModule] =
      ExternalModules.loadModule(clazz) { o =>
        DatasourceModule.Lightweight(o.asInstanceOf[LightweightDatasourceModule])
      }

    def loadHeavyweight(clazz: Class[_]): Stream[F, DatasourceModule] =
      ExternalModules.loadModule(clazz) { o =>
        DatasourceModule.Heavyweight(o.asInstanceOf[HeavyweightDatasourceModule])
      }

    for {
      clazz <- ExternalModules.loadClass(className, classLoader)
      datasource <- handleFailedDatasource(loadLightweight(clazz) handleErrorWith κ(loadHeavyweight(clazz)))
    } yield datasource
  }
}
