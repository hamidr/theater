import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import com.theater.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits.*
import scala.concurrent.duration.{FiniteDuration, SECONDS, DurationInt}

class BehaviorsSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "Behaviors.same returns the same behavior it was assigned to for an actor" in {
    val proof: Ref[IO, Boolean] = Ref.unsafe(false)

    val justASimpleBehavior = Behaviors.receive[Boolean]:
      case (ctx, false) => ctx.self.send(true) >> Behaviors.same
      case (_, n)       => proof.set(n) >> Behaviors.stop

    val onReceiveStop = Behaviors.setup[Boolean]: ctx =>
      ctx.self.send(false).as(justASimpleBehavior)

    ActorSystem.run(onReceiveStop) >> proof.get.asserting(_ shouldBe true)
  }

  "Behaviors.stop should stop the flow as when a behavior is returned" in {
    val proof: Ref[IO, Int] = Ref.unsafe(0)

    val onReceiveStop = Behaviors.receive[Unit]: (ctx, _) =>
      proof.update(_ + 1) >> Behaviors.stop

    val run = for {
      sys <- ActorSystem.init
      ref  <- sys.spawn(onReceiveStop, "Wow")
      _ <- ref.send(())
      _ <- ref.send(()) // necessary for the proof
      _ <- sys.waitOnStop
    } yield ()

    (run >> proof.get).asserting(_ shouldBe 1)
  }

  "Must raise signals while the state of actor is changing" in {
    val proof = Ref.unsafe[IO, List[LifeCycle]](Nil)
    val state = Ref.unsafe[IO, List[Int]](Nil)
    def testSubject(cnt: Int): Behavior[Unit] =
      Behaviors.receive[Unit]: (ctx, _) =>
        val causeToRestart = state.get.map(_.size).flatMap: total =>
          if total == 10 then Behaviors.stop
          else if total == 4 || cnt == 2 then IO.delay(10 / 0) >> IO.delay(testSubject(cnt + 1))
          else IO.delay(testSubject(cnt + 1))
        state.update(_.appended(cnt)) >> causeToRestart
      .onFailure[java.lang.ArithmeticException](Supervisor.restart)
      .onSignal { actorState => proof.update(_.appended(actorState)) }

    val start = Behaviors.setup[Unit]: ctx =>
      Seq.range(0, 10)
        .evalTap(_ => ctx.self.send(()))
        .as(testSubject(0))

    import LifeCycle.*
    (ActorSystem.run(start).timeout(1.seconds).attempt >> proof.get)
      .asserting(_ shouldBe List(PreStart, PreRestart, PreRestart, PreRestart, PostStop))
  }

}