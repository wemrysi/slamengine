package slamdata.engine

import scalaz.concurrent._
import scalaz.stream._

import scodec.bits.ByteVector

import argonaut._
import Argonaut._

import unfiltered.response._

package object api {
  def ResponseProcess[A](p: Process[Task, A])(f: A => ByteVector): ResponseStreamer = new ResponseStreamer {
    def stream(os: java.io.OutputStream): Unit = {
      val rez = p.map(f) to io.chunkW(os)

      rez.run.run

      ()
    }
  }

  def ResponseJson(json: Json): ResponseStreamer = new ResponseStreamer {
    def stream(os: java.io.OutputStream): Unit = {
      new java.io.DataOutputStream(os).write(json.toString.getBytes("UTF-8"))

      ()
    }
  }
}