import cats.effect.*
import cats.effect.testing.scalatest.AsyncIOSpec
import com.theater.*
import com.theater.MailBoxSettings.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class MessageBoxSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  "Unbounded mailbox should not be limited" in {
    val effect = Ref.unsafe[IO, List[Int]](Nil)
    val test = Behaviors.receive[Int]:
      case (_, 0) => IO.sleep(100.millisecond) >> Behaviors.same
      case (_, n) if n < 10 => effect.update(_.appended(n)) >> Behaviors.same
      case (_, 10) => Behaviors.stop

    val task = for
      ref <- test.spawn("test", Unbounded)
      _ <- Seq.range(0, 11).evalTap(n => ref.send(n))
      _ <- IO.sleep(400.millisecond)
    yield ()

    task >> effect.get.asserting(_ shouldBe List(1,2,3,4,5,6,7,8,9))
  }

  "Bounded mailbox should backpressure when the actor hasn't processed" in {
    val test =
      Behaviors.receive[Int]:
        case (_, 0) => IO.sleep(200.millisecond) >> Behaviors.same
        case (_, n) => Behaviors.stop

    val task = for
      ref <- test.spawn("test", Bounded(5))
      res <- Seq.range(0, 11)
        .evalMap(n => ref.send(n).timeout(10.milliseconds).attempt)
      timeouts = res.filter(_.isLeft)
      _   <- IO.sleep(300.millisecond)
    yield timeouts

    task.map(_.size).asserting(_ shouldBe 5)
  }

  "DropOldIfFull mailbox should drop the oldest message in the queue when queue is full" in {
    val testEffect = Ref.unsafe[IO, List[Int]](Nil)

    val test =
      Behaviors.receive[Int]:
        case (_, 0) => IO.sleep(200.millisecond) >> testEffect.update(_.appended(0)) >> Behaviors.same
        case (_, 10) => testEffect.update(_.appended(10)) >> Behaviors.stop
        case (_, n) => testEffect.update(_.appended(n)) >> Behaviors.same

    val task = for
      ref <- test.spawn("test", DropOldIfFull(5))
      _ <- Seq.range(0, 11).evalMap(n => ref.send(n))
      _ <- IO.sleep(400.millisecond)
    yield ()

    task >> testEffect.get.asserting(x => (x.size <= 6, x.last) shouldBe (true, 10))
  }

  "DropNewIfFull mailbox should drop the newest message in the queue when queue is full" in {
    val testEffect = Ref.unsafe[IO, List[Int]](Nil)

    val test =
      Behaviors.receive[Int]:
        case (_, 0) => IO.sleep(200.millisecond) >> testEffect.update(_.appended(0)) >> Behaviors.same
        case (_, 10) => Behaviors.stop
        case (_, n) => testEffect.update(_.appended(n)) >> Behaviors.same

    val task = for
      ref <- test.spawn("test", DropNewIfFull(5))
      _ <- Seq.range(0, 11).evalMap(n => ref.send(n))
      _ <- IO.sleep(300.millisecond)
    yield ()

    task >> testEffect.get.asserting(_ shouldBe List(6, 7, 8, 9))
  }
}