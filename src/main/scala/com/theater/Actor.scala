package com.theater

import cats.effect.std.UUIDGen
import cats.effect.{IO, Ref}
import cats.implicits.*
import com.theater.ActorState.Terminated
import fs2.*
import fs2.concurrent.{Channel, SignallingRef}

import java.util.UUID

sealed trait ActorRef[T] {
  def id: UUID
  def name: String
  def send(msg: T): IO[Unit]
  def stop(): IO[Unit]
  def events: Stream[IO, ActorState.Terminated]
  def notify(ter: ActorState.Terminated): IO[Unit]
  def cascadeStop: Stream[IO, Nothing]
}

final case class DeadLetter[T](dead: ActorRef[T]) extends Exception("Dead actor found")
final case class StopThenEscalate(ex: Throwable) extends Exception(ex)
case object StopFlow extends Exception()

enum ActorState {
  case PreStart
  case PostStop
  case PreRestart
  case Terminated(ref: ActorRef[?], err: Throwable)
}

object RootActor extends ActorRef[Nothing] {
  override val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
  override val name: String = "RootActor"
  override def send(msg: Nothing): IO[Unit] = IO.unit
  override def notify(ter: ActorState.Terminated): IO[Unit] = IO.unit
  override def cascadeStop: Stream[IO, Nothing] = Stream.empty
  override val events: Stream[IO, Terminated] = Stream.empty
  override def stop(): IO[Unit] = IO.unit
}

type Process = Stream[IO, Nothing]

final class Actor[T](
  val id: UUID,
  val name: String,
  mailBox: MessageBox[T],
  stopCommand: SignallingRef[IO, Boolean],
  deathSignal: Channel[IO, Terminated],
  deathReport: Channel[IO, Terminated],
  setup: BehaviorSetup[T]
) extends ActorRef[T] {
  import ActorState.*

  private val isDead: Ref[IO, Boolean] =
    Ref.unsafe(false)

  private val refBehavior: Ref[IO, Behavior[T]] =
    Ref.unsafe(Pass[T])

  private val closed: IO[Unit] = {
    deathReport.close.void
      >> deathSignal.close.void
      >> isDead.set(true)
  }

  private def initBehavior(ctx: ActorContext[T]): IO[Unit] =
    setup.eval(ctx).flatMap(setBehavior)

  private def currentBehavior: IO[Behavior[T]] =
    refBehavior.get

  private def setBehavior(next: Behavior[T]): IO[Unit] =
    refBehavior.set(next)

  private val processDeathEvents: Stream[IO, Nothing] =
    deathReport.stream.evalTap(onSignal).drain

  private def onSignal: ActorState => IO[Unit] = { state =>
    currentBehavior >>= (_.signal(state))
  }

  private def raiseSignal(newState: ActorState): IO[Unit] = {
    val observeAct = newState match {
      case ter: Terminated => deathSignal.send(ter).void
      case PreRestart | PreStart | PostStop => IO.unit
    }

    observeAct >> onSignal(newState)
  }

  override def events: Stream[IO, ActorState.Terminated] =
    deathSignal.stream

  override def notify(ter: ActorState.Terminated): IO[Unit] =
    deathReport.send(ter).void

  override def send(msg: T): IO[Unit] = isDead.get.flatMap {
    case false => mailBox.push(msg)
    case true  => IO.raiseError(DeadLetter(this))
  }

  def cascadeStop: Process =
    Stream.raiseError(StopFlow).pauseWhen(stopCommand)

  def stop(): IO[Unit] =
    stopCommand.set(false)

  def process(parentEvent: Process, ctx: ActorContext[T]): Process = {
    Stream
      .eval(initBehavior(ctx))
      .evalTap(_ => raiseSignal(PreStart))
      >> parentEvent.mergeHaltR(cascadeStop.mergeHaltR(mailBox.messages))
      .evalMap { msg =>
        for {
          curr <- currentBehavior
          next <- IO.defer(curr.receive(ctx, msg))
            .handleErrorWith(curr.handleError)
          _ <- next match {
            case _: Same[T] => IO.unit
            case _: Restart[T] => raiseSignal(PreRestart) >> initBehavior(ctx)
            case _: Stop[T] => IO.raiseError(StopFlow)
            case _ => (curr != next).foldM(IO.unit)(setBehavior(next))
          }
        } yield ()
      }
      .attempt
      .evalTap { // do not mess with the flow
        case Left(StopFlow) => raiseSignal(PostStop)
        case Left(err)      => raiseSignal(Terminated(this, err))
        case Right(_)       => IO.unit
      }
      .takeWhile(_.isRight)
      .drain
      .mergeHaltL(processDeathEvents)
      .onFinalize(closed)
  }
}

object Actor {
  def init[T](name: String, mailBox: MessageBox[T], setup: BehaviorSetup[T]): IO[Actor[T]] = {
    for {
      id <- UUIDGen[IO].randomUUID
      deathSignal <- Channel.bounded[IO, Terminated](1)
      deathReport <- Channel.bounded[IO, Terminated](1)
      stopCommand <- SignallingRef.of[IO, Boolean](true)
    } yield Actor[T](id, name, mailBox, stopCommand, deathSignal, deathReport, setup)
  }
}

