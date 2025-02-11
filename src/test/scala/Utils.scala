import com.theater.*

import cats.effect.*

def selfStart(starter: BehaviorSpec[Unit]): IO[Unit] =
  val setup = Behaviors.setup[Unit]: ctx =>
    ctx.self.send(()).as(starter)
  ActorSystem.run[Unit](setup)