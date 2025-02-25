# Theater
A State Machine + Actor Model.

A typelevel alternative to Typed Akka built on top of Fs2 and Cats Effects.

## Features:
- Actor Model (built on Cats-effects/fs2)
- Backpressure tied to actors' processing
- State Machine for each actor
- Life Cycle Signaling (PreStart, PostStop, PreRestart, Terminated)
- Utilizing virtual threads (1 actor = 1 virtual thread)

## System Constructs:

### `BehaviorFlow[T]`
The starting point to construct any actor is to define a `BehaviorFlow[T]` which can be spawned.
There are currently two ways to do this:
- Use `Behavior.setup[T]` to construct an initial flow which receives a
  `Context[T] => IO[Behavior[T]]` to call upon creation of the actor.
```scala 3
val init = Behaviors.setup[T]: ctx =>
  val firstState = Behaviors.receive: (_, event) =>
    Behaviors.same
  IO.pure(firstState)
```
- Or create a behavior which is a special kind of BehaviorFlow without an initialization call:
```scala 3
val waitToReceiveFirstMsg = Behaviors.receive: (_, event) =>
  Behaviors.same
```

### Behavior[T]:
- Define your Behavior via Behaviors.receive and compose its capabilities
   - Implement the following function: `(Context[T], ActorRef[T]) => IO[BehaviorState[T]]`
- Use `onFailure[ExceptionType](...)` to assign a supervisor
- Use `onSignal` to assign your signal handler
- Use `onIdleTrigger` to define a default trigger when the actor is idle for some time

### ActorRef[T]
A reference to any actor. Its properties consist of:
- `id`: A unique UUID assigned to each actor
- `name`: A name assigned to an Actor upon creation via `spawn`
- `stop()`: To stop the current actor
- `send(T)`: Appends a message to the queue of this actor

### Context[T]
With context, you can:
- `self`: Get a reference to the current ActorRef[T]
- `spawn`: Create a new Actor as a child
- `spawnAnonymously`: Create a new actor on its own
- `watch`: Monitor an actor to receive their termination event

### BehaviorState[T]
A `BehaviorState[T]` is a functor of `(Context[T], T) => IO[BehaviorState]` which is assigned to
an actor after each message has been handled.
To process each message, one must return one of the following:
- Any `Behavior[T]` constructed via `Behaviors.receive`
- `Behaviors.stop`: To stop the flow and terminate the actor
- `Behaviors.same`: To resume the flow without altering the state

### Supervisor
A supervisor is a mechanism assigned to any Actor to handle unhandled exceptions.
It is assigned to a `Behavior[T]` using `onFailure[AnyTypeOfException](supervisor)`.
Several strategies are defined within the system:
- `Supervisor.stop`: Stops the actor
- `Supervisor.restart`: Re-initiates the actor
- `Supervisor.resume`: Skips the exception
- `Supervisor.escalate`: Stops the actor and escalates it to the watcher/parent

## Show me the code
The following code results in printing "Hello World!" by creating an actor and stopping it after 200ms.

```scala 3
// A behavior is how an actor handles a message
// Every actor after handling a message must return a new Behavior, here by using "Behaviors.same"
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

## Examples
From our unit tests, the simplicity of the library is evident.

You can read the code like this:

1. Define an actor that receives two kinds of messages: a Var and a Close command.
2. When a message is received:
   - If Var is received by the actor, it must be appended to a list (aka proof),
   - If Close is received, it must terminate the Actor. Otherwise, the actor stays idle for 200ms and assumes the same behavior for the next message.
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

A naive but generic load balancer for a behavior:

```scala 3
    def initLoadBalancer[T](workerSize: Int, task: Behavior[T]): BehaviorFlow[T] = Behaviors.setup[T]: ctx =>
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
        _     <- Seq.range(0, 100, 1).evalTap(rand => ref.send(rand))
        _     <- IO.sleep(200.milliseconds)
        state <- Behaviors.stop[Unit]
      yield state
```