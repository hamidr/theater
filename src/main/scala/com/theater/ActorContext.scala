package com.theater

import cats.effect.*
import com.theater.MailBoxSettings.Unbounded
import fs2.*
import java.util.UUID

trait ActorContext[T] {
  def self: ActorRef[T]
  def watch(actorRef: ActorRef[?]): IO[Unit]
  def spawn[A](behavior: BehaviorSetup[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]]
  def spawnAnonymously[A](behavior: BehaviorSetup[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]]
}

private class Context[T](
  execContext: System,
  val self: ActorRef[T]
) extends ActorContext[T] {
  private val children: Ref[IO, Map[UUID, Context[?]]] = Ref.unsafe(Map.empty)
  private val watching: Ref[IO, Set[UUID]] = Ref.unsafe(Set.empty)

  private def onStop(): IO[Unit] = {
    val ctxList = children.get.map(_.values.toList)
    Stream.evals(ctxList)
      .evalMap(_.self.stop())
      .compile
      .drain
      >> watching.set(Set.empty)
  }

  private def installWatcher(childRef: ActorRef[?]): IO[Unit] = execContext.execute {
    childRef.events.evalTap(self.notify).drain
  }

  def watch(childRef: ActorRef[?]): IO[Unit] = {
    val toRemove = watching.update(_.excl(childRef.id))
    val exec = execContext.execute {
      childRef.events.evalTap(self.notify).drain.onFinalize(toRemove)
    }
    watching.flatModify { m =>
      if m.contains(childRef.id) then (m, IO.unit)
      else (m.incl(childRef.id), exec)
    }
  }

  override def spawnAnonymously[A](setup: BehaviorSetup[A], name: String, mailSettings: MailBoxSettings = Unbounded): IO[ActorRef[A]] = {
    for {
      mailBox <- MessageBox.init[A](mailSettings)
      actorRef <- Actor.init[A](name, mailBox, setup)
      ctx = Context[A](execContext, actorRef)
      process = actorRef.process(Stream.empty, ctx)
        .onFinalize(ctx.onStop())
      _ <- execContext.execute(process)
    } yield actorRef
  }

  override def spawn[A](setup: BehaviorSetup[A], name: String, mailSettings: MailBoxSettings): IO[ActorRef[A]] = {
    for {
      mailBox <- MessageBox.init[A](mailSettings)
      actorRef <- Actor.init[A](name, mailBox, setup)
      ctx = Context[A](execContext, actorRef)
      _ <- children.update(_.updated(actorRef.id, ctx))
      _ <- installWatcher(actorRef)
      removeChild = children.update(_.removed(actorRef.id))
      process = actorRef.process(self.stopEvent, ctx)
        .onFinalize(removeChild)
        .onFinalize(ctx.onStop())
      _ <- execContext.execute(process)
    } yield actorRef
  }
}