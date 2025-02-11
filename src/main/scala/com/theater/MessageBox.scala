package com.theater

import cats.effect.IO
import cats.effect.std.{Dequeue, Mutex, Queue}
import fs2.*
import cats.implicits.*

enum MailBoxSettings:
  case Unbounded
  case Bounded(cap: Int)
  case DropOldIfFull(cap: Int)
  case DropNewIfFull(cap: Int)
end MailBoxSettings

trait MessageBox[T]:
  def messages: Stream[IO, T]
  def push(msg: T): IO[Unit]

private enum QueueState:
  case DropNew, DropOld

private class MailBoxOnFullDropRecent[T](
  mutex: Mutex[IO],
  queue: Dequeue[IO, T]
) extends MessageBox[T]:
  def messages: Stream[IO, T] =
    Stream.repeatEval:
      mutex.lock.use { _ => queue.take }

  def push(msg: T): IO[Unit] =
    mutex.lock.surround:
      queue.tryOffer(msg)
        .flatMap:
          _.foldM(queue.takeFront.void >> queue.offer(msg))(IO.unit)
  end push
end MailBoxOnFullDropRecent

private class MailBoxOnFullDropOldest[T](mutex: Mutex[IO], queue: Dequeue[IO, T]) extends MessageBox[T]:
  def messages: Stream[IO, T] =
    Stream.repeatEval:
      mutex.lock.use { _ => queue.take }

  def push(msg: T): IO[Unit] = mutex.lock.surround:
    queue.tryOffer(msg).flatMap:
      _.foldM(queue.takeBack.void >> queue.offer(msg))(IO.unit)
  end push
end MailBoxOnFullDropOldest

private class QueueMailBox[T](queue: Queue[IO, T]) extends MessageBox[T]:
  def messages: Stream[IO, T] = Stream.repeatEval(queue.take)
  def push(msg: T): IO[Unit]  = queue.offer(msg)
end QueueMailBox

object IllegalStateMailbox extends Exception("Defined size for queue is unacceptable")

object MessageBox:
  import MailBoxSettings.*
  import QueueState.*

  def init[T](mailBoxSettings: MailBoxSettings): IO[MessageBox[T]] =
    mailBoxSettings match
      case Unbounded => Queue.unbounded[IO, T].map(queue => QueueMailBox[T](queue))
      case Bounded(n) if n > 0 => Queue.bounded[IO, T](n).map(queue => QueueMailBox[T](queue))
      case DropOldIfFull(n) if n > 0 => lockedMailBox(n, DropOld)
      case DropNewIfFull(n) if n > 0 => lockedMailBox(n, DropNew)
      case _ => IO.raiseError(IllegalStateMailbox)
  end init

  private def lockedMailBox[T](n: Int, state: QueueState): IO[MessageBox[T]] =
    Mutex[IO].flatMap: lock =>
      Dequeue
        .bounded[IO, T](n)
        .map: queue =>
          state match
            case DropOld => MailBoxOnFullDropOldest[T](lock, queue)
            case DropNew => MailBoxOnFullDropRecent[T](lock, queue)
  end lockedMailBox
end MessageBox


