package com.theater

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration
import scala.reflect.TypeTest

type InitContext[T]   = ActorContext[T] => IO[BehaviorSpec[T]]
type Receiver[T]      = (ActorContext[T], T) => IO[BehaviorSpec[T]]
type SignalHandler[T] = PartialFunction[LifeCycle, IO[Unit]]

sealed trait ErrorHandler[T]
  extends (Throwable => IO[BehaviorSpec[T]])

sealed trait Behavior[T]:
  def eval(ctx: ActorContext[T]): IO[BehaviorSpec[T]]

final class SetupContext[T](initContext: InitContext[T]) extends Behavior[T]:
  override def eval(ctx: ActorContext[T]): IO[BehaviorSpec[T]] = initContext(ctx)

final case class OnTimeout[T](
  timeout: FiniteDuration,
  onTimeout: T
)

sealed trait BehaviorSpec[T] extends Behavior[T]:
  final type OnReceive = Receiver[T]
  final type OnSignal  = SignalHandler[T]
  final type OnError   = ErrorHandler[T]
  final type OnIdle    = Option[OnTimeout[T]]

  final override def eval(ctx: ActorContext[T]): IO[BehaviorSpec[T]] = IO.pure(this)
  def onReceive:     OnReceive
  def onSignalEvent: OnSignal
  def onError:       OnError
  def onIdle:        OnIdle
end BehaviorSpec

sealed class Pass[T] extends BehaviorSpec[T]:
  override def onReceive:    OnReceive = (_, _) => Behaviors.same[T]
  override def onSignalEvent: OnSignal = PartialFunction.empty
  override def onError:        OnError = Supervisor.resume[T]
  override def onIdle:          OnIdle = None
end Pass

//We need the type! Not the implementation.
final class Same[T]    extends Pass[T]
final class Restart[T] extends Pass[T]
final class Stop[T]    extends Pass[T]

private final case class BehaviorLens[T](
  override val onReceive:     Receiver[T],
  override val onSignalEvent: SignalHandler[T],
  override val onError:       ErrorHandler[T],
  override val onIdle:        Option[OnTimeout[T]]
) extends BehaviorSpec[T]

object Behaviors:
  def setup[T](init: InitContext[T]): Behavior[T] =
    SetupContext(init)

  def empty[T]: BehaviorSpec[T] =
    Pass[T]

  def receive[T](rcv: Receiver[T]): BehaviorSpec[T] =
    BehaviorLens[T](
      onReceive     = rcv,
      onSignalEvent = PartialFunction.empty,
      onError       = Supervisor.escalate[T],
      onIdle        = None
    )
  end receive

  def stop[T]: IO[BehaviorSpec[T]] = IO.pure(Stop[T])
  def same[T]: IO[BehaviorSpec[T]] = IO.pure(Same[T])
end Behaviors

private final class ErrorHandlerInstance[T](f: Throwable => IO[BehaviorSpec[T]]) extends ErrorHandler[T]:
  override def apply(v1: Throwable): IO[BehaviorSpec[T]] = f(v1)

private def fromFunctor[T](f: Throwable => IO[BehaviorSpec[T]]) =
  ErrorHandlerInstance[T](f)

object Supervisor:
  def stop[T]:     ErrorHandler[T] = fromFunctor { _ => Behaviors.stop }
  def restart[T]:  ErrorHandler[T] = fromFunctor { _ => IO.pure(Restart[T]) }
  def resume[T]:   ErrorHandler[T] = fromFunctor { _ => Behaviors.same[T] }
  def escalate[T]: ErrorHandler[T] = fromFunctor { IO.raiseError }
end Supervisor

extension [T](inner: BehaviorSpec[T]) {
  private def toLens: BehaviorLens[T] =
    BehaviorLens(inner.onReceive, inner.onSignalEvent, inner.onError, inner.onIdle)

  def onSignal(sigHandler: SignalHandler[T]): BehaviorSpec[T] =
    toLens.copy(onSignalEvent = inner.onSignalEvent.orElse(sigHandler))

  def onFailure[Ex <: Throwable](strategy: ErrorHandler[T])(using tt: TypeTest[Throwable, Ex]): BehaviorSpec[T] =
    val onError: ErrorHandler[T] = fromFunctor: ex =>
      tt.unapply(ex).fold(IO.raiseError(ex))(strategy)
    val errorHandler = fromFunctor(inner.onError(_).handleErrorWith(onError))
    toLens.copy(onError = errorHandler)

  def onIdleTrigger(timeout: FiniteDuration, onTimeout: T): BehaviorSpec[T] =
    toLens.copy(onIdle = Some(OnTimeout(timeout, onTimeout)))
}

extension [T](inner: Behavior[T]) {
  def spawn(name: String, mailBoxSettings: MailBoxSettings = MailBoxSettings.Unbounded): IO[ActorRef[T]] =
    ActorSystem.init.flatMap(_.spawn(inner, name, mailBoxSettings))
}