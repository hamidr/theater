package com.theater

import cats.effect.IO
import fs2.*

extension (cond: Boolean)
  inline def foldM[A](onFalse: IO[A])(task: IO[A]): IO[A] =
    if cond then task
    else onFalse
end extension

extension [T](seq: Seq[T])
  def evalTap(f: T => IO[Unit]): IO[Unit] =
    Stream.emits(seq).covary[IO].evalTap(f).compile.drain

  def evalMap[R](f: T => IO[R]): IO[Vector[R]] =
    Stream.emits(seq).covary[IO].evalMap(f).compile.toVector
end extension