# Theater
It's a simple alternative to typed akka for typelevel ecosystem.

# Example
From one our unit tests we can show how easy it is to use this library.
This is a very simple and generic load balancer for a behavior:

```scala
  "A load balancer example" in {
    def initLoadBalancer[T](workerSize: Int, task: Behavior[T]): BehaviorSetup[T] = Behaviors.setup[T] { ctx =>
      def balance(workers: Vector[ActorRef[T]], index: Int): Behavior[T] = {
        Behaviors.receive[T] { (_, msg) =>
            val next = if ((index + 1) >= workerSize) then 0 else index + 1
            workers(index).send(msg).as(balance(workers, next))
          }
      }

      for {
        workers <- Stream.range[IO, Int](0, workerSize)
          .evalMap(n => ctx.spawn(task, "worker" + n))
          .compile.toVector
      } yield balance(workers, 0)
    }

    val proof = Ref.unsafe[IO, Map[UUID, Int]](Map.empty)
    val updateAndDie = Behaviors.receive[Int] { (ctx, msg) =>
      proof.update(_.updatedWith(ctx.self.id)(_.map(_ + msg).orElse(Some(msg)))) >> Behaviors.same
    }

    val init = Behaviors.receive[Unit] { (ctx, _) =>
      for {
        ref <- ctx.spawn(initLoadBalancer(10, updateAndDie), "load_balancer")
        _ <- Stream.range[IO, Int](0, 100, 1).evalMap(rand => ref.send(rand)).compile.drain
        _ <- IO.sleep(FiniteDuration(200, MILLISECONDS))
        state <- Behaviors.stop[Unit]
      } yield state
    }

    (ActorSystem.startFlow(init) >> proof.get).asserting(proofValue => proofValue.size shouldBe 10)
  }
```
