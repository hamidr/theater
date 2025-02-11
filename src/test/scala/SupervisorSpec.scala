import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import com.theater.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits.*
import fs2.*

class SupervisorSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "Exceptions must be propagated to onFailure" in {
    val effect = Ref.unsafe[IO, Int](0)
    val start = Behaviors.receive[Unit]: (ctx, _) =>
      IO.delay(10 / 0) >> effect.set(1) >> Behaviors.same
    .onFailure[Throwable](Supervisor.stop)

    selfStart(start) >> effect.get.asserting(_ shouldBe 0)
  }

  "Must handle an specific exception by catching it" in {
    val start = Behaviors.receive[Unit]: (ctx, _) =>
      IO.delay(10 / 0) >> Behaviors.stop
    .onFailure[java.lang.ArithmeticException](Supervisor.stop)

    selfStart(start).asserting(_ shouldBe ())
  }

  "Must handle compose exception handling and applying it from inner to outer layer" in {
    val start: BehaviorSpec[Unit] = Behaviors.receive[Unit]: (ctx, _) =>
      val z: Int = ???
      IO.delay(z + 1) >> Behaviors.same
    .onFailure[java.lang.ArithmeticException](Supervisor.resume)
    .onFailure[NotImplementedError](Supervisor.stop)

    selfStart(start).asserting(_ shouldBe ())
  }

  "Must restart from its starting state/behavior on failure as specified" in {
    val proof = Ref.unsafe[IO, List[Int]](Nil)

    def testSubject(state: Int): BehaviorSpec[Unit] =
      Behaviors.receive[Unit]: (ctx, _) =>
        val causeToRestart = proof.get
          .map(_.size)
          .flatMap:
            case 10 => Behaviors.stop
            case n if (n == 4 || state == 2) =>
              IO.delay(10 / 0) >> IO.delay(testSubject(state + 1))
            case ow => IO.delay(testSubject(state + 1))
        proof.update(_.appended(state)) >> causeToRestart
    .onFailure[java.lang.ArithmeticException](Supervisor.restart)
    .onFailure[NotImplementedError](Supervisor.stop)

    val start: BehaviorSpec[Unit] = Behaviors.receive: (ctx, _) =>
      for
        newActor <- ctx.spawnAnonymously(testSubject(0), "testSubject")
        _        <- Seq.range(0, 10).evalTap(_ => newActor.send(()))
        done     <- Behaviors.stop[Unit]
      yield done

    selfStart(start) >> proof.get.asserting(_ shouldBe List(0, 1, 2, 0, 0, 1, 2, 0, 1, 2))
  }
}