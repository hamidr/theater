# Theater
It is a State Machine + Actor Model.

A typelevel alternative to Typed Akka on top of Fs2 and CatsEffects

# Show me the code
The following code results in printing "Hello World!" by creating an actor and stopping it after 200ms.

```scala 3
// A behavior is how an actor handles a message
// Every actor after handling a message must return a new BehaviorSpec, here by using "Behaviors.same"
// the next behavior stays the same.
val aPrinter = 
  Behaviors.receive[String]: (_, msg) =>
    IO.println(msg) >> Behaviors.same

//An actor can be started using "spawn" method from type behavior:
val io = for
    ref <- aPrinter.spawn("name_of_the_actor")
    _   <- ref.send("Hello")
    _   <- ref.send(" ")
    _   <- ref.send("World!")
    _   <- IO.sleep(200.milliseconds) //Let it do its tasks sequentially in private
    _   <- ref.stop()
  yield ()
```

# Examples
From our unit tests, the simplicity of the library is obvious.

You can read the code like this:

1. Define me an actor who receives two kinds of messages, a Var and a Close command.
2. When a message is received:
   - If Var is received by the actor, it must be appended to a list(aka proof),
   - and when it's Close, it must terminate the Actor, and then the actor stays idle for 200ms and assumes the same behavior for the next message.
3. When the actor is idle for 100ms, a trigger to send a Close command is initiated to stop the actor.

```scala 3
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

```

This is a very simple and generic load balancer for a behavior:

```scala 3
    def initLoadBalancer[T](workerSize: Int, task: Behavior[T]): BehaviorSetup[T] = Behaviors.setup[T]: ctx =>
      def balance(workers: Vector[ActorRef[T]], index: Int): Behavior[T] =
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
        _     <- Seq.range(0, 100, 1).evalTap(ref.send)
        _     <- IO.sleep(200.milliseconds)
        state <- Behaviors.stop[Unit]
      yield state

    selfStart(init) >> proof.get.asserting(proofValue => proofValue.size shouldBe 10)
```
