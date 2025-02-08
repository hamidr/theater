package com.theater

import cats.effect.std.UUIDGen
import cats.effect.{IO, Ref}
import cats.implicits.*
import com.theater
import com.theater.ActorState.Terminated
import fs2.*
import fs2.concurrent.{Channel, SignallingRef}

import java.util.UUID
import scala.concurrent.TimeoutException

trait ActorRef[T]:
  def id: UUID
  def name: String
  def send(msg: T): IO[Unit]
  def stop(): IO[Unit]
  def events: Stream[IO, ActorState.Terminated]
end ActorRef

private sealed trait ActorEvents:
  def stopEvent: Stream[IO, Nothing]
  def notify(ter: ActorState.Terminated): IO[Unit]
end ActorEvents

sealed trait ActorImpl[T]
  extends ActorRef[T] with ActorEvents

final case class DeadLetter[T](dead: ActorRef[T]) extends Exception("Dead actor found")
private case object StopFlow extends Exception()

enum ActorState:
  case PreStart
  case PostStop
  case PreRestart
  case Terminated(ref: ActorRef[?], err: Throwable)
end ActorState

object RootActor extends ActorImpl[Nothing]:
  override val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
  override val name: String = "RootActor"
  override def send(msg: Nothing): IO[Unit] = IO.unit
  override def notify(ter: ActorState.Terminated): IO[Unit] = IO.unit
  override def stopEvent: Stream[IO, Nothing] = Stream.empty
  override val events: Stream[IO, Terminated] = Stream.empty
  override def stop(): IO[Unit] = IO.unit
end RootActor

type Process = Stream[IO, Nothing]

final class Actor[T](
  val id: UUID,
  val name: String,
  mailBox: MessageBox[T],
  stopCommand: SignallingRef[IO, Boolean],
  deathSignal: Channel[IO, Terminated],
  deathReport: Channel[IO, Terminated],
  setup: BehaviorSetup[T]
) extends ActorImpl[T]:
  import ActorState.*

  private val isDead: Ref[IO, Boolean] =
    Ref.unsafe(false)

  private val refBehavior: Ref[IO, Behavior[T]] =
    Ref.unsafe(Pass[T])

  private val closed: IO[Unit] =
    deathReport.close.void
      >> deathSignal.close.void
      >> isDead.set(true)

  private def initBehavior(ctx: ActorContext[T]): IO[Behavior[T]] =
    setup.eval(ctx).flatMap(setBehavior)

  private def currentBehavior: IO[Behavior[T]] =
    refBehavior.get

  private def setBehavior(next: Behavior[T]): IO[Behavior[T]] =
    refBehavior.set(next).as(next)

  private val processDeathEvents: Stream[IO, Nothing] =
    deathReport.stream.evalTap(onSignal).drain

  private def onSignal: ActorState => IO[Unit] = state =>
    currentBehavior >>= (_.signal(state))

  private def raiseSignal(newState: ActorState): Behavior[T] => IO[Unit] = behavior =>
    val observeAct = newState match
      case ter: Terminated => deathSignal.send(ter).void
      case PreRestart | PreStart | PostStop => IO.unit

    observeAct >> behavior.signal(newState)
  end raiseSignal

  override def events: Stream[IO, ActorState.Terminated] =
    deathSignal.stream

  override def notify(ter: ActorState.Terminated): IO[Unit] =
    deathReport.send(ter).void

  override def send(msg: T): IO[Unit] =
    isDead.get.flatMap:
      case false => mailBox.push(msg)
      case true  => IO.raiseError(DeadLetter(this))
  end send

  def stopEvent: Process =
    Stream.raiseError(StopFlow)
      .pauseWhen(stopCommand)

  def stop(): IO[Unit] =
    stopCommand.set(false)

  private def withTimeout(behavior: Behavior[T]): Pipe[IO, T, T] = s =>
    behavior.timeout
      .fold(s): t =>
        Stream.never[IO]
          .timeout(t.timeout)
          .handleErrorWith:
            case _ :TimeoutException => Stream.emit(t.onTimeout)
            case ow => Stream.raiseError[IO](ow)
          .mergeHaltR(s)
  end withTimeout

  private def flow(ctx: ActorContext[T]): Pipe[IO, T, Nothing] = src =>
    val behaviors = Stream
      .eval(initBehavior(ctx))
      .evalTap(raiseSignal(PreStart))
      ++ Stream.repeatEval(currentBehavior)

    behaviors
      .flatMap: behavior =>
        src
          .through(withTimeout(behavior))
          .evalMap: msg =>
            IO.defer(behavior.receive(ctx, msg))
              .handleErrorWith(behavior.handleError)
          .evalMap:
            case _: Same[T]    => IO.pure(true)
            case _: Restart[T] => raiseSignal(PreRestart)(behavior) >> initBehavior(ctx).as(false)
            case _: Stop[T]    => IO.raiseError(StopFlow)
            case state         => (behavior != state).foldM(IO.pure(true))(setBehavior(state).as(false))
          .takeWhile(state => state)
          .drain
  end flow

  def process(caseCadeSignal: Process, ctx: ActorContext[T]): Process =
    mailBox.messages
      .through(flow(ctx))
      .concurrently(caseCadeSignal)
      .concurrently(stopEvent)
      .attempt
      .evalTap:
        case Left(StopFlow) => currentBehavior >>= raiseSignal(PostStop)
        case Left(err)      => currentBehavior >>= raiseSignal(Terminated(this, err))
        case Right(_)       => IO.unit
      .takeWhile(_.isRight)
      .drain
      .mergeHaltL(processDeathEvents)
      .onFinalize(closed)
  end process
end Actor

object Actor:
  def init[T](name: String, mailBox: MessageBox[T], setup: BehaviorSetup[T]): IO[Actor[T]] =
    for
      id <- UUIDGen[IO].randomUUID
      deathSignal <- Channel.bounded[IO, Terminated](1)
      deathReport <- Channel.bounded[IO, Terminated](1)
      stopCommand <- SignallingRef.of[IO, Boolean](true)
    yield Actor[T](id, name, mailBox, stopCommand, deathSignal, deathReport, setup)
  end init
end Actor

