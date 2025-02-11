package com.theater

import cats.effect.*
import com.theater.MailBoxSettings.Unbounded
import fs2.*
import java.util.UUID

trait ActorContext[T]:
  def self: ActorRef[T]
  def watch(actorRef: ActorRef[?]): IO[Unit]
  def spawn[A](behavior: Behavior[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]]
  def spawnAnonymously[A](behavior: Behavior[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]]
end ActorContext

private class Context[T](
  execContext: System,
  val self: ActorImpl[T]
) extends ActorContext[T]:
  private val children: Ref[IO, Map[UUID, Context[?]]] = Ref.unsafe(Map.empty)
  private val watching: Ref[IO, Set[UUID]] = Ref.unsafe(Set.empty)

  private def onStop(): IO[Unit] =
    val ctxList = children.get.map(_.values.toList)
    Stream.evals(ctxList)
      .evalMap(_.self.stop())
      .compile
      .drain
      >> watching.set(Set.empty)
  end onStop

  private def installWatcher(childRef: Actor[?]): IO[Unit] =
    val eventProcess = childRef.events.evalTap(self.notify).drain
    execContext.execute(eventProcess)

  def watch(childRef: ActorRef[?]): IO[Unit] =
    val toRemove     = watching.update(_.excl(childRef.id))
    val eventProcess = childRef.events.evalTap(self.notify).drain.onFinalize(toRemove)
    val exec         = execContext.execute(eventProcess)
    watching.flatModify:
      case m if m.contains(childRef.id) => (m, IO.unit)
      case m => (m.incl(childRef.id), exec)
  end watch

  override def spawnAnonymously[A](
    setup: Behavior[A],
    name: String,
    mailSettings: MailBoxSettings
  ): IO[ActorRef[A]] =
    for
      mailBox  <- MessageBox.init[A](mailSettings)
      actorRef <- Actor.init[A](name, mailBox, setup)
      ctx      = Context[A](execContext, actorRef)
      process  = actorRef.process(Stream.empty, ctx)
        .onFinalize(ctx.onStop())
      _        <- execContext.execute(process)
    yield actorRef
  end spawnAnonymously

  override def spawn[A](
    setup: Behavior[A],
    name: String,
    mailSettings: MailBoxSettings
  ): IO[ActorRef[A]] =
    for
      mailBox     <- MessageBox.init[A](mailSettings)
      actorRef    <- Actor.init[A](name, mailBox, setup)
      ctx         = Context[A](execContext, actorRef)
      _           <- children.update(_.updated(actorRef.id, ctx))
      _           <- installWatcher(actorRef)
      removeChild = children.update(_.removed(actorRef.id))
      process     = actorRef.process(self.stopEvent, ctx)
        .onFinalize(removeChild)
        .onFinalize(ctx.onStop())
      _           <- execContext.execute(process)
    yield actorRef
  end spawn
end Context