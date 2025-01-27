package com.theater

import cats.effect.IO
import cats.effect.std.{Dequeue, Mutex, Queue}
import fs2.*
import cats.implicits.*


enum MailBoxSettings {
  case Unbounded
  case Bounded(cap: Int)
  case DropOldIfFull(cap: Int)
  case DropNewIfFull(cap: Int)
}

trait MessageBox[T] {
  def messages: Stream[IO, T]
  def push(msg: T): IO[Unit]
}

object MessageBox {
  private enum QueueState {
    case DropNew, DropOld
  }
  import MailBoxSettings.*
  import QueueState.*

  private class MailBoxOnFullDropRecent[T](mutex: Mutex[IO], queue: Dequeue[IO, T]) extends MessageBox[T] {
    def messages: Stream[IO, T] = Stream.repeatEval(mutex.lock.use { _ => queue.take })
    def push(msg: T): IO[Unit] = mutex.lock.surround {
      queue.tryOffer(msg) >>= {
        _.foldM(queue.takeFront.void >> queue.offer(msg))(IO.unit)
      }
    }
  }

  private class MailBoxOnFullDropOldest[T](mutex: Mutex[IO], queue: Dequeue[IO, T]) extends MessageBox[T] {
    def messages: Stream[IO, T] = Stream.repeatEval(mutex.lock.use { _ => queue.take })
    def push(msg: T): IO[Unit] = mutex.lock.surround {
      queue.tryOffer(msg) >>= { _.foldM(queue.takeBack.void >> queue.offer(msg))(IO.unit) }
    }
  }

  private class QueueMailBox[T](queue: Queue[IO, T]) extends MessageBox[T] {
    def messages: Stream[IO, T] = Stream.fromQueueUnterminated(queue)
    def push(msg: T): IO[Unit] = queue.offer(msg)
  }

  def init[T](mailBoxSettings: MailBoxSettings): IO[MessageBox[T]] = mailBoxSettings match {
    case Unbounded => Queue.unbounded[IO, T].map(queue => QueueMailBox[T](queue))
    case Bounded(n) if n > 0 => Queue.bounded[IO, T](n).map(queue => QueueMailBox[T](queue))
    case DropOldIfFull(n) if n > 0 => lockedMailBox(n, DropOld)
    case DropNewIfFull(n) if n > 0 => lockedMailBox(n, DropNew)
    case _ => IO.raiseError(new Exception("Defined size for queue is unacceptable"))
  }

  private def lockedMailBox[T](n: Int, state: QueueState): IO[MessageBox[T]] = Mutex[IO] >>= { lock =>
    Dequeue.bounded[IO, T](n).map(queue =>
      state match {
        case DropOld => MailBoxOnFullDropOldest[T](lock, queue)
        case DropNew => MailBoxOnFullDropRecent[T](lock, queue)
      }
    )
  }
}

