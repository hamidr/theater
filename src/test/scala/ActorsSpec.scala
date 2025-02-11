import cats.effect.*
import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits.*

import java.util.UUID
import scala.concurrent.duration.*
import com.theater.*
import com.theater.LifeCycle.Terminated

class ActorsSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "An Actor must onReceive only one message when only one message was sent to it" in {
    val proof: Ref[IO, Int] = Ref.unsafe(0)

    val onReceiveStop = Behaviors.receive[Unit]: (ctx, randomId) =>
      proof.update(_ + 1) >> Behaviors.stop

    selfStart(onReceiveStop) >> proof.get.asserting(_ shouldBe 1)
  }

  "An Actor must onReceive the message it was sent to" in {
    val effectRef: Ref[IO, Option[UUID]] = Ref.unsafe(None)

    val onReceiveStop = Behaviors.receive[UUID]: (ctx, randomId) =>
      effectRef.set(randomId.some) >> Behaviors.stop

    val doRun = for
      ctx <- ActorSystem.init
      ref <- ctx.spawn(onReceiveStop, "test")
      id <- UUIDGen[IO].randomUUID
      _ <- ref.send(id)
      _ <- ctx.waitOnStop
      recOpt <- effectRef.get
      receivedId <- IO.fromOption(recOpt)(new Exception("MUST have been received"))
    yield (receivedId, id)

    doRun.asserting((id1, id2) => (id1 == id2) shouldBe true)
  }

  "An Actor must be able to send message to itself" in {
    val logMessages: Ref[IO, List[Int]] = Ref.unsafe(Nil)

    val selfSend = Behaviors.receive[Int]: (ctx, number) =>
      val action =
        if number < 3 then ctx.self.send(number + 1) >> Behaviors.same[Int]
        else Behaviors.stop[Int]

      logMessages.update(_.appended(number)) >> action

    val doRun = for
      ctx <- ActorSystem.init
      ref <- ctx.spawn(selfSend, "test")
      _   <- ref.send(0)
      _   <- ctx.waitOnStop.timeout(300.milli)
      numbers <- logMessages.get
    yield numbers

    doRun.asserting(_ shouldBe List(0, 1, 2, 3))
  }


  "An Actor must be able to spawn other actors" in {
    val counter: Ref[IO, Int] = Ref.unsafe(0)

    val nextGen = Behaviors.receive[Unit]: (ctx, _) =>
      counter.update(_ + 1) >> Behaviors.stop

    def init(counting: Int, actorCount: Int): BehaviorSpec[Unit] = Behaviors.receive[Unit]: (ctx, _) =>
      if actorCount == counting then
        IO.sleep(FiniteDuration(200, MILLISECONDS)) >> Behaviors.stop
      else for
        newActor <- ctx.spawn(nextGen, "nextGen")
        _ <- newActor.send(())
        _ <- ctx.self.send(())
      yield init(counting + 1, actorCount)
    end init

    selfStart(init(0, 3)) >> counter.get.asserting(_ shouldBe 3)
  }

  "An Actor must be able to send a message to other actors" in {
    enum Operation:
      case Plus, Minus, Divide, Multiply

    enum SendToOrGetCalculated:
      case Calculate(n: Double, op: Operation)
      case Calculated(n: Double)

    import SendToOrGetCalculated.*
    import Operation.*

    case class CalcAndSendBack(from: ActorRef[SendToOrGetCalculated], msg: Calculate)

    def calculator(acc: Double = 0, iteration: Int): BehaviorSpec[CalcAndSendBack] =
      Behaviors.receive[CalcAndSendBack]: (ctx, msg) =>
        val result = msg.msg.op match
          case Plus => acc + msg.msg.n
          case Minus => acc - msg.msg.n
          case Divide => acc / msg.msg.n
          case Multiply => acc * msg.msg.n

        msg.from.send(Calculated(result)) >> {
          if iteration == 4 then Behaviors.stop
          else IO.delay(calculator(result, iteration + 1))
        }
    end calculator

    def sendToGetCalculated(log: Double => IO[Unit], inc: ActorRef[CalcAndSendBack]) =
      Behaviors.receive[SendToOrGetCalculated]: (ctx, msg) =>
        val task = msg match
          case msg@Calculate(n, op) => inc.send(CalcAndSendBack(ctx.self, msg))
          case Calculated(n)        => log(n)

        task >> Behaviors.same

    val listRef = Ref.unsafe[IO, List[Double]](Nil)

    def init: Behavior[Unit] = Behaviors.setup: ctx =>
      for
        calc  <- ctx.spawn(calculator(0, 1), "incr")
        proxy <- ctx.spawn(sendToGetCalculated(n => listRef.update(_.appended(n)), calc), "just_send")
        _ <- proxy.send(Calculate(10, Plus))
        _ <- proxy.send(Calculate(100, Divide))
        _ <- proxy.send(Calculate(5, Plus))
        _ <- proxy.send(Calculate(7.1, Minus))
        _ <- proxy.send(Calculate(7.1, Minus))
      yield Behaviors.empty
    end init

    ActorSystem.run(init)
      .timeout(200.milli).attempt >> listRef.get
      .asserting(_ shouldBe List(10, 0.1, 5.1, -2.0))
  }

  "An actor must watch other actors' and be prompted when they are terminated" in {
    val proof = Ref.unsafe[IO, Map[UUID, Int]](Map.empty)
    val dying = Behaviors.receive[Unit]: (_, _) =>
      IO.delay(10 / 0) >> Behaviors.stop

    val init = Behaviors.receive[Unit]: (ctx, _) =>
      for
        ref <- ctx.spawn(dying, "will_die_soon")
        _ <- proof.update(_.updated(ref.id, 0))
        _ <- ctx.watch(ref)
        _ <- ref.send(())
        next <- Behaviors.same[Unit]
      yield next
    .onSignal:
      case Terminated(ref, _:java.lang.ArithmeticException) =>
        proof.update { map => map.updated(ref.id, map.getOrElse(ref.id, -1) + 1) }

    selfStart(init).timeout(200.milli)
      .attempt >> proof.get
      .asserting(proofValue => (proofValue.size, proofValue.head._2) shouldBe ((1, 1)))
  }

  "A load balancer example" in {
    def initLoadBalancer[T](workerSize: Int, task: BehaviorSpec[T]): Behavior[T] = Behaviors.setup[T]: ctx =>
      def balance(workers: Vector[ActorRef[T]], index: Int): BehaviorSpec[T] =
        Behaviors.receive[T]: (_, msg) =>
          val next = if (index + 1) >= workerSize then 0 else index + 1
          workers(index).send(msg).as(balance(workers, next))
      end balance

      Seq.range(0, workerSize)
        .evalMap(n => ctx.spawn(task, "worker" + n))
        .map(balance(_, 0))
    end initLoadBalancer

    val proof = Ref.unsafe[IO, Map[UUID, Int]](Map.empty)
    val updateAndDie = Behaviors.receive[Int]: (ctx, msg) =>
      proof.update(_.updatedWith(ctx.self.id)(_.map(_ + msg).orElse(Some(msg)))) >> Behaviors.same

    val init = Behaviors.receive[Unit]: (ctx, _) =>
      for
        ref   <- ctx.spawn(initLoadBalancer(10, updateAndDie), "load_balancer")
        _     <- Seq.range(0, 100, 1).evalTap(rand => ref.send(rand))
        _     <- IO.sleep(200.milliseconds)
        state <- Behaviors.stop[Unit]
      yield state

    selfStart(init) >> proof.get.asserting(proofValue => proofValue.size shouldBe 10)
  }

  "onIdleTrigger(duration, msg) should trigger a message to the actor when idle for the specified time" in {
    enum State:
      case Var(v: String)
      case Close

    val proof = Ref.unsafe[IO, List[String]](Nil)

    val logVar = Behaviors.receive[State]:
      case (_, State.Close) => Behaviors.stop
      case (_, State.Var(msg)) =>
        proof.update(_.appended(msg)) >>
          IO.sleep(200.millisecond) >>
          Behaviors.same
    .onIdleTrigger(100.millisecond, State.Close)

    val init = Behaviors.setup[State]: ctx =>
      Seq("v1", "v2").evalTap: name =>
        ctx.self.send(State.Var(name))
      .as(logVar)

    init.spawn("test")
      >> IO.sleep(150.milliseconds)
      >> proof.get.asserting(_ shouldBe List("v1"))
  }

  "onIdleTrigger(duration, msg) should trigger a message for the specified time repeatedly" in {
    val proof = Ref.unsafe[IO, List[Int]](Nil)
    val logTimeout = Behaviors.receive[Int]: (ctx, n) =>
      proof.update(_.appended(n))
        >> proof.get
        .map(_.size)
        .flatMap:
          case _ if n == 1 => Behaviors.stop
          case s if s >= 3 => ctx.self.send(1) >> Behaviors.same
          case _ => Behaviors.same
    .onIdleTrigger(10.millisecond, 0)

    ActorSystem.run(logTimeout)
      >> proof.get.asserting(_ shouldBe List(0,0,0,1))
  }
}