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

package quasar.qsu
package minimizers

import quasar.common.effect.NameGenerator
import quasar.contrib.matryoshka._
import quasar.ejson.implicits._
import quasar.fp._
import quasar.contrib.iota._
import quasar.fp.ski.κ
import quasar.qscript.{
  construction,
  Hole,
  MonadPlannerErr,
  ReduceIndex
}
import quasar.qscript.RecFreeS._
import quasar.qsu.{QScriptUniform => QSU}
import slamdata.Predef._

import matryoshka.{delayEqual, BirecursiveT, EqualT, ShowT}
import matryoshka.data.free._

import scalaz.{Equal, Free, Monad, Scalaz}, Scalaz._

// FIXME: Need to rewrite bucket references to point to the collapsed node
// FIXME: May need to reorder `ReifyBuckets` until after MAJ
sealed abstract class MergeReductions[T[_[_]]: BirecursiveT: EqualT: ShowT] extends Minimizer[T] {

  import MinimizeAutoJoins._
  import QSUGraph.Extractors._

  val qprov: QProv[T]
  type P = qprov.P

  implicit def PEqual: Equal[P]

  private lazy val B = Bucketing(qprov)
  private val func = construction.Func[T]
  private val recFunc = construction.RecFunc[T]

  def couldApplyTo(candidates: List[QSUGraph]): Boolean = {
    candidates forall {
      case QSReduce(_, _, _, _) => true
      case _ => false
    }
  }

  def extract[
      G[_]: Monad: NameGenerator: MonadPlannerErr: RevIdxM: MinStateM[T, P, ?[_]]](
      qgraph: QSUGraph): Option[(QSUGraph, (QSUGraph, FreeMap) => G[QSUGraph])] = qgraph match {

    case qgraph @ QSReduce(src, buckets, reducers, repair) =>
      def rebuild(src: QSUGraph, fm: FreeMap): G[QSUGraph] = {
        def rewriteBucket(bucket: Access[Hole]): FreeMapA[Access[Hole]] = bucket match {
          case v @ Access.Value(_) => fm.map(κ(v))
          case other => Free.pure[MapFunc, Access[Hole]](other)
        }

        val buckets2 = buckets.map(_.flatMap(rewriteBucket))
        val reducers2 = reducers.map(_.map(_.flatMap(κ(fm))))

        updateGraph[T, G](qprov, QSU.QSReduce(src.root, buckets2, reducers2, repair)) map { rewritten =>
          qgraph.overwriteAtRoot(rewritten.vertices(rewritten.root)) :++ rewritten :++ src
        }
      }

      Some((src, rebuild _))
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def apply[
      G[_]: Monad: NameGenerator: MonadPlannerErr: RevIdxM: MinStateM[T, P, ?[_]]](
      qgraph: QSUGraph,
      source: QSUGraph,
      candidates: List[QSUGraph],
      fm: FreeMapA[Int]): G[Option[(QSUGraph, QSUGraph)]] = {

    val reducerAttempt = candidates collect {
      case g @ QSReduce(source, buckets, reducers, repair) =>
        (g.root, source, buckets, reducers, repair)
    }

    val (_, _, buckets, _, _) = reducerAttempt.head

    val sourceCheck = reducerAttempt filter {
      case (_, cSrc, cBuckets, _, _) =>
        cSrc.root === source.root && cBuckets === buckets
    }

    if (sourceCheck.lengthCompare(candidates.length) === 0) {
      val lifted = reducerAttempt.zipWithIndex map {
        case ((root, _, buckets, reducers, repair), i) =>
          (
            root,
            buckets,
            reducers,
            // we use maps here to avoid definedness issues in reduction results
            func.MakeMapS(i.toString, repair))
      }

      // this is fine, because we can't be here if candidates is empty
      // doing it this way avoids an extra (and useless) Option state in the fold
      val (lhroot, _, lhreducers, lhrepair) = lifted.head

      // squish all the reducers down into lhead
      val (_, (roots, reducers, repair)) =
        lifted.tail.foldLeft((lhreducers.length, (Set(lhroot), lhreducers, lhrepair))) {
          case ((roffset, (lroots, lreducers, lrepair)), (rroot, _, rreducers, rrepair)) =>
            val reducers = lreducers ::: rreducers

            val roffset2 = roffset + rreducers.length

            val repair = func.ConcatMaps(
              lrepair,
              rrepair map {
                case ReduceIndex(e) =>
                  ReduceIndex(e.rightMap(_ + roffset))
              })

            (roffset2, (lroots + rroot, reducers, repair))
        }

      // 107.7, All chiropractors, all the time
      val adjustedFM = fm.asRec flatMap { i =>
        // get the value back OUT of the map
        recFunc.ProjectKeyS(recFunc.Hole, i.toString)
      }

      val redPat = QSU.QSReduce[T, Symbol](source.root, buckets, reducers, repair)

      for {
        rewritten <- updateGraph[T, G](qprov, redPat)

        QAuth(dims, gkeys) <- MinStateM[T, P, G].gets(_.auth)

        //foo = println(s"ROOTS: $roots")

        dims2 = dims mapValues { p =>
          roots.foldLeft(p)((p, frm) => B.rename(frm, rewritten.root, p))
        }

        //bar = println(s"RENAMED DIMS: $dims2")

        _ <- MinStateM[T, P, G].put(MinimizationState(QAuth(dims2, gkeys)))

        back = qgraph.overwriteAtRoot(QSU.Map[T, Symbol](rewritten.root, adjustedFM)) :++ rewritten
      } yield Some((rewritten, back))
    } else {
      (None: Option[(QSUGraph, QSUGraph)]).point[G]
    }
  }
}

object MergeReductions {
  def apply[T[_[_]]: BirecursiveT: EqualT: ShowT](
      qp: QProv[T])(implicit P: Equal[qp.P])
      : Minimizer.Aux[T, qp.P] =
    new MergeReductions[T] {
      val qprov: qp.type = qp
      val PEqual = P
    }
}
