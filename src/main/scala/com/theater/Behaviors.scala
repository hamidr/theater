package com.theater

import cats.effect.IO
import scala.concurrent.duration.FiniteDuration
import scala.reflect.TypeTest

type InitContext[T]   = ActorContext[T] => IO[Behavior[T]]
type Receiver[T]      = (ActorContext[T], T) => IO[BehaviorState[T]]
type SignalHandler[T] = PartialFunction[LifeCycle, IO[Unit]]

sealed trait ErrorHandler[T]
  extends (Throwable => IO[BehaviorState[T]])

sealed trait BehaviorFlow[T]:
  def eval(ctx: ActorContext[T]): IO[Behavior[T]]

final class SetupContext[T](initContext: InitContext[T]) extends BehaviorFlow[T]:
  override def eval(ctx: ActorContext[T]): IO[Behavior[T]] = initContext(ctx)

final case class OnTimeout[T](
  timeout: FiniteDuration,
  onTimeout: T
)

sealed trait BehaviorState[T]

final class Same[T]    extends BehaviorState[T]
final class Stop[T]    extends BehaviorState[T]
final class Restart[T] extends BehaviorState[T]

sealed trait Behavior[T]
  extends BehaviorFlow[T]
    with BehaviorState[T]:
  def onReceive:     Receiver[T]
  def onSignalEvent: SignalHandler[T]
  def onError:       ErrorHandler[T]
  def onIdle:        Option[OnTimeout[T]]
end Behavior

private final case class BehaviorSpec[T] private(
  override val onReceive:     Receiver[T],
  override val onSignalEvent: SignalHandler[T],
  override val onError:       ErrorHandler[T],
  override val onIdle:        Option[OnTimeout[T]]
) extends Behavior[T]:

  override def eval(ctx: ActorContext[T]): IO[Behavior[T]] = IO.pure(this)

  def onFailure[Ex <: Throwable](strategy: ErrorHandler[T])(using tt: TypeTest[Throwable, Ex]): BehaviorSpec[T] =
    val onError: ErrorHandler[T] = fromFunctor: ex =>
      tt.unapply(ex).fold(IO.raiseError(ex))(strategy)
    val errorHandler = fromFunctor(this.onError(_).handleErrorWith(onError))
    this.copy(onError = errorHandler)
  end onFailure

  def onSignal(sigHandler: SignalHandler[T]): BehaviorSpec[T] =
    this.copy(onSignalEvent = this.onSignalEvent.orElse(sigHandler))

  def onIdleTrigger(timeout: FiniteDuration, onTimeout: T): BehaviorSpec[T] =
    this.copy(onIdle = Some(OnTimeout(timeout, onTimeout)))

end BehaviorSpec

object BehaviorSpec:
  def init[T](rcv: Receiver[T]): BehaviorSpec[T] =
    BehaviorSpec[T](
      onReceive     = rcv,
      onSignalEvent = PartialFunction.empty,
      onError       = Supervisor.escalate[T],
      onIdle        = None
    )
  end init
end BehaviorSpec

object Behaviors:
  def setup[T](init: InitContext[T]): BehaviorFlow[T] = SetupContext(init)
  def receive[T](rcv: Receiver[T]): BehaviorSpec[T] = BehaviorSpec.init[T](rcv)
  def stop[T]: IO[BehaviorState[T]] = IO.pure(Stop[T])
  def same[T]: IO[BehaviorState[T]] = IO.pure(Same[T])
end Behaviors

private final class ErrorHandlerInstance[T](f: Throwable => IO[BehaviorState[T]]) extends ErrorHandler[T]:
  override def apply(v1: Throwable): IO[BehaviorState[T]] = f(v1)

private def fromFunctor[T](f: Throwable => IO[BehaviorState[T]]) =
  ErrorHandlerInstance[T](f)

object Supervisor:
  def stop[T]:     ErrorHandler[T] = fromFunctor { _ => Behaviors.stop[T] }
  def restart[T]:  ErrorHandler[T] = fromFunctor { _ => IO.pure(Restart[T]) }
  def resume[T]:   ErrorHandler[T] = fromFunctor { _ => Behaviors.same[T] }
  def escalate[T]: ErrorHandler[T] = fromFunctor { IO.raiseError }
end Supervisor

extension [T](inner: BehaviorFlow[T]) {
  def spawn(name: String, mailBoxSettings: MailBoxSettings = MailBoxSettings.Unbounded): IO[ActorRef[T]] =
    ActorSystem.init.flatMap(_.spawn(inner, name, mailBoxSettings))
}