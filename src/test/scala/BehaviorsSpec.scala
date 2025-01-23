import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import com.theater.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import fs2.*
import cats.implicits.*
import scala.concurrent.duration.{FiniteDuration, SECONDS}

class BehaviorsSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "Behaviors.same returns the same behavior it was assigned to for an actor" in {
    case object JustASimpleBehavior extends Behavior[Boolean] {
      override def signal: SignalHandler[Boolean] = _ => IO.unit

      override def handleError: ErrorHandler[Boolean] = Supervisor.escalate

      override def receive: Receiver[Boolean] = { (ctx, n) =>
        if (!n) then
          ctx.self.send(true) >> Behaviors.same
        else
          proof.set(n) >> Behaviors.stop
      }

      def getChange: IO[Boolean] = proof.get
      private val proof: Ref[IO, Boolean] = Ref.unsafe(false)

    }

    val onReceiveStop = Behaviors.receive[Unit] { (ctx, _) =>
      ctx.spawnAnonymously(JustASimpleBehavior, "Just An Actor").flatMap(_.send(false)) >> Behaviors.stop
    }

    (ActorSystem.startFlow(onReceiveStop) >> JustASimpleBehavior.getChange)
      .asserting(_ shouldBe true)
  }

  "Behaviors.stop should stop the flow as when a behavior is returned" in {
    val proof: Ref[IO, Int] = Ref.unsafe(0)

    val onReceiveStop = Behaviors.receive[Unit] { (ctx, _) =>
      proof.update(_ + 1) >> Behaviors.stop
    }

    val run = ActorSystem.execAndWait { ctx =>
      for {
        ref  <- ctx.spawnAnonymously(onReceiveStop, "Wow")
        _ <- ref.send(())
        _ <- ref.send(()) // necessary for the proof
      } yield ()
    }

    (run >> proof.get).asserting(_ shouldBe 1)
  }

  "Must raise signals while the state of actor is changing" in {
    val proof = Ref.unsafe[IO, List[ActorState]](Nil)
    val state = Ref.unsafe[IO, List[Int]](Nil)
    def testSubject(cnt: Int): Behavior[Unit] = Behaviors.receive[Unit] { (ctx, _) =>
        val causeToRestart = state.get.map(_.size) >>= { total =>
          if (total == 10) then Behaviors.stop
          else if (total == 4 || cnt == 2) then IO.delay(10 / 0) >> IO.delay(testSubject(cnt + 1))
          else IO.delay(testSubject(cnt + 1))
        }
        state.update(_.appended(cnt)) >> causeToRestart
      }
      .onFailure[java.lang.ArithmeticException](Supervisor.restart)
      .onSignal { actorState => proof.update(_.appended(actorState)) }

    val start: Behavior[Unit] = Behaviors.receive { (ctx, _) =>
      for {
        newActor <- ctx.spawnAnonymously(testSubject(0), "testSubject")
        _ <- Stream.range[IO, Int](0, 10).evalTap(_ => newActor.send(())).compile.drain
        done <- Behaviors.stop[Unit]
      } yield done
    }

    import ActorState.*
    (ActorSystem.startFlow(start) >> proof.get)
      .asserting(_ shouldBe List(PreStart, PreRestart, PreRestart, PreRestart, PostStop))
  }

}