name := "realworld"
organization := "io.realworld"
version := "1.0.0"

scalaVersion := "3.3.8"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "com.typesafe.slick" %% "slick"          % "3.6.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.6.1",
  "org.xerial"          % "sqlite-jdbc"    % "3.53.4.0"
)

// The stage task builds an optimized, self-contained distribution without docs or sources.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
scalacOptions ++= Seq("-feature")
