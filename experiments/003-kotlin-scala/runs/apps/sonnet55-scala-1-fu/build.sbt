name := "realworld"
version := "1.0"
scalaVersion := "3.3.6"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "org.playframework" %% "play-slick" % "6.2.0",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.6.0",
  "org.xerial" % "sqlite-jdbc" % "3.50.3.0"
)

// Quieter, faster builds
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
