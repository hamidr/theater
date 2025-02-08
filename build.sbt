name := "theater"
version := "0.1"
scalaVersion := "3.6.2"
maintainer := "hamidr.dev@gmail.com"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "2.13.0",
  "org.typelevel" %% "cats-effect" % "3.5.7",
  "co.fs2"        %% "fs2-core" % "3.11.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.typelevel" %% "cats-effect-testing-scalatest" % "1.6.0" % Test,
  "org.scalameta" %% "munit" % "1.1.0" % Test
)

enablePlugins(JavaAppPackaging)

scalacOptions ++= Seq(
  "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
  "-encoding", "utf-8",                // Specify character encoding used by source files.
  "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials",            // Existential types (besides wildcard types) can be written and inferred
  "-language:experimental.macros",     // Allow macro definition (besides implementation and application)
  "-language:higherKinds",             // Allow higher-kinded types
  "-language:implicitConversions",     // Allow definition of implicit functions called views
  "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
  "-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
)

Compile / console / scalacOptions --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")

Global / cancelable := true