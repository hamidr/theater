package com.theater

import cats.effect.IO
import scala.reflect.TypeTest

type InitContext[T] = ActorContext[T] => IO[Behavior[T]]
type Receiver[T] = (ActorContext[T], T) => IO[Behavior[T]]
type SignalHandler[T] = ActorState => IO[Unit]
sealed trait ErrorHandler[T] extends (Throwable => IO[Behavior[T]])

sealed trait BehaviorSetup[T] {
  def eval(ctx: ActorContext[T]): IO[Behavior[T]]
}

final class SetupContext[T](initContext: InitContext[T]) extends BehaviorSetup[T] {
  override def eval(ctx: ActorContext[T]): IO[Behavior[T]] = initContext(ctx)
}

trait Behavior[T] extends BehaviorSetup[T] {
  override def eval(ctx: ActorContext[T]): IO[Behavior[T]] = IO.pure(this)
  def receive: Receiver[T]
  def signal: SignalHandler[T]
  def handleError: ErrorHandler[T]
}

sealed class Pass[T] extends Behavior[T] {
  override def receive: Receiver[T] = { (_, _) => Behaviors.same[T] }
  override def signal: SignalHandler[T] = _ => IO.unit
  override def handleError: ErrorHandler[T] = Supervisor.resume[T]
}

//We need the type! Not the implementation.
final class Same[T]    extends Pass[T]
final class Restart[T] extends Pass[T]
final class Stop[T]    extends Pass[T]

private final class BehaviorLens[T](
  override val receive: Receiver[T],
  override val signal: SignalHandler[T],
  override val handleError: ErrorHandler[T]
) extends Behavior[T]

object Behaviors {
  def setup[T](init: InitContext[T]): BehaviorSetup[T] = SetupContext(init)

  def empty[T]: Behavior[T] = Pass[T]

  def receive[T](rcv: Receiver[T]): Behavior[T] =
    BehaviorLens[T](receive = rcv, signal = _ => IO.unit, handleError = Supervisor.escalate[T])

  def stop[T]: IO[Behavior[T]] = Stop[T].asIO
  def same[T]: IO[Behavior[T]] = Same[T].asIO
}

private final class ErrorHandlerInstance[T](f: Throwable => IO[Behavior[T]]) extends ErrorHandler[T] {
  override def apply(v1: Throwable): IO[Behavior[T]] = f(v1)
}

private def fromFunctor[T](f: Throwable => IO[Behavior[T]]) = ErrorHandlerInstance[T](f)

object Supervisor {
  def stop[T]: ErrorHandler[T]     = fromFunctor { _ => Behaviors.stop }
  def restart[T]: ErrorHandler[T]  = fromFunctor { _ => IO.pure(Restart[T]) }
  def resume[T]: ErrorHandler[T]   = fromFunctor { _ => Behaviors.same[T] }
  def escalate[T]: ErrorHandler[T] = fromFunctor { IO.raiseError }
}

extension [T](inner: => Behavior[T]) {
  def asIO: IO[Behavior[T]] = IO.delay(inner)

  def onSignal(sigHandler: SignalHandler[T]): Behavior[T] =
    BehaviorLens(inner.receive, sigHandler, inner.handleError)

  def onFailure[Ex <: Throwable](strategy: ErrorHandler[T])(using tt: TypeTest[Throwable, Ex]): Behavior[T] = {
    val onError: ErrorHandler[T] = fromFunctor { ex => tt.unapply(ex).fold(IO.raiseError(ex))(strategy) }
    val errorHandler = fromFunctor { inner.handleError(_).handleErrorWith(onError) }
    BehaviorLens(inner.receive, inner.signal, errorHandler)
  }
}