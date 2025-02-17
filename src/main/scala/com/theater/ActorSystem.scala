package com.theater

import cats.effect.{IO, Ref}
import com.theater.MailBoxSettings.Unbounded
import fs2.*
import fs2.concurrent.SignallingRef

import java.util.concurrent.*
import scala.concurrent.ExecutionContext

trait System:
  def execute(process: Process): IO[Unit]
  def waitOnStop: IO[Unit]
  def shutdown(): IO[Unit]
end System

class ActorSystem(
  shutdownSignal: SignallingRef[IO, Boolean],
  waitSignal: SignallingRef[IO, Boolean]
)(using ec: ExecutionContext) extends System:
  private val rootCtx: ActorContext[Nothing] =
    Context[Nothing](this, RootActor)

  private val processCount =
    Ref.unsafe[IO, Int](0)

  def spawn[A](behavior: Behavior[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]] =
    rootCtx.spawnAnonymously(behavior, name, mailSettings)

  private def increment(): IO[Unit] =
    processCount.update(_ + 1)

  private def decrement(): IO[Unit] =
    processCount.flatModify:
      case 1 => (0, waitSignal.set(true))
      case n => (n - 1, IO.unit)

  override def execute(process: Process): IO[Unit] =
    increment() >>
      process.interruptWhen(shutdownSignal)
        .onFinalize(decrement())
        .compile.drain
        .startOn(ec)
        .void
  end execute

  override def shutdown(): IO[Unit] =
   shutdownSignal.set(true) >> waitOnStop

  override def waitOnStop: IO[Unit] =
    Stream.never[IO].interruptWhen(waitSignal).compile.drain

end ActorSystem

object ActorSystem:
  private given executionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

  def init: IO[ActorSystem] = for
    shutdownSignal <- SignallingRef.of[IO, Boolean](false)
    waitSignal     <- SignallingRef.of[IO, Boolean](false)
  yield ActorSystem(shutdownSignal, waitSignal)

  def run[T](starter: Behavior[T]): IO[Unit] =
    for
      sys <- ActorSystem.init
      _   <- sys.spawn(starter, "init")
      _   <- sys.waitOnStop
    yield ()
  end run
end ActorSystem