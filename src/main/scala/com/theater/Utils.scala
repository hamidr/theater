package com.theater

import cats.effect.IO

extension (cond: Boolean) {
  inline def foldM[A](onFalse: IO[A])(task: IO[A]): IO[A] =
    if cond then task
    else onFalse
}