import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import cats.implicits.*

import com.theater.*

import scala.concurrent.duration.{FiniteDuration, SECONDS}

class ActorSystemSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "shuts down when there are no actors alive" in {
    //starts and by receiving the first message stops.
    val onReceiveStop = Behaviors.receive[Unit]: (ctx, _) =>
      Behaviors.stop

    selfStart(onReceiveStop).asserting(_ shouldBe ())
  }

  "kick starts by sending ONE unit message to start the flow" in {
    val effectRef: Ref[IO, Double] = Ref.unsafe(0)

    val startAndStop = Behaviors.receive[Unit]: (ctx, _) =>
      effectRef.update(_ + 1) >>
        Behaviors.stop

    selfStart(startAndStop) >> effectRef.get.asserting(_ shouldBe 1)
  }

}