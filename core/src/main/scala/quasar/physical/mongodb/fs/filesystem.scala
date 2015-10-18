package quasar
package physical
package mongodb
package fs

import quasar.Predef._
import quasar.fs._

import com.mongodb.MongoServerException
import com.mongodb.async.client.MongoClient
import scalaz._, Scalaz._
import scalaz.stream._
import scalaz.concurrent.Task
import pathy.Path._

object filesystem {
  import FileSystem._, PathError2._, MongoDb._

  type GenState          = (String, Long)
  type FsStateT[F[_], A] = StateT[F, GenState, A]
  type FsMongo[A]        = FsStateT[MongoDb, A]

  /** TODO: There are still some questions regarding Path
    *   1) Should we be requiring Rel or Abs paths? Abs seems
    *      like it might be a better fit as "/" refers to the
    *      root of this filesystem, they also have less ambiguity
    *      w.r.t. the relative "operators", '.' and '..', i.e.
    *      "./foo/../../bar/" is a valid relative path that refers
    *      to the parent of the current directory, this shouldn't be
    *      allowed in an absolute path.
    *
    *   2) We should assume all paths will be canonicalized and can do so
    *      with a FileSystem ~> FileSystem that canonicalizes everything.
    *
    *   3) Currently, parsing a directory like "/../foo/bar/" as an absolute
    *      dir succeeds, this should probably be changed to fail.
    */

  /** Interpret [[FileSystem]] into [[FsMongo]], given the name of a database
    * to use as a default location for temp collections when no other database
    * can be deduced.
    */
  def interpret(defaultDb: String): FileSystem ~> FsMongo = new (FileSystem ~> FsMongo) {
    def apply[A](fs: FileSystem[A]) = fs match {
      case Move(scenario, semantics) =>
        scenario.fold(moveDir(_, _, semantics), moveFile(_, _, semantics))
          .run.liftM[FsStateT]

      case Delete(path) =>
        path.fold(deleteDir, deleteFile)
          .run.liftM[FsStateT]

      case ListContents(dir) =>
        (dirName(dir) match {
          case Some(_) =>
            collectionsInDir(dir)
              .map(_.flatMap(_.asFile.map(Node.File)).toSet)
              .run

          case None if depth(dir) == 0 =>
            collections
              .map(_.asFile map (Node.File))
              .pipe(process1.stripNone)
              .runLog
              .map(_.toSet.right[PathError2])

          case None =>
            nonExistentParent[Set[Node]](dir).run
        }).liftM[FsStateT]

      case TempFile(maybeNear) =>
        val dbName = maybeNear
          .flatMap(f => Collection.fromFile(f).toOption)
          .cata(_.databaseName, defaultDb)

        freshName map (n => dir(dbName) </> file(n))
    }
  }

  def run(client: MongoClient): FsMongo ~> Task =
    new (FsMongo ~> Task) {
      def apply[A](fs: FsMongo[A]) =
        Task.delay(scala.util.Random.nextInt().toHexString)
          .map(s => s"__quasar.tmp_${s}_")
          .flatMap(p => fs.eval((p, 0)).run(client))
    }

  ////

  private type M[A] = PathErr2T[MongoDb, A]
  private type G[E, A] = EitherT[MongoDb, E, A]

  private val prefixL: GenState @> String =
    Lens.firstLens

  private val seqL: GenState @> Long =
    Lens.secondLens

  private def moveToRename(sem: MoveSemantics): RenameSemantics = {
    import RenameSemantics._
    sem.fold(Overwrite, FailIfExists, Overwrite)
  }

  private def moveDir(src: RelDir[Sandboxed], dst: RelDir[Sandboxed], sem: MoveSemantics)
                     : M[Unit] = {
    for {
      colls    <- collectionsInDir(src)
      srcFiles <- if (colls.nonEmpty) colls.flatMap(_.asFile).point[M]
                  else MonadError[G, PathError2].raiseError(DirNotFound(src))
      dstFiles =  srcFiles flatMap (_ relativeTo (src) map (dst </> _))
      _        <- srcFiles zip dstFiles traverseU {
                    case (s, d) => moveFile(s, d, sem)
                  }
    } yield ()
  }

  private def moveFile(src: RelFile[Sandboxed], dst: RelFile[Sandboxed], sem: MoveSemantics)
                      : M[Unit] = {
    /** Error codes obtained from MongoDB `renameCollection` docs:
      * See http://docs.mongodb.org/manual/reference/command/renameCollection/
      */
    def reifyMongoErr(m: MongoDb[Unit]): M[Unit] =
      EitherT(m.attempt flatMap {
        case -\/(e: MongoServerException) if e.getCode == 10026 =>
          FileNotFound(src).left.point[MongoDb]

        case -\/(e: MongoServerException) if e.getCode == 10027 =>
          FileExists(dst).left.point[MongoDb]

        case -\/(t) =>
          fail(t)

        case \/-(_) =>
          ().right.point[MongoDb]
      })

    def ensureDstExists(dstColl: Collection): M[Unit] =
      EitherT(collectionsIn(dstColl.databaseName)
                .filter(_ == dstColl)
                .runLast
                .map(_.toRightDisjunction(FileNotFound(dst)).void))

    for {
      srcColl <- collFromFileM(src)
      dstColl <- collFromFileM(dst)
      rSem    =  moveToRename(sem)
      _       <- sem.fold(().point[M], ().point[M], ensureDstExists(dstColl))
      _       <- reifyMongoErr(rename(srcColl, dstColl, rSem))
    } yield ()
  }

  // TODO: Really need a Path#fold[A] method, which will be much more reliable
  //       than this process of deduction.
  private def deleteDir(dir: RelDir[Sandboxed]): M[Unit] =
    dirName(dir) match {
      case Some(n) if depth(dir) == 1 =>
        dropDatabase(n.value).liftM[PathErr2T]

      case Some(_) =>
        collectionsInDir(dir)
          .flatMap(_.traverseU_(c => dropCollection(c).liftM[PathErr2T]))

      case None if depth(dir) == 0 =>
        dropAllDatabases.liftM[PathErr2T]

      case None =>
        nonExistentParent(dir)
    }

  private def deleteFile(file: RelFile[Sandboxed]): M[Unit] =
    collFromFileM(file) flatMap (c => dropCollection(c).liftM[PathErr2T])

  private def collectionsInDir(dir: RelDir[Sandboxed]): M[Vector[Collection]] =
    collFromDirM(dir) flatMap (c =>
      collectionsIn(c.databaseName)
        .filter(_.collectionName startsWith c.collectionName)
        .runLog.map(_.toVector).liftM[PathErr2T])

  private def collFromDirM(dir: RelDir[Sandboxed]): M[Collection] =
    EitherT(Collection.fromDir(dir).point[MongoDb])

  private def collFromFileM(file: RelFile[Sandboxed]): M[Collection] =
    EitherT(Collection.fromFile(file).point[MongoDb])

  // TODO: This would be eliminated if we switched to AbsDir everywhere.
  private def nonExistentParent[A](dir: RelDir[Sandboxed]): M[A] =
    InvalidPath(dir.left, "directory refers to nonexistent parent")
      .raiseError[G, A]

  private def freshName: FsMongo[String] =
    (prefixL.st |@| seqL.modo(_ + 1))(_ + _).lift[MongoDb]
}
