name := "realworld-play"
organization := "io.realworld"
version := "1.0.0"

scalaVersion := "3.3.8"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "com.typesafe.slick" %% "slick" % "3.6.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.6.1",
  "org.xerial" % "sqlite-jdbc" % "3.50.3.0",
  "org.slf4j" % "slf4j-nop" % "2.0.16" % Test
)

// Play's sbt plugin injects its own routes imports; keep compiler output quiet.
scalacOptions ++= Seq("-feature")
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
