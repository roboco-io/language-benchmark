name := "realworld"
version := "1.0.0"
scalaVersion := "3.3.6"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  filters,
  "com.typesafe.slick" %% "slick" % "3.6.0",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.6.0",
  "org.xerial" % "sqlite-jdbc" % "3.50.3.0"
)

scalacOptions ++= Seq("-feature")

// Release build: no API docs, no source jars in the staged distribution.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false

// JVM flags baked into the staged launcher (conf/application.ini).
Universal / javaOptions ++= Seq(
  "-J--enable-native-access=ALL-UNNAMED",
  "-J--sun-misc-unsafe-memory-access=allow",
  "-J-XX:+UseG1GC"
)
