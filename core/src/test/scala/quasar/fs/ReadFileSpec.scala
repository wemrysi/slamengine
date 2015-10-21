package quasar
package fs

import quasar.Predef._
import quasar.fp._

import scalaz._
import scalaz.std.vector._
import pathy.Path._

class ReadFileSpec extends FileSystemSpec {
  import DataGen._, PathyGen._

  "ReadFile" should {
    "scan should read data until an empty vector is received" ! prop {
      (f: RelFile[Sandboxed], xs: Vector[Data]) => xs.nonEmpty ==> {
        val p = write.appendF(f, xs).drain ++ read.scanAll(f)

        evalLogZero(p).run.toEither must beRight(xs)
      }
    }

    "scan should automatically close the read handle when terminated early" ! prop {
      (f: RelFile[Sandboxed], xs: Vector[Data]) => xs.nonEmpty ==> {
        val n = xs.length / 2
        val p = write.appendF(f, xs).drain ++ read.scanAll(f).take(n)

        p.translate[M](runT).runLog
          .run.leftMap(_.rm)
          .run(emptyState)
          .run must_== ((Map.empty, \/.right(xs take n)))
      }
    }

    "scan should automatically close the read handle on failure" >> todo
  }
}
