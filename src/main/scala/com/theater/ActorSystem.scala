package com.theater

import cats.effect.{IO, Ref}
import com.theater.MailBoxSettings.Unbounded
import fs2.*
import fs2.concurrent.SignallingRef

trait System {
  def execute(process: Process): IO[Unit]
}

class ActorSystem(
  shutdownSignal: SignallingRef[IO, Boolean],
  waitSignal: SignallingRef[IO, Boolean]
) extends System {

  private val rootCtx: ActorContext[Nothing] = Context[Nothing](this, RootActor)

  private val processCount = Ref.unsafe[IO, Int](0)

  def spawn[A](behavior: BehaviorSetup[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]] =
    rootCtx.spawnAnonymously(behavior, name, mailSettings)

  def increment(): IO[Unit] = processCount.update(_ + 1)

  def decrement(): IO[Unit] = processCount.flatModify {
    case 1 => (0, waitSignal.set(true))
    case n => (n - 1, IO.unit)
  }

  override def execute(process: Process): IO[Unit] = {
    increment() >> process.interruptWhen(shutdownSignal).onFinalize(decrement()).compile.drain.start.void
  }

  def shutdown(): IO[Unit] = shutdownSignal.set(true) >> waitOnExec

  def waitOnExec: IO[Unit] = Stream.never[IO].interruptWhen(waitSignal).compile.drain

  def run(f: ActorContext[Nothing] => IO[Unit]): IO[Unit] = f(rootCtx)
}

object ActorSystem {
  def init: IO[ActorSystem] = {
    for {
      shutdownSignal <- SignallingRef.of[IO, Boolean](false)
      waitSignal <- SignallingRef.of[IO, Boolean](false)
    } yield ActorSystem(shutdownSignal, waitSignal)
  }

  def execAndWait(f: ActorContext[Nothing] => IO[Unit]): IO[Unit] = {
    for {
      system <- init
      _ <- system.run(f(_))
      _ <- system.waitOnExec
    } yield ()
  }

  def startFlow(starter: BehaviorSetup[Unit]): IO[Unit] = execAndWait { ctx =>
    ctx.spawnAnonymously(starter, "Starter").flatMap(_.send(()))
  }
}