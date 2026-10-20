name := "realworld-play"
organization := "io.realworld"
version := "1.0.0"

scalaVersion := "3.3.8"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  filters,
  "com.typesafe.slick" %% "slick"          % "3.6.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.6.1",
  "org.xerial"          % "sqlite-jdbc"    % "3.50.3.0"
)

scalacOptions ++= Seq("-feature")

// Release build: no docs, no source jars in the staged distribution.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
