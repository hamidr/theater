package com.theater

import cats.effect.IO
import scala.concurrent.duration.FiniteDuration
import scala.reflect.TypeTest

type InitContext[T]   = ActorContext[T] => IO[BehaviorLens[T]]
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

private sealed class Pass[T] extends BehaviorSpec[T]:
  override def onReceive:    OnReceive = (_, _) => Behaviors.same[T]
  override def onSignalEvent: OnSignal = PartialFunction.empty
  override def onError:        OnError = Supervisor.stop[T]
  override def onIdle:          OnIdle = None
end Pass

//We need the type! Not the implementation.
private final class Same[T]    extends Pass[T]
private final class Restart[T] extends Pass[T]
private final class Stop[T]    extends Pass[T]

final case class BehaviorLens[T] private(
  override val onReceive:     Receiver[T],
  override val onSignalEvent: SignalHandler[T],
  override val onError:       ErrorHandler[T],
  override val onIdle:        Option[OnTimeout[T]]
) extends BehaviorSpec[T]:
  def onSignal(sigHandler: SignalHandler[T]): BehaviorLens[T] =
    this.copy(onSignalEvent = this.onSignalEvent.orElse(sigHandler))

  def onFailure[Ex <: Throwable](strategy: ErrorHandler[T])(using tt: TypeTest[Throwable, Ex]): BehaviorLens[T] =
    val onError: ErrorHandler[T] = fromFunctor: ex =>
      tt.unapply(ex).fold(IO.raiseError(ex))(strategy)
    val errorHandler = fromFunctor(this.onError(_).handleErrorWith(onError))
    this.copy(onError = errorHandler)
  end onFailure

  def onIdleTrigger(timeout: FiniteDuration, onTimeout: T): BehaviorLens[T] =
    this.copy(onIdle = Some(OnTimeout(timeout, onTimeout)))
  
end BehaviorLens

object BehaviorLens:
  def init[T](rcv: Receiver[T]): BehaviorLens[T] =
    BehaviorLens[T](
      onReceive     = rcv,
      onSignalEvent = PartialFunction.empty,
      onError       = Supervisor.escalate[T],
      onIdle        = None
    )
  end init
end BehaviorLens

object Behaviors:
  def setup[T](init: InitContext[T]): Behavior[T]   = SetupContext(init)
  def receive[T](rcv: Receiver[T]): BehaviorLens[T] = BehaviorLens.init[T](rcv)
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

extension [T](inner: Behavior[T]) {
  def spawn(name: String, mailBoxSettings: MailBoxSettings = MailBoxSettings.Unbounded): IO[ActorRef[T]] =
    ActorSystem.init.flatMap(_.spawn(inner, name, mailBoxSettings))
}